package io.github.sherazi5110.cloudstream

import io.github.sherazi5110.cloudstream.player.SubtitleStyle
import io.github.sherazi5110.cloudstream.ui.delayText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleStyleTest {
    private val srt = """
1
00:00:01,000 --> 00:00:03,000
Subtitles by ExampleSubs
www.example-subs.com

2
00:00:04,000 --> 00:00:06,000
[door knocks]

3
00:00:07,000 --> 00:00:09,000
JOHN: Is anyone there?
<i>(whispering) Stay quiet.</i>

4
00:00:10,000 --> 00:00:12,000
♪ La la la ♪

5
00:00:13,000 --> 00:00:15,000
- We should go.
- [sighs] Fine.
""".trimStart()

    @Test fun removesCaptionsAdsAndRenumbers() {
        val out = SubtitleStyle.clean(srt, SubtitleStyle.Clean(noCaptions = true, noAds = true))
        assertFalse(out.contains("ExampleSubs"))
        assertFalse(out.contains("door knocks"))
        assertFalse(out.contains("La la"))
        assertFalse(out.contains("JOHN:"))
        assertTrue(out.contains("Is anyone there?"))
        assertTrue(out.contains("<i>Stay quiet.</i>"))
        assertTrue(out.contains("- Fine."))
        // Cues 1, 2 and 4 are gone: the rest are numbered 1 and 2.
        assertTrue(out.startsWith("1\n00:00:07,000 --> 00:00:09,000\n"))
        assertTrue(out.contains("\n2\n00:00:13,000 --> 00:00:15,000\n"))
        assertFalse(out.contains("\n3\n"))
    }

    @Test fun uppercaseKeepsTags() {
        val out = SubtitleStyle.clean(srt, SubtitleStyle.Clean(noAds = false, uppercase = true))
        assertTrue(out.contains("<i>(WHISPERING) STAY QUIET.</i>"))
        assertTrue(out.contains("SUBTITLES BY EXAMPLESUBS"))
    }

    @Test fun webVttKeepsHeaderAndCueIds() {
        val vtt = "WEBVTT\n\nintro\n00:01.000 --> 00:03.000\n[music]\n\nline\n00:04.000 --> 00:06.000\nHello there\n"
        val out = SubtitleStyle.clean(vtt, SubtitleStyle.Clean(noCaptions = true))
        assertTrue(out.startsWith("WEBVTT\n\n"))
        assertFalse(out.contains("intro"))
        assertTrue(out.contains("line\n00:04.000 --> 00:06.000\nHello there"))
    }

    @Test fun assIsLeftAlone() {
        val ass = "[Script Info]\nTitle: x\n\n[Events]\nDialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,[noise] Hi\n"
        assertEquals(ass, SubtitleStyle.clean(ass, SubtitleStyle.Clean(noCaptions = true)))
    }

    @Test fun decodesWesternAndUtf8() {
        assertEquals("Café", SubtitleStyle.decode("Café".toByteArray(Charsets.UTF_8)))
        assertEquals("Café", SubtitleStyle.decode(byteArrayOf(0x43, 0x61, 0x66, 0xE9.toByte())))
    }

    @Test fun vlcOptions() {
        val o = SubtitleStyle.vlcOptions(SubtitleStyle.Look(size = "LARGE", color = "#ffeb3b", bold = true, edge = "SHADOW", background = true, backgroundOpacity = 100, height = 2, align = "LEFT"))
        assertTrue("--freetype-rel-fontsize=13" in o)
        assertTrue("--freetype-color=${0xffeb3b}" in o)
        assertTrue("--freetype-bold" in o)
        assertTrue("--freetype-outline-thickness=0" in o)
        assertTrue("--freetype-shadow-opacity=200" in o)
        assertTrue("--freetype-background-opacity=255" in o)
        assertTrue("--sub-margin=90" in o)
        assertTrue("--subsdec-align=1" in o)
        val d = SubtitleStyle.vlcOptions(SubtitleStyle.Look())
        assertTrue("--freetype-outline-thickness=4" in d && "--freetype-background-opacity=0" in d && "--freetype-rel-fontsize=16" in d)
    }

    @Test fun delayLabels() {
        assertEquals("In sync", delayText(0))
        assertEquals("+0.3 s", delayText(300))
        assertEquals("−1.5 s", delayText(-1500))
    }
}
