package io.github.sherazi5110.cloudstream.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import dev.jdtech.mpv.MPVLib

/**
 * mpv (libmpv, FFmpeg underneath): strong with files and formats. It plays through the local proxy like VLC
 * (the proxy adds the extension's headers, fixes disguised segments and serves inline playlists).
 */
class MpvEngine(context: Context) : PlaybackEngine {
    override val kind = EngineKind.MPV
    override var listener: PlaybackEngine.Listener? = null
    private val main = Handler(Looper.getMainLooper())
    override var opening = false
        private set
    private var surfaceView: SurfaceView? = null
    override val view: View? get() = surfaceView
    private var ended = false
    private var paused = false
    private var released = false

    private val mpv: MPVLib = (MPVLib.create(context.applicationContext) ?: error("mpv couldn't start")).apply {
        val s = io.github.sherazi5110.cloudstream.Settings
        val memMb = s.bufferMemoryMb.takeIf { it > 0 } ?: if (io.github.sherazi5110.cloudstream.Images.lowMemoryDevice) 48 else 160
        setOptionString("vo", "gpu")
        setOptionString("gpu-context", "android")
        setOptionString("opengl-es", "yes")
        setOptionString("hwdec", "mediacodec,mediacodec-copy")
        setOptionString("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1")
        setOptionString("ao", "audiotrack,opensles")
        setOptionString("tls-verify", "no")
        setOptionString("cache", "yes")
        setOptionString("cache-secs", (s.bufferAheadSec).toString())
        setOptionString("demuxer-max-bytes", "${memMb}MiB")
        setOptionString("demuxer-max-back-bytes", "${(memMb / 4).coerceAtLeast(16)}MiB")
        setOptionString("cache-pause-initial", "yes")
        setOptionString("cache-pause-wait", (s.startBufferMs / 1000.0).toString())
        setOptionString("volume-max", "200")
        setOptionString("keep-open", "yes")         // stays on the last frame at the end, for the "ended" check
        setOptionString("force-window", "no")
        setOptionString("idle", "yes")
        setOptionString("sub-auto", "no")
        // FFmpeg refuses HLS segments named like pictures; the proxy renames them, this lets the rest through too.
        setOptionString("demuxer-lavf-o", "allowed_extensions=ALL,extension_picky=0")
        init()
        addObserver(object : MPVLib.EventObserver {
            override fun eventProperty(property: String) = Unit
            override fun eventProperty(property: String, value: Long) {
                main.post {
                    when (property) {
                        "time-pos" -> listener?.onTime(value * 1000)
                        "width" -> lastW = value.toInt().also { reportSize() }
                        "height" -> lastH = value.toInt().also { reportSize() }
                    }
                }
            }
            override fun eventProperty(property: String, value: Double) {
                main.post {
                    when (property) {
                        "time-pos/full" -> listener?.onTime((value * 1000).toLong())
                        "duration/full" -> if (value > 0) listener?.onLength((value * 1000).toLong())
                    }
                }
            }
            override fun eventProperty(property: String, value: Boolean) {
                main.post {
                    when (property) {
                        "pause" -> { paused = value; if (value) listener?.onPaused() else if (!opening) listener?.onPlaying() }
                        "paused-for-cache" -> listener?.onBuffering(value)
                        "eof-reached" -> if (value && !opening) { ended = true; listener?.onEnded() }
                    }
                }
            }
            override fun eventProperty(property: String, value: String) = Unit
            override fun event(eventId: Int) {
                main.post {
                    when (eventId) {
                        EVENT_FILE_LOADED -> {
                            opening = false; loaded = true
                            // mpv takes subtitle files only once the video is loaded.
                            pendingSubs.forEach { cmd("sub-add", it, "select") }; pendingSubs.clear()
                        }
                        // The file ended before it ever loaded: mpv couldn't open it.
                        EVENT_END_FILE -> when {
                            // Loading a new source first ends the one before: that end isn't a failure.
                            skipEnd > 0 -> skipEnd--
                            else -> {
                                fileActive = false
                                if (!loaded && opening) { opening = false; listener?.onError("mpv couldn't open this source") }
                            }
                        }
                        EVENT_PLAYBACK_RESTART -> { opening = false; listener?.onBuffering(false); if (!paused) listener?.onPlaying() }
                    }
                }
            }
        })
        observeProperty("time-pos/full", FORMAT_DOUBLE)
        observeProperty("duration/full", FORMAT_DOUBLE)
        observeProperty("pause", FORMAT_FLAG)
        observeProperty("paused-for-cache", FORMAT_FLAG)
        observeProperty("eof-reached", FORMAT_FLAG)
        observeProperty("width", FORMAT_INT64)
        observeProperty("height", FORMAT_INT64)
    }

    private var loaded = false
    /** A file is loaded or loading (replacing it ends it first: that end is skipped). */
    private var fileActive = false
    private val pendingSubs = mutableListOf<String>()
    private var skipEnd = 0
    private var lastW = 0
    private var lastH = 0
    private fun reportSize() { if (lastW > 0 && lastH > 0) listener?.onVideoSize(lastW, lastH) }

    private fun cmd(vararg args: String) { if (!released) runCatching { mpv.command(arrayOf(*args)) } }

    override fun open(r: OpenRequest) {
        ended = false
        opening = true
        loaded = false
        listener?.onBuffering(true)
        runCatching {
            mpv.setPropertyString("start", if (r.startMs > 0) "${r.startMs / 1000.0}" else "none")
            mpv.setPropertyString("hwdec", if (r.softwareDecode) "no" else "mediacodec,mediacodec-copy")
            mpv.setPropertyDouble("speed", r.rate.toDouble())
            mpv.setPropertyDouble("volume", r.volume.toDouble())
            mpv.setPropertyString("af", if (r.levelAudio) "lavfi=[acompressor=threshold=-24dB:ratio=4:attack=25:release=150:makeup=3]" else "")
            mpv.setPropertyString("audio-spdif", if (r.passthrough) "ac3,eac3,dts,dts-hd,truehd" else "")
            if (!r.proxied) {
                val fields = r.headers.entries.filter { !it.key.equals("User-Agent", true) }.joinToString(",") { "${it.key}: ${it.value.replace(",", "\\,")}" }
                mpv.setPropertyString("http-header-fields", fields)
                r.headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.let { mpv.setPropertyString("user-agent", it.value) }
            } else mpv.setPropertyString("http-header-fields", "")
        }
        setZoom(r.zoom)
        if (fileActive) skipEnd++
        fileActive = true
        pendingSubs.clear()
        cmd("loadfile", r.url, "replace")
        r.subtitles.forEach { addSubtitle(it) }
        mpv.setPropertyBoolean("pause", false)
    }

    override fun play() { runCatching { mpv.setPropertyBoolean("pause", false) } }
    override fun pause() { runCatching { mpv.setPropertyBoolean("pause", true) } }
    override fun seekTo(ms: Long) { cmd("seek", "${ms / 1000.0}", "absolute+keyframes") }
    override fun setRate(rate: Float) { runCatching { mpv.setPropertyDouble("speed", rate.toDouble()) } }
    override fun setVolume(percent: Int) { runCatching { mpv.setPropertyDouble("volume", percent.toDouble()) } }
    override fun setZoom(z: Zoom) {
        runCatching {
            mpv.setPropertyDouble("panscan", if (z == Zoom.FILL) 1.0 else 0.0)
            mpv.setPropertyBoolean("keepaspect", z != Zoom.STRETCH)
            mpv.setPropertyString("video-aspect-override", when (z) { Zoom.WIDE -> "16:9"; Zoom.TV -> "4:3"; else -> "no" })
        }
    }
    override fun setDelays(audioMs: Long, subtitleMs: Long) {
        runCatching { mpv.setPropertyDouble("audio-delay", audioMs / 1000.0); mpv.setPropertyDouble("sub-delay", subtitleMs / 1000.0) }
    }

    private fun tracks(type: String): List<TrackInfo> = runCatching {
        val n = mpv.getPropertyInt("track-list/count") ?: 0
        (0 until n).mapNotNull { i ->
            if (mpv.getPropertyString("track-list/$i/type") != type) return@mapNotNull null
            val id = mpv.getPropertyInt("track-list/$i/id") ?: return@mapNotNull null
            val title = mpv.getPropertyString("track-list/$i/title")
            val lang = mpv.getPropertyString("track-list/$i/lang")?.let { l -> java.util.Locale.forLanguageTag(l).displayLanguage.ifBlank { l } }
            TrackInfo(id, listOfNotNull(title, lang).distinct().joinToString(" · ").ifBlank { "Track $id" })
        }
    }.getOrDefault(emptyList())

    private fun selected(prop: String) = runCatching { mpv.getPropertyString(prop)?.toIntOrNull() }.getOrNull() ?: -1

    override fun audioTracks() = if (opening) emptyList() else tracks("audio")
    override fun audioTrack() = selected("aid")
    override fun setAudio(id: Int) { runCatching { mpv.setPropertyString("aid", if (id < 0) "no" else id.toString()) } }
    override fun subtitleTracks() = if (opening) emptyList() else listOf(TrackInfo(-1, "Off")) + tracks("sub")
    override fun subtitleTrack() = selected("sid")
    override fun setSubtitle(id: Int) { runCatching { mpv.setPropertyString("sid", if (id < 0) "no" else id.toString()) } }
    override fun addSubtitle(uri: Uri) {
        val path = if (uri.scheme == "file") uri.path ?: uri.toString() else uri.toString()
        if (loaded) cmd("sub-add", path, "select") else pendingSubs += path
    }

    override fun reallyEnded() = ended

    private val callback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            if (released) return
            runCatching {
                mpv.attachSurface(holder.surface)
                mpv.setOptionString("force-window", "yes")
                mpv.setPropertyString("vo", "gpu")
            }
        }
        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            if (!released) runCatching { mpv.setPropertyString("android-surface-size", "${width}x$height") }
        }
        override fun surfaceDestroyed(holder: SurfaceHolder) {
            if (released) return
            runCatching {
                mpv.setPropertyString("vo", "null")
                mpv.setOptionString("force-window", "no")
                mpv.detachSurface()
            }
        }
    }

    override fun createView(context: Context): View = SurfaceView(context)

    override fun attach(view: View, hdr: Boolean) {
        val v = view as? SurfaceView ?: return
        surfaceView?.takeIf { it !== v }?.holder?.removeCallback(callback)
        surfaceView = v
        v.holder.addCallback(callback)
        if (v.holder.surface?.isValid == true) callback.surfaceCreated(v.holder)
    }

    override fun detach(view: View) {
        if (view !== surfaceView) return
        surfaceView?.holder?.removeCallback(callback)
        callback.surfaceDestroyed(view.holder)
        surfaceView = null
    }

    override fun release() {
        listener = null
        main.removeCallbacksAndMessages(null)
        surfaceView?.let { detach(it) }
        released = true
        runCatching { mpv.command(arrayOf("stop")) }
        // Destroying waits for mpv's threads: off the main thread.
        Thread { runCatching { mpv.destroy() } }.start()
    }

    private companion object {
        const val FORMAT_FLAG = 3
        const val FORMAT_INT64 = 4
        const val FORMAT_DOUBLE = 5
        const val EVENT_FILE_LOADED = 8
        const val EVENT_PLAYBACK_RESTART = 21
        const val EVENT_END_FILE = 7
    }
}
