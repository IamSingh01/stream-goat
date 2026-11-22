package com.lagradost.cloudstream3.linux.ui

import javafx.application.Platform
import javafx.scene.control.Label
import javafx.scene.media.Media
import javafx.scene.media.MediaPlayer as FxMediaPlayer
import javafx.scene.media.MediaView
import javafx.util.Duration
import uk.co.caprica.vlcj.media.TrackType
import uk.co.caprica.vlcj.player.base.Equalizer
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.base.State
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer
import java.io.File

/** Which player plays a source on Windows, Mac and Linux. */
enum class EngineKind(val label: String) {
    /** JavaFX's own streaming player: reads HLS itself (real length, exact seeking), like ExoPlayer on Android. */
    STREAM("Stream Goat player"),
    VLC("VLC"),
}

/** An audio or subtitle track (id -1 = off). Named like vlcj's, so the player code reads the same for both players. */
class EngineTrack(private val id: Int, private val description: String) {
    fun id() = id
    fun description() = description
}

/** What a player tells the screen around it (on any thread; the screen moves it to the UI thread). */
interface EngineEvents {
    fun playing()
    fun paused()
    fun buffering(cache: Float)
    fun timeChanged(ms: Long)
    fun lengthChanged(ms: Long)
    fun seekableChanged(seekable: Boolean)
    fun streamAdded(audio: Boolean)
    fun finished()
    fun error()
}

/** VLC or the Stream Goat player behind the same calls. Delays are in microseconds, as in VLC. */
interface PlayerEngine {
    val kind: EngineKind
    /** Opens [url] (VLC: with its options), starting at [startAt] ms. False when it can't even start. */
    fun open(url: String, options: List<String>, startAt: Long?): Boolean
    fun play()
    fun pause()
    fun stop()
    fun release()
    fun setTime(ms: Long)
    fun time(): Long
    val isPlaying: Boolean
    val isSeekable: Boolean
    fun ended(): Boolean
    fun rate(): Float
    fun setRate(rate: Float)
    /** Percent, 0-200 (VLC goes louder than 100). */
    fun setVolume(percent: Int)
    fun setMute(muted: Boolean)
    fun setEqualizer(eq: Equalizer?)
    fun audioTracks(): List<EngineTrack>
    fun audioTrack(): Int
    fun setAudioTrack(id: Int)
    fun audioDelay(): Long
    fun setAudioDelay(us: Long)
    fun subtitleTracks(): List<EngineTrack>
    fun subtitleTrack(): Int
    fun setSubtitleTrack(id: Int)
    fun subtitleDelay(): Long
    fun setSubtitleDelay(us: Long)
    /** A subtitle file (SRT or WebVTT); [select] shows it at once. */
    fun addSubtitle(file: File, select: Boolean)
    fun snapshot(file: File): Boolean
}

// ---------------------------------------------------------------- VLC

class VlcEngine(video: VideoView, events: EngineEvents) : PlayerEngine {
    override val kind = EngineKind.VLC
    private val player: EmbeddedMediaPlayer = Vlc.factory!!.mediaPlayers().newEmbeddedMediaPlayer()

    init {
        video.showMedia(null, null)
        player.videoSurface().set(video.surface)
        player.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun playing(mediaPlayer: MediaPlayer) = events.playing()
            override fun paused(mediaPlayer: MediaPlayer) = events.paused()
            override fun buffering(mediaPlayer: MediaPlayer, newCache: Float) = events.buffering(newCache)
            override fun timeChanged(mediaPlayer: MediaPlayer, newTime: Long) = events.timeChanged(newTime)
            override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) = events.lengthChanged(newLength)
            override fun seekableChanged(mediaPlayer: MediaPlayer, newSeekable: Int) = events.seekableChanged(newSeekable == 1)
            override fun elementaryStreamAdded(mediaPlayer: MediaPlayer, type: TrackType, id: Int) = events.streamAdded(type == TrackType.AUDIO)
            override fun finished(mediaPlayer: MediaPlayer) = events.finished()
            override fun error(mediaPlayer: MediaPlayer) = events.error()
        })
    }

    override fun open(url: String, options: List<String>, startAt: Long?): Boolean {
        val opts = options.toMutableList()
        // Seeking with :start-time works for files and HLS alike, before the first frame.
        startAt?.takeIf { it > 1000 }?.let { opts += ":start-time=${it / 1000.0}" }
        return player.media().play(url, *opts.toTypedArray())
    }
    override fun play() = player.controls().play()
    override fun pause() = player.controls().pause()
    override fun stop() = player.controls().stop()
    override fun release() = player.release()
    override fun setTime(ms: Long) = player.controls().setTime(ms)
    override fun time() = player.status().time()
    override val isPlaying get() = player.status().isPlaying
    override val isSeekable get() = player.status().isSeekable
    override fun ended() = player.status().state() == State.ENDED
    override fun rate() = player.status().rate()
    override fun setRate(rate: Float) { player.controls().setRate(rate) }
    override fun setVolume(percent: Int) { player.audio().setVolume(percent) }
    override fun setMute(muted: Boolean) = player.audio().setMute(muted)
    override fun setEqualizer(eq: Equalizer?) = player.audio().setEqualizer(eq)
    override fun audioTracks() = player.audio().trackDescriptions().map { EngineTrack(it.id(), it.description()) }
    override fun audioTrack() = player.audio().track()
    override fun setAudioTrack(id: Int) { player.audio().setTrack(id) }
    override fun audioDelay() = player.audio().delay()
    override fun setAudioDelay(us: Long) { player.audio().setDelay(us) }
    override fun subtitleTracks() = player.subpictures().trackDescriptions().map { EngineTrack(it.id(), it.description()) }
    override fun subtitleTrack() = player.subpictures().track()
    override fun setSubtitleTrack(id: Int) { player.subpictures().setTrack(id) }
    override fun subtitleDelay() = player.subpictures().delay()
    override fun setSubtitleDelay(us: Long) { player.subpictures().setDelay(us) }
    override fun addSubtitle(file: File, select: Boolean) {
        player.media().addSlave(uk.co.caprica.vlcj.media.MediaSlaveType.SUBTITLE, file.toPath().toUri().toString(), select)
    }
    override fun snapshot(file: File) = runCatching { player.snapshots().save(file) }.getOrDefault(false)
}

// ---------------------------------------------------------------- the Stream Goat player (JavaFX)

/**
 * JavaFX's media player: it reads HLS playlists itself (like ExoPlayer on Android), so streams VLC mistakes for live
 * (NetMirror: 27:46:39, seeks landing in the wrong place) show their real length and seek where you ask. It plays
 * H.264/AAC streams through the local proxy (headers, disguised segments, inline playlists); anything else falls
 * back to VLC. It draws subtitles itself (SRT/WebVTT files, the same ones VLC gets).
 */
class StreamEngine(private val video: VideoView, private val events: EngineEvents) : PlayerEngine {
    override val kind = EngineKind.STREAM
    private val view = MediaView().apply { isSmooth = true }
    private val subtitle = Label().apply {
        isWrapText = true
        isMouseTransparent = true
        isVisible = false
        textAlignment = javafx.scene.text.TextAlignment.CENTER
        style = "-fx-text-fill: white; -fx-font-size: 26px; -fx-font-weight: bold; -fx-background-color: rgba(0,0,0,0.55);" +
            "-fx-background-radius: 6; -fx-padding: 4 12 4 12; -fx-effect: dropshadow(gaussian, black, 4, 0.6, 0, 0);"
    }
    private var mp: FxMediaPlayer? = null
    private var volume = 1.0
    private var muted = false
    private var speed = 1.0
    private var ended = false
    private var seekable = false
    private var lastTime = -1L
    private var pendingStart: Long? = null

    private class Cue(val start: Long, val end: Long, val text: String)
    private val tracks = mutableListOf<List<Cue>>()
    private var shown = -1
    private var subDelayUs = 0L

    init {
        video.showMedia(view, subtitle)
        video.heightProperty().addListener { _, _, h -> subtitle.style = subtitle.style.replace(Regex("-fx-font-size: [0-9.]+px"), "-fx-font-size: ${(h.toDouble() / 24).coerceIn(14.0, 46.0).toInt()}px") }
    }

    private var opening = 0

    override fun open(url: String, options: List<String>, startAt: Long?): Boolean {
        release(keepView = true)
        ended = false; seekable = false; lastTime = -1
        val ticket = ++opening
        // First a look at the stream (off the UI thread): what JavaFX can't play goes straight to VLC.
        Thread({
            val problem = runCatching { FxSupport.problem(url) }.getOrNull()
            Platform.runLater {
                if (ticket != opening) return@runLater
                if (problem != null) {
                    android.util.Log.i("player", "Stream Goat player can't play this stream ($problem): VLC plays it")
                    events.error()
                } else if (!start(url, startAt)) events.error()
            }
        }, "stream-check").apply { isDaemon = true }.start()
        return true
    }

    private fun start(url: String, startAt: Long?): Boolean {
        val media = runCatching { Media(url) }.getOrElse { android.util.Log.w("player", "Stream Goat player can't open it: $it"); return false }
        val player = runCatching { FxMediaPlayer(media) }.getOrElse { android.util.Log.w("player", "Stream Goat player: $it"); return false }
        mp = player
        media.setOnError { events.error() }
        player.setOnError { android.util.Log.w("player", "Stream Goat player: ${player.error?.message}"); events.error() }
        player.setOnReady {
            seekable = true
            media.width.takeIf { it > 0 }?.let { video.mediaSize(it, media.height) }
            val length = media.duration
            if (length != null && !length.isUnknown && !length.isIndefinite) events.lengthChanged(length.toMillis().toLong())
            events.seekableChanged(true)
            events.streamAdded(false)
            pendingStart = startAt?.takeIf { it > 1000 }
            applyAudio()
            player.rate = speed
            player.play()
        }
        player.totalDurationProperty().addListener { _, _, d ->
            if (d != null && !d.isUnknown && !d.isIndefinite && d.toMillis() > 0) events.lengthChanged(d.toMillis().toLong())
        }
        media.widthProperty().addListener { _, _, w -> if (w.toInt() > 0) video.mediaSize(w.toInt(), media.height) }
        player.setOnPlaying {
            // Resuming: HLS seeks once it's actually playing (a seek before that is ignored).
            pendingStart?.let { pendingStart = null; player.seek(Duration.millis(it.toDouble())) }
            events.buffering(100f); events.playing()
        }
        player.setOnPaused { events.paused() }
        player.setOnStalled { events.buffering(0f) }
        player.setOnEndOfMedia { ended = true; events.finished() }
        player.currentTimeProperty().addListener { _, _, t ->
            val ms = t.toMillis().toLong()
            showSubtitle(ms)
            // About 4 times a second is plenty for the clock and the seek bar.
            if (lastTime < 0 || kotlin.math.abs(ms - lastTime) >= 250) { lastTime = ms; events.timeChanged(ms) }
        }
        view.mediaPlayer = player
        return true
    }

    private fun applyAudio() { mp?.volume = volume; mp?.isMute = muted }

    override fun play() { mp?.play() }
    override fun pause() { mp?.pause() }
    override fun stop() { mp?.stop() }
    override fun release() = release(keepView = false)

    private fun release(keepView: Boolean) {
        opening++
        val old = mp ?: return run { if (!keepView) video.showMedia(null, null) }
        mp = null
        view.mediaPlayer = null
        runCatching { old.stop() }
        runCatching { old.dispose() }
        if (!keepView) video.showMedia(null, null)
    }

    override fun setTime(ms: Long) { mp?.seek(Duration.millis(ms.toDouble())); showSubtitle(ms) }
    override fun time() = mp?.currentTime?.toMillis()?.toLong() ?: 0L
    override val isPlaying get() = mp?.status == FxMediaPlayer.Status.PLAYING
    override val isSeekable get() = seekable
    override fun ended() = ended
    override fun rate() = speed.toFloat()
    override fun setRate(rate: Float) { speed = rate.toDouble(); mp?.rate = speed }
    // JavaFX stops at 100 %: louder isn't possible with this player.
    override fun setVolume(percent: Int) { volume = (percent / 100.0).coerceIn(0.0, 1.0); applyAudio() }
    override fun setMute(muted: Boolean) { this.muted = muted; applyAudio() }
    override fun setEqualizer(eq: Equalizer?) = Unit
    override fun audioTracks() = emptyList<EngineTrack>()
    override fun audioTrack() = -1
    override fun setAudioTrack(id: Int) = Unit
    override fun audioDelay() = 0L
    override fun setAudioDelay(us: Long) = Unit

    override fun subtitleTracks() = listOf(EngineTrack(-1, "Disable")) + tracks.indices.map { EngineTrack(it, "Track ${it + 1}") }
    override fun subtitleTrack() = shown
    override fun setSubtitleTrack(id: Int) { shown = if (id in tracks.indices) id else -1; showSubtitle(time()) }
    override fun subtitleDelay() = subDelayUs
    override fun setSubtitleDelay(us: Long) { subDelayUs = us; showSubtitle(time()) }

    override fun addSubtitle(file: File, select: Boolean) {
        val cues = runCatching { parseSubtitles(file.readText(Charsets.UTF_8)) }.getOrDefault(emptyList())
        tracks += cues
        if (select) shown = tracks.lastIndex
        showSubtitle(time())
    }

    private fun showSubtitle(ms: Long) {
        val cues = tracks.getOrNull(shown)
        val t = ms - subDelayUs / 1000
        val cue = cues?.firstOrNull { t >= it.start && t < it.end }
        Platform.runLater {
            subtitle.text = cue?.text.orEmpty()
            subtitle.isVisible = cue != null
            video.requestLayout()
        }
    }

    override fun snapshot(file: File): Boolean = runCatching {
        val img = view.snapshot(null, null)
        val w = img.width.toInt(); val h = img.height.toInt()
        val out = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val reader = img.pixelReader
        for (y in 0 until h) for (x in 0 until w) out.setRGB(x, y, reader.getArgb(x, y))
        javax.imageio.ImageIO.write(out, "png", file)
    }.getOrDefault(false)

    companion object {
        private val timing = Regex("""(?:(\d+):)?(\d{1,2}):(\d{2})[.,](\d{1,3})\s*-->\s*(?:(\d+):)?(\d{1,2}):(\d{2})[.,](\d{1,3})""")
        private val tags = Regex("""<[^>]+>|\{\\[^}]*}""")

        private fun ms(h: String?, m: String, s: String, f: String) =
            ((h?.toLongOrNull() ?: 0) * 3600 + m.toLong() * 60 + s.toLong()) * 1000 + f.padEnd(3, '0').take(3).toLong()

        /** SRT and WebVTT cues: their times and their text (formatting tags removed). */
        private fun parseSubtitles(text: String): List<Cue> =
            text.replace("\r\n", "\n").replace('\r', '\n').split(Regex("\n\\s*\n")).mapNotNull { block ->
                val lines = block.trim('\n', '﻿').lines()
                val at = lines.indexOfFirst { timing.containsMatchIn(it) }.takeIf { it >= 0 } ?: return@mapNotNull null
                val g = timing.find(lines[at])!!.groupValues
                val body = lines.drop(at + 1).joinToString("\n").replace(tags, "").trim()
                if (body.isEmpty()) null else Cue(ms(g[1].ifEmpty { null }, g[2], g[3], g[4]), ms(g[5].ifEmpty { null }, g[6], g[7], g[8]), body)
            }.sortedBy { it.start }
    }
}

/**
 * What JavaFX's HLS player can't play, found before trying (it fails with "Could not create player!" or plays
 * without sound): H.265/AV1/Dolby Vision video, Dolby (AC-3/E-AC-3) sound, a separate audio track, SAMPLE-AES.
 * AES-128 is fine: the proxy decrypts it. Returns the reason, or null when it should play.
 */
object FxSupport {
    private val badCodec = Regex("CODECS=\"[^\"]*(hvc1|hev1|dvh1|dvhe|av01|ac-3|ec-3)", RegexOption.IGNORE_CASE)
    private val client = java.net.http.HttpClient.newBuilder().proxy(java.net.ProxySelector.of(null))
        .connectTimeout(java.time.Duration.ofSeconds(10)).build()

    private fun get(url: String, range: Boolean = false): ByteArray? {
        val request = java.net.http.HttpRequest.newBuilder(java.net.URI(url)).timeout(java.time.Duration.ofSeconds(20))
            .apply { if (range) header("Range", "bytes=0-${188 * 1500}") }.build()
        val response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofByteArray())
        return response.body().takeIf { response.statusCode() in 200..299 }
    }

    fun problem(url: String): String? {
        if (!url.startsWith("http://127.0.0.1:")) return null
        var text = get(url)?.toString(Charsets.UTF_8) ?: return null
        if (!text.trimStart().startsWith("#EXTM3U")) return null
        badCodec.find(text)?.let { return "${it.groupValues[1]} codec" }
        if (Regex("#EXT-X-MEDIA:[^\n]*TYPE=AUDIO[^\n]*URI=").containsMatchIn(text)) return "separate audio track"
        if ("#EXT-X-STREAM-INF" in text) {
            // JavaFX picks a variant by bandwidth; the first one is representative enough.
            val variant = text.lineSequence().dropWhile { !it.startsWith("#EXT-X-STREAM-INF") }.drop(1)
                .firstOrNull { it.isNotBlank() && !it.startsWith("#") } ?: return null
            text = get(variant.trim())?.toString(Charsets.UTF_8) ?: return null
        }
        if ("METHOD=SAMPLE-AES" in text) return "SAMPLE-AES encryption"
        val segment = text.lineSequence().firstOrNull { it.isNotBlank() && !it.startsWith("#") }?.trim() ?: return null
        return tsProblem(get(segment, range = true) ?: return null)
    }

    /** Reads the MPEG-TS program table of a segment's start: which video and sound it carries. */
    internal fun tsProblem(data: ByteArray): String? {
        var start = (0 until minOf(data.size, 188 * 4)).firstOrNull { i ->
            data[i] == 0x47.toByte() && (i + 188 >= data.size || data[i + 188] == 0x47.toByte())
        } ?: return null
        var pmtPid = -1
        while (start + 188 <= data.size) {
            val b = data
            val pid = ((b[start + 1].toInt() and 0x1f) shl 8) or (b[start + 2].toInt() and 0xff)
            val unitStart = b[start + 1].toInt() and 0x40 != 0
            val afc = (b[start + 3].toInt() shr 4) and 3
            var off = start + 4 + if (afc and 2 != 0) 1 + (b[start + 4].toInt() and 0xff) else 0
            if (unitStart && afc and 1 != 0 && off < start + 188) {
                off += 1 + (b[off].toInt() and 0xff)
                if (pid == 0 && off + 12 <= start + 188) {
                    pmtPid = ((b[off + 10].toInt() and 0x1f) shl 8) or (b[off + 11].toInt() and 0xff)
                } else if (pid == pmtPid && off + 12 <= start + 188) {
                    val sectionEnd = minOf(start + 188, off + 3 + (((b[off + 1].toInt() and 0x0f) shl 8) or (b[off + 2].toInt() and 0xff)) - 4)
                    var i = off + 12 + (((b[off + 10].toInt() and 0x0f) shl 8) or (b[off + 11].toInt() and 0xff))
                    while (i + 5 <= sectionEnd) {
                        when (b[i].toInt() and 0xff) {
                            0x24 -> return "H.265 video"
                            0x81, 0x87 -> return "Dolby sound"
                        }
                        i += 5 + (((b[i + 3].toInt() and 0x0f) shl 8) or (b[i + 4].toInt() and 0xff))
                    }
                    return null
                }
            }
            start += 188
        }
        return null
    }
}
