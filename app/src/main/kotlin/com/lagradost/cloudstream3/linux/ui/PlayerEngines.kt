package com.lagradost.cloudstream3.linux.ui

import com.sun.jna.Native
import com.sun.jna.Pointer
import uk.co.caprica.vlcj.media.TrackType
import uk.co.caprica.vlcj.player.base.Equalizer
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.base.State
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer
import java.io.File

/** Which player plays a source on Windows, Mac and Linux. */
enum class EngineKind(val label: String) {
    /** mpv (FFmpeg): reads HLS itself (separate audio tracks, real length, exact seeking), like ExoPlayer on Android. */
    MPV("mpv"),
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

/** mpv or VLC behind the same calls. Delays are in microseconds, as in VLC. */
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

// ---------------------------------------------------------------- mpv

/**
 * mpv (libmpv, FFmpeg inside): reads HLS itself like ExoPlayer on Android, with separate audio and subtitle tracks,
 * the real length and exact seeking. Sources come through the local proxy (headers, disguised segments, inline
 * playlists). Frames are drawn by mpv's software renderer straight into the video view's buffer; subtitles are drawn
 * into the frame by mpv (libass).
 */
class MpvEngine(private val video: VideoView, private val events: EngineEvents) : PlayerEngine {
    override val kind = EngineKind.MPV
    private val lib = Mpv.lib ?: error("libmpv isn't installed")
    private val ctx: Pointer = Mpv.create() ?: error("mpv didn't start")
    private var render: Pointer? = null
    @Volatile private var released = false
    @Volatile private var pos = 0L
    @Volatile private var paused = false
    @Volatile private var started = false
    @Volatile private var endedFlag = false
    @Volatile private var seekable = false
    @Volatile private var speed = 1f
    @Volatile private var dw = 0L
    @Volatile private var dh = 0L
    @Volatile private var vw = 0L
    @Volatile private var vh = 0L
    /** Errors from a file being replaced aren't this one's. */
    @Volatile private var loading = 0
    private val wake = java.util.concurrent.Semaphore(0)
    // Kept in a field: JNA callbacks must not be collected while mpv can call them.
    private val onUpdate = Mpv.UpdateCallback { wake.release() }
    private var format = "bgra"
    private val eventThread: Thread
    private val renderThread: Thread

    init {
        video.reset()
        fun opt(k: String, v: String) = lib.mpv_set_option_string(ctx, k, v)
        opt("vo", "libmpv")
        opt("hwdec", "auto-copy-safe")
        opt("idle", "yes")
        opt("keep-open", "yes")
        opt("terminal", "no")
        opt("osc", "no")
        opt("input-default-bindings", "no")
        opt("input-vo-keyboard", "no")
        opt("ytdl", "no")
        opt("audio-client-name", "Stream Goat")
        opt("volume-max", "200")
        opt("hls-bitrate", "max")
        opt("network-timeout", "30")
        opt("cache", "yes")
        opt("demuxer-max-bytes", "200MiB")
        opt("demuxer-readahead-secs", "30")
        opt("sub-auto", "no")
        opt("sub-font-size", "44")
        opt("sub-border-size", "2.5")
        if (video.upscale) opt("sws-scaler", "lanczos")
        // MPV_LOG=<file>: mpv's own log (what FFmpeg says about a stream), for finding why one doesn't play.
        System.getenv("MPV_LOG")?.let { opt("log-file", it); opt("msg-level", "all=v") }
        check(lib.mpv_initialize(ctx) >= 0) { "mpv didn't start" }
        listOf("time-pos" to 5, "duration" to 5, "pause" to 3, "paused-for-cache" to 3, "seekable" to 3, "eof-reached" to 3,
            "track-list/count" to 4, "speed" to 5, "dwidth" to 4, "dheight" to 4, "width" to 4, "height" to 4)
            .forEachIndexed { i, (name, format) -> lib.mpv_observe_property(ctx, i + 1L, name, format) }
        val api = Mpv.cString("sw")
        val ref = com.sun.jna.ptr.PointerByReference()
        check(lib.mpv_render_context_create(ref, ctx, Mpv.Params(listOf(Mpv.PARAM_API_TYPE to api)).memory) >= 0) { "mpv can't draw" }
        render = ref.value
        lib.mpv_render_context_set_update_callback(ref.value, onUpdate, null)
        eventThread = Thread(::eventLoop, "mpv-events").apply { isDaemon = true; start() }
        renderThread = Thread(::renderLoop, "mpv-render").apply { isDaemon = true; start() }
    }

    private fun prop(name: String): String? {
        val p = lib.mpv_get_property_string(ctx, name) ?: return null
        return try { p.getString(0, "UTF-8") } finally { lib.mpv_free(p) }
    }
    private fun set(name: String, value: String) = lib.mpv_set_property_string(ctx, name, value)
    private fun command(vararg args: String) = lib.mpv_command(ctx, com.sun.jna.StringArray(args, "UTF-8"))

    private fun eventLoop() {
        while (true) {
            val ev = lib.mpv_wait_event(ctx, 1.0)
            when (ev.getInt(0)) {
                1 -> return // shutdown
                22 -> property(ev.getLong(8), ev.getPointer(16))
                7 -> { // end of a file: an error is a failed source (EOF stays open: keep-open)
                    val reason = ev.getPointer(16)?.getInt(0) ?: 0
                    if (reason == 4 && !released && loading == 0) {
                        android.util.Log.w("mpv", "couldn't play: " + lib.mpv_error_string(ev.getPointer(16).getInt(4)))
                        events.error()
                    }
                    if (loading > 0) loading--
                }
                8 -> events.streamAdded(false) // file loaded
            }
            if (released && ev.getInt(0) == 0) return
        }
    }

    private fun property(id: Long, p: Pointer?) {
        p ?: return
        val format = p.getInt(8)
        val data = p.getPointer(16)
        if (format == 0 || data == null) return
        when (id) {
            1L -> { // time-pos
                pos = (data.getDouble(0) * 1000).toLong()
                if (!started && !paused) { started = true; events.buffering(100f); events.playing() }
                events.timeChanged(pos)
            }
            2L -> events.lengthChanged((data.getDouble(0) * 1000).toLong())
            3L -> {
                paused = data.getInt(0) != 0
                if (paused) events.paused() else if (started) events.playing()
            }
            4L -> events.buffering(if (data.getInt(0) != 0) 0f else 100f)
            5L -> { seekable = data.getInt(0) != 0; events.seekableChanged(seekable) }
            6L -> if (data.getInt(0) != 0 && started && !endedFlag) { endedFlag = true; events.finished() }
            7L -> if (data.getLong(0) > 0) events.streamAdded(true)
            8L -> speed = data.getDouble(0).toFloat()
            9L -> { dw = data.getLong(0); wake.release() }
            10L -> { dh = data.getLong(0); wake.release() }
            11L -> vw = data.getLong(0)
            12L -> vh = data.getLong(0)
        }
    }

    private fun renderLoop() {
        val size = com.sun.jna.Memory(8)
        val stride = com.sun.jna.Memory(8)
        var fmt = Mpv.cString(format)
        while (!released) {
            wake.tryAcquire(200, java.util.concurrent.TimeUnit.MILLISECONDS)
            val r = render ?: continue
            if (released) break
            if (lib.mpv_render_context_update(r) and 1L == 0L) continue
            if (dw <= 0 || dh <= 0) continue
            val target = video.mpvBuffer(vw.toInt().takeIf { it > 0 } ?: dw.toInt(), vh.toInt().takeIf { it > 0 } ?: dh.toInt(), dw.toInt(), dh.toInt())
            size.setInt(0, target.width); size.setInt(4, target.height)
            stride.setLong(0, target.width * 4L)
            val params = Mpv.Params(listOf(Mpv.PARAM_SW_SIZE to size, Mpv.PARAM_SW_FORMAT to fmt, Mpv.PARAM_SW_STRIDE to stride,
                Mpv.PARAM_SW_POINTER to Native.getDirectBufferPointer(target.buffer)))
            val rc = lib.mpv_render_context_render(r, params.memory)
            if (rc < 0 && format == "bgra") {
                // An mpv without "bgra" output: "bgr0" leaves the 4th byte undefined, which is set opaque here.
                format = "bgr0"; fmt = Mpv.cString(format); continue
            }
            if (format == "bgr0") {
                val b = target.buffer
                for (i in 3 until target.width * target.height * 4 step 4) b.put(i, -1)
            }
            video.mpvFrame()
        }
    }

    override fun open(url: String, options: List<String>, startAt: Long?): Boolean {
        started = false; endedFlag = false; pos = 0
        set("start", startAt?.takeIf { it > 1000 }?.let { "%.3f".format(java.util.Locale.ROOT, it / 1000.0) } ?: "none")
        set("pause", "no")
        if (prop("idle-active") != "yes") loading++
        return command("loadfile", url, "replace") >= 0
    }
    override fun play() { set("pause", "no") }
    override fun pause() { set("pause", "yes") }
    override fun stop() { command("stop") }
    override fun release() {
        if (released) return
        released = true
        wake.release()
        runCatching { renderThread.join(2000) }
        render?.let { lib.mpv_render_context_set_update_callback(it, null, null); lib.mpv_render_context_free(it) }
        render = null
        lib.mpv_terminate_destroy(ctx)
    }
    override fun setTime(ms: Long) { endedFlag = false; command("seek", "%.3f".format(java.util.Locale.ROOT, ms / 1000.0), "absolute") }
    override fun time() = pos
    override val isPlaying get() = started && !paused && !endedFlag
    override val isSeekable get() = seekable
    override fun ended() = endedFlag
    override fun rate() = speed
    override fun setRate(rate: Float) { set("speed", "%.2f".format(java.util.Locale.ROOT, rate)) }
    override fun setVolume(percent: Int) { set("volume", percent.coerceIn(0, 200).toString()) }
    override fun setMute(muted: Boolean) { set("mute", if (muted) "yes" else "no") }
    override fun setEqualizer(eq: Equalizer?) {
        // VLC's 10 bands as FFmpeg equalizer filters (the Audio menu's presets).
        val bands = listOf(60, 170, 310, 600, 1000, 3000, 6000, 12000, 14000, 16000)
        val amps = eq?.amps()
        if (amps == null || amps.all { it == 0f } && eq.preamp() == 0f) { set("af", ""); return }
        val filters = bands.indices.filter { it < amps.size }.joinToString(",") { i ->
            "equalizer=f=${bands[i]}:t=o:w=1:g=${"%.1f".format(java.util.Locale.ROOT, amps[i])}"
        } + ",volume=${"%.1f".format(java.util.Locale.ROOT, eq.preamp())}dB"
        set("af", "lavfi=[$filters]")
    }

    private fun tracks(type: String): List<EngineTrack> {
        val n = prop("track-list/count")?.toIntOrNull() ?: 0
        return (0 until n).mapNotNull { i ->
            if (prop("track-list/$i/type") != type) return@mapNotNull null
            val id = prop("track-list/$i/id")?.toIntOrNull() ?: return@mapNotNull null
            val lang = prop("track-list/$i/lang")
            val title = prop("track-list/$i/title")
                ?.replace(Regex("^\\d+\\.\\s*"), "") // NetMirror numbers its tracks: "2. Hindi"
            val name = title ?: lang?.let { com.lagradost.cloudstream3.linux.OnlineSubtitles.languageName(it) } ?: "Track $id"
            EngineTrack(id, if (lang != null) "$name - [$lang]" else name)
        }
    }
    private fun trackId(name: String) = prop(name)?.toIntOrNull() ?: -1
    override fun audioTracks() = listOf(EngineTrack(-1, "Disable")) + tracks("audio")
    override fun audioTrack() = trackId("aid")
    override fun setAudioTrack(id: Int) { set("aid", if (id < 0) "no" else id.toString()) }
    override fun audioDelay() = ((prop("audio-delay")?.toDoubleOrNull() ?: 0.0) * 1_000_000).toLong()
    override fun setAudioDelay(us: Long) { set("audio-delay", "%.3f".format(java.util.Locale.ROOT, us / 1_000_000.0)) }
    override fun subtitleTracks() = listOf(EngineTrack(-1, "Disable")) + tracks("sub")
    override fun subtitleTrack() = trackId("sid")
    override fun setSubtitleTrack(id: Int) { set("sid", if (id < 0) "no" else id.toString()) }
    override fun subtitleDelay() = ((prop("sub-delay")?.toDoubleOrNull() ?: 0.0) * 1_000_000).toLong()
    override fun setSubtitleDelay(us: Long) { set("sub-delay", "%.3f".format(java.util.Locale.ROOT, us / 1_000_000.0)) }
    override fun addSubtitle(file: File, select: Boolean) { command("sub-add", file.absolutePath, if (select) "select" else "auto") }
    override fun snapshot(file: File) = command("screenshot-to-file", file.absolutePath, "subtitles") >= 0 && file.isFile
}
