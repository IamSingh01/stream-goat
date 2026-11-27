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

    /** Why the last read failed, in WeTrakr's own words (shown by Sync now), or null. */
    @Volatile var lastError: String? = null
        private set

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
            var rowsSeen = 0
            var sample: String? = null
            lastError = null
            var limit = 5000
            for (target in listOf("movies", "episodes")) {
                // Compact pages are walked with a cursor: none on the first call, then each answer's X-Pagination-Next as
                // "after", until there is none.
                var after: String? = null
                var page = 1
                while (page < 2000) {
                    val q = "compact=true&limit=$limit" + (since?.let { "&from_date=${java.net.URLEncoder.encode(it, "UTF-8")}" } ?: "") +
                        (after?.let { "&after=${java.net.URLEncoder.encode(it, "UTF-8")}" } ?: "")
                    val answer = WeTrakr.getPage("/sync/tracking/watched/history/$target?$q")
                    if (answer == null) { lastError = "WeTrakr couldn't be reached (offline, or signed out: connect again)"; return@withLock null }
                    val (code, body, next) = answer
                    if (code !in 200..299) {
                        // A smaller page size first (the server may cap it), then give up with WeTrakr's own words.
                        if (limit > 100 && code in 400..499 && code != 401 && code != 403) { limit = 100; continue }
                        val said = runCatching { JSONObject(body).let { it.optString("message").ifBlank { it.optString("error") } } }.getOrNull()
                            ?.takeIf { it.isNotBlank() } ?: body.take(160)
                        lastError = "WeTrakr answered $code" + (if (code == 401) " (sign-in expired: disconnect and connect again)" else "") + ": $said"
                        android.util.Log.w("wetrakr", "history $target page $page: $code $said")
                        return@withLock null
                    }
                    val rows = runCatching { JSONArray(body) }.getOrNull()
                        ?: runCatching { JSONObject(body).let { o -> o.optJSONArray("items") ?: o.optJSONArray("data") ?: o.optJSONArray("results") } }.getOrNull()
                    if (rows == null) { lastError = "WeTrakr's answer wasn't a list: ${body.take(120)}"; android.util.Log.w("wetrakr", lastError!!); return@withLock null }
                    for (i in 0 until rows.length()) {
                        val o = rows.optJSONObject(i) ?: continue
                        rowsSeen++
                        if (sample == null) sample = o.toString().take(200)
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
                    if (next == null || next == after || rows.length() == 0) break
                    after = next
                    page++
                }
            }
            if (rowsSeen > 0 && films == 0 && eps.isEmpty() && m.isEmpty() && e.isEmpty()) {
                // Rows came but none could be read: say what they look like instead of importing nothing.
                lastError = "WeTrakr sent $rowsSeen entries in a form Stream Goat doesn't read yet: $sample"
                android.util.Log.w("wetrakr", lastError!!)
                return@withLock null
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
        // What was watched in another extension (or on WeTrakr) is marked here too: kept by ids, signed in or not.
        if (WeTrakr.available && WeTrakr.connected) pull()
        val info = runCatching { Metadata.details(r) }.getOrNull()
        val tmdb = info?.tmdbId
        val imdb = info?.imdbId ?: runCatching { r.getImdbIdSafe() }.getOrNull()
        if (tmdb == null && imdb == null) return 0
        noteOffered(tmdb, imdb, playables)
        if (movies().isEmpty() && episodes().isEmpty()) return 0
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

    private fun save() = synchronized(this) {
        prefs.edit().putStringSet("movies", movies().toSet()).putStringSet("episodes", episodes().toSet())
            .putStringSet("shows", shows().toSet()).apply()
    }

    /** The title's ids (TMDB, IMDb), or null when neither is known. */
    private suspend fun idsOf(r: LoadResponse): Pair<Int?, String?>? {
        val info = runCatching { Metadata.details(r) }.getOrNull()
        val tmdb = info?.tmdbId
        val imdb = info?.imdbId ?: r.getImdbIdSafe()
        return if (tmdb == null && imdb == null) null else tmdb to imdb
    }

    /** Remembers here, by ids, that these were watched: every extension that lists the title shows it watched. */
    private fun remember(tmdb: Int?, imdb: String?, isMovie: Boolean, eps: List<Pair<Int, Int>>) = synchronized(this) {
        val k = keys(tmdb, imdb)
        if (isMovie) k.forEach { movies() += it }
        else eps.forEach { (s, e) -> k.forEach { key -> episodes() += "$key:$s:$e"; shows() += key } }
        save()
    }

    /** A film or episode finished in the player (from 80%): remembered by ids for every extension. */
    fun finished(r: LoadResponse, season: Int?, episode: Int?) {
        App.scope.launch(Dispatchers.IO) {
            runCatching {
                val (tmdb, imdb) = idsOf(r) ?: return@runCatching
                val isMovie = episode == null
                remember(tmdb, imdb, isMovie, if (isMovie) emptyList() else listOf((season ?: 1) to episode!!))
                changed()
            }
        }
    }

    /**
     * Marked watched by hand: remembered by ids for every extension, and sent to WeTrakr when connected (an already
     * watched title doesn't get a second play).
     */
    fun push(r: LoadResponse, playables: List<Playable>) {
        if (playables.isEmpty()) return
        App.scope.launch(Dispatchers.IO) {
            runCatching {
                val (tmdb, imdb) = idsOf(r) ?: return@runCatching
                val isMovie = r.type == TvType.Movie || r.type == TvType.AnimeMovie || playables.all { it.episode == null }
                val eps = playables.filter { it.episode != null }.map { (it.season ?: 1) to it.episode!! }
                remember(tmdb, imdb, isMovie, eps)
                changed()
                if (!WeTrakr.available || !WeTrakr.connected) return@runCatching
                val ids = JSONObject().apply { tmdb?.let { put("tmdb", it) }; imdb?.let { put("imdb", it) } }
                val body = JSONObject().put("allow_rewatch", false)
                if (isMovie) body.put("movies", JSONArray().put(JSONObject().put("ids", ids).put("status", "watched")))
                else {
                    val seasons = JSONArray()
                    eps.groupBy { it.first }.forEach { (s, list) ->
                        seasons.put(JSONObject().put("number", s).put("episodes", JSONArray().apply {
                            list.forEach { put(JSONObject().put("number", it.second).put("status", "watched")) }
                        }))
                    }
                    body.put("shows", JSONArray().put(JSONObject().put("ids", ids).put("seasons", seasons)))
                }
                WeTrakr.postAuthed("/sync/tracking", body)
            }
        }
    }

    /** Marked not watched by hand: forgotten here for every extension (WeTrakr keeps its own history). */
    fun unmark(r: LoadResponse, playables: List<Playable>) {
        App.scope.launch(Dispatchers.IO) {
            runCatching {
                val (tmdb, imdb) = idsOf(r) ?: return@runCatching
                val k = keys(tmdb, imdb)
                val isMovie = r.type == TvType.Movie || r.type == TvType.AnimeMovie || playables.all { it.episode == null }
                synchronized(this@WeTrakrSync) {
                    if (isMovie) k.forEach { movies() -= it }
                    else playables.filter { it.episode != null }.forEach { p -> k.forEach { episodes() -= "$it:${p.season ?: 1}:${p.episode}" } }
                    save()
                }
                changed()
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

    /**
     * What a poster shows: a big green WATCHED stamp ([big]) for a film or a show whose aired episodes were all watched;
     * a small CONTINUE badge for a show part-way through; NEW EPISODES when you had caught up and newer ones aired.
     */
    enum class Stamp(val label: String, val big: Boolean) {
        WATCHED("Watched", true), CONTINUE("Continue", false), NEW_EPISODES("New episodes", false)
    }

    /**
     * The stamp for a poster: watched here in any extension or on WeTrakr, matched by the title's TMDB / IMDb id.
     * Null: not watched.
     */
    suspend fun stamp(item: com.lagradost.cloudstream3.SearchResponse): Stamp? {
        val movieType = when (item.type) {
            TvType.Movie, TvType.AnimeMovie, TvType.Torrent -> true
            TvType.TvSeries, TvType.Anime, TvType.Cartoon, TvType.AsianDrama, TvType.OVA -> false
            else -> null
        }
        // This extension's own history: a film finished, or a show with finished episodes.
        val local = Library.titleFinished(item.apiName, item.url)
        val localStamp = local?.let { noEpisode -> if (movieType ?: noEpisode) Stamp.WATCHED else Stamp.CONTINUE }
        if (localStamp == Stamp.WATCHED) return localStamp
        if (movies().isEmpty() && shows().isEmpty()) return localStamp
        val (tmdb, imdb, isMovie) = Metadata.posterIds(item) ?: return localStamp
        val k = keys(tmdb, imdb)
        val movie = isMovie ?: movieType
        if (movie != false && k.any { it in movies() }) return Stamp.WATCHED
        if (movie == true || k.none { it in shows() }) return localStamp
        val watched = HashSet<Pair<Int, Int>>()
        for (e in episodes()) {
            val p = e.split(':')
            if (p.size == 3 && p[0] in k) {
                val s = p[1].toIntOrNull() ?: continue; val n = p[2].toIntOrNull() ?: continue
                if (s > 0) watched += s to n
            }
        }
        if (watched.isEmpty()) return localStamp
        val tv = tmdb ?: imdb?.let { Metadata.tmdbForImdb(it) }
        val aired = tv?.let { Metadata.aired(it) } ?: return Stamp.CONTINUE
        return seriesStamp(watched, withOffered(aired, offered(k)))
    }

    /**
     * The newest episode each show's extension offers, by ids ("t123" → "1:8"). TMDB counts only the episodes that
     * have aired, and a weekly show's extension can already have more (Dead-End Job: 4 aired, 8 offered): a show
     * is WATCHED only once the newest offered episode was watched too. Noted when its title page opens.
     */
    private fun noteOffered(tmdb: Int?, imdb: String?, playables: List<Playable>) {
        val newest = playables.filter { it.episode != null && (it.season ?: 1) > 0 }
            .maxWithOrNull(compareBy({ it.season ?: 1 }, { it.episode!! })) ?: return
        val value = "${newest.season ?: 1}:${newest.episode}"
        val map = runCatching { org.json.JSONObject(prefs.getString("offered", null) ?: "{}") }.getOrDefault(org.json.JSONObject())
        val k = keys(tmdb, imdb)
        if (k.all { map.optString(it) == value }) return
        k.forEach { map.put(it, value) }
        prefs.edit().putString("offered", map.toString()).apply()
    }

    private fun offered(k: List<String>): Pair<Int, Int>? {
        val map = runCatching { org.json.JSONObject(prefs.getString("offered", null) ?: "{}") }.getOrNull() ?: return null
        return k.firstNotNullOfOrNull { key -> map.optString(key).split(':').takeIf { it.size == 2 } }
            ?.let { (s, e) -> (s.toIntOrNull() ?: return null) to (e.toIntOrNull() ?: return null) }
    }

    /** [a] with the extension's newest episode counted as out, when it's beyond what TMDB says has aired. */
    internal fun withOffered(a: Metadata.Aired, offered: Pair<Int, Int>?): Metadata.Aired {
        val (s, e) = offered ?: return a
        if (s < a.lastSeason || (s == a.lastSeason && e <= a.lastEpisode)) return a
        return a.copy(seasons = a.seasons + (s to maxOf(a.seasons[s] ?: 0, e)), lastSeason = s, lastEpisode = e)
    }

    /** All aired episodes watched → WATCHED; caught up with only the newest missing (recent) → NEW_EPISODES; else CONTINUE. */
    internal fun seriesStamp(watched: Set<Pair<Int, Int>>, a: Metadata.Aired, today: java.time.LocalDate = java.time.LocalDate.now()): Stamp {
        val aired = (1..a.lastSeason).flatMap { s ->
            val count = if (s == a.lastSeason) a.lastEpisode else (a.seasons[s] ?: 0)
            (1..count).map { s to it }
        }
        val missing = aired.filter { it !in watched }
        if (missing.isEmpty()) return Stamp.WATCHED
        val last = watched.maxWithOrNull(compareBy({ it.first }, { it.second })) ?: return Stamp.CONTINUE
        val onlyNewer = missing.all { it.first > last.first || (it.first == last.first && it.second > last.second) }
        val recent = a.lastAirDate?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }?.isAfter(today.minusDays(120)) == true
        // Caught up means every earlier season was finished: only the latest season is (partly) missing.
        val onlyLatestSeason = missing.all { it.first == a.lastSeason }
        val newSeason = a.lastSeason > 1 && watched.none { it.first == a.lastSeason }
        return if (onlyNewer && recent && onlyLatestSeason && (missing.size <= 3 || newSeason)) Stamp.NEW_EPISODES else Stamp.CONTINUE
    }

    private fun LoadResponse.getImdbIdSafe(): String? = runCatching { getImdbId() }.getOrNull()?.takeIf { it.startsWith("tt") }
}
