package com.lagradost.cloudstream3.linux

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Streaming Catalogs and Cinemeta come preinstalled, first in the list, once: an add-on removed stays removed. */
class StremioPreinstallTest {
    @Test
    fun `preinstalled add-ons are added once, first, and a removed one stays removed`(): Unit = runBlocking {
        Platform.init(); Network.init()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        fun manifest(id: String, name: String) = """{"id":"$id","name":"$name","version":"1.0.0","types":["movie","series"],"idPrefixes":["tt"],
            "resources":["catalog"],"catalogs":[{"type":"movie","id":"$id","name":"$name"}]}"""
        listOf("catalogs" to manifest("pw.ers.netflix-catalog", "Streaming Catalogs"), "cinemeta" to manifest("com.linvo.cinemeta", "Cinemeta")).forEach { (path, body) ->
            server.createContext("/$path/manifest.json") { x -> val b = body.toByteArray(); x.sendResponseHeaders(200, b.size.toLong()); x.responseBody.use { it.write(b) } }
        }
        server.start()
        val base = "http://127.0.0.1:${server.address.port}"
        val prefs = Platform.context.getSharedPreferences("stremio_addons", 0)
        val savedAddons = prefs.getString("addons", "[]"); val savedOffered = prefs.getString("preinstalled", "[]")
        val before = StremioAddons.preinstalled
        prefs.edit().putString("addons", "[]").putString("preinstalled", "[]").commit()
        server.createContext("/own/manifest.json") { x -> val b = manifest("my.own", "My own").toByteArray(); x.sendResponseHeaders(200, b.size.toLong()); x.responseBody.use { it.write(b) } }
        try {
            // Someone already has an add-on of their own.
            StremioAddons.install("$base/own/manifest.json")
            StremioAddons.preinstalled = listOf("$base/catalogs/manifest.json", "$base/cinemeta/manifest.json")
            assertEquals(listOf("Streaming Catalogs", "Cinemeta"), StremioAddons.preinstall())
            assertEquals(listOf("Streaming Catalogs", "Cinemeta", "My own"), StremioAddons.all().map { it.manifest.name })
            // Next start: nothing new.
            assertEquals(emptyList(), StremioAddons.preinstall())
            // Removed by the user: not added again.
            StremioAddons.remove("$base/cinemeta/manifest.json")
            assertEquals(emptyList(), StremioAddons.preinstall())
            assertTrue(StremioAddons.all().none { it.manifest.name == "Cinemeta" })
        } finally {
            StremioAddons.preinstalled = before
            prefs.edit().putString("addons", savedAddons).putString("preinstalled", savedOffered).commit()
            server.stop(0)
        }
    }
}
