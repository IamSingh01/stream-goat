package com.lagradost.cloudstream3.linux

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** WeTrakr sync against a stand-in for api.wetrakr.com: device sign-in, refresh, scrobbling and sign-out. */
class WeTrakrTest {
    private lateinit var server: HttpServer
    private val requests = LinkedBlockingQueue<Triple<String, Map<String, String>, JSONObject>>()
    private var polls = 0
    private var access = "acc1"

    @BeforeTest
    fun setUp() {
        Platform.init()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val body = runCatching { JSONObject(ex.requestBody.readBytes().decodeToString()) }.getOrDefault(JSONObject())
            val headers = ex.requestHeaders.entries.associate { it.key.lowercase() to it.value.first() }
            requests += Triple(ex.requestURI.path, headers, body)
            val (code, out) = when (ex.requestURI.path) {
                "/oauth/device/code" -> 200 to JSONObject().put("device_code", "dev123").put("user_code", "K7PX4M")
                    .put("verification_url", "https://wetrakr.com/activate").put("expires_in", 600).put("interval", 5)
                "/oauth/device/token" -> if (++polls < 2) 400 to JSONObject().put("error", "authorization_pending")
                    else 200 to JSONObject().put("access_token", access).put("refresh_token", "ref1").put("expires_in", 604800)
                "/oauth/token/refresh" -> 200 to JSONObject().put("access_token", "acc2").put("refresh_token", "ref2").put("expires_in", 604800)
                "/scrobble/start" -> 201 to JSONObject().put("action", "start")
                "/scrobble/stop" -> 201 to JSONObject().put("action", "scrobble")
                "/oauth/logout" -> 200 to JSONObject().put("revoked", true)
                else -> 404 to JSONObject()
            }
            val bytes = out.toString().toByteArray()
            ex.sendResponseHeaders(code, bytes.size.toLong()); ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        System.setProperty("cs.wetrakr.api", "http://127.0.0.1:${server.address.port}")
        System.setProperty("cs.wetrakr.client", "test-client")
    }

    @AfterTest
    fun tearDown() {
        WeTrakr.disconnect()
        Thread.sleep(300)
        server.stop(0)
        System.clearProperty("cs.wetrakr.api"); System.clearProperty("cs.wetrakr.client")
    }

    private fun next() = requests.poll(5, TimeUnit.SECONDS)!!
    private val api = object : MainAPI() {}

    @Test
    fun `device sign-in, now watching, marked watched, refresh and sign-out`() = runBlocking {
        WeTrakr.disconnect(); requests.clear()
        assertTrue(WeTrakr.available)
        assertFalse(WeTrakr.connected)
        val code = WeTrakr.start()
        assertEquals("K7PX4M", code.userCode)
        next().let { (path, headers, body) ->
            assertEquals("/oauth/device/code", path)
            assertEquals("1", headers["wetrakr-api-version"])
            assertTrue(headers["user-agent"].orEmpty().matches(Regex("StreamGoat/[0-9.]+ .*")), "names the app (WeTrakr blocks library defaults)")
            assertEquals("test-client", body.getString("client_id"))
        }
        assertFalse(WeTrakr.poll(code), "pending")
        assertTrue(WeTrakr.poll(code), "approved")
        assertTrue(WeTrakr.connected)
        requests.clear()

        val show = api.newTvSeriesLoadResponse("Breaking Bad", "https://x/bb", TvType.TvSeries, emptyList()) { year = 2008 }
        val s = WeTrakr.Session(show, 1, 2)
        s.playing(12.5)
        next().let { (path, headers, body) ->
            assertEquals("/scrobble/start", path)
            assertEquals("Bearer acc1", headers["authorization"])
            assertEquals("test-client", headers["wetrakr-api-key"])
            assertEquals("Breaking Bad", body.getJSONObject("show").getString("title"))
            assertEquals(2, body.getJSONObject("episode").getInt("number"))
            assertEquals(12.5, body.getDouble("progress"))
        }
        s.tick(20.0) // too soon after the last update: nothing sent
        s.stopped(96.0)
        next().let { (path, _, body) -> assertEquals("/scrobble/stop", path); assertEquals(96.0, body.getDouble("progress")) }
        s.stopped(97.0) // a playback stops once
        assertNull(requests.poll(1, TimeUnit.SECONDS))

        // A token that runs out within a day is renewed before the next call.
        Platform.context.getSharedPreferences(Profiles.file("wetrakr"), 0).edit().putLong("expires", System.currentTimeMillis() + 3600_000).apply()
        val movie = api.newMovieLoadResponse("Dune: Part Two", "https://x/dune", TvType.Movie, "data") { year = 2024 }
        WeTrakr.Session(movie, null, null).playing(1.0)
        next().let { (path, _, body) -> assertEquals("/oauth/token/refresh", path); assertEquals("ref1", body.getString("refresh_token")) }
        next().let { (path, headers, body) ->
            assertEquals("/scrobble/start", path)
            assertEquals("Bearer acc2", headers["authorization"])
            assertEquals("Dune: Part Two", body.getJSONObject("movie").getString("title"))
            assertFalse(body.has("episode"))
        }

        WeTrakr.disconnect()
        assertFalse(WeTrakr.connected)
        next().let { (path, _, body) -> assertEquals("/oauth/logout", path); assertEquals("ref2", body.getString("refresh_token")) }
        Unit
    }
}
