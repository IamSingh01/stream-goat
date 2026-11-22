package com.lagradost.cloudstream3.linux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** NetMirror's playlists don't say they end: VLC took them for live (27:46:39, wrong seeks) until the proxy adds it. */
class VodEndTest {
    private fun playlist(segments: Int, seconds: Double, head: String = "") =
        "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:10\n$head" + (0 until segments).joinToString("") { "#EXTINF:$seconds,\nseg$it.ts\n" }

    @Test fun `a whole episode without an end gets one`() {
        val out = StreamProxy.vodEnd(playlist(270, 10.0))   // 45 minutes
        assertTrue(out.trimEnd().endsWith("#EXT-X-ENDLIST"))
        assertTrue("#EXT-X-PLAYLIST-TYPE:VOD" in out)
    }

    @Test fun `a live channel's moving window is left alone`() {
        val live = playlist(6, 6.0, "#EXT-X-MEDIA-SEQUENCE:48211\n")
        assertEquals(live, StreamProxy.vodEnd(live))
        val longWindow = playlist(200, 6.0, "#EXT-X-MEDIA-SEQUENCE:9000\n")   // a long live window, still live
        assertEquals(longWindow, StreamProxy.vodEnd(longWindow))
    }

    @Test fun `a growing event or a finished playlist is left alone`() {
        val event = playlist(300, 10.0, "#EXT-X-PLAYLIST-TYPE:EVENT\n")
        assertEquals(event, StreamProxy.vodEnd(event))
        val done = playlist(300, 10.0) + "#EXT-X-ENDLIST\n"
        assertEquals(done, StreamProxy.vodEnd(done))
    }

    @Test fun `a short clip is left alone`() {
        val clip = playlist(20, 6.0)
        assertEquals(clip, StreamProxy.vodEnd(clip))
    }
}
