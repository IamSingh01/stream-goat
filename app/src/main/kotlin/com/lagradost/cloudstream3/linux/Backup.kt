package com.lagradost.cloudstream3.linux

import android.content.SharedPreferences
import com.lagradost.cloudstream3.linux.ui.AppInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Back up and restore: every settings store (app settings, profiles, library and history, repositories, the
 * list of installed extensions, extension settings, Live TV sources, Stremio add-ons) as one JSON file.
 * The file works on both apps (Linux and Android). Extensions themselves aren't in it: after a restore,
 * the ones that are missing are downloaded again from their repositories.
 */
object Backup {
    const val FORMAT = "cloudstream-linxdroid-backup"

    /**
     * Settings stores that hold nothing worth moving (caches, this device's paths), or that belong to this device
     * only: its Google Drive sign-in (cloud_backup) and its last crash report (crash).
     */
    private val skip = Regex("^(WebView|androidx|com\\.google)|cache|^(cloud_backup|crash)$", RegexOption.IGNORE_CASE)

    private fun names(): List<String> = File(AppDirs.data, "prefs").listFiles { f -> f.name.endsWith(".json") }
        ?.map { it.name.removeSuffix(".json") }.orEmpty().filterNot { skip.containsMatchIn(it) }.sorted()

    private fun prefs(name: String): SharedPreferences = Platform.context.getSharedPreferences(name, 0)

    fun export(): String {
        val stores = JSONObject()
        for (name in names()) {
            val values = JSONObject()
            prefs(name).getAll().forEach { (k, v) ->
                values.put(k, when (v) {
                    is String -> JSONObject().put("s", v)
                    is Boolean -> JSONObject().put("b", v)
                    is Int -> JSONObject().put("i", v)
                    is Long -> JSONObject().put("l", v)
                    is Float -> JSONObject().put("f", v.toDouble())
                    is Set<*> -> JSONObject().put("set", JSONArray(v.map { it.toString() }))
                    else -> return@forEach
                })
            }
            if (values.length() > 0) stores.put(name, values)
        }
        return JSONObject().put("format", FORMAT).put("version", 1).put("app", AppInfo.VERSION)
            .put("created", System.currentTimeMillis()).put("stores", stores).toString(1)
    }

    /** Replaces the settings stores found in [json]. Returns how many stores were restored. */
    fun import(json: String): Int {
        val root = JSONObject(json)
        require(root.optString("format") == FORMAT) { "This isn't a Stream Goat backup file" }
        val stores = root.getJSONObject("stores")
        var n = 0
        for (name in stores.keys()) {
            if (skip.containsMatchIn(name)) continue
            val edit = prefs(name).edit().clear()
            putAll(edit, stores.getJSONObject(name))
            edit.commit()
            n++
        }
        return n
    }

    private fun putAll(edit: SharedPreferences.Editor, values: JSONObject) {
        for (k in values.keys()) {
            val o = values.getJSONObject(k)
            when {
                o.has("s") -> edit.putString(k, o.getString("s"))
                o.has("b") -> edit.putBoolean(k, o.getBoolean("b"))
                o.has("i") -> edit.putInt(k, o.getInt("i"))
                o.has("l") -> edit.putLong(k, o.getLong("l"))
                o.has("f") -> edit.putFloat(k, o.getDouble("f").toFloat())
                o.has("set") -> o.getJSONArray("set").let { a -> edit.putStringSet(k, (0 until a.length()).map { a.getString(it) }.toSet()) }
            }
        }
    }

    /** The settings stores of a backup file (name → key → value). */
    fun stores(json: String): JSONObject {
        val root = JSONObject(json)
        require(root.optString("format") == FORMAT) { "This isn't a Stream Goat backup file" }
        return root.getJSONObject("stores")
    }

    /** A backup file holding [stores] (the shared Google Drive backup: content only, see BackupMerge). */
    fun document(stores: JSONObject): String = JSONObject().put("format", FORMAT).put("version", 1).put("app", AppInfo.VERSION)
        .put("created", System.currentTimeMillis()).put("stores", stores).toString(1)

    /**
     * Writes the synced stores of [merged] that differ from [current] (both content only, see BackupMerge); a
     * store gone from [merged] is emptied. This device's own settings are never touched. Returns the changed stores.
     */
    fun applyContent(merged: JSONObject, current: JSONObject): Set<String> {
        val changed = mutableSetOf<String>()
        for (name in (merged.keys().asSequence() + current.keys().asSequence()).toSet()) {
            if (skip.containsMatchIn(name) || BackupMerge.deviceOnly.containsMatchIn(name)) continue
            val m = merged.optJSONObject(name)
            val c = current.optJSONObject(name)
            if (m != null && c != null && BackupMerge.canon(m) == BackupMerge.canon(c)) continue
            if (m == null && c == null) continue
            val edit = prefs(name).edit().clear()
            if (m != null) putAll(edit, m)
            edit.commit()
            changed += name
        }
        return changed
    }

    fun fileName(): String = "cloudstream-backup-" + java.time.LocalDate.now() + ".json"
}
