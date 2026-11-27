package com.lagradost.cloudstream3.linux

import kotlin.test.Test
import kotlin.test.assertEquals

class BrandIdentityTest {
    @Test
    fun `desktop identity is RoarStream by Aman Singh`() {
        assertEquals("RoarStream", BrandIdentity.APP_NAME)
        assertEquals("Aman Singh", BrandIdentity.DEVELOPER_NAME)
    }
}
