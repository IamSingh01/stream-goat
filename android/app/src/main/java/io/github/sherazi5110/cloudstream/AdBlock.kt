package io.github.sherazi5110.cloudstream

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * The ad blocker for the Browse tab (and anything else that loads web pages in the app). It uses the open-source
 * filter lists everyone uses (EasyList, EasyPrivacy, Peter Lowe's list), downloads them again every few days by
 * itself, and keeps only what's cheap to check on every request: the blocked host names (and their exceptions), plus
 * a short list of common "hide this element" rules for what slips through. Checking a request is a few hash lookups.
 */
object AdBlock {
    data class FilterList(val id: String, val name: String, val url: String)

    val lists = listOf(
        FilterList("easylist", "EasyList (ads)", "https://easylist.to/easylist/easylist.txt"),
        FilterList("easyprivacy", "EasyPrivacy (trackers)", "https://easylist.to/easylist/easyprivacy.txt"),
        FilterList("pgl", "Peter Lowe's ad and tracking servers", "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=hosts&showintro=0&mimetype=plaintext"),
    )
    private const val REFRESH_MS = 3L * 24 * 60 * 60 * 1000

    /** Parsed lists: blocked hosts, allowed hosts (exceptions) and element-hiding selectors. */
    class Rules(val blocked: Set<String>, val allowed: Set<String>, val hide: List<String>) {
        /** True when [host] or a parent domain of it is blocked (and not allowed). */
        fun blocks(host: String): Boolean {
            var h = host.lowercase().trimEnd('.')
            while (true) {
                if (h in allowed) return false
                if (h in blocked) return true
                val dot = h.indexOf('.')
                if (dot < 0 || h.indexOf('.', dot + 1) < 0) return false   // stop at "example.com"
                h = h.substring(dot + 1)
            }
        }
        val css: String by lazy { if (hide.isEmpty()) "" else hide.joinToString(",") + "{display:none!important}" }
    }

    /** Reads Adblock Plus syntax (and plain hosts files): only network rules on whole hosts and generic hiding rules. */
    fun parse(texts: List<String>, maxHide: Int = 400): Rules {
        val blocked = HashSet<String>(60_000)
        val allowed = HashSet<String>()
        val hide = ArrayList<String>()
        val hostRule = Regex("^(@@)?\\|\\|([a-z0-9.-]+\\.[a-z]{2,})\\^(\\$(.*))?$")
        texts.forEach { text ->
            text.lineSequence().forEach { raw ->
                val line = raw.trim()
                if (line.isEmpty() || line[0] == '!' || line[0] == '[' || (line[0] == '#' && !line.startsWith("##"))) return@forEach
                if (line.startsWith("##")) {
                    // Generic hiding rules only (no :has() and such, which are slow to match).
                    val sel = line.substring(2)
                    if (hide.size < maxHide && ':' !in sel && sel.length < 80 && ',' !in sel) hide += sel
                    return@forEach
                }
                if ("#" in line) return@forEach                     // site-specific hiding rules: skipped
                val parts = line.split(Regex("\\s+"))
                if (parts.size == 2 && (parts[0] == "0.0.0.0" || parts[0] == "127.0.0.1")) {   // hosts-file line
                    if (parts[1] != "localhost" && '.' in parts[1]) blocked += parts[1].lowercase()
                    return@forEach
                }
                val m = hostRule.matchEntire(line.lowercase()) ?: return@forEach
                val opts = m.groupValues[4]
                // Rules limited to some sites or file types would block too much as whole-host rules.
                if ("domain=" in opts || "~third-party" in opts || "popup" in opts || "document" in opts) return@forEach
                if (m.groupValues[1] == "@@") allowed += m.groupValues[2] else blocked += m.groupValues[2]
            }
        }
        return Rules(blocked, allowed, hide)
    }

    var enabled by mutableStateOf(true)
        private set
    var updatedAt by mutableLongStateOf(0L)
        private set
    var ruleCount by mutableIntStateOf(0)
        private set
    var updating by mutableStateOf(false)
        private set
    /** Requests blocked since the app started. */
    val blockedCount = AtomicInteger()

    @Volatile private var rules: Rules = Rules(emptySet(), emptySet(), emptyList())
    private val dir get() = File(App.context.filesDir, "adblock").apply { mkdirs() }

    fun turn(on: Boolean) { enabled = on; Settings.adBlock = on }

    fun blocks(host: String?): Boolean = enabled && host != null && rules.blocks(host)
    fun hideCss(): String = if (enabled) rules.css else ""

    /** At start: reads the saved lists, and downloads fresh ones when they're older than a few days. */
    fun start() {
        enabled = Settings.adBlock
        App.scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            load()
            val age = System.currentTimeMillis() - updatedAt
            if (updatedAt == 0L || age > REFRESH_MS) update()
        }
    }

    private fun load() {
        val files = lists.map { File(dir, it.id + ".txt") }.filter { it.isFile }
        if (files.isEmpty()) return
        val parsed = parse(files.map { it.readText() })
        rules = parsed
        ruleCount = parsed.blocked.size
        updatedAt = files.minOf { it.lastModified() }
    }

    /** Downloads every list again (a list that fails keeps its last copy). */
    suspend fun update() = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        if (updating) return@withContext
        updating = true
        try {
            lists.forEach { l ->
                runCatching {
                    val text = com.lagradost.cloudstream3.app.get(l.url, timeout = 60).text
                    if (text.length > 1000) File(dir, l.id + ".txt").writeText(text)
                }
            }
            load()
        } finally { updating = false }
    }
}
