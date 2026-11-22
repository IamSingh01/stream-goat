package io.github.sherazi5110.cloudstream

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * Site addresses: streaming sites often move to a new domain before their extension is
 * updated. Overriding an extension's mainUrl lets it keep working meanwhile, like the
 * "clone site" option in the Android app.
 */
object SiteOverrides {
    private val prefs get() = App.context.getSharedPreferences("site_overrides", 0)
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

/** Checks the project's GitHub releases for a newer version, and downloads and installs it inside the app. */
object Updates {
    const val REPO = "sherazi5110/stream-goat"
    const val RELEASES = "https://github.com/$REPO/releases/latest"

    /** [apk]: the download for this device (null = only the release page). [test]: a test build. */
    data class Release(val version: String, val url: String, val apk: String? = null, val size: Long = 0, val test: Boolean = false,
                       val patch: String? = null, val patchSize: Long = 0, val sha256: String? = null)

    /** The download size: the patch when there is one for this copy, else the whole APK. */
    val Release.downloadSize get() = if (patch != null) patchSize else size

    /** "release" (published versions) or "test" (the test builds). */
    var channel: String
        get() = App.context.getSharedPreferences("app_settings", 0).getString("update_channel", null) ?: "release"
        set(v) { App.context.getSharedPreferences("app_settings", 0).edit().putString("update_channel", v).apply() }

    /** The APK made for this device's processor, else the universal one. */
    private fun pickApk(assets: org.json.JSONArray): Pair<String, Long>? {
        val all = (0 until assets.length()).map { assets.getJSONObject(it) }.filter { it.optString("name").endsWith(".apk") && "android" in it.optString("name") }
        val abis = android.os.Build.SUPPORTED_ABIS.toList() + "universal"
        for (abi in abis) all.firstOrNull { it.optString("name") == "streamgoat-android-$abi.apk" }
            ?.let { return it.optString("browser_download_url") to it.optLong("size") }
        return null
    }

    /** The first 16 hex digits of the installed APK's SHA-256 (patch names start from it), remembered per install. */
    private fun installedSha(): String? = runCatching {
        val ctx = App.context
        val apk = java.io.File(ctx.applicationInfo.sourceDir)
        val stamp = ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime
        val prefs = ctx.getSharedPreferences("app_settings", 0)
        prefs.getString("apk_sha_$stamp", null) ?: run {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            apk.inputStream().use { i -> val buf = ByteArray(1 shl 16); var n: Int; while (i.read(buf).also { n = it } > 0) md.update(buf, 0, n) }
            md.digest().joinToString("") { "%02x".format(it) }.take(16).also { prefs.edit().putString("apk_sha_$stamp", it).apply() }
        }
    }.getOrNull()

    /** [r] with the patch from this copy to its APK, when the release has one (and GitHub's SHA-256 of the APK). */
    private fun withPatch(r: Release, assets: org.json.JSONArray?): Release {
        val list = assets?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: return r
        val apk = list.firstOrNull { it.optString("browser_download_url") == r.apk } ?: return r
        val sha = apk.optString("digest").removePrefix("sha256:").takeIf { it.length == 64 }
        val mine = installedSha() ?: return r.copy(sha256 = sha)
        val patch = list.firstOrNull { it.optString("name") == apk.optString("name") + ".from-$mine.patch" }
        return r.copy(sha256 = sha, patch = patch?.optString("browser_download_url"), patchSize = patch?.optLong("size") ?: 0)
    }

    /** The latest release (or test build, on the test channel) if it's newer than what's installed, else null. */
    suspend fun check(current: String): Release? = withContext(Dispatchers.IO) { checkRelease(current) }

    private suspend fun checkRelease(current: String): Release? {
        if (channel == "test") {
            val json = JSONObject(app.get("https://api.github.com/repos/$REPO/releases/tags/test-build", timeout = 15).text)
            val (apk, size) = pickApk(json.optJSONArray("assets") ?: return null) ?: return null
            val assets = json.getJSONArray("assets")
            val updated = (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.optString("browser_download_url") == apk }
                ?.optString("updated_at")?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() } ?: return null
            val installed = App.context.packageManager.getPackageInfo(App.context.packageName, 0).lastUpdateTime
            // Built more than 10 minutes after this copy was installed: a newer test build.
            return if (updated > installed + 10 * 60_000L)
                withPatch(Release("test build of " + java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(updated)),
                    json.optString("html_url"), apk, size, test = true), assets) else null
        }
        val json = JSONObject(app.get("https://api.github.com/repos/$REPO/releases/latest", timeout = 15).text)
        val tag = json.optString("tag_name").takeIf { it.isNotBlank() } ?: return null
        val apk = json.optJSONArray("assets")?.let { pickApk(it) }
        return if (newer(tag, current)) withPatch(Release(tag.trimStart('v', 'V'), json.optString("html_url", RELEASES), apk?.first, apk?.second ?: 0),
            json.optJSONArray("assets")) else null
    }

    // ---------- download and install ----------

    /** Download progress (0-100) while an update downloads; null when not downloading. */
    var progress by androidx.compose.runtime.mutableStateOf<Int?>(null)
        private set

    /** Whether Android lets this app install updates ("Install unknown apps" for Stream Goat). */
    fun canInstall(): Boolean = android.os.Build.VERSION.SDK_INT < 26 || App.context.packageManager.canRequestPackageInstalls()

    /** Opens Android's "Install unknown apps" switch for Stream Goat. */
    fun askInstallPermission(context: android.content.Context) {
        runCatching {
            context.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                android.net.Uri.parse("package:" + context.packageName)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /**
     * Downloads [r]'s APK and hands it to Android's installer, which installs it over this app: settings,
     * extensions, history and downloads are kept (the APK must be signed with the same key as this copy).
     */
    suspend fun downloadAndInstall(r: Release) {
        val url = r.apk ?: error("No download for this device")
        progress = 0
        try {
            // A patch first (a fraction of the size): the new APK is rebuilt from this copy and must match GitHub's
            // SHA-256 of it exactly. Anything wrong with it: the whole APK, as before.
            if (r.patch != null && r.sha256 != null) {
                val rebuilt = runCatching { withContext(Dispatchers.IO) { patched(r) } }
                    .onFailure { android.util.Log.w("update", "patch not used: $it") }.getOrNull()
                if (rebuilt != null) { install(rebuilt); return }
                withContext(Dispatchers.Main) { progress = 0 }
            }
            val file = withContext(Dispatchers.IO) {
                val dir = java.io.File(App.context.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
                val out = java.io.File(dir, "streamgoat-update.apk")
                val req = okhttp3.Request.Builder().url(url).header("User-Agent", "StreamGoat").build()
                app.baseClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) error("Download failed (HTTP ${resp.code})")
                    val total = resp.body.contentLength().takeIf { it > 0 } ?: r.size
                    out.outputStream().use { o ->
                        resp.body.byteStream().use { i ->
                            val buf = ByteArray(256 * 1024); var done = 0L; var n: Int
                            while (i.read(buf).also { n = it } > 0) {
                                o.write(buf, 0, n); done += n
                                if (total > 0) withContext(Dispatchers.Main) { progress = (done * 100 / total).toInt() }
                            }
                        }
                    }
                }
                out
            }
            install(file)
        } finally { progress = null }
    }

    private suspend fun patched(r: Release): java.io.File {
        val dir = java.io.File(App.context.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val patch = java.io.File(dir, "update.patch")
        val req = okhttp3.Request.Builder().url(r.patch!!).header("User-Agent", "StreamGoat").build()
        app.baseClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("patch download failed (HTTP ${resp.code})")
            val total = resp.body.contentLength().takeIf { it > 0 } ?: r.patchSize
            patch.outputStream().use { o ->
                resp.body.byteStream().use { i ->
                    val buf = ByteArray(256 * 1024); var done = 0L; var n: Int
                    while (i.read(buf).also { n = it } > 0) {
                        o.write(buf, 0, n); done += n
                        if (total > 0) withContext(Dispatchers.Main) { progress = (done * 100 / total).toInt() }
                    }
                }
            }
        }
        val out = java.io.File(dir, "streamgoat-update.apk")
        Bspatch.apply(java.io.File(App.context.applicationInfo.sourceDir), patch, out)
        patch.delete()
        val md = java.security.MessageDigest.getInstance("SHA-256")
        out.inputStream().use { i -> val buf = ByteArray(1 shl 16); var n: Int; while (i.read(buf).also { n = it } > 0) md.update(buf, 0, n) }
        val sha = md.digest().joinToString("") { "%02x".format(it) }
        if (!sha.equals(r.sha256, true)) { out.delete(); error("the rebuilt APK doesn't match") }
        return out
    }

    /** Android's package installer, in a session: it asks the person to confirm, then replaces this app. */
    private suspend fun install(apk: java.io.File) = withContext(Dispatchers.IO) {
        val ctx = App.context
        val installer = ctx.packageManager.packageInstaller
        val params = android.content.pm.PackageInstaller.SessionParams(android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        if (android.os.Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(android.content.pm.PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("streamgoat.apk", 0, apk.length()).use { out -> apk.inputStream().use { it.copyTo(out, 256 * 1024) }; session.fsync(out) }
            val intent = android.content.Intent(ctx, UpdateReceiver::class.java).setAction(UpdateReceiver.ACTION)
            val flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT or (if (android.os.Build.VERSION.SDK_INT >= 31) android.app.PendingIntent.FLAG_MUTABLE else 0)
            session.commit(android.app.PendingIntent.getBroadcast(ctx, id, intent, flags).intentSender)
        }
    }

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
        get() = App.context.getSharedPreferences("app_settings", 0).getString("update_last_check", null)?.toLongOrNull() ?: 0
        set(v) { App.context.getSharedPreferences("app_settings", 0).edit().putString("update_last_check", v.toString()).apply() }
}
