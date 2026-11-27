package com.lagradost.cloudstream3.linux

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Site addresses: streaming sites often move to a new domain before their extension is
 * updated. Overriding an extension's mainUrl lets it keep working meanwhile, like the
 * "clone site" option in the Android app.
 */
object SiteOverrides {
    private val prefs get() = Platform.context.getSharedPreferences("site_overrides", 0)
    /**
     * Each loaded extension's own address, remembered before any override is applied. Kept per
     * instance, so an updated (reloaded) extension's new address is picked up.
     */
    private val originals = java.util.Collections.synchronizedMap(java.util.WeakHashMap<MainAPI, String>())

    fun original(api: MainAPI): String = originals.getOrPut(api) { api.mainUrl }

    fun get(api: MainAPI): String? = prefs.getString(api.name, null)?.takeIf { it.isNotBlank() }

    fun set(api: MainAPI, url: String?) {
        original(api)
        val clean = url?.trim()?.trimEnd('/')?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        prefs.edit().putString(api.name, clean ?: "").apply()
        apply(api)
    }

    /** Applies the saved address (or restores the original) to a loaded extension. */
    fun apply(api: MainAPI) {
        val base = original(api)
        val wanted = get(api) ?: base
        if (api.mainUrl != wanted) runCatching { api.mainUrl = wanted }
    }
}

/**
 * Updates from the project's GitHub releases (or its "test-build" pre-release, with Test builds on).
 *
 * When only RoarStream's own code changed, the GitHub builds attach a signed patch for each system
 * (roarstream-update-<windows|mac|linux>.zip, a few MB): it's downloaded, checked and installed into the data
 * folder, and used from the next start (see Boot.java). Otherwise the full installer is offered, as before.
 */
object Updates {
    const val REPO = "IamSingh01/stream-goat"
    const val RELEASES = "https://github.com/$REPO/releases/latest"
    const val TEST_BUILDS = "https://github.com/$REPO/releases/tag/test-build"

    /** A newer version: [patch] when it can be installed from inside the app. */
    data class Release(val version: String, val url: String, val build: String? = null, val patch: Patch? = null)
    class Patch(val url: String, val size: Long, val props: java.util.Properties)

    /** This build (the GitHub builds set it; "dev" for local builds). */
    val build: String by lazy {
        runCatching {
            java.util.Properties().apply { Updates::class.java.getResourceAsStream("/roarstream-build.properties")!!.use { load(it) } }
                .getProperty("build")
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "dev"
    }

    private val osKey get() = when { Os.isWindows -> "windows"; Os.isMac -> "mac"; else -> "linux" }

    /** The newest version if it's newer than [current] (with Test builds: a different, not older, test build). */
    suspend fun check(current: String): Release? = withContext(Dispatchers.IO) {
        val test = Settings.testBuilds
        val json = JSONObject(app.get("https://api.github.com/repos/$REPO/releases/" + if (test) "tags/test-build" else "latest", timeout = 15).text)
        val assets = json.optJSONArray("assets") ?: org.json.JSONArray()
        fun asset(name: String) = (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.optString("name") == name }
        val props = asset("roarstream-update-$osKey.properties")?.let { a ->
            runCatching { java.util.Properties().apply { load(java.io.StringReader(app.get(a.optString("browser_download_url"), timeout = 15).text)) } }.getOrNull()
        }
        val page = json.optString("html_url", if (test) TEST_BUILDS else RELEASES)
        val release = if (test) {
            val p = props ?: return@withContext null
            val version = p.getProperty("version").orEmpty()
            if (p.getProperty("build") == build || newer(current, version)) return@withContext null
            Release(version, page, p.getProperty("build"))
        } else {
            val tag = json.optString("tag_name").takeIf { it.isNotBlank() } ?: return@withContext null
            if (!newer(tag, current)) return@withContext null
            Release(tag.trimStart('v', 'V'), page, props?.getProperty("build"))
        }
        val zip = asset("roarstream-update-$osKey.zip")
        val patch = if (props != null && zip != null && props.getProperty("version") == release.version && patchFits(props))
            Patch(zip.optString("browser_download_url"), zip.optLong("size"), props) else null
        release.copy(patch = patch)
    }

    /** A patch fits when every other jar it needs is exactly the one installed (only RoarStream's own code changed). */
    private fun patchFits(props: java.util.Properties): Boolean {
        if (!com.lagradost.cloudstream3.linux.Boot.canVerify() || com.lagradost.cloudstream3.linux.Boot.installedAppJar() == null) return false
        val needed = props.stringPropertyNames().filter { it.startsWith("requires.") }.associate { it.removePrefix("requires.") to props.getProperty(it) }
        val installed = installedJars.filterKeys { it !in ours }
        return needed.isNotEmpty() && needed.keys == installed.keys && needed.all { (name, sha) -> installedJars[name].equals(sha, true) }
    }

    private val ours = setOf("app.jar", "cs-library-jvm.jar", "android-shim.jar")
    private val installedJars: Map<String, String> by lazy {
        System.getProperty("java.class.path").orEmpty().split(java.io.File.pathSeparator).map { java.io.File(it) }
            .filter { it.isFile && it.name.endsWith(".jar") && it.name !in ours }
            .associate { it.name to com.lagradost.cloudstream3.linux.Boot.sha256(it) }
    }

    /** Downloads, checks and installs [patch]; it's used from the next start. */
    suspend fun install(patch: Patch, progress: (Double) -> Unit) = withContext(Dispatchers.IO) {
        val data = AppDirs.data
        val zip = java.io.File(data, "update-download.zip")
        val staged = java.io.File(data, "update.new")
        staged.deleteRecursively()
        try {
            val request = okhttp3.Request.Builder().url(patch.url).build()
            app.baseClient.newCall(request).execute().use { r ->
                if (!r.isSuccessful) error("download failed (${r.code})")
                val total = r.body.contentLength().takeIf { it > 0 } ?: patch.size
                zip.outputStream().use { out ->
                    val input = r.body.byteStream()
                    val buf = ByteArray(1 shl 16)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); done += n
                        if (total > 0) progress(done.toDouble() / total)
                    }
                }
            }
            staged.mkdirs()
            java.util.zip.ZipFile(zip).use { z ->
                for (e in z.entries()) {
                    val name = e.name
                    if (e.isDirectory || '/' in name || '\\' in name || name.startsWith(".")) continue
                    z.getInputStream(e).use { inp -> java.io.File(staged, name).outputStream().use { inp.copyTo(it) } }
                }
            }
            val text = java.io.File(staged, "patch.properties").readBytes()
            if (!com.lagradost.cloudstream3.linux.Boot.verify(text, java.io.File(staged, "patch.sig").readBytes())) error("the update's signature doesn't match")
            val props = java.util.Properties().apply { load(text.inputStream()) }
            props.stringPropertyNames().filter { it.startsWith("jar.") }.forEach { k ->
                val jar = java.io.File(staged, k.removePrefix("jar."))
                if (!jar.isFile || !com.lagradost.cloudstream3.linux.Boot.sha256(jar).equals(props.getProperty(k), true)) error("the update is damaged")
            }
            java.io.File(staged, "installation.txt").writeText(com.lagradost.cloudstream3.linux.Boot.installation() ?: error("not an installed app"))
            // Swapped in by Boot at the next start (the jars of a running update can't be replaced on Windows).
            val target = com.lagradost.cloudstream3.linux.Boot.nextDir()
            target.deleteRecursively()
            if (!staged.renameTo(target)) error("couldn't install the update")
            android.util.Log.i("update", "installed ${props.getProperty("version")} (${props.getProperty("build")}), used from the next start")
        } finally {
            zip.delete()
            staged.deleteRecursively()
        }
    }

    /** Opens the app again (with the update) and closes this one. */
    fun restart() {
        val launcher = System.getProperty("jpackage.app-path")?.takeIf { java.io.File(it).isFile }
        if (launcher != null) {
            val command = when {
                // A moment's wait (ping: timeout needs a console), so this copy has closed when the new one opens.
                Os.isWindows -> {
                    val script = java.io.File(AppDirs.data, "restart.cmd")
                    script.writeText("@echo off\r\nping -n 3 127.0.0.1 >nul\r\nstart \"\" \"$launcher\"\r\n")
                    listOf("cmd", "/c", script.path)
                }
                else -> listOf("/bin/sh", "-c", "(sleep 2; exec \"\$0\") >/dev/null 2>&1 &", launcher)
            }
            runCatching { ProcessBuilder(command).start() }
        }
        javafx.application.Platform.exit()
        kotlin.system.exitProcess(0)
    }

    /** The patch this run started with, if any (Boot sets it): "<version> <build>". */
    val runningPatch: String? get() = System.getProperty("roarstream.patch")

    /** Compares dotted versions, ignoring a leading v/V: "V4.0.2" > "4.0.1". */
    fun newer(candidate: String, current: String): Boolean {
        fun parts(v: String) = v.trimStart('v', 'V').split('.', '-').map { it.toIntOrNull() ?: 0 }
        val a = parts(candidate)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    var lastCheck: Long
        get() = Platform.context.getSharedPreferences("linux_settings", 0).getString("update_last_check", null)?.toLongOrNull() ?: 0
        set(v) { Platform.context.getSharedPreferences("linux_settings", 0).edit().putString("update_last_check", v.toString()).apply() }
}
