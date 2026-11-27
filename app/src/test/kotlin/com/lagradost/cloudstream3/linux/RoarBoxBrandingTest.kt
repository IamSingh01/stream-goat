package com.lagradost.cloudstream3.linux

import com.lagradost.cloudstream3.linux.ui.AppInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import java.nio.file.Files

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

    @Test
    fun `legacy desktop data directory migrates without losing files`() {
        val root = Files.createTempDirectory("roarbox-brand-migration").toFile()
        try {
            val legacy = java.io.File(root, "Stream Goat").apply { mkdirs() }
            java.io.File(legacy, "settings.json").writeText("""{"kept":true}""")
            val current = java.io.File(root, "RoarBox")

            val migrated = Os.migrateLegacyDataDir(current, legacy)

            assertEquals(current, migrated)
            assertTrue(java.io.File(current, "settings.json").isFile)
            assertEquals("""{"kept":true}""", java.io.File(current, "settings.json").readText())
            assertTrue(!legacy.exists())
        } finally {
            root.deleteRecursively()
        }
    }
}
