package io.github.sherazi5110.cloudstream

import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.getAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.getMalId
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Intro, recap and credits times for anime episodes from AniSkip (api.aniskip.com, a free community
 * database keyed by MyAnimeList id). The id comes from the extension, else from AniList by title.
 * Used by the player's "Skip intro" button and to start the next-episode countdown at the credits.
 */
object SkipTimes {
    enum class Kind(val label: String) { INTRO("Skip intro"), RECAP("Skip recap"), CREDITS("Skip credits") }

    /** Milliseconds from the start of the episode. */
    data class Segment(val kind: Kind, val start: Long, val end: Long)

    private val base get() = System.getProperty("cs.aniskip") ?: "https://api.aniskip.com"
    private val cache = mutableMapOf<String, List<Segment>>()

    private val animeTypes = setOf(TvType.Anime, TvType.OVA, TvType.AnimeMovie)

    /**
     * Whether this title is anime. Many extensions (MovieBox, Cinemeta, dubbed-anime sites) list anime as an ordinary
     * series, so the type alone missed them: an anime id from the extension, an anime-only extension or an "Anime"
     * tag also count.
     */
    fun applies(r: LoadResponse): Boolean {
        if (r.type in animeTypes) return true
        if (r.getMalId() != null || r.getAniListId() != null) return true
        val api = runCatching { Engine.provider(r.apiName) }.getOrNull()
        if (api != null && api.supportedTypes.isNotEmpty() && api.supportedTypes.all { it in animeTypes }) return true
        return r.tags.orEmpty().any { it.trim().equals("anime", true) || it.trim().equals("animes", true) }
    }

    suspend fun forEpisode(r: LoadResponse, episode: Int?, durationMs: Long): List<Segment> = withContext(Dispatchers.IO) {
        if (!applies(r)) return@withContext emptyList()
        val mal = r.getMalId()?.toIntOrNull()
            ?: r.getAniListId()?.toIntOrNull()?.let { id -> runCatching { Metadata.anilistMalId(id) }.getOrNull() }
            ?: runCatching { Metadata.anilistMedia(Metadata.cleanTitle(r.name).first)?.optInt("idMal")?.takeIf { it > 0 } }.getOrNull()
            ?: run { android.util.Log.i("skip", "${r.name}: no MyAnimeList id found, no skip times"); return@withContext emptyList() }
        val ep = episode ?: 1
        val key = "$mal:$ep"
        synchronized(cache) { cache[key] }?.let { return@withContext it }
        val length = if (durationMs > 0) durationMs / 1000 else 0
        val url = "$base/v2/skip-times/$mal/$ep?types=op&types=ed&types=mixed-op&types=mixed-ed&types=recap&episodeLength=$length"
        val segments = runCatching {
            val json = JSONObject(app.get(url, timeout = 10).text)
            val results = json.optJSONArray("results") ?: return@runCatching emptyList()
            (0 until results.length()).mapNotNull { results.optJSONObject(it) }.mapNotNull { o ->
                val kind = when (o.optString("skipType")) {
                    "op", "mixed-op" -> Kind.INTRO
                    "ed", "mixed-ed" -> Kind.CREDITS
                    "recap" -> Kind.RECAP
                    else -> return@mapNotNull null
                }
                val i = o.optJSONObject("interval") ?: return@mapNotNull null
                Segment(kind, (i.optDouble("startTime") * 1000).toLong(), (i.optDouble("endTime") * 1000).toLong())
            }.filter { it.end > it.start }.sortedBy { it.start }
        }.getOrDefault(emptyList())
        android.util.Log.i("skip", "${r.name} (MAL $mal) episode $ep: " + if (segments.isEmpty()) "no skip times on AniSkip yet" else segments.joinToString { "${it.kind.label} ${it.start / 1000}-${it.end / 1000}s" })
        synchronized(cache) { cache[key] = segments }
        segments
    }
}
