package io.github.sherazi5110.cloudstream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdBlockTest {
    private val easylist = """
        [Adblock Plus 2.0]
        ! Title: EasyList
        ||doubleclick.net^
        ||ads.example.com^${'$'}third-party
        ||popads.net^${'$'}popup
        ||site-only.com^${'$'}domain=foo.com
        @@||ok.doubleclick.net^
        ##.ad-banner
        ##div[id^="google_ads"]
        example.org##.sponsored
        /banner/*/ad.
    """.trimIndent()
    private val hosts = "# hosts\n0.0.0.0 tracker.io\n127.0.0.1 localhost\n"

    @Test fun blocksHostsAndTheirSubdomains() {
        val r = AdBlock.parse(listOf(easylist, hosts))
        assertTrue(r.blocks("doubleclick.net"))
        assertTrue(r.blocks("stats.g.doubleclick.net"))
        assertTrue(r.blocks("ads.example.com"))
        assertTrue(r.blocks("cdn.tracker.io"))
        assertFalse(r.blocks("example.com"))
        assertFalse(r.blocks("localhost"))
    }

    @Test fun exceptionsAndNarrowRulesDontBlock() {
        val r = AdBlock.parse(listOf(easylist))
        assertFalse(r.blocks("ok.doubleclick.net"))      // @@ exception
        assertFalse(r.blocks("popads.net"))              // popup-only rule
        assertFalse(r.blocks("site-only.com"))           // only on some sites
    }

    @Test fun keepsOnlySimpleGenericHidingRules() {
        val r = AdBlock.parse(listOf(easylist))
        assertEquals(listOf(".ad-banner", "div[id^=\"google_ads\"]"), r.hide)
        assertTrue(r.css.endsWith("{display:none!important}"))
    }
}
