package com.lagradost.cloudstream3.linux

import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.mapper
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Small persistent cache of provider Home responses.
 *
 * The cache is profile-scoped and is only a first-paint optimization: cached cards are
 * shown immediately, then the extension is queried and the cache is replaced by fresh data.
 * A stale entry is never treated as authoritative.
 */
object HomeCache {
    data class SnapshotEntry(val provider: String, val section: String, val order: Int, val response: HomePageResponse)

    private const val PREFIX = "home_cache:"
    private const val ORDER_KEY = "home_cache_order"
    private const val MAX_ENTRIES = 80
    private const val MAX_RESPONSE_BYTES = 1_500_000L
    private const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

    private val prefs get() = Platform.context.getSharedPreferences(Profiles.file("home_cache"), 0)

    private fun key(provider: String, section: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest("$provider\u0000$section".toByteArray(Charsets.UTF_8))
        return PREFIX + bytes.joinToString("") { "%02x".format(it) }
    }

    private fun order(): MutableList<String> = runCatching {
        val raw = prefs.getString(ORDER_KEY, null) ?: return@runCatching mutableListOf()
        JSONArray(raw).let { json -> MutableList(json.length()) { json.getString(it) } }
    }.getOrDefault(mutableListOf())

    /** Cached response for a provider/catalog, if it is still within the seven-day window. */
    fun get(provider: String, section: String): HomePageResponse? {
        val cacheKey = key(provider, section)
        val savedAt = prefs.getLong("$cacheKey:at", 0L)
        if (savedAt <= 0L || System.currentTimeMillis() - savedAt > MAX_AGE_MS) {
            prefs.edit().remove(cacheKey).remove("$cacheKey:at").remove("$cacheKey:meta").apply()
            return null
        }
        val raw = prefs.getString(cacheKey, null) ?: return null
        return runCatching { mapper.readValue<HomePageResponse>(raw) }
            .onFailure { System.err.println("W/home-cache: ignoring invalid cache for $provider / $section: $it") }
            .getOrNull()
    }

    /** Read the previous Home snapshot before extensions have registered. */
    fun snapshot(): List<SnapshotEntry> = order().mapNotNull { cacheKey ->
        runCatching {
            val meta = prefs.getString("$cacheKey:meta", null)?.let(::JSONObject) ?: return@runCatching null
            val provider = meta.getString("provider")
            val section = meta.getString("section")
            val response = get(provider, section) ?: return@runCatching null
            SnapshotEntry(provider, section, meta.optInt("order"), response)
        }.getOrNull()
    }.sortedBy { it.order }

    /** Store a successful provider response; never replace a good cache with an empty result. */
    fun put(provider: String, section: String, order: Int, response: HomePageResponse) {
        runCatching {
            val raw = mapper.writeValueAsString(response)
            if (raw.toByteArray(Charsets.UTF_8).size > MAX_RESPONSE_BYTES) return
            if (response.items.isEmpty() || response.items.all { it.list.isEmpty() }) return
            val cacheKey = key(provider, section)
            val keys = order().apply {
                remove(cacheKey)
                add(cacheKey)
            }
            val editor = prefs.edit()
                .putString(cacheKey, raw)
                .putLong("$cacheKey:at", System.currentTimeMillis())
                .putString("$cacheKey:meta", JSONObject().put("provider", provider).put("section", section).put("order", order).toString())
            while (keys.size > MAX_ENTRIES) {
                val expired = keys.removeAt(0)
                editor.remove(expired).remove("$expired:at").remove("$expired:meta")
            }
            // Persist the response and its index together before the provider task exits.
            // apply() is asynchronous and can lose the first successful Home response if
            // the app is closed or crashes immediately after the request completes.
            editor.putString(ORDER_KEY, JSONArray(keys).toString())
            if (!editor.commit()) {
                System.err.println("W/home-cache: SharedPreferences commit failed for $provider / $section")
            }
        }.onFailure {
            System.err.println("W/home-cache: couldn't save $provider / $section: $it")
        }
    }

}
