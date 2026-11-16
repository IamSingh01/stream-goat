package com.lagradost.cloudstream3.linux

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Public playlists (iptv-org, free-codecs): channels in several groups, headers as #EXTINF attributes, lines a
 * converter broke, names with commas; the same channel in two lists; and checking which channels answer.
 */
class LiveTvPlaylistTest {
    @Test
    fun `channel lines from public playlists`() {
        val list = LiveTv.parseM3u("pk", """
            #EXTM3U url-tvg="https://epg.example/a.xml.gz"
            #EXTINF:-1 tvg-id="1Plus1Ukraina.ua@SD" tvg-logo="https://i.example/a.png" group-title="General",like Gecko) Chrome/120.0.0.0 Safari/537.36" group-title="General",1+1 Ukraina (1080p)
            http://stream.example/219/index.m3u8
            #EXTINF:-1 tvg-id="CartoonNetwork.pk@SD" group-title="Animation;Kids",Cartoon Network (Pakistan)
            https://cdn.example/cn/chunks.m3u8
            #EXTINF:-1 tvg-id="Nature.us@SD" group-title="Documentary",Love Nature, 4K
            https://cdn.example/nature.m3u8
            #EXTINF:-1 tvg-id="X.pk" http-referrer="https://site.example/" http-user-agent="Mozilla/5.0 Test" group-title="News",X News
            https://cdn.example/x.m3u8
            #EXTINF:-1,No Attributes
            https://cdn.example/plain.m3u8
        """.trimIndent())
        assertEquals(listOf("1+1 Ukraina (1080p)", "Cartoon Network (Pakistan)", "Love Nature, 4K", "X News", "No Attributes"), list.map { it.name })
        // The broken line wanted desktop Chrome 120: asked as it.
        assertEquals("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36", list[0].headers["User-Agent"])
        assertEquals("General", list[0].group)
        // Both groups kept: it shows under Kids too (and in a kids profile).
        assertEquals(listOf("Animation", "Kids"), list[1].groups)
        assertEquals("Animation · Kids", list[1].groupLabel)
        assertEquals(mapOf("Referer" to "https://site.example/", "User-Agent" to "Mozilla/5.0 Test"), list[3].headers)
        assertNull(list[4].group)
    }

    @Test
    fun `the same channel in another list is a backup`() {
        fun ch(key: String, name: String, id: String?, url: String) = LiveTv.Channel(key, key.substringBefore(':'), name, null, null, url, epgId = id)
        val hum = ch("pk:1", "Hum TV (720p)", "HumTV.pk@HD", "https://a.example/hum.m3u8")
        val all = listOf(
            hum,
            ch("general:7", "Hum TV HD", "HumTV.pk@SD", "https://b.example/hum.m3u8"),   // same tvg-id, other quality
            ch("kids:3", "HUM TV [Not 24/7]", null, "https://c.example/hum.m3u8"),        // same name without tags
            ch("general:8", "Hum News", "HumNews.pk@SD", "https://d.example/news.m3u8"),  // a different channel
            ch("general:9", "Hum TV", "HumTV.pk@SD", "https://a.example/hum.m3u8"),       // the same address: not a backup
        )
        assertEquals(listOf("general:7", "kids:3"), LiveTv.alternativesIn(all, hum).map { it.key })
        // Not the same channel: a country in brackets, or another language feed of the same id.
        val atvPk = ch("pk:2", "ATV (Pakistan) (1080p)", "ATV.pk@SD", "https://a.example/atv.m3u8")
        val godArabic = ch("pk:3", "God Stands TV Arabic", "GodStandsTV.pk@Arabic", "https://a.example/gs-ar.m3u8")
        val others = listOf(atvPk, godArabic, ch("general:1", "ATV (Armenia) (1080p)", "ATV.am@SD", "https://e.example/atv.m3u8"),
            ch("general:2", "ATV (Pakistan)", null, "https://f.example/atv.m3u8"),
            ch("pk:4", "God Stands TV Urdu", "GodStandsTV.pk@Urdu", "https://a.example/gs-ur.m3u8"))
        assertEquals(listOf("general:2"), LiveTv.alternativesIn(others, atvPk).map { it.key })
        assertEquals(emptyList(), LiveTv.alternativesIn(others, godArabic).map { it.key })
    }

    @Test
    fun `checking channels finds the ones that don't answer`(): Unit = runBlocking {
        Platform.init(); Network.init()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        fun route(path: String, code: Int, body: String) = server.createContext(path) { x ->
            val bytes = body.toByteArray(); x.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong()); x.responseBody.use { it.write(bytes) }
        }
        route("/ok/master.m3u8", 200, "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000\nlow.m3u8\n")
        route("/ok/low.m3u8", 200, "#EXTM3U\n#EXTINF:6,\nseg1.ts\n")
        route("/dead/master.m3u8", 200, "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000\nlow.m3u8\n")  // its quality is gone
        route("/page", 200, "<!DOCTYPE html><html><body>Watch live on our site</body></html>")
        route("/ts", 200, "G" + "\u0000".repeat(500))
        route("/blocked.m3u8", 403, "")
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            fun ch(key: String, url: String) = LiveTv.Channel("check:$key", "check", key, null, null, url)
            val list = listOf(ch("ok", "$base/ok/master.m3u8"), ch("ts", "$base/ts"), ch("dead", "$base/dead/master.m3u8"),
                ch("page", "$base/page"), ch("blocked", "$base/blocked.m3u8"), ch("down", "http://127.0.0.1:1/x.m3u8"))
            var last = 0 to 0
            val working = LiveTv.check(list) { done, ok -> last = done to ok }
            assertEquals(2, working)
            assertEquals(6 to 2, last)
            val why = list.associate { it.name to LiveTv.health(it) }
            assertTrue(why.getValue("ok")!!.ok && why.getValue("ts")!!.ok)
            assertEquals("gone (HTTP 404)", why.getValue("dead")!!.why)
            assertEquals("a web page, not a stream", why.getValue("page")!!.why)
            assertTrue(why.getValue("blocked")!!.why!!.startsWith("refused (HTTP 403)"))
            assertEquals("its server refused the connection", why.getValue("down")!!.why)
        } finally {
            server.stop(0)
        }
    }
}
