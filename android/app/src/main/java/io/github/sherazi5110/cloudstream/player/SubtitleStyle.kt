package io.github.sherazi5110.cloudstream.player

/**
 * How subtitles look (Settings → the player's "Subtitle style") and the clean-up applied to subtitle files:
 * closed captions, ads and capitals. The look becomes libVLC options for VLC, and a styled subtitle view for
 * ExoPlayer (ExoEngine.restyle).
 */
object SubtitleStyle {
    /** A snapshot of the style settings (plain values, so it can be tested and compared). */
    data class Look(
        val size: String = "NORMAL",
        val color: String = "#ffffff",
        val font: String = "DEFAULT",
        val bold: Boolean = false,
        val edge: String = "OUTLINE",
        val outline: Int = 4,
        val outlineColor: String = "#000000",
        val background: Boolean = false,
        val backgroundColor: String = "#000000",
        val backgroundOpacity: Int = 60,
        val height: Int = 0,
        val align: String = "CENTER",
    )

    /** Text clean-up of subtitle files (only files: subtitles inside the video can't be changed). */
    data class Clean(
        val noCaptions: Boolean = false,
        val noAds: Boolean = true,
        val uppercase: Boolean = false,
        val italic: Boolean = false,
    ) {
        val active get() = noCaptions || noAds || uppercase || italic
        val key get() = (if (noCaptions) "c" else "") + (if (noAds) "a" else "") + (if (uppercase) "u" else "") + (if (italic) "i" else "")
    }

    /** The style chosen in Settings. */
    fun current() = io.github.sherazi5110.cloudstream.Settings.let { s ->
        Look(s.subtitleSize, s.subtitleColor, s.subtitleFont, s.subtitleBold, s.subtitleEdge, s.subtitleOutline.takeIf { it > 0 } ?: 4,
            s.subtitleOutlineColor, s.subtitleBackground, s.subtitleBackgroundColor, s.subtitleBackgroundOpacity, s.subtitleHeight, s.subtitleAlign)
    }
    fun currentClean() = io.github.sherazi5110.cloudstream.Settings.let { s ->
        Clean(s.subtitleNoCaptions, s.subtitleNoAds, s.subtitleUppercase, s.subtitleItalic)
    }

    val sizes = listOf("TINY" to "Tiny", "SMALL" to "Small", "NORMAL" to "Normal", "LARGE" to "Large", "HUGE" to "Huge")
    val colors = listOf("#ffffff" to "White", "#ffeb3b" to "Yellow", "#7fdbff" to "Cyan", "#8bc34a" to "Green", "#ff9800" to "Orange", "#ff80ab" to "Pink", "#000000" to "Black")
    val fonts = listOf("DEFAULT" to "Default", "SERIF" to "Serif", "MONO" to "Monospace", "CONDENSED" to "Condensed")
    val edges = listOf("OUTLINE" to "Outline", "SHADOW" to "Shadow", "BOTH" to "Outline + shadow", "NONE" to "None")
    val outlines = listOf(2 to "Thin", 4 to "Normal", 6 to "Thick")
    val heights = listOf(0 to "Bottom", 1 to "Raised", 2 to "Higher", 3 to "High")
    val aligns = listOf("CENTER" to "Centre", "LEFT" to "Left", "RIGHT" to "Right")

    /** libVLC divides the picture height by this to get the font size: smaller = bigger text. */
    fun relFontSize(size: String) = when (size) { "TINY" -> 23; "SMALL" -> 19; "LARGE" -> 13; "HUGE" -> 10; else -> 16 }

    /** The preview's font size in sp for a ~1080-line picture shown on a phone or TV. */
    fun previewSp(size: String) = when (size) { "TINY" -> 13f; "SMALL" -> 16f; "LARGE" -> 23f; "HUGE" -> 30f; else -> 19f }

    /** ExoPlayer: the text's height as a share of the picture's, for [Look.size] (Normal = ExoPlayer's own). */
    fun exoTextSize(size: String) = when (size) { "TINY" -> 0.038f; "SMALL" -> 0.045f; "LARGE" -> 0.066f; "HUGE" -> 0.085f; else -> 0.0533f }

    /** ExoPlayer: the space under the subtitles as a share of the picture's height, for [Look.height]. */
    fun exoBottom(height: Int) = when (height) { 1 -> 0.14f; 2 -> 0.22f; 3 -> 0.30f; else -> 0.08f }

    /** Pixels from the bottom for [Look.height]. */
    fun margin(height: Int) = when (height) { 1 -> 40; 2 -> 90; 3 -> 150; else -> 0 }

    fun rgb(hex: String): Int = hex.removePrefix("#").toIntOrNull(16)?.and(0xffffff) ?: 0xffffff

    /** The libVLC options for [look]. */
    fun vlcOptions(look: Look): List<String> = buildList {
        add("--freetype-rel-fontsize=${relFontSize(look.size)}")
        add("--freetype-color=${rgb(look.color)}")
        when (look.font) {
            "SERIF" -> add("--freetype-font=serif")
            "MONO" -> add("--freetype-font=monospace")
            "CONDENSED" -> add("--freetype-font=sans-serif-condensed")
        }
        if (look.bold) add("--freetype-bold")
        val outline = look.edge == "OUTLINE" || look.edge == "BOTH"
        val shadow = look.edge == "SHADOW" || look.edge == "BOTH"
        add("--freetype-outline-thickness=${if (outline) look.outline.coerceIn(0, 6) else 0}")
        add("--freetype-outline-color=${rgb(look.outlineColor)}")
        add("--freetype-shadow-opacity=${if (shadow) 200 else 0}")
        if (look.background) {
            add("--freetype-background-color=${rgb(look.backgroundColor)}")
            add("--freetype-background-opacity=${(look.backgroundOpacity.coerceIn(0, 100) * 255 / 100)}")
        } else add("--freetype-background-opacity=0")
        add("--sub-margin=${margin(look.height)}")
        add("--subsdec-align=${when (look.align) { "LEFT" -> 1; "RIGHT" -> 2; else -> 0 }}")
    }

    private val timing = Regex("""-->""")
    private val bracketed = Regex("""\[[^\]\n]*]|\([^)\n]*\)""")
    private val music = Regex("""^[\s\-–]*[♪♫#*]+.*$|^.*[♪♫]+[\s\-–]*$""")
    private val speaker = Regex("""^(\s*(?:<[^>]+>\s*)*[-–]?\s*)[A-Z][A-Z0-9 .'&#-]{0,30}:\s+""")
    private val tags = Regex("""<[^>]*>""")
    private val ad = Regex(
        """(?i)(\b(subtitles?|subs|synced?|sync|resync(ed)?|corrected|ripped|captions?|captioned|translated|translation|timing|encoded|transcript)\s*(and\s*\w+\s*)?(by|:)\s*\S)""" +
            """|opensubtitles|addic7ed|subscene|podnapisi|yts\.|yify|\bwww\.|https?://|\.(com|org|net|io|tv|me)\b""" +
            """|downloaded from|support us|become (a )?vip|advertise your|rate this subtitle|osdb|subdl""",
    )

    private fun plain(line: String) = line.replace(tags, "").replace(Regex("""^[\s\-–]+|[\s\-–]+$"""), "")

    /** One cue's text lines after the clean-up; empty = drop the cue. */
    internal fun cleanLines(lines: List<String>, c: Clean): List<String> {
        var out = lines
        if (c.noAds && out.any { ad.containsMatchIn(it.replace(tags, "")) }) return emptyList()
        if (c.noCaptions) out = out.map { l ->
            if (music.matches(plain(l))) "" else l.replace(bracketed, "").replace(speaker, "$1").replace(Regex("""\s{2,}"""), " ")
                .replace(Regex("""^((?:<[^>]+>)*)\s+"""), "$1").replace(Regex("""\s+(</[^>]+>)"""), "$1").trimEnd()
        }
        out = out.filter { plain(it).isNotEmpty() }
        if (c.uppercase) out = out.map { l ->
            buildString {
                var i = 0
                tags.findAll(l).forEach { m -> append(l.substring(i, m.range.first).uppercase()); append(m.value); i = m.range.last + 1 }
                append(l.substring(i).uppercase())
            }
        }
        if (c.italic && out.isNotEmpty()) out = out.map { if (it.contains("<i>", true)) it else "<i>$it</i>" }
        return out
    }

    /**
     * Cleans an SRT or WebVTT file's text ([c]); other formats (ASS/SSA, which carry their own look) are
     * returned unchanged. SRT cues are numbered again after cues are dropped.
     */
    fun clean(text: String, c: Clean): String {
        if (!c.active) return text
        val t = text.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n')
        if (t.trimStart().startsWith("[Script Info]", true) || t.contains("\n[Events]")) return text
        val vtt = t.trimStart().startsWith("WEBVTT")
        val blocks = t.split(Regex("""\n[ \t]*\n+"""))
        val out = StringBuilder()
        var n = 0
        for (b in blocks) {
            val lines = b.trim('\n').split('\n')
            val at = lines.indexOfFirst { timing.containsMatchIn(it) }
            if (at < 0) { if (b.isNotBlank()) out.append(b.trim('\n')).append("\n\n"); continue }
            val text2 = cleanLines(lines.drop(at + 1), c)
            if (text2.isEmpty()) continue
            n++
            val head = lines.take(at)
            if (!vtt) out.append(n).append('\n') else head.forEach { out.append(it).append('\n') }
            out.append(lines[at]).append('\n')
            text2.forEach { out.append(it).append('\n') }
            out.append('\n')
        }
        return out.toString()
    }

    /** Subtitle file bytes → text: UTF-8 (with or without BOM), UTF-16, else the detected or a Western charset. */
    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 2 && (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() || bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()))
            return String(bytes, Charsets.UTF_16)
        val utf8 = Charsets.UTF_8.newDecoder()
        runCatching { return utf8.decode(java.nio.ByteBuffer.wrap(bytes)).toString() }
        val detected = runCatching {
            val d = org.mozilla.universalchardet.UniversalDetector()
            d.handleData(bytes, 0, bytes.size); d.dataEnd()
            d.detectedCharset?.let { java.nio.charset.Charset.forName(it) }
        }.getOrNull()
        return String(bytes, detected ?: java.nio.charset.Charset.forName("windows-1252"))
    }
}
