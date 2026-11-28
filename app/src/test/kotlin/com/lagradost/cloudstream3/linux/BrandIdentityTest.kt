package com.lagradost.cloudstream3.linux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BrandIdentityTest {
    @Test
    fun `desktop identity is RoarStream by Aman Singh`() {
        assertEquals("RoarStream", BrandIdentity.APP_NAME)
        assertEquals("Aman Singh", BrandIdentity.DEVELOPER_NAME)
        assertTrue(BrandIdentity::class.java.getResourceAsStream(BrandIdentity.LOGO_RESOURCE)?.use { it.read() >= 0 } == true)
    }
}
