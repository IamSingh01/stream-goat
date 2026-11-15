package io.github.sherazi5110.cloudstream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTvCountryTest {
    @Test fun countryFromIptvOrgIds() {
        assertEquals("pk", LiveTv.countryOfId("HumTV.pk@SD"))
        assertEquals("uk", LiveTv.countryOfId("BBCNews.uk"))
        assertEquals(null, LiveTv.countryOfId("SomeChannel"))
        assertEquals(null, LiveTv.countryOfId(null))
    }

    @Test fun parsesCountryAndRegionLock() {
        val text = "#EXTM3U\n#EXTINF:-1 tvg-id=\"GeoNews.pk@SD\" group-title=\"News\",Geo News (720p) [Geo-blocked]\nhttps://a.example/geo.m3u8\n" +
            "#EXTINF:-1 tvg-id=\"CNN.us@SD\",CNN (1080p)\nhttps://b.example/cnn.m3u8\n"
        val list = LiveTv.parseM3u("s", text)
        assertEquals("PK", list[0].country)
        assertTrue(list[0].regionLocked)
        assertEquals("US", list[1].country)
        assertFalse(list[1].regionLocked)
    }

    @Test fun iptvOrgAddresses() {
        assertTrue(LiveTv.isIptvOrgIndex("https://iptv-org.github.io/iptv/index.m3u"))
        assertTrue(LiveTv.isIptvOrgIndex("https://iptv-org.github.io/iptv/index.country.m3u"))
        assertFalse(LiveTv.isIptvOrgIndex("https://iptv-org.github.io/iptv/countries/pk.m3u"))
        assertEquals("https://iptv-org.github.io/iptv/countries/pk.m3u", LiveTv.iptvOrgCountryUrl("PK"))
        assertEquals("Pakistan", LiveTv.countryName("pk"))
    }
}
