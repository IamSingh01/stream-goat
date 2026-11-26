package com.lagradost.cloudstream3.linux

import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The shared backup's merge: what changed on each device is kept, item by item. */
class BackupMergeTest {
    private fun bookmark(url: String, at: Long) = JSONObject().put("apiName", "X").put("url", url).put("name", url).put("addedAt", at)

    /** A backup's stores with one library: My list ([bookmarks]) and watch progress ([progress]). */
    private fun stores(bookmarks: List<JSONObject>, progress: Map<String, Double> = emptyMap(), extra: Pair<String, String>? = null): JSONObject {
        val lib = JSONObject()
            .put("bookmarks", JSONObject().put("s", JSONArray(bookmarks).toString()))
            .put("progress", JSONObject().put("s", JSONObject(progress.mapValues { JSONObject().put("position", it.value).put("duration", 100.0) }).toString()))
        val out = JSONObject().put("library", lib)
        extra?.let { out.put(it.first, JSONObject().put("value", JSONObject().put("s", it.second))) }
        return out
    }

    private fun urls(stores: JSONObject): List<String> {
        val a = JSONArray(stores.getJSONObject("library").getJSONObject("bookmarks").getString("s"))
        return (0 until a.length()).map { a.getJSONObject(it).getString("url") }
    }

    private fun position(stores: JSONObject, key: String): Double? =
        JSONObject(stores.getJSONObject("library").getJSONObject("progress").getString("s")).optJSONObject(key)?.getDouble("position")

    @Test fun `added on both devices, both are kept (newest first)`() {
        val base = stores(listOf(bookmark("a", 1)))
        val phone = stores(listOf(bookmark("a", 1), bookmark("phone", 3)))
        val tv = stores(listOf(bookmark("a", 1), bookmark("tv", 2)))
        assertEquals(listOf("phone", "tv", "a"), urls(BackupMerge.merge(base, phone, tv)))
    }

    @Test fun `removed on one device and untouched on the other is removed`() {
        val base = stores(listOf(bookmark("a", 1), bookmark("b", 2)))
        val here = stores(listOf(bookmark("b", 2)))             // a removed here
        val there = stores(listOf(bookmark("a", 1), bookmark("b", 2), bookmark("c", 3)))
        assertEquals(listOf("c", "b"), urls(BackupMerge.merge(base, here, there)))
        // …and the other way round.
        assertEquals(listOf("c", "b"), urls(BackupMerge.merge(base, there.let { stores(listOf(bookmark("a", 1), bookmark("b", 2), bookmark("c", 3))) }, here)))
    }

    @Test fun `the first sync (no base) removes nothing`() {
        val here = stores(listOf(bookmark("a", 1)))
        val there = stores(listOf(bookmark("b", 2)))
        assertEquals(listOf("b", "a"), urls(BackupMerge.merge(null, here, there)))
    }

    @Test fun `progress - each title's latest, one record never mixed`() {
        val base = stores(emptyList(), mapOf("ep1" to 10.0, "ep2" to 10.0))
        val here = stores(emptyList(), mapOf("ep1" to 50.0, "ep2" to 10.0))      // watched ep1 further here
        val there = stores(emptyList(), mapOf("ep1" to 10.0, "ep2" to 70.0, "ep3" to 5.0))
        val m = BackupMerge.merge(base, here, there)
        assertEquals(50.0, position(m, "ep1"))
        assertEquals(70.0, position(m, "ep2"))
        assertEquals(5.0, position(m, "ep3"))
    }

    @Test fun `the same entry changed on both - the newer one wins whole`() {
        val base = JSONObject().put("library", JSONObject().put("history", JSONObject().put("s",
            JSONArray().put(JSONObject().put("apiName", "X").put("url", "u").put("episode", 1).put("updatedAt", 1)).toString())))
        fun with(ep: Int, at: Long) = JSONObject().put("library", JSONObject().put("history", JSONObject().put("s",
            JSONArray().put(JSONObject().put("apiName", "X").put("url", "u").put("episode", ep).put("updatedAt", at)).toString())))
        val m = BackupMerge.merge(base, with(2, 5), with(3, 9))
        val e = JSONArray(m.getJSONObject("library").getJSONObject("history").getString("s")).getJSONObject(0)
        assertEquals(3, e.getInt("episode")); assertEquals(9, e.getInt("updatedAt"))
    }

    @Test fun `sets and plain values - three-way`() {
        fun s(vararg v: String) = JSONObject().put("k", JSONObject().put("set", JSONArray(v.toList())))
        val m = BackupMerge.merge(JSONObject().put("st", s("a", "b")), JSONObject().put("st", s("a", "b", "c")), JSONObject().put("st", s("b")))
        assertEquals(listOf("b", "c"), m.getJSONObject("st").getJSONObject("k").getJSONArray("set").let { a -> (0 until a.length()).map { a.getString(it) } })
        // A value changed only on the other device comes here; one changed here stays.
        val base = stores(emptyList(), extra = "home_catalogs" to "1")
        assertEquals("2", BackupMerge.merge(base, base, stores(emptyList(), extra = "home_catalogs" to "2"))
            .getJSONObject("home_catalogs").getJSONObject("value").getString("s"))
        assertEquals("3", BackupMerge.merge(base, stores(emptyList(), extra = "home_catalogs" to "3"), base)
            .getJSONObject("home_catalogs").getJSONObject("value").getString("s"))
    }

    @Test fun `each device's own settings and sign-ins aren't synced`() {
        val all = JSONObject().put("app_settings", JSONObject()).put("linux_settings", JSONObject()).put("cloud_backup", JSONObject())
            .put("profile_settings", JSONObject()).put("profile_settings@kid", JSONObject()).put("wetrakr@kid", JSONObject())
            .put("library", JSONObject()).put("library@kid", JSONObject()).put("wetrakr_sync", JSONObject()).put("rebuild_preference", JSONObject())
        assertEquals(setOf("library", "library@kid", "wetrakr_sync", "rebuild_preference"), BackupMerge.content(all).keys().asSequence().toSet())
        assertTrue(BackupMerge.deviceOnly.containsMatchIn("app_settings"))
        assertFalse(BackupMerge.deviceOnly.containsMatchIn("music_playlists"))
    }

    @Test fun `the same data in another key order is the same`() {
        assertEquals(BackupMerge.canon(JSONObject("""{"a":1,"b":[1,2.5,"x"]}""")), BackupMerge.canon(JSONObject("""{"b":[1,2.5,"x"],"a":1.0}""")))
    }
}
