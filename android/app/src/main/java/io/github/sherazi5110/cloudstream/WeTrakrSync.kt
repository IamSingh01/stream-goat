package io.github.sherazi5110.cloudstream

import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.getImdbId
import com.lagradost.cloudstream3.TvType
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Two-way WeTrakr sync. Playback already reports to WeTrakr (scrobble); this adds the other way: what you watched
 * anywhere else that uses WeTrakr (other apps, add-ons, the website) shows as watched here too, and marking something
 * watched here by hand is sent there. WeTrakr's watch history is read in full once, then only what's new.
 *
 * Titles are matched by their TMDB / IMDb ids (and season and episode numbers), which is how every app on WeTrakr
 * names them, so a show watched in another app matches whichever extension it's opened from here.
 */
object WeTrakrSync {
    private val prefs get() = App.context.getSharedPreferences(Profiles.file("wetrakr_sync"), 0)
    private val lock = Mutex()
    @Volatile private var movies: MutableSet<String>? = null
    @Volatile private var episodes: MutableSet<String>? = null
    @Volatile private var shows: MutableSet<String>? = null

    /** Bumped whenever watched marks change (here or from WeTrakr), so posters redraw their stamps. */
    var version by androidx.compose.runtime.mutableIntStateOf(0)
        private set

    private fun loadSet(key: String): MutableSet<String> = prefs.getStringSet(key, emptySet())!!.toMutableSet()

    private fun movies() = movies ?: loadSet("movies").also { movies = it }
    private fun episodes() = episodes ?: loadSet("episodes").also { episodes = it }
    /** Shows with at least one watched episode (by the same keys), for the posters. */
    private fun shows() = shows ?: (loadSet("shows").ifEmpty { episodes().mapTo(HashSet()) { it.substringBefore(':') } }).also { shows = it }

    /** Keys for one title: by TMDB and by IMDb id, whichever is known. */
    internal fun keys(tmdb: Int?, imdb: String?): List<String> =
        listOfNotNull(tmdb?.let { "t$it" }, imdb?.takeIf { it.startsWith("tt") }?.let { "i$it" })

    fun movieWatched(tmdb: Int?, imdb: String?) = keys(tmdb, imdb).any { it in movies() }
    fun episodeWatched(tmdb: Int?, imdb: String?, season: Int, episode: Int) =
        keys(tmdb, imdb).any { "$it:$season:$episode" in episodes() }

    /** What a full sync found: titles and episodes watched on WeTrakr. */
    data class Summary(val films: Int, val episodes: Int)

    /**
     * Reads WeTrakr's watch history: everything the first time and on [full] (Sync now: rebuilt from scratch, so a
     * play removed on WeTrakr goes here too), otherwise the plays added since the last time. [force] skips the
     * 2-minute pause between reads. Returns the totals on a full read.
     */
    suspend fun pull(force: Boolean = false, full: Boolean = false): Summary? = withContext(Dispatchers.IO) {
        if (!WeTrakr.available || !WeTrakr.connected) return@withContext null
        lock.withLock {
            val last = prefs.getLong("pulled_at", 0L)
            if (!force && !full && System.currentTimeMillis() - last < 2 * 60_000L) return@withLock null
            val started = System.currentTimeMillis()
            val everything = full || last == 0L
            // Plays added a moment before the previous read could be missed by clock differences: overlap by an hour.
            val since = if (everything) null else java.time.Instant.ofEpochMilli(last - 3_600_000L).toString()
            val m = if (everything) HashSet() else movies()
            val e = if (everything) HashSet() else episodes()
            val sh = if (everything) HashSet() else shows()
            var films = 0
            val eps = HashSet<String>()
            for (target in listOf("movies", "episodes")) {
                var page = 1
                while (page < 200) {
                    val q = "compact=true&limit=5000&page=$page" + (since?.let { "&from_date=$it" } ?: "")
                    val (code, body) = WeTrakr.get("/sync/tracking/watched/history/$target?$q") ?: return@withLock null
                    if (code !in 200..299) return@withLock null
                    val rows = runCatching { JSONArray(body) }.getOrNull() ?: break
                    for (i in 0 until rows.length()) {
                        val o = rows.optJSONObject(i) ?: continue
                        if (target == "movies") {
                            val k = keys(o.idsTmdb("ids"), o.idsImdb("ids"))
                            if (k.isNotEmpty() && k.none { it in m }) films++
                            k.forEach { m += it }
                        } else {
                            val s = o.optInt("season_number", -1); val n = o.optInt("number", -1)
                            if (s < 0 || n < 0) continue
                            val k = keys(o.idsTmdb("show_ids"), o.idsImdb("show_ids"))
                            k.firstOrNull()?.let { eps += "$it:$s:$n" }
                            k.forEach { e += "$it:$s:$n"; sh += it }
                        }
                    }
                    if (rows.length() < 5000) break
                    page++
                }
            }
            movies = m; episodes = e; shows = sh
            prefs.edit().putStringSet("movies", m.toSet()).putStringSet("episodes", e.toSet()).putStringSet("shows", sh.toSet())
                .putLong("pulled_at", started).apply()
            changed()
            Library.notifyChanged()
            if (everything) Summary(films, eps.size) else null
        }
    }

    private fun JSONObject.idsTmdb(key: String) = optJSONObject(key)?.optInt("tmdb", 0)?.takeIf { it > 0 }
    private fun JSONObject.idsImdb(key: String) = optJSONObject(key)?.optString("imdb")?.takeIf { it.startsWith("tt") }

    /**
     * Marks here what WeTrakr says was watched, for a title being opened: the movie, or each episode by its season
     * and number. Returns how many were marked.
     */
    suspend fun apply(apiName: String, r: LoadResponse, playables: List<Playable>): Int {
        if (!WeTrakr.available || !WeTrakr.connected) return 0
        pull()
        val info = runCatching { Metadata.details(r) }.getOrNull()
        val tmdb = info?.tmdbId
        val imdb = info?.imdbId ?: runCatching { r.getImdbIdSafe() }.getOrNull()
        if (tmdb == null && imdb == null) return 0
        val isMovie = r.type == TvType.Movie || r.type == TvType.AnimeMovie || playables.all { it.episode == null }
        val todo = playables.filter { p ->
            !Library.isWatched(apiName, p.data) && if (isMovie) movieWatched(tmdb, imdb)
            else p.episode != null && episodeWatched(tmdb, imdb, p.season ?: 1, p.episode)
        }
        if (todo.isNotEmpty()) withContext(Dispatchers.Main) {
            Library.markWatched(apiName, r.url, r.name, r.posterUrl, todo.map { it.data to it.label })
        }
        return todo.size
    }

    /** Sends titles marked watched by hand here to WeTrakr (an already watched title doesn't get a second play). */
    fun push(r: LoadResponse, playables: List<Playable>) {
        if (!WeTrakr.available || !WeTrakr.connected || playables.isEmpty()) return
        App.scope.launch(Dispatchers.IO) {
            runCatching {
                val info = Metadata.details(r)
                val tmdb = info?.tmdbId
                val imdb = info?.imdbId ?: r.getImdbIdSafe()
                val ids = JSONObject().apply { tmdb?.let { put("tmdb", it) }; imdb?.let { put("imdb", it) } }
                if (ids.length() == 0) return@runCatching
                val isMovie = r.type == TvType.Movie || r.type == TvType.AnimeMovie || playables.all { it.episode == null }
                val body = JSONObject().put("allow_rewatch", false)
                if (isMovie) {
                    body.put("movies", JSONArray().put(JSONObject().put("ids", ids).put("status", "watched")))
                    synchronized(this@WeTrakrSync) { keys(tmdb, imdb).forEach { movies() += it } }
                } else {
                    val seasons = JSONArray()
                    playables.filter { it.episode != null }.groupBy { it.season ?: 1 }.forEach { (s, eps) ->
                        seasons.put(JSONObject().put("number", s).put("episodes", JSONArray().apply {
                            eps.forEach { put(JSONObject().put("number", it.episode).put("status", "watched")) }
                        }))
                        eps.forEach { ep -> keys(tmdb, imdb).forEach { k -> episodes() += "$k:$s:${ep.episode}"; shows() += k } }
                    }
                    body.put("shows", JSONArray().put(JSONObject().put("ids", ids).put("seasons", seasons)))
                }
                WeTrakr.postAuthed("/sync/tracking", body)
                withContext(Dispatchers.Main) { changed() }
            }
        }
    }

    /** Keeps reading every 5 minutes while the app runs, so a title finished in another WeTrakr app shows soon. */
    fun start() {
        App.scope.launch(Dispatchers.IO) {
            while (true) {
                runCatching { pull(force = true) }
                delay(5 * 60_000L)
            }
        }
    }

    fun forget() { movies = null; episodes = null; shows = null; prefs.edit().clear().apply(); changed() }

    private fun changed() { App.scope.launch(Dispatchers.Main) { version++ } }
    init { Library.onChange { changed() } }

    /** What a poster's green stamp says. */
    enum class Stamp(val label: String) { WATCHED("Watched"), WATCHING("Watching") }

    /**
     * The stamp for a poster: watched here (a film finished, or episodes of a show), or watched on WeTrakr, matched by
     * the title's TMDB / IMDb id whichever extension lists it. Null: not watched.
     */
    suspend fun stamp(item: com.lagradost.cloudstream3.SearchResponse): Stamp? {
        val movieType = when (item.type) {
            com.lagradost.cloudstream3.TvType.Movie, com.lagradost.cloudstream3.TvType.AnimeMovie, com.lagradost.cloudstream3.TvType.Torrent -> true
            com.lagradost.cloudstream3.TvType.TvSeries, com.lagradost.cloudstream3.TvType.Anime, com.lagradost.cloudstream3.TvType.Cartoon,
            com.lagradost.cloudstream3.TvType.AsianDrama, com.lagradost.cloudstream3.TvType.OVA -> false
            else -> null
        }
        Library.titleFinished(item.apiName, item.url)?.let { noEpisode ->
            return if (movieType ?: noEpisode) Stamp.WATCHED else Stamp.WATCHING
        }
        if (!WeTrakr.available || !WeTrakr.connected) return null
        if (movies().isEmpty() && shows().isEmpty()) return null
        val (tmdb, imdb, isMovie) = Metadata.posterIds(item) ?: return null
        val k = keys(tmdb, imdb)
        val movie = k.any { it in movies() }
        val show = k.any { it in shows() }
        return when {
            isMovie == true -> if (movie) Stamp.WATCHED else null
            isMovie == false -> if (show) Stamp.WATCHING else null
            movie -> Stamp.WATCHED
            show -> Stamp.WATCHING
            else -> null
        }
    }

    private fun LoadResponse.getImdbIdSafe(): String? = runCatching { getImdbId() }.getOrNull()?.takeIf { it.startsWith("tt") }
}
