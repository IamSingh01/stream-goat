package com.lagradost.cloudstream3.linux

import com.lagradost.cloudstream3.linux.ui.AppInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Guards the desktop product identity and its packaged primary mark. */
class RoarBoxBrandingTest {
    @Test
    fun `desktop product name is RoarBox`() {
        assertEquals("RoarBox", AppInfo.NAME)
    }

    @Test
    fun `RoarBox primary logo is packaged as a classpath resource`() {
        assertNotNull(
            javaClass.getResourceAsStream("/ui/brand/roarbox-mark.png"),
            "RoarBox primary logo resource is missing"
        )?.close()
    }
}
