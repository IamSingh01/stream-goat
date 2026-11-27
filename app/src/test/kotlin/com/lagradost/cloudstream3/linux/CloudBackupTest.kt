package com.lagradost.cloudstream3.linux

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.URLDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Google Drive backup against a stand-in for Google: code sign-in (with "pending" first), one shared backup that
 * every device keeps in sync (changes from both sides kept, old per-device backups merged in and trashed, each
 * device's settings left alone), a skipped upload when nothing changed, an expired token refreshed, restore, sign-out.
 */
class CloudBackupTest {
    private class FakeGoogle {
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var polls = 0
        var uploads = 0
        var refreshes = 0
        var revoked: String? = null
        var validAccess = "access-1"
        private var nextId = 1
        private var clock = 1_700_000_000_000L
        /** id → name, appProperties, modifiedTime, content */
        val files = linkedMapOf<String, JSONObject>()
        /** Moved to Drive's trash. */
        val trashed = linkedMapOf<String, JSONObject>()
        /** Folders the app made: id → name. */
        val folders = linkedMapOf<String, String>()

        val base get() = "http://127.0.0.1:${server.address.port}"

        private fun form(x: HttpExchange) = x.requestBody.readBytes().decodeToString().split('&').filter { it.contains('=') }
            .associate { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }

        private fun send(x: HttpExchange, code: Int, body: String) {
            val bytes = body.toByteArray()
            x.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong()); x.responseBody.use { it.write(bytes) }
        }

        private fun authorized(x: HttpExchange) = x.requestHeaders.getFirst("Authorization") == "Bearer $validAccess"

        init {
            server.createContext("/device/code") { x ->
                // Google's code sign-in refuses drive.appdata ("Invalid device flow scope"); drive.file is allowed.
                if (form(x)["scope"] != "https://www.googleapis.com/auth/drive.file") return@createContext send(x, 400, """{"error":"invalid_scope"}""")
                send(x, 200, """{"device_code":"dev-1","user_code":"GQV-XHF-RNL","verification_url":"https://www.google.com/device","expires_in":60,"interval":1}""")
            }
            server.createContext("/token") { x ->
                val f = form(x)
                when (f["grant_type"]) {
                    "urn:ietf:params:oauth:grant-type:device_code" ->
                        if (++polls < 2) send(x, 428, """{"error":"authorization_pending"}""")
                        else send(x, 200, """{"access_token":"access-1","refresh_token":"refresh-1","expires_in":3600}""")
                    "refresh_token" -> {
                        require(f["refresh_token"] == "refresh-1")
                        refreshes++; validAccess = "access-${refreshes + 1}"
                        send(x, 200, """{"access_token":"$validAccess","expires_in":3600}""")
                    }
                    else -> send(x, 400, """{"error":"unsupported_grant_type"}""")
                }
            }
            server.createContext("/revoke") { x -> revoked = form(x)["token"]; send(x, 200, "") }
            server.createContext("/drive/v3/about") { x ->
                if (!authorized(x)) send(x, 401, "{}") else send(x, 200, """{"user":{"emailAddress":"goat@example.com"}}""")
            }
            server.createContext("/drive/v3/files") { x ->
                if (!authorized(x)) return@createContext send(x, 401, "{}")
                val id = x.requestURI.path.removePrefix("/drive/v3/files").removePrefix("/")
                when {
                    id.isEmpty() && x.requestMethod == "POST" -> {
                        val meta = JSONObject(x.requestBody.readBytes().decodeToString())
                        require(meta.getString("mimeType") == "application/vnd.google-apps.folder")
                        val fid = "folder-${nextId++}"; folders[fid] = meta.getString("name")
                        send(x, 200, """{"id":"$fid"}""")
                    }
                    id.isEmpty() && URLDecoder.decode(x.requestURI.rawQuery, "UTF-8").contains("application/vnd.google-apps.folder") -> {
                        val q = URLDecoder.decode(x.requestURI.rawQuery, "UTF-8")
                        val list = JSONArray()
                        folders.filter { q.contains("name = '${it.value}'") }.keys.forEach { list.put(JSONObject().put("id", it)) }
                        send(x, 200, JSONObject().put("files", list).toString())
                    }
                    id.isEmpty() -> {
                        val q = URLDecoder.decode(x.requestURI.rawQuery, "UTF-8")
                        require(!q.contains("spaces=appDataFolder") && q.contains("name contains 'stream-goat-backup'"))
                        val list = JSONArray()
                        files.forEach { (fid, f) -> list.put(JSONObject().put("id", fid).put("name", f.getString("name"))
                            .put("modifiedTime", java.time.Instant.ofEpochMilli(f.getLong("modified")).toString())
                            .put("appProperties", f.getJSONObject("appProperties"))) }
                        send(x, 200, JSONObject().put("files", list).toString())
                    }
                    x.requestMethod == "DELETE" -> { files.remove(id); send(x, 204, "") }
                    // Moved to the trash: gone from the lists (Google keeps it 30 days).
                    x.requestMethod == "PATCH" && JSONObject(x.requestBody.readBytes().decodeToString()).optBoolean("trashed") ->
                        { files.remove(id)?.let { trashed[id] = it }; send(x, 200, """{"id":"$id"}""") }
                    x.requestURI.rawQuery == "alt=media" -> send(x, 200, files[id]?.getString("content") ?: "")
                    else -> send(x, 404, "{}")
                }
            }
            server.createContext("/upload/drive/v3/files") { x ->
                if (!authorized(x)) return@createContext send(x, 401, "{}")
                val boundary = x.requestHeaders.getFirst("Content-Type").substringAfter("boundary=").trim('"')
                val parts = x.requestBody.readBytes().decodeToString().split("--$boundary").filter { it.contains("{") }
                val meta = JSONObject(parts[0].substringAfter("\r\n\r\n").trim())
                val content = parts[1].substringAfter("\r\n\r\n").trim()
                val id = if (x.requestMethod == "POST") {
                    require(meta.getJSONArray("parents").getString(0) in folders)
                    "file-${nextId++}".also { files[it] = JSONObject().put("name", meta.getString("name")) }
                } else x.requestURI.path.substringAfterLast('/').also { require(x.requestMethod == "PATCH" && it in files) }
                val at = clock++
                files.getValue(id).put("appProperties", meta.getJSONObject("appProperties")).put("content", content).put("modified", at)
                uploads++
                send(x, 200, """{"id":"$id","modifiedTime":"${java.time.Instant.ofEpochMilli(at)}"}""")
            }
            server.start()
        }

        /** A backup file put there by another device (an old per-device one, or the shared one changed). */
        fun put(name: String, device: String, deviceId: String, stores: JSONObject, id: String? = null): String {
            val fid = id ?: "file-${nextId++}"
            val content = JSONObject().put("format", Backup.FORMAT).put("version", 1).put("stores", stores).toString()
            files[fid] = JSONObject().put("name", name).put("content", content).put("modified", clock++)
                .put("appProperties", JSONObject().put("device", device).put("deviceId", deviceId))
            return fid
        }

        fun stores(id: String): JSONObject = JSONObject(files.getValue(id).getString("content")).getJSONObject("stores")
    }

    private val store get() = Platform.context.getSharedPreferences("cloud_test", 0)
    private val account get() = Platform.context.getSharedPreferences("cloud_backup", 0)

    @Test
    fun `a DNS without an answer for Google falls back to DNS over HTTPS`() {
        val blocked = okhttp3.Dns { throw java.net.UnknownHostException("Unable to resolve host \"$it\": No address associated with hostname") }
        val local = java.net.InetAddress.getByName("127.0.0.1")
        val rescued = CloudBackup.RescueDns(blocked, listOf("Cloudflare" to okhttp3.Dns { throw java.net.UnknownHostException("down") }, "Google" to okhttp3.Dns { listOf(local) }))
        assertEquals(listOf(local), rescued.lookup("oauth2.googleapis.com"))
        // Nothing answers: the message says what each one said.
        val none = CloudBackup.RescueDns(blocked, listOf("Cloudflare" to okhttp3.Dns { emptyList() }, "Google" to okhttp3.Dns { throw java.net.SocketTimeoutException("timeout") }))
        val error = runCatching { none.lookup("oauth2.googleapis.com") }.exceptionOrNull()
        assertTrue(error is java.net.UnknownHostException)
        assertEquals("oauth2.googleapis.com not found (this device: No address associated with hostname; Cloudflare: no answer; Google: SocketTimeoutException timeout)", error!!.message)
        // A working DNS is used as it is.
        assertEquals(listOf(local), CloudBackup.RescueDns(okhttp3.Dns { listOf(local) }, emptyList()).lookup("oauth2.googleapis.com"))
    }

    @Test
    fun `sign-in steps have a second address at accounts google com`() {
        val e = CloudBackup.Endpoints()
        assertEquals("https://accounts.google.com/o/oauth2/device/code", CloudBackup.fallbackUrl(e.deviceCode, e))
        assertEquals("https://accounts.google.com/o/oauth2/token", CloudBackup.fallbackUrl(e.token, e))
        assertEquals("https://accounts.google.com/o/oauth2/revoke", CloudBackup.fallbackUrl(e.revoke, e))
        assertEquals(null, CloudBackup.fallbackUrl("${e.drive}/files", e))
    }

    /** My list in the test's library store ("sync_test" stands for library: it's content, so it's synced). */
    private val lib get() = Platform.context.getSharedPreferences("sync_test", 0)
    private val device get() = Platform.context.getSharedPreferences("app_settings", 0)
    private fun list(vararg urls: String) = JSONArray(urls.map { JSONObject().put("apiName", "X").put("url", it) }).toString()
    private fun listStore(vararg urls: String) = JSONObject().put("sync_test", JSONObject().put("bookmarks", JSONObject().put("s", list(*urls))))
    private fun urlsOf(json: String?) = JSONArray(json ?: "[]").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("url") }.sorted() }
    private fun urlsHere() = urlsOf(lib.getString("bookmarks", null))
    private fun urlsIn(stores: JSONObject) = urlsOf(stores.optJSONObject("sync_test")?.optJSONObject("bookmarks")?.optString("s"))

    @Test
    fun `sign in with a code, one shared backup kept in sync by every device, old ones merged and trashed, sign out`(): Unit = runBlocking {
        Platform.init(); Network.init()
        val google = FakeGoogle()
        val before = CloudBackup.endpoints
        CloudBackup.endpoints = CloudBackup.Endpoints(
            deviceCode = "${google.base}/device/code", token = "${google.base}/token", revoke = "${google.base}/revoke",
            drive = "${google.base}/drive/v3", upload = "${google.base}/upload/drive/v3",
        )
        CloudBackup.clientId = "test-client"; CloudBackup.clientSecret = "test-secret"
        val layout = device.getString("layout", null)
        try {
            CloudBackup.signOut()
            val signIn = CloudBackup.startSignIn()
            assertEquals("GQV-XHF-RNL", signIn.userCode)
            assertTrue(CloudBackup.finishSignIn(signIn))
            assertEquals(2, google.polls)
            assertTrue(CloudBackup.state.value.signedIn)
            assertEquals("goat@example.com", CloudBackup.state.value.email)
            google.folders["folder-0"] = "Stream Goat backups"

            // Old versions left one backup per device: two from the TV (an old install and the latest) and the tablet's.
            val oldTv = google.put("stream-goat-backup-tv-old.json", "Google TV Streamer", "tv-old", listStore("deleted-long-ago"))
            google.put("stream-goat-backup-tv.json", "Google TV Streamer", "tv", listStore("tv-film"))
            google.put("stream-goat-backup-tab.json", "Lenovo", "tab", listStore("tablet-film"))
            // This computer: its own film, and its own layout setting.
            lib.edit().putString("bookmarks", list("pc-film")).commit()
            device.edit().putString("layout", "tv").commit()

            // The first sync: one shared file, with everyone's films (the newest backup of each device), the old ones
            // in the trash. Each device's own settings aren't in it.
            assertTrue(CloudBackup.backupNow())
            val shared = google.files.entries.single()
            assertEquals(CloudBackup.SHARED, shared.value.getString("name"))
            assertEquals(listOf("pc-film", "tablet-film", "tv-film"), urlsIn(google.stores(shared.key)))
            assertEquals(listOf("pc-film", "tablet-film", "tv-film"), urlsHere())
            assertFalse(google.stores(shared.key).has("app_settings"))
            assertFalse(google.stores(shared.key).has("cloud_backup"))
            assertEquals(3, google.trashed.size)
            assertTrue(oldTv in google.trashed)
            assertTrue(CloudBackup.backups().single().shared)

            // Nothing changed anywhere: nothing is uploaded.
            val uploads = google.uploads
            assertFalse(CloudBackup.backupNow(onlyIfChanged = true))
            assertEquals(uploads, google.uploads)

            // The TV removes the tablet's film and adds one; meanwhile this computer adds one. Both changes stay.
            google.put(CloudBackup.SHARED, "Google TV Streamer", "tv", listStore("pc-film", "tv-film", "tv-new"), id = shared.key)
            lib.edit().putString("bookmarks", list("pc-film", "tablet-film", "tv-film", "pc-new")).commit()
            assertTrue(CloudBackup.backupNow(onlyIfChanged = true))
            assertEquals(listOf("pc-film", "pc-new", "tv-film", "tv-new"), urlsHere())
            assertEquals(listOf("pc-film", "pc-new", "tv-film", "tv-new"), urlsIn(google.stores(shared.key)))
            assertEquals(1, google.files.size, "the same file, replaced")
            // This computer's layout was never touched by the sync.
            assertEquals("tv", device.getString("layout", null))

            // Another device's change alone comes here too (an expired token is refreshed on the way).
            google.put(CloudBackup.SHARED, "Lenovo", "tab", listStore("pc-film", "pc-new", "tv-film", "tv-new", "tablet-new"), id = shared.key)
            google.validAccess = "access-expired-on-server"
            assertTrue(CloudBackup.backupNow(onlyIfChanged = true))
            assertTrue(google.refreshes >= 1)
            assertEquals(listOf("pc-film", "pc-new", "tablet-new", "tv-film", "tv-new"), urlsHere())

            // Restore replaces what's here with the shared backup.
            lib.edit().putString("bookmarks", list("only-here")).commit()
            CloudBackup.restore(CloudBackup.backups().single())
            assertEquals(listOf("pc-film", "pc-new", "tablet-new", "tv-film", "tv-new"), urlsHere())

            CloudBackup.signOut()
            assertEquals("refresh-1", google.revoked)
            assertFalse(CloudBackup.state.value.signedIn)
            assertFailsWith<CloudBackup.CloudException> { CloudBackup.backupNow() }
        } finally {
            lib.edit().clear().commit()
            device.edit().apply { if (layout == null) remove("layout") else putString("layout", layout) }.commit()
            CloudBackup.endpoints = before
            google.server.stop(0)
        }
    }
}
