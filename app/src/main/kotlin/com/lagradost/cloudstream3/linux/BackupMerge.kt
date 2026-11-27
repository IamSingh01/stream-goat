package com.lagradost.cloudstream3.linux

import org.json.JSONArray
import org.json.JSONObject

/**
 * The shared Google Drive backup keeps every Stream Goat app (phone, TV, Windows, Mac, Linux) in sync. Each device
 * merges its data with the shared file instead of overwriting it, so what you did on the phone and what you did on
 * the TV both stay:
 *
 * - three-way: what changed since this device last synced ([base]) on this device ([local]) and in the shared file
 *   ([remote]) is kept from both sides; something removed on one side and untouched on the other is removed;
 * - inside the library's lists (My list, history, watch progress, watched marks, extensions, repositories,
 *   playlists…) item by item, recognised by their name, address or id;
 * - when both sides changed the same item, the newer one (by its time stamp) wins, else this device's.
 *
 * Only content is synced; each device's own settings, sign-ins and caches ([deviceOnly]) stay on the device.
 * The same code is in the Android app (BackupMerge.kt there), so the shared file means the same on every app.
 */
object BackupMerge {
    /**
     * Stores that belong to one device: its settings (layout, player, buffer, DNS, theme…, also per profile), its
     * sign-ins (Google Drive, WeTrakr), caches, torrents, crash reports. Never synced, never overwritten.
     */
    val deviceOnly = Regex(
        "^(app_settings|linux_settings|cloud_backup|crash|torrents|measured_quality|metadata)$" +
            "|^(profile_settings|wetrakr)(@.*)?$|^(WebView|androidx|com\\.google)|cache",
        RegexOption.IGNORE_CASE,
    )

    /** The stores of a backup file that are synced (content, not this device's own). */
    fun content(stores: JSONObject): JSONObject {
        val out = JSONObject()
        for (name in stores.keys()) if (!deviceOnly.containsMatchIn(name)) out.put(name, stores.get(name))
        return out
    }

    /** Merges the stores (name → key → typed value, as in a backup file). [base] null: first sync, nothing removed. */
    fun merge(base: JSONObject?, local: JSONObject, remote: JSONObject?): JSONObject {
        if (remote == null) return local
        val out = JSONObject()
        for (name in (local.keys().asSequence() + remote.keys().asSequence()).toSet().sorted()) {
            val b = base?.optJSONObject(name)
            val l = local.optJSONObject(name)
            val r = remote.optJSONObject(name)
            val merged = mergeValue(b ?: Absent, l ?: Absent, r ?: Absent) { bb, ll, rr -> mergeStore(bb, ll, rr) }
            if (merged is JSONObject && merged.length() > 0) out.put(name, merged)
        }
        return out
    }

    private object Absent

    private fun mergeStore(b: Any, l: Any, r: Any): Any {
        if (l !is JSONObject || r !is JSONObject) return pick(l, r)
        val bo = b as? JSONObject
        val out = JSONObject()
        for (k in (l.keys().asSequence() + r.keys().asSequence()).toSet()) {
            val v = mergeValue(bo?.opt(k) ?: Absent, l.opt(k) ?: Absent, r.opt(k) ?: Absent) { bb, ll, rr -> mergeTyped(bb, ll, rr) }
            if (v !== Absent) out.put(k, v)
        }
        return out
    }

    /**
     * The three-way rule: unchanged on one side → the other side's; changed on both the same way → that; changed
     * on both differently → [both]. Removed on one side but changed on the other → kept (nothing lost).
     */
    private inline fun mergeValue(b: Any, l: Any, r: Any, both: (Any, Any, Any) -> Any): Any {
        if (same(l, r)) return l
        if (same(l, b)) return r
        if (same(r, b)) return l
        if (l === Absent) return r
        if (r === Absent) return l
        return both(b, l, r)
    }

    /** One setting: {"s": text} (often JSON: the library's lists), {"set": [...]}, or a number / true-false. */
    private fun mergeTyped(b: Any, l: Any, r: Any): Any {
        if (l !is JSONObject || r !is JSONObject) return pick(l, r)
        val bo = b as? JSONObject
        if (l.has("s") && r.has("s")) {
            val lj = parse(l.getString("s")); val rj = parse(r.getString("s"))
            if (lj != null && rj != null) {
                val bj = bo?.optString("s", null)?.let { parse(it) } ?: Absent
                return JSONObject().put("s", deep(bj, lj, rj).let { if (it === Absent) lj else it }.toString())
            }
        }
        if (l.has("set") && r.has("set")) {
            val bs = bo?.optJSONArray("set")?.strings().orEmpty()
            val ls = l.getJSONArray("set").strings(); val rs = r.getJSONArray("set").strings()
            val removed = (bs - ls) + (bs - rs)
            return JSONObject().put("set", JSONArray(((ls + rs) - removed).toSortedSet().toList()))
        }
        return l
    }

    private fun parse(s: String): Any? = when (s.trimStart().firstOrNull()) {
        '{' -> runCatching { JSONObject(s) }.getOrNull()
        '[' -> runCatching { JSONArray(s) }.getOrNull()
        else -> null
    }

    private fun JSONArray.strings(): Set<String> = (0 until length()).map { getString(it) }.toSet()

    /** Values inside a setting's JSON (a list, a map, one entry). */
    private fun deep(b: Any, l: Any, r: Any): Any = mergeValue(b, l, r) { bb, ll, rr ->
        when {
            ll is JSONArray && rr is JSONArray -> mergeList(bb as? JSONArray, ll, rr)
            ll is JSONObject && rr is JSONObject ->
                if (isRecord(ll) && isRecord(rr)) newer(ll, rr)
                else {
                    val bo = bb as? JSONObject
                    val out = JSONObject()
                    for (k in (ll.keys().asSequence() + rr.keys().asSequence()).toSet()) {
                        val v = deep(bo?.opt(k) ?: Absent, ll.opt(k) ?: Absent, rr.opt(k) ?: Absent)
                        if (v !== Absent) out.put(k, v)
                    }
                    out
                }
            else -> pick(ll, rr)
        }
    }

    /** Lists: item by item ([idOf]); the result newest first when the items have a time stamp. */
    private fun mergeList(b: JSONArray?, l: JSONArray, r: JSONArray): JSONArray {
        val bm = b?.byId().orEmpty(); val lm = l.byId(); val rm = r.byId()
        val out = ArrayList<Any>()
        for ((id, lv) in lm) {
            val rv = rm[id]
            val bv = bm[id] ?: Absent
            when {
                rv != null -> deep(bv, lv, rv).takeIf { it !== Absent }?.let { out += it }
                bv !== Absent && same(lv, bv) -> Unit           // removed in the shared file, untouched here
                else -> out += lv                                // added here (or changed here)
            }
        }
        for ((id, rv) in rm) {
            if (id in lm) continue
            val bv = bm[id] ?: Absent
            if (bv !== Absent && same(rv, bv)) continue          // removed here, untouched there
            out += rv                                            // added on another device (or changed there)
        }
        val time = timeKey(out)
        if (time != null) out.sortByDescending { (it as JSONObject).optLong(time) }
        return JSONArray(out)
    }

    private val idKeys = listOf(listOf("apiName", "url"), listOf("apiName", "data"), listOf("internalName"), listOf("url"),
        listOf("id"), listOf("data"), listOf("name"))

    private fun idOf(v: Any): String {
        if (v is JSONObject) for (keys in idKeys) if (keys.all { v.has(it) && !v.isNull(it) }) return keys.joinToString("|") { v.get(it).toString() }
        return canon(v)
    }

    private fun JSONArray.byId(): LinkedHashMap<String, Any> {
        val m = LinkedHashMap<String, Any>()
        for (i in 0 until length()) { val v = get(i); m.putIfAbsent(idOf(v), v) }
        return m
    }

    private val timeKeys = listOf("updatedAt", "addedAt", "watchedAt", "lastWatched", "time", "date", "modified", "created")

    private fun timeKey(items: List<Any>): String? =
        if (items.isEmpty() || items.any { it !is JSONObject }) null
        else timeKeys.firstOrNull { k -> items.all { (it as JSONObject).opt(k) is Number } }

    /** An entry whose fields are all plain values (one progress, one bookmark): taken whole, never mixed. */
    private fun isRecord(o: JSONObject) = o.keys().asSequence().all { val v = o.opt(it); v !is JSONObject && v !is JSONArray }

    private fun newer(l: JSONObject, r: JSONObject): JSONObject {
        val k = timeKeys.firstOrNull { l.opt(it) is Number && r.opt(it) is Number } ?: return l
        return if (r.getLong(k) > l.getLong(k)) r else l
    }

    private fun pick(l: Any, r: Any): Any = if (l === Absent) r else l

    private fun same(a: Any, b: Any): Boolean = a === b || (a !== Absent && b !== Absent && canon(a) == canon(b))

    /** The same text for the same data, whatever the order of its keys. */
    fun canon(v: Any?): String = when (v) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> v.keys().asSequence().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canon(v.opt(it)) }
        is JSONArray -> (0 until v.length()).joinToString(",", "[", "]") { canon(v.opt(it)) }
        is String -> JSONObject.quote(v)
        is Number -> if (v.toDouble() == v.toLong().toDouble()) v.toLong().toString() else v.toString()
        else -> v.toString()
    }
}
