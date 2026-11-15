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
 * Google Drive backup against a stand-in for Google: code sign-in (with "pending" first), each device's own file in
 * Drive's app folder (a phone's and a TV's don't overwrite each other), a skipped upload when nothing changed, an
 * expired token refreshed, restoring another device's backup, deleting one, sign-out.
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
                files.getValue(id).put("appProperties", meta.getJSONObject("appProperties")).put("content", content).put("modified", clock++)
                uploads++
                send(x, 200, """{"id":"$id"}""")
            }
            server.start()
        }
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
        assertEquals("oauth2.googleapis.com not found (this device: No address associated with hostname; Cloudflare: no answer; Google: timeout)", error!!.message)
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

    @Test
    fun `sign in with a code, each device backs up its own file, restore another device's, sign out`(): Unit = runBlocking {
        Platform.init(); Network.init()
        val google = FakeGoogle()
        val before = CloudBackup.endpoints
        CloudBackup.endpoints = CloudBackup.Endpoints(
            deviceCode = "${google.base}/device/code", token = "${google.base}/token", revoke = "${google.base}/revoke",
            drive = "${google.base}/drive/v3", upload = "${google.base}/upload/drive/v3",
        )
        CloudBackup.clientId = "test-client"; CloudBackup.clientSecret = "test-secret"
        try {
            CloudBackup.signOut()
            val signIn = CloudBackup.startSignIn()
            assertEquals("GQV-XHF-RNL", signIn.userCode)
            assertTrue(CloudBackup.finishSignIn(signIn))
            assertEquals(2, google.polls)
            assertTrue(CloudBackup.state.value.signedIn)
            assertEquals("goat@example.com", CloudBackup.state.value.email)
            val tv = CloudBackup.deviceId

            // This device ("the TV") backs up its own file; unchanged, nothing is uploaded again.
            store.edit().putString("value", "tv").commit()
            assertEquals(emptyList(), CloudBackup.backups())
            assertTrue(CloudBackup.backupNow())
            assertFalse(CloudBackup.backupNow(onlyIfChanged = true))
            assertEquals(1, google.uploads)
            val mine = CloudBackup.backups().single()
            assertTrue(mine.mine)
            assertEquals("stream-goat-backup-$tv.json", google.files.values.single().getString("name"))
            assertFalse(JSONObject(google.files.values.single().getString("content")).getJSONObject("stores").has("cloud_backup"))

            // "The phone" (another installation on the same account) has its own file: nothing is overwritten.
            account.edit().putString("device_id", "phone-1").commit()
            store.edit().putString("value", "phone").commit()
            assertTrue(CloudBackup.backupNow())
            account.edit().putString("device_id", tv).commit()
            val all = CloudBackup.backups()
            assertEquals(2, all.size)
            // Both went into one "Stream Goat backups" folder in My Drive.
            assertEquals(listOf("Stream Goat backups"), google.folders.values.toList())
            val phone = all.first()
            assertEquals("phone-1", phone.deviceId)
            assertFalse(phone.mine)

            // The TV restores the phone's backup (an expired token is refreshed on the way).
            store.edit().putString("value", "changed on the tv").commit()
            google.validAccess = "access-expired-on-server"
            CloudBackup.restore(phone)
            assertTrue(google.refreshes >= 1)
            assertEquals("phone", store.getString("value", null))

            // An old device's backup can be deleted; the TV's own stays and is updated in place.
            CloudBackup.delete(phone)
            assertEquals(listOf(tv), CloudBackup.backups().map { it.deviceId })
            store.edit().putString("value", "tv again").commit()
            assertTrue(CloudBackup.backupNow(onlyIfChanged = true))
            assertEquals(1, google.files.size)

            CloudBackup.signOut()
            assertEquals("refresh-1", google.revoked)
            assertFalse(CloudBackup.state.value.signedIn)
            assertEquals(tv, CloudBackup.deviceId, "signing out keeps this device's backup name")
            assertFailsWith<CloudBackup.CloudException> { CloudBackup.backupNow() }
        } finally {
            store.edit().clear().commit()
            CloudBackup.endpoints = before
            google.server.stop(0)
        }
    }
}
