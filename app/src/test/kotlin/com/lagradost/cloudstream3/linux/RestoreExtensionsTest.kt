package com.lagradost.cloudstream3.linux

import com.lagradost.cloudstream3.APIHolder
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A restored backup lists installed extensions but not their files (and a phone's backup has phone paths): the app
 * downloads each missing one again by itself instead of showing "Missing file", and Reinstall does it on demand.
 */
class RestoreExtensionsTest {
    private val repo = File(System.getProperty("example.repo"))
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            val f = repo.resolve(ex.requestURI.path.trimStart('/'))
            if (!f.isFile) { ex.sendResponseHeaders(404, -1); ex.close(); return@createContext }
            ex.sendResponseHeaders(200, f.length()); ex.responseBody.use { f.inputStream().copyTo(it) }
        }
        start()
    }

    init { Platform.init() }

    @AfterTest
    fun cleanUp() {
        PluginManager.loaded.keys.toList().forEach { PluginManager.unloadPlugin(it) }
        PluginManager.installed().forEach { PluginManager.uninstall(it.internalName) }
        server.stop(0)
    }

    private fun record(data: PluginData) = PluginManager::class.java.getDeclaredMethod("record", PluginData::class.java)
        .apply { isAccessible = true }.invoke(PluginManager, data)

    @Test
    fun `missing extension files are downloaded again after a restore`() = runBlocking {
        // As restored from a phone: an Android path that doesn't exist here, and the address it was installed from.
        val url = "http://127.0.0.1:${server.address.port}/InternetArchive.cs3"
        record(PluginData("InternetArchive", url, true,
            "/data/user/0/io.github.sherazi5110.cloudstream/files/Extensions/somerepo/InternetArchive.123.cs3", 1, null, "Internet Archive"))
        PluginManager.loadAll()
        assertNull(PluginManager.failures["InternetArchive"], "no failure after downloading it again")
        assertTrue(APIHolder.allProviders.any { it.name == "Internet Archive" })
        val data = PluginManager.installed().single { it.internalName == "InternetArchive" }
        assertTrue(File(data.filePath).isFile, "its new file is on this computer")
        assertTrue(data.filePath.startsWith(RepositoryManager.extensionsDir.absolutePath))

        // Reinstall: deletes and downloads it again, loaded and working.
        assertTrue(PluginManager.reinstall("InternetArchive").isSuccess)
        assertEquals(1, PluginManager.loaded.values.count { it.data.internalName == "InternetArchive" })
    }
}
