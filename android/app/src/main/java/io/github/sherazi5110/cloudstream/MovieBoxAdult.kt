package io.github.sherazi5110.cloudstream

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Removes adult titles (MovieBox's "Midnight" content) from MovieBox's answers while adult content is off for this
 * profile. A title ("subject") counts as adult by the rules Phisher's MovieBox extension uses on MovieBox's data.
 */
object MovieBoxAdult {
    private val adultGenres = setOf("adult", "erotic", "erotica", "hot", "18+")
    private val titleWords = listOf("porn", "hentai", "xxx", "seduced", "nude", "naked", "erotica", "vivamax", "ullu", "charmsukh",
        "kooku", "primeplay", "bhabhi", "x-rated", "uncut", "سكس", "جنس")
    private val sex = Regex("\\bsex\\b")
    private val sensual = listOf("pleasure", "seductive", "naked", "escort", "affair", "lover", "mistress", "sensual")

    fun isAdult(o: JSONObject): Boolean {
        val genreText = o.optString("genre").lowercase()
        val genres = genreText.split(',').map { it.trim() }
        val title = o.optString("title").lowercase()
        if (genres.any { it in adultGenres }) return true
        if (sex.containsMatchIn(title) || titleWords.any { it in title }) return true
        val rating = o.optString("contentRating")
        val notForKids = o.optInt("restrictKid", 0) == 1
        if (notForKids && (rating.equals("R", true) || rating.equals("TV-MA", true) || rating.equals("NC-17", true)) &&
            genres.any { it in setOf("romance", "erotic", "hot", "drama") }) return true
        if (o.optInt("subjectType", 1) == 7 && ("romance" in genreText || "drama" in genreText) && sensual.any { it in title }) return true
        return false
    }

    /** A title in MovieBox's data: an object with a subject id and a title. */
    private fun isSubject(o: JSONObject) = (o.has("subjectId") || o.has("subject_id")) && o.has("title")

    /** [removed] counts the titles taken out (one counter per answer: answers are filtered at the same time). */
    private fun clean(v: Any?, removed: IntArray): Any? = when (v) {
        is JSONObject -> {
            v.keys().asSequence().toList().forEach { k -> v.put(k, clean(v.get(k), removed)) }
            v
        }
        is JSONArray -> {
            val out = JSONArray()
            for (i in 0 until v.length()) {
                val item = v.get(i)
                val subject = (item as? JSONObject)?.let { if (isSubject(it)) it else it.optJSONObject("subject")?.takeIf(::isSubject) }
                // A row item can wrap its title ({"subject": {...}}).
                if (subject != null && isAdult(subject)) { removed[0]++; continue }
                out.put(clean(item, removed))
            }
            out
        }
        else -> v
    }

    fun filter(response: Response): Response {
        val type = response.body.contentType()
        if (!response.isSuccessful || type?.subtype?.contains("json") != true) return response
        return runCatching {
            val raw = response.peekBody(8L * 1024 * 1024)
            val bytes = raw.bytes()
            val text = if (response.header("Content-Encoding").equals("gzip", true))
                java.util.zip.GZIPInputStream(bytes.inputStream()).use { it.readBytes().decodeToString() } else bytes.decodeToString()
            if (!text.trimStart().startsWith("{")) return response
            val removed = IntArray(1)
            val cleaned = clean(JSONObject(text), removed) as JSONObject
            if (removed[0] == 0) return response
            android.util.Log.i("moviebox", "Hid ${removed[0]} adult title(s) from ${response.request.url.encodedPath}")
            val body = cleaned.toString().toResponseBody(type.toString().toMediaTypeOrNull())
            response.close()
            response.newBuilder().removeHeader("Content-Encoding").removeHeader("Content-Length").body(body).build()
        }.getOrElse { response }
    }
}
