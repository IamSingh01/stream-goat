package io.github.sherazi5110.cloudstream

import com.lagradost.cloudstream3.app
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Live scores for every sport (ESPN's public scoreboard, the one its own apps use: no account or key) and sports
 * news (Google News). Used by the Sports tab.
 */
object Sports {
    /** One feed of ESPN's scoreboard; [group] is the sport it's listed under in the tab. */
    private data class Feed(val group: String, val sport: String, val league: String? = null)

    private val feeds = listOf(
        Feed("Football", "soccer"),
        Feed("Cricket", "cricket"),
        Feed("Basketball", "basketball", "nba"),
        Feed("American football", "football", "nfl"),
        Feed("American football", "football", "college-football"),
        Feed("Tennis", "tennis"),
        Feed("Ice hockey", "hockey"),
        Feed("MMA", "mma"),
        Feed("Baseball", "baseball"),
        Feed("F1", "racing", "f1"),
        Feed("Golf", "golf", "pga"),
    )

    /** The sports the tab can filter by, in order. */
    val groups: List<String> = feeds.map { it.group }.distinct()

    /** What Google News is searched for, per sport ("All" = its Sports headlines). */
    private val newsQuery = mapOf("Football" to "football soccer", "American football" to "NFL", "Ice hockey" to "NHL hockey",
        "MMA" to "UFC MMA", "F1" to "Formula 1")

    data class Side(
        val name: String,
        val short: String,
        val logo: String?,
        val score: String?,
        val winner: Boolean,
        /** Tennis: games in each set. */
        val sets: List<String> = emptyList(),
    )

    data class Match(
        val id: String,
        val group: String,
        val league: String,
        /** What the match is: "Group B, Major League Tournament at Dambulla", a tournament or the teams. */
        val title: String,
        /** "pre" (not started), "in" (live) or "post" (over). */
        val state: String,
        /** "3:08 - 2nd", "Stumps", "Final", "3rd Set"… */
        val status: String,
        val start: Long,
        val location: String?,
        val link: String?,
        val sides: List<Side>,
        /** Extra lines for the match's page: toss, close of play, broadcast… */
        val notes: List<String>,
    ) {
        val live get() = state == "in"
        val upcoming get() = state == "pre"
        val finished get() = state == "post"
    }

    data class News(val title: String, val source: String?, val link: String, val time: Long)

    /** Every sport's matches (live, coming up and today's results), all feeds at once. */
    suspend fun matches(): List<Match> = withContext(Dispatchers.IO) {
        coroutineScope {
            feeds.map { f -> async { runCatching { fetch(f) }.getOrDefault(emptyList()) } }.awaitAll().flatten()
                .distinctBy { it.id }
        }
    }

    private suspend fun fetch(f: Feed): List<Match> {
        val url = "https://site.web.api.espn.com/apis/v2/scoreboard/header?sport=${f.sport}" +
            (f.league?.let { "&league=$it" } ?: "") + "&lang=en&region=us"
        val json = JSONObject(app.get(url, timeout = 15).text)
        val out = ArrayList<Match>()
        val sports = json.optJSONArray("sports") ?: return out
        for (i in 0 until sports.length()) {
            val leagues = sports.getJSONObject(i).optJSONArray("leagues") ?: continue
            for (j in 0 until leagues.length()) {
                val league = leagues.getJSONObject(j)
                val leagueName = league.optString("shortName").ifBlank { league.optString("name") }.ifBlank { league.optString("abbreviation") }
                val events = league.optJSONArray("events") ?: continue
                for (k in 0 until events.length()) runCatching { out += match(events.getJSONObject(k), f.group, leagueName) }
            }
        }
        return out
    }

    private fun match(e: JSONObject, group: String, league: String): Match {
        val type = e.optJSONObject("fullStatus")?.optJSONObject("type")
        val state = e.optString("status").ifBlank { type?.optString("state").orEmpty() }
        val sides = e.optJSONArray("competitors").objects().sortedBy { if (it.optString("homeAway") == "away") 0 else 1 }
            .let { list -> if (list.size > 2) list.sortedBy { it.optInt("order", 99) } else list }
            .map { c ->
                Side(
                    name = c.optString("displayName").ifBlank { c.optString("name") },
                    short = c.optString("abbreviation").ifBlank { c.optString("shortDisplayName") },
                    logo = c.optString("logoDark").ifBlank { c.optString("logo") }.ifBlank { null },
                    score = c.optString("score").ifBlank { null },
                    winner = c.optBoolean("winner"),
                    sets = c.optJSONArray("linescores").objects().map { it.optString("displayValue") },
                )
            }
        val status = e.optString("summary").ifBlank { type?.optString("shortDetail").orEmpty() }.ifBlank { type?.optString("description").orEmpty() }
        val notes = buildList {
            type?.optString("detail")?.takeIf { it.isNotBlank() && it != status }?.let { add(it) }
            e.optJSONArray("notes").objects().filter { it.optString("type") in setOf("toss", "closeofplay", "matchdays", "event") || it.optString("type").isBlank() }
                .mapNotNull { it.optString("text").takeIf { t -> t.isNotBlank() } }.takeLast(4).forEach { add(it) }
            e.optString("broadcast").takeIf { it.isNotBlank() }?.let { add("On $it") }
        }
        val title = listOf(e.optString("description"), e.optString("title"), e.optString("name"))
            .firstOrNull { it.isNotBlank() && it.length > 3 } ?: league
        return Match(
            id = e.optString("uid").ifBlank { e.optString("id") } + "|" + e.optString("competitionId"),
            group = group, league = league, title = title, state = state, status = status,
            start = parseTime(e.optString("date")), location = e.optString("location").ifBlank { null },
            link = e.optString("link").ifBlank { null }, sides = sides, notes = notes,
        )
    }

    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    private fun parseTime(s: String): Long = runCatching { java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli() }
        .recoverCatching { java.time.OffsetDateTime.parse(s.replace("Z", ":00Z")).toInstant().toEpochMilli() }.getOrDefault(0L)

    /** Sports headlines from Google News, for one sport or all ([group] null). */
    suspend fun news(group: String?): List<News> = withContext(Dispatchers.IO) {
        val region = java.util.Locale.getDefault().country.takeIf { it.length == 2 } ?: "US"
        val lang = java.util.Locale.getDefault().language.ifBlank { "en" }
        val tail = "hl=$lang-$region&gl=$region&ceid=$region:$lang"
        val url = if (group == null) "https://news.google.com/rss/headlines/section/topic/SPORTS?$tail"
            else "https://news.google.com/rss/search?q=" + java.net.URLEncoder.encode((newsQuery[group] ?: group) + " when:2d", "UTF-8") + "&$tail"
        val xml = app.get(url, timeout = 15).text
        xml.split("<item>").drop(1).mapNotNull { item ->
            fun tag(name: String) = Regex("<$name[^>]*>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(item)?.groupValues?.get(1)?.let { unescape(it) }
            val source = tag("source")
            val title = tag("title")?.let { t -> source?.let { t.removeSuffix(" - $it") } ?: t } ?: return@mapNotNull null
            val link = tag("link") ?: return@mapNotNull null
            val time = runCatching {
                java.time.ZonedDateTime.parse(tag("pubDate"), java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
            }.getOrDefault(0L)
            News(title, source, link, time)
        }.distinctBy { it.title }.distinctBy { it.link }.take(40)
    }

    private fun unescape(s: String) = s.replace("<![CDATA[", "").replace("]]>", "")
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">").trim()
}
