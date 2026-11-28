package com.lagradost.cloudstream3.linux

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OsMigrationTest {
    @Test
    fun `renames legacy data directory when destination is absent`() {
        val root = Files.createTempDirectory("roarstream-migration").toFile()
        try {
            val legacy = root.resolve("Stream Goat").apply { mkdirs() }
            legacy.resolve("Extensions/repository.json").apply { parentFile.mkdirs(); writeText("repo") }
            val current = root.resolve("RoarStream")

            assertEquals(current, Os.migrate(current, legacy))
            assertTrue(current.resolve("Extensions/repository.json").readText() == "repo")
            assertFalse(legacy.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `keeps both directories untouched if new and legacy data both exist`() {
        val root = Files.createTempDirectory("roarstream-migration-conflict").toFile()
        try {
            val legacy = root.resolve("Stream Goat").apply { mkdirs() }
            val current = root.resolve("RoarStream").apply { mkdirs() }
            legacy.resolve("legacy-profile.json").writeText("legacy")
            current.resolve("current-profile.json").writeText("current")

            assertEquals(current, Os.migrate(current, legacy))
            assertEquals("legacy", legacy.resolve("legacy-profile.json").readText())
            assertEquals("current", current.resolve("current-profile.json").readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `returns current directory when no legacy data exists`() {
        val root = Files.createTempDirectory("roarstream-migration-empty").toFile()
        try {
            val current = root.resolve("RoarStream")
            val legacy = root.resolve("Stream Goat")
            assertEquals(current, Os.migrate(current, legacy))
            assertFalse(current.exists())
        } finally {
            root.deleteRecursively()
        }
    }
}
