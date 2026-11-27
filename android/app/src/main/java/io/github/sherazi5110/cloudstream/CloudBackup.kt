package io.github.sherazi5110.cloudstream

import android.content.SharedPreferences
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.InetAddress
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.security.MessageDigest

/**
 * Backup to Google Drive. Sign-in uses Google's code sign-in for TVs and other devices ("OAuth 2.0 for TV and
 * limited-input devices"): this app shows a code, you enter it at google.com/device on your phone or computer.
 * The same works on phones, Android TV and Windows/Linux.
 *
 * The backup (the same file as Settings → Backup) is kept in a "Stream Goat backups" folder in My Drive (scope
 * drive.file: the app sees only the files it created itself, never the rest of the Drive; Google's code sign-in
 * doesn't allow the hidden app folder, drive.appdata). Every device (phone, TV, Windows, Mac, Linux) shares one
 * backup file and keeps it in sync: each merges its changes with the file instead of overwriting it (BackupMerge),
 * so My list, history, progress, watched marks, extensions and the rest are the same everywhere; each device's own
 * settings stay on it. With automatic backup on, it syncs when the app starts and comes back, when it goes to the
 * background, and every 5 minutes. (Older versions kept one file per device: they're merged in and moved to trash.)
 *
 * The Google client id comes from the build (GitHub secrets GOOGLE_CLIENT_ID / GOOGLE_CLIENT_SECRET, see
 * docs/google-drive-setup.md). Builds without it show that Google sign-in isn't set up.
 */
object CloudBackup {
    const val FILE_PREFIX = "stream-goat-backup"
    /** The one backup every device shares and keeps in sync (older versions kept one file per device). */
    const val SHARED = "$FILE_PREFIX.json"
    /** Automatic backup: checked this often, uploaded only when something changed. */
    const val AUTO_INTERVAL_MS = 5 * 60_000L
    const val SCOPE = "https://www.googleapis.com/auth/drive.file"
    /** The folder in My Drive the backups go to (created with the first backup). */
    const val FOLDER = "Stream Goat backups"

    /** Google's addresses; tests point them at a local server. */
    class Endpoints(
        val deviceCode: String = "https://oauth2.googleapis.com/device/code",
        val token: String = "https://oauth2.googleapis.com/token",
        val revoke: String = "https://oauth2.googleapis.com/revoke",
        val drive: String = "https://www.googleapis.com/drive/v3",
        val upload: String = "https://www.googleapis.com/upload/drive/v3",
        /** Google's older sign-in address, used when the one above can't be found (see [fallbackUrl]). */
        val authFallback: String? = "https://accounts.google.com/o/oauth2",
    )
    @Volatile var endpoints = Endpoints()

    /** From the build; tests and developers can set them (see docs/google-drive-setup.md). */
    @Volatile var clientId: String = BuildConfig.GOOGLE_CLIENT_ID
    @Volatile var clientSecret: String = BuildConfig.GOOGLE_CLIENT_SECRET
    val configured: Boolean get() = clientId.isNotBlank()

    /** This device's account. Kept out of backups (see Backup.skip), so a restore never moves it. */
    private val prefs: SharedPreferences get() = App.context.getSharedPreferences("cloud_backup", 0)

    data class State(
        val signedIn: Boolean,
        val email: String?,
        val lastBackup: Long,
        val busy: String? = null,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(readState())
    val state: StateFlow<State> = _state
    private fun readState(busy: String? = null, error: String? = null) = State(
        prefs.getString("refresh", null) != null, prefs.getString("email", null), prefs.getLong("last_backup", 0), busy, error,
    )
    private fun publish(busy: String? = null, error: String? = null) { _state.value = readState(busy, error) }

    var auto: Boolean
        get() = prefs.getBoolean("auto", true)
        set(v) { prefs.edit().putBoolean("auto", v).apply() }

    /** This installation's own backup file in Drive (never part of a backup, so a restore keeps it). */
    val deviceId: String
        get() = prefs.getString("device_id", null) ?: java.util.UUID.randomUUID().toString().also {
            prefs.edit().putString("device_id", it).commit()
        }

    // ------------------------------------------------------------------ sign-in

    /** A code waiting to be entered at [url]. */
    data class SignIn(val userCode: String, val url: String, val deviceCode: String, val intervalSeconds: Int, val expiresAt: Long)

    class CloudException(message: String) : Exception(message)

    /** The app's connection (and DNS setting), with [RescueDns] for when that DNS has no answer for Google. */
    @Volatile private var built: Pair<OkHttpClient, OkHttpClient>? = null
    private val client: OkHttpClient get() {
        val base = app.baseClient
        built?.takeIf { it.first === base }?.let { return it.second }
        return base.newBuilder().dns(RescueDns(base.dns)).build().also { built = base to it }
    }
    private val json = "application/json; charset=UTF-8".toMediaType()

    private suspend fun call(request: Request): Pair<Int, String> = withContext(Dispatchers.IO) {
        fun send(r: Request) = client.newCall(r).execute().use { it.code to (it.body?.string() ?: "") }
        try {
            send(request)
        } catch (e: java.net.UnknownHostException) {
            // Sign-in can also go through accounts.google.com, which any device that signs in to Google can find.
            val other = fallbackUrl(request.url.toString(), endpoints)
            val second = other?.let { runCatching { send(request.newBuilder().url(it).build()) } }
            second?.getOrNull()?.let { return@withContext it }
            val tried = (second?.exceptionOrNull() as? java.net.UnknownHostException)?.message?.let { "; $it" } ?: ""
            // Is it only Google, or does this app reach nothing? (Android answers "No address associated with
            // hostname" for every name when the app is blocked from the internet: a firewall, data limits.)
            val others = runCatching { Dns.SYSTEM.lookup("github.com"); "other sites are found" }.getOrElse { "other sites aren't found either" }
            val advice = if (others.startsWith("other sites are"))
                "Something on this device or network blocks Google: an ad blocker, a VPN or firewall app, Private DNS or the router. " +
                    "Allow googleapis.com and accounts.google.com there, or try another network."
            else "This app may be blocked from the internet on this device: check its settings (data usage, background data), " +
                "a firewall or ad-block app, or the network."
            throw CloudException("Couldn't reach Google: ${e.message}$tried; $others. $advice")
        }
    }

    /** The same sign-in step at Google's older address (device code, token, revoke), or null. */
    internal fun fallbackUrl(url: String, e: Endpoints = endpoints): String? {
        val base = e.authFallback ?: return null
        return when (url) {
            e.deviceCode -> "$base/device/code"
            e.token -> "$base/token"
            e.revoke -> "$base/revoke"
            else -> null
        }
    }

    /**
     * Looks a name up with the app's DNS first; when that has no answer (Private DNS, an ad blocker or a router
     * filter: "Unable to resolve host oauth2.googleapis.com", reported from a tablet), asks Cloudflare's, then
     * Google's DNS over HTTPS, reached by IP address, so no local DNS is needed.
     */
    internal class RescueDns(private val first: Dns, private val rescuers: List<Pair<String, Dns>> = defaultRescuers) : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            try {
                return first.lookup(hostname)
            } catch (e: java.net.UnknownHostException) {
                // What each one answered goes into the message, so a report shows what blocks it.
                val why = mutableListOf("this device: ${e.message?.substringAfterLast(": ")?.take(70) ?: "no answer"}")
                for ((name, r) in rescuers) {
                    try {
                        val found = r.lookup(hostname)
                        if (found.isNotEmpty()) return found
                        why += "$name: no answer"
                    } catch (x: Exception) {
                        // DNS over HTTPS reports only the name; the reason (blocked, timeout, certificate…) is the cause.
                        val reason = generateSequence(x as Throwable) { it.cause }.last()
                        why += "$name: ${reason.javaClass.simpleName}${reason.message?.takeIf { it != hostname }?.let { " ${it.take(70)}" } ?: ""}"
                    }
                }
                throw java.net.UnknownHostException("$hostname not found (${why.joinToString("; ")})")
            }
        }

        companion object {
            private val defaultRescuers: List<Pair<String, Dns>> by lazy {
                val boot = OkHttpClient.Builder().callTimeout(8, TimeUnit.SECONDS).build()
                listOf(
                    Triple("Cloudflare", "https://cloudflare-dns.com/dns-query", listOf("1.1.1.1", "1.0.0.1")),
                    Triple("Google DNS", "https://dns.google/dns-query", listOf("8.8.8.8", "8.8.4.4")),
                ).map { (name, url, ips) ->
                    name to DnsOverHttps.Builder().client(boot).url(url.toHttpUrl()).bootstrapDnsHosts(ips.map { InetAddress.getByName(it) }).build()
                }
            }
        }
    }

    private fun form(vararg pairs: Pair<String, String>) = FormBody.Builder().apply { pairs.forEach { add(it.first, it.second) } }.build()

    /** Step 1: asks Google for a code to show. */
    suspend fun startSignIn(): SignIn {
        if (!configured) throw CloudException("Google sign-in isn't set up in this build")
        val (code, body) = call(Request.Builder().url(endpoints.deviceCode).post(form("client_id" to clientId, "scope" to SCOPE)).build())
        val o = runCatching { JSONObject(body) }.getOrNull() ?: throw CloudException("Google didn't answer ($code)")
        if (code != 200) throw CloudException(o.optString("error_description").ifBlank { o.optString("error", "error $code") })
        return SignIn(
            o.getString("user_code"),
            o.optString("verification_url").ifBlank { o.optString("verification_uri", "https://www.google.com/device") },
            o.getString("device_code"),
            o.optInt("interval", 5).coerceAtLeast(1),
            System.currentTimeMillis() + o.optLong("expires_in", 1800) * 1000,
        )
    }

    /** Step 2: waits until the code has been entered (true), or it was refused or expired (exception). */
    suspend fun finishSignIn(signIn: SignIn): Boolean {
        var interval = signIn.intervalSeconds * 1000L
        while (System.currentTimeMillis() < signIn.expiresAt) {
            delay(interval)
            val (code, body) = call(Request.Builder().url(endpoints.token).post(form(
                "client_id" to clientId, "client_secret" to clientSecret, "device_code" to signIn.deviceCode,
                "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
            )).build())
            val o = runCatching { JSONObject(body) }.getOrNull() ?: continue
            if (code == 200 && o.has("access_token")) {
                saveTokens(o)
                prefs.edit().putString("email", runCatching { fetchEmail() }.getOrNull()).apply()
                publish()
                return true
            }
            when (o.optString("error")) {
                "authorization_pending" -> Unit
                "slow_down" -> interval += 5000
                "access_denied" -> throw CloudException("Sign-in was cancelled")
                "expired_token" -> throw CloudException("The code expired. Try again.")
                else -> throw CloudException(o.optString("error_description").ifBlank { o.optString("error", "error $code") })
            }
        }
        throw CloudException("The code expired. Try again.")
    }

    private fun saveTokens(o: JSONObject) {
        prefs.edit().apply {
            putString("access", o.getString("access_token"))
            putLong("access_until", System.currentTimeMillis() + (o.optLong("expires_in", 3600) - 60) * 1000)
            o.optString("refresh_token").takeIf { it.isNotBlank() }?.let { putString("refresh", it) }
        }.commit()
    }

    private val tokenLock = Mutex()

    /** A valid access token, refreshed when needed. */
    private suspend fun accessToken(force: Boolean = false): String = tokenLock.withLock {
        val current = prefs.getString("access", null)
        if (!force && current != null && System.currentTimeMillis() < prefs.getLong("access_until", 0)) return current
        val refresh = prefs.getString("refresh", null) ?: throw CloudException("Not signed in")
        val (code, body) = call(Request.Builder().url(endpoints.token).post(form(
            "client_id" to clientId, "client_secret" to clientSecret, "refresh_token" to refresh, "grant_type" to "refresh_token",
        )).build())
        val o = runCatching { JSONObject(body) }.getOrNull()
        if (code == 200 && o?.has("access_token") == true) { saveTokens(o); return o.getString("access_token") }
        if (o?.optString("error") == "invalid_grant") {
            // Access was removed (in the Google account, or the password changed): this device is signed out.
            forget()
            throw CloudException("Google access was removed. Sign in again.")
        }
        throw CloudException("Couldn't reach Google (${o?.optString("error")?.ifBlank { null } ?: code})")
    }

    /** Calls Drive with the token; on 401 refreshes once. */
    private suspend fun drive(build: Request.Builder): Pair<Int, String> {
        var (code, body) = call(build.header("Authorization", "Bearer ${accessToken()}").build())
        if (code == 401) {
            val again = call(build.header("Authorization", "Bearer ${accessToken(force = true)}").build())
            code = again.first; body = again.second
        }
        return code to body
    }

    private suspend fun fetchEmail(): String? {
        val (code, body) = drive(Request.Builder().url("${endpoints.drive}/about?fields=user(emailAddress,displayName)"))
        if (code != 200) return null
        val user = JSONObject(body).optJSONObject("user") ?: return null
        return user.optString("emailAddress").ifBlank { user.optString("displayName") }.ifBlank { null }
    }

    private fun forget() {
        prefs.edit().remove("access").remove("access_until").remove("refresh").remove("email").remove("last_backup").remove("last_hash").commit()
        runCatching { baseFile.delete() }
        publish()
    }

    /** Signs this device out and removes its access from the Google account. The backup stays in Drive. */
    suspend fun signOut() {
        val token = prefs.getString("refresh", null)
        forget()
        if (token != null) runCatching { call(Request.Builder().url(endpoints.revoke).post(form("token" to token)).build()) }
    }

    // ------------------------------------------------------------------ backup file

    /** A backup in Drive: the shared one ([SHARED], every device's) or an old one of a single device. */
    data class Remote(
        val id: String, val modified: String, val device: String?, val app: String?, val size: Long, val deviceId: String?,
        val name: String = "",
    ) {
        /** The one backup every device shares and keeps in sync. */
        val shared: Boolean get() = name == SHARED
        /** This device's own old backup (from before the shared one). */
        val mine: Boolean get() = !shared && deviceId != null && deviceId == CloudBackup.deviceId
        /** When it was saved, in milliseconds (0 if unknown). */
        val time: Long get() = runCatching { java.time.Instant.parse(modified).toEpochMilli() }.getOrDefault(0)
    }

    /** The shared backup first, then any old ones, newest first. */
    suspend fun backups(): List<Remote> {
        val q = URLEncoder.encode("name contains '$FILE_PREFIX' and trashed = false", "UTF-8")
        val (code, body) = drive(Request.Builder().url(
            "${endpoints.drive}/files?q=$q&pageSize=50&orderBy=modifiedTime%20desc&fields=files(id,name,modifiedTime,size,appProperties)"))
        if (code != 200) throw CloudException("Drive answered $code")
        val files = JSONObject(body).optJSONArray("files") ?: return emptyList()
        return (0 until files.length()).map { i ->
            val f = files.getJSONObject(i)
            val props = f.optJSONObject("appProperties")
            Remote(f.getString("id"), f.optString("modifiedTime"), props?.optString("device")?.ifBlank { null }, props?.optString("app")?.ifBlank { null },
                f.optString("size").toLongOrNull() ?: 0, props?.optString("deviceId")?.ifBlank { null }, f.optString("name"))
        }.sortedWith(compareByDescending<Remote> { it.shared }.thenByDescending { it.time })
    }

    /** The "Stream Goat backups" folder: the one the app made before (on any device), or a new one. */
    private suspend fun folder(): String {
        val q = URLEncoder.encode("mimeType = 'application/vnd.google-apps.folder' and name = '$FOLDER' and trashed = false", "UTF-8")
        val (code, body) = drive(Request.Builder().url("${endpoints.drive}/files?q=$q&pageSize=10&orderBy=createdTime&fields=files(id)"))
        if (code != 200) throw CloudException("Drive answered $code")
        JSONObject(body).optJSONArray("files")?.optJSONObject(0)?.optString("id")?.ifBlank { null }?.let { return it }
        val meta = JSONObject().put("name", FOLDER).put("mimeType", "application/vnd.google-apps.folder")
        val (made, answer) = drive(Request.Builder().url("${endpoints.drive}/files?fields=id").post(meta.toString().toRequestBody(json)))
        if (made !in 200..299) throw CloudException("Drive answered $made: ${answer.take(200)}")
        return JSONObject(answer).getString("id")
    }

    /** The shared backup (or, before there is one, the newest old one). */
    suspend fun remote(): Remote? = backups().firstOrNull()

    /** Removes a backup from Drive. */
    suspend fun delete(remote: Remote) {
        val (code, _) = drive(Request.Builder().url("${endpoints.drive}/files/${remote.id}").delete())
        if (code !in 200..299 && code != 404) throw CloudException("Drive answered $code")
    }

    /** Moves a backup to Drive's trash (Google keeps it there for 30 days). */
    private suspend fun trash(remote: Remote) {
        val (code, _) = drive(Request.Builder().url("${endpoints.drive}/files/${remote.id}")
            .patch(JSONObject().put("trashed", true).toString().toRequestBody(json)))
        if (code !in 200..299 && code != 404) throw CloudException("Drive answered $code")
    }

    private suspend fun download(remote: Remote): String {
        val (code, content) = drive(Request.Builder().url("${endpoints.drive}/files/${remote.id}?alt=media"))
        if (code != 200) throw CloudException("Drive answered $code")
        return content
    }

    /**
     * The shared backup as this device last synced it (which file, its version, its content): what changed since,
     * here and there, is merged. Only for that file: another account's (or a re-made) file starts afresh, so
     * nothing is removed because it's missing there.
     */
    private class Base(val fileId: String, val stamp: String, val stores: JSONObject)
    private val baseFile get() = java.io.File(App.context.filesDir, "cloud_sync_base.json")
    private fun readBase(): Base? = runCatching {
        val o = JSONObject(baseFile.readText()); Base(o.getString("file"), o.getString("stamp"), o.getJSONObject("stores"))
    }.getOrNull()
    private fun writeBase(fileId: String, stamp: String, stores: JSONObject) {
        runCatching { baseFile.writeText(JSONObject().put("file", fileId).put("stamp", stamp).put("stores", stores).toString()) }
    }

    private val backupLock = Mutex()

    /**
     * Syncs this device with the shared backup: downloads it, merges it with what's here both ways (BackupMerge:
     * what changed on either side is kept), keeps the result here and uploads it in place of the old one. The
     * first time, the old one-file-per-device backups are merged in too (the newest of each device) and moved to
     * Drive's trash. With [onlyIfChanged], does nothing when nothing changed here or there (returns false).
     */
    suspend fun backupNow(onlyIfChanged: Boolean = false): Boolean = backupLock.withLock {
        try {
            val all = backups()
            val main = all.firstOrNull { it.shared }
            val old = all.filter { it !== main }
            val local = withContext(Dispatchers.IO) { BackupMerge.content(Backup.stores(Backup.export())) }
            val base = readBase()?.takeIf { main != null && it.fileId == main.id }
            val remoteChanged = main != null && main.modified != base?.stamp
            if (onlyIfChanged && old.isEmpty() && main != null && !remoteChanged &&
                BackupMerge.canon(local) == BackupMerge.canon(base!!.stores)) return@withLock false
            publish(busy = "Syncing…")
            val remote = when {
                main == null -> null
                !remoteChanged -> base!!.stores
                else -> BackupMerge.content(Backup.stores(download(main)))
            }
            var merged = BackupMerge.merge(base?.stores, local, remote)
            // The old one-file-per-device backups (and a second shared one, if two devices made it at once): the
            // newest of each other device is merged in, so nothing is lost; this device's own is older than now.
            val olds = old.filter { it.shared } + old.filter { !it.shared && !it.mine }.groupBy { it.device ?: it.id }.values.map { l -> l.maxBy { it.time } }
            for (o in olds) runCatching { BackupMerge.content(Backup.stores(download(o))) }.getOrNull()?.let { merged = BackupMerge.merge(null, merged, it) }
            val changedHere = withContext(Dispatchers.IO) { Backup.applyContent(merged, local) }
            var fileId = main?.id
            var stamp = main?.modified ?: ""
            if (remote == null || BackupMerge.canon(merged) != BackupMerge.canon(remote)) {
                val meta = JSONObject().put("name", SHARED).put("appProperties", JSONObject().put("device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}").put("app", runCatching { App.context.packageManager.getPackageInfo(App.context.packageName, 0).versionName }.getOrNull() ?: "?").put("deviceId", deviceId))
                if (main == null) meta.put("parents", org.json.JSONArray().put(folder()))
                val body = MultipartBody.Builder().setType("multipart/related".toMediaType())
                    .addPart(meta.toString().toRequestBody(json))
                    .addPart(Backup.document(merged).toRequestBody(json))
                    .build()
                val request = if (main == null) Request.Builder().url("${endpoints.upload}/files?uploadType=multipart&fields=id,modifiedTime").post(body)
                else Request.Builder().url("${endpoints.upload}/files/${main.id}?uploadType=multipart&fields=id,modifiedTime").patch(body)
                val (code, answer) = drive(request)
                if (code !in 200..299) throw CloudException("Drive answered $code: ${answer.take(200)}")
                val saved = JSONObject(answer)
                fileId = saved.getString("id")
                stamp = saved.optString("modifiedTime").ifBlank { backups().firstOrNull { it.id == fileId }?.modified ?: "" }
            }
            writeBase(fileId!!, stamp, merged)
            // Merged in: the old files go to Drive's trash (kept there 30 days), so Restore shows one backup.
            for (o in old) runCatching { trash(o) }
            prefs.edit().putLong("last_backup", System.currentTimeMillis()).apply()
            if (changedHere.isNotEmpty()) synced(changedHere)
            publish()
            true
        } catch (e: Exception) {
            publish(error = e.message ?: e.toString())
            throw e
        }
    }

    /** Another device's changes arrived: the library's screens refresh, its new extensions are downloaded. */
    private suspend fun synced(stores: Set<String>) {
        runCatching { Library.notifyChanged() }
        if (stores.any { it.startsWith("rebuild_preference") }) runCatching { PluginManager.installMissing(includeNew = false) }
    }

    /**
     * Replaces what's here with a backup ([which], or the shared one); then the missing extensions are downloaded.
     * Returns their names.
     */
    suspend fun restore(which: Remote? = null): List<String> = backupLock.withLock {
        publish(busy = "Restoring…")
        try {
            val r = which ?: remote() ?: throw CloudException("There's no backup in this Google account yet")
            val content = download(r)
            withContext(Dispatchers.IO) { Backup.import(content) }
            // Restored from the shared backup: this device is now in step with it.
            if (r.shared) writeBase(r.id, r.modified, BackupMerge.content(Backup.stores(content)))
            publish(busy = "Downloading the extensions…")
            val got = runCatching { PluginManager.installMissing(includeNew = false) }.getOrDefault(emptyList())
            runCatching { Library.notifyChanged() }
            publish()
            got
        } catch (e: Exception) {
            publish(error = e.message ?: e.toString())
            throw e
        }
    }

    // ------------------------------------------------------------------ automatic backup

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null

    /** Starts the automatic backup (every 5 minutes, only when something changed). */
    fun startAuto(firstDelayMs: Long = 60_000) {
        if (loop?.isActive == true) return
        loop = scope.launch {
            delay(firstDelayMs)
            while (isActive) {
                soonNow()
                delay(AUTO_INTERVAL_MS)
            }
        }
    }

    /**
     * Syncs now if automatic backup is on and something changed here or on another device. Coming back to the app
     * calls it (at most once a minute); going to the background calls it with [always], so nothing is left behind.
     */
    fun soon(always: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!always && now - lastSoon < 60_000) return
        lastSoon = now
        scope.launch { soonNow() }
    }
    @Volatile private var lastSoon = 0L

    private suspend fun soonNow() {
        if (!configured || !auto || prefs.getString("refresh", null) == null) return
        runCatching { backupNow(onlyIfChanged = true) }
    }
}
