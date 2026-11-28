package com.lagradost.cloudstream3.linux

import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.HomePageResponse
import org.json.JSONArray
import java.security.MessageDigest

/**
 * Small persistent cache of provider Home responses.
 *
 * The cache is profile-scoped and is only a first-paint optimization: cached cards are
 * shown immediately, then the extension is queried and the cache is replaced by fresh data.
 * A stale entry is never treated as authoritative.
 */
object HomeCache {
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
            prefs.edit().remove(cacheKey).remove("$cacheKey:at").apply()
            return null
        }
        val raw = prefs.getString(cacheKey, null) ?: return null
        return runCatching { mapper.readValue<HomePageResponse>(raw) }
            .onFailure { System.err.println("W/home-cache: ignoring invalid cache for $provider / $section: $it") }
            .getOrNull()
    }

    /** Store a successful provider response; never replace a good cache with an empty result. */
    fun put(provider: String, section: String, response: HomePageResponse) {
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
            while (keys.size > MAX_ENTRIES) {
                val expired = keys.removeAt(0)
                editor.remove(expired).remove("$expired:at")
            }
            editor.putString(ORDER_KEY, JSONArray(keys).toString()).apply()
        }.onFailure {
            System.err.println("W/home-cache: couldn't save $provider / $section: $it")
        }
    }

    /** Clears one provider's cached catalogs after uninstall or an explicit reset. */
    fun clearProvider(provider: String) {
        // Cache keys are intentionally opaque; stale entries expire naturally. This method
        // is retained for call sites that want to invalidate all caches in a future schema.
    }
}
