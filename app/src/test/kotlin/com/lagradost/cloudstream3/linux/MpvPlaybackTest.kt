package com.lagradost.cloudstream3.linux

import com.lagradost.cloudstream3.linux.ui.EngineEvents
import com.lagradost.cloudstream3.linux.ui.Mpv
import com.lagradost.cloudstream3.linux.ui.MpvEngine
import com.lagradost.cloudstream3.linux.ui.VideoView
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Test
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * mpv plays a NetMirror-like stream through the proxy: a master playlist served as text from a .php address, video
 * and two audio languages as separate playlists without an end marker, segments named .jpg behind a fake PNG
 * header, a subtitle track whose address has [brackets]. Real length, both audio tracks, the resume point, seeking.
 * Needs libmpv and ffmpeg (skipped without them).
 */
class MpvPlaybackTest {
    private fun ffmpeg(vararg args: String) =
        ProcessBuilder(listOf("ffmpeg", "-loglevel", "error", "-y") + args).redirectErrorStream(true).start().waitFor() == 0

    @Test
    fun `mpv plays a NetMirror-like stream with its real length and both audio languages`() {
        if (!Mpv.available || runCatching { ffmpeg("-version") }.getOrDefault(false).not()) {
            println("libmpv or ffmpeg not installed; skipping")
            return
        }
        val dir = kotlin.io.path.createTempDirectory("mpv-nm").toFile()
        File(dir, "v").mkdirs(); File(dir, "a").mkdirs()
        assertTrue(ffmpeg("-f", "lavfi", "-i", "testsrc=size=160x90:rate=5", "-t", "660", "-c:v", "libx264", "-g", "50", "-preset", "ultrafast",
            "-an", "-f", "hls", "-hls_time", "10", "-hls_playlist_type", "vod", "-hls_segment_filename", "$dir/v/s%03d.ts", "$dir/v/index.m3u8"))
        assertTrue(ffmpeg("-f", "lavfi", "-i", "sine", "-t", "660", "-c:a", "aac", "-b:a", "24k",
            "-f", "hls", "-hls_time", "10", "-hls_playlist_type", "vod", "-hls_segment_filename", "$dir/a/s%03d.ts", "$dir/a/index.m3u8"))
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(56)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val base = "http://127.0.0.1:${server.address.port}"
        server.createContext("/") { ex ->
            val path = ex.requestURI.path
            val (type, body) = when {
                path == "/master.php" -> "text/plain" to ("#EXTM3U\n#EXT-X-VERSION:3\n" +
                    "#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID=\"subs\",NAME=\"Hindi [CC]\",LANGUAGE=\"hi.[CC]\",URI=\"$base/subs/hi.[CC].m3u8\"\n" +
                    "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"aac\",LANGUAGE=\"eng\",NAME=\"1. English\",DEFAULT=YES,URI=\"$base/a/play.php\"\n" +
                    "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"aac\",LANGUAGE=\"hin\",NAME=\"2. Hindi\",DEFAULT=NO,URI=\"$base/a/play.php\"\n" +
                    "#EXT-X-STREAM-INF:BANDWIDTH=600000,AUDIO=\"aac\",RESOLUTION=160x90,SUBTITLES=\"subs\"\n$base/v/play.php\n").toByteArray()
                path.endsWith("/play.php") -> "text/plain" to File(dir, path.split('/')[1] + "/index.m3u8").readText()
                    .replace("#EXT-X-ENDLIST", "").replace("#EXT-X-PLAYLIST-TYPE:VOD\n", "").replace(Regex("(s\\d+)\\.ts"), "$1.jpg").toByteArray()
                path.endsWith(".jpg") -> "image/jpeg" to (png + File(dir, path.removeSuffix(".jpg").trimStart('/') + ".ts").readBytes())
                else -> "text/plain" to ByteArray(0)
            }
            ex.responseHeaders.add("Content-Type", type)
            ex.sendResponseHeaders(if (body.isEmpty()) 404 else 200, if (body.isEmpty()) -1 else body.size.toLong())
            if (body.isNotEmpty()) ex.responseBody.use { it.write(body) } else ex.close()
        }
        server.start()
        runCatching { javafx.application.Platform.startup {} }
        val playing = CountDownLatch(1)
        val lengthV = java.util.concurrent.atomic.AtomicLong()
        val timeV = java.util.concurrent.atomic.AtomicLong()
        val errorsV = java.util.concurrent.atomic.AtomicInteger()
        val video = VideoView()
        val engine = MpvEngine(video, object : EngineEvents {
            override fun playing() = playing.countDown()
            override fun paused() = Unit
            override fun buffering(cache: Float) = Unit
            override fun timeChanged(ms: Long) = timeV.set(ms)
            override fun lengthChanged(ms: Long) = lengthV.set(ms)
            override fun seekableChanged(seekable: Boolean) = Unit
            override fun streamAdded(audio: Boolean) = Unit
            override fun finished() = Unit
            override fun error() { errorsV.incrementAndGet() }
        })
        try {
            val url = StreamProxy.wrap("$base/master.php", mapOf("User-Agent" to "test")).substringBeforeLast('/') + "/playlist.m3u8"
            assertTrue(engine.open(url, emptyList(), 30_000))
            assertTrue(playing.await(30, TimeUnit.SECONDS), "never started playing")
            assertTrue(timeV.get() >= 29_000, "didn't start at the resume point: ${timeV.get()}")
            val deadline = System.currentTimeMillis() + 10_000
            while ((lengthV.get() == 0L || video.sourceW == 0) && System.currentTimeMillis() < deadline) Thread.sleep(100)
            assertTrue(lengthV.get() in 655_000..665_000, "length ${lengthV.get()} (VLC showed NetMirror as 27:46:39)")
            assertEquals(listOf("English - [eng]", "Hindi - [hin]"), engine.audioTracks().filter { it.id() >= 0 }.map { it.description() })
            assertEquals(160, video.sourceW)
            engine.setTime(400_000)
            Thread.sleep(2000)
            assertTrue(timeV.get() in 395_000..640_000, "seek landed at ${timeV.get()}")
            assertEquals(0, errorsV.get())
        } finally {
            engine.release()
            server.stop(0)
            dir.deleteRecursively()
        }
    }
}
