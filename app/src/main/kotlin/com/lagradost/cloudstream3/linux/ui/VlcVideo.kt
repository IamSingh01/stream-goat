package com.lagradost.cloudstream3.linux.ui

import com.lagradost.cloudstream3.linux.Settings
import javafx.application.Platform
import javafx.scene.image.ImageView
import javafx.scene.image.PixelBuffer
import javafx.scene.image.PixelFormat
import javafx.scene.image.WritableImage
import javafx.scene.layout.Region
import javafx.scene.shape.Rectangle
import javafx.stage.Screen
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.embedded.videosurface.CallbackVideoSurface
import uk.co.caprica.vlcj.player.embedded.videosurface.VideoSurfaceAdapters
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** libVLC, loaded once. Null when VLC isn't installed (sudo apt install vlc). */
object Vlc {
    private var current: MediaPlayerFactory? = null
    private var loaded = false
    private var stale = false

    val factory: MediaPlayerFactory?
        @Synchronized get() {
            if (!loaded) { loaded = true; current = create() }
            return current
        }

    /** VLC has been started (or found missing): reading [factory] won't wait. */
    val isLoaded: Boolean get() = loaded

    /**
     * Starts VLC in the background soon after the app opens (issue #68): its first start can take many seconds on
     * Windows (it scans its plugins), which froze the window when Settings or a video was opened first.
     */
    fun warmUp(then: (() -> Unit)? = null) {
        Thread({ runCatching { factory }; then?.invoke() }, "vlc-start").apply { isDaemon = true }.start()
    }

    /**
     * Subtitle style and battery saver are engine options: restart the engine to apply
     * them, now if nothing is playing, otherwise when playback ends.
     */
    @Synchronized fun optionsChanged(playing: Boolean) {
        if (playing) { stale = true; return }
        if (loaded) runCatching { current?.release() }
        current = null; loaded = false; stale = false
    }

    @Synchronized fun playbackEnded() {
        if (stale) optionsChanged(playing = false)
    }

    private fun subtitleArgs(): List<String> {
        val size = when (Settings.subtitleSize) { "SMALL" -> 20; "LARGE" -> 12; "HUGE" -> 9; else -> 16 }
        val color = Settings.subtitleColor.removePrefix("#").toIntOrNull(16) ?: 0xffffff
        return buildList {
            add("--freetype-rel-fontsize=$size")
            add("--freetype-color=$color")
            add("--freetype-outline-thickness=${Settings.subtitleOutline}")
            if (Settings.subtitleBackground) { add("--freetype-background-opacity=170"); add("--freetype-background-color=0") }
        }
    }

    private fun create(): MediaPlayerFactory? =
        runCatching {
            // vlcj only looks in the system's x86 library folders; add the Flatpak's own
            // libVLC (/app/lib) and ARM folders (e.g. ARM Chromebooks).
            val dirs = listOf("/app/lib", "/usr/lib/aarch64-linux-gnu", "/usr/lib/arm-linux-gnueabihf", "/usr/lib/riscv64-linux-gnu")
                .filter { java.io.File(it, "libvlc.so.5").exists() }
            if (dirs.isNotEmpty()) {
                val current = System.getProperty("jna.library.path")
                System.setProperty("jna.library.path", (listOfNotNull(current) + dirs).joinToString(java.io.File.pathSeparator))
            }
            // Discover VLC from installed macOS application/library metadata, not assumed install prefixes.
            val discoveredVlc = if (com.lagradost.cloudstream3.linux.Os.isMac)
                com.lagradost.cloudstream3.linux.Os.discoverMacNativeLibraries(setOf("libvlc.dylib"), "org.videolan.vlc").firstOrNull() else null
            val discoveredCore = if (com.lagradost.cloudstream3.linux.Os.isMac)
                com.lagradost.cloudstream3.linux.Os.discoverMacNativeLibraries(setOf("libvlccore.dylib"), "org.videolan.vlc").firstOrNull() else null
            val bundled = com.lagradost.cloudstream3.linux.Os.bundledVlcDir()
            val nativeDirs = listOfNotNull(discoveredVlc?.parentFile, discoveredCore?.parentFile, bundled)
            if (nativeDirs.isNotEmpty()) {
                val current = System.getProperty("jna.library.path")
                System.setProperty("jna.library.path", (nativeDirs.map { it.absolutePath } + listOfNotNull(current)).joinToString(java.io.File.pathSeparator))
            }
            discoveredCore?.let { runCatching { com.sun.jna.NativeLibrary.getInstance(it.absolutePath) } }
            discoveredVlc?.let { runCatching { com.sun.jna.NativeLibrary.getInstance(it.absolutePath) } }
            bundled?.let { dir ->
                val core = java.io.File(dir, when { com.lagradost.cloudstream3.linux.Os.isWindows -> "libvlccore.dll"; com.lagradost.cloudstream3.linux.Os.isMac -> "libvlccore.dylib"; else -> "libvlccore.so.9" })
                if (core.isFile) runCatching { com.sun.jna.NativeLibrary.getInstance(core.absolutePath) }
            }
            if (!NativeDiscovery().discover()) return@runCatching null
            val args = buildList {
                if (discoveredVlc != null) {
                    val pluginDir = discoveredVlc.parentFile?.parentFile?.walkTopDown()?.maxDepth(6)
                        ?.firstOrNull { it.isDirectory && it.name == "plugins" }
                    if (pluginDir != null) add("--plugin-path=${pluginDir.absolutePath}")
                }
                add("--quiet")
                add("--no-video-title-show")       // no file name flashed over the video
                add("--no-snapshot-preview")
                add("--no-xlib")                   // frames go to callbacks; never touch X11 from VLC
                add("--avcodec-hw=any")            // VAAPI/VDPAU when available, software otherwise
                addAll(subtitleArgs())
                // Lanczos for scaling (sharper lines when anime enhance upscales; bicubic otherwise).
                if (Settings.animeEnhance != "OFF" && !Settings.batterySaver) add("--swscale-mode=9")
                if (Settings.batterySaver) {
                    add("--avcodec-skiploopfilter=4")  // skip H.264 deblocking: much less CPU
                    add("--avcodec-fast")
                }
            }
            MediaPlayerFactory(args)
        }.onFailure { System.err.println("W/vlc: libVLC not available: $it") }.getOrNull()

    val version: String? get() = factory?.application()?.version()
}

enum class Zoom(val label: String, val aspect: Double?) {
    FIT("Fit", null), FILL("Fill (crop)", null), STRETCH("Stretch", null), ORIGINAL("Original size", null),
    R16_9("16:9", 16.0 / 9), R4_3("4:3", 4.0 / 3), R21_9("21:9", 21.0 / 9),
}

/**
 * Shows libVLC's video inside the JavaFX scene.
 *
 * VLC's "vmem" output (modules/video_output/vmem.c) calls back for each frame instead of
 * opening a window. Frames are BGRA. Each callback snapshots the decoder-owned buffer
 * into reusable memory; only the JavaFX thread copies that snapshot into its PixelBuffer.
 * This prevents VLC/mpv from overwriting pixels while the GPU is uploading the previous
 * frame. VLC blends subtitles into the frame itself (src/video_output/video_output.c).
 * Frames larger than the screen are scaled down by VLC first: a 4K stream on a 1080p
 * screen moves a quarter of the pixels through the CPU and GPU.
 */
class VideoView : Region() {
    private val image = ImageView().apply { isSmooth = true; isPreserveRatio = false; isCache = false }
    @Volatile private var pixelBuffer: PixelBuffer<ByteBuffer>? = null
    @Volatile private var displayPixels: ByteBuffer? = null
    private val frameLock = Any()
    @Volatile private var latestFrame: ByteArray? = null
    @Volatile private var frameReady = false
    private val pending = AtomicBoolean(false)
    @Volatile private var closed = false
    private var bufferW = 0
    private var bufferH = 0
    /** Display size: buffer size corrected for non-square pixels (anamorphic video). */
    private var displayW = 0.0
    private var displayH = 0.0

    var zoom: Zoom = Zoom.FIT
        set(v) { field = v; requestLayout() }

    /** Anime enhance: VLC upscales small video to the screen (Lanczos) instead of JavaFX stretching it. Set before playing. */
    @Volatile var upscale = false

    /** The video's own size (0 until it starts). */
    @Volatile var sourceW = 0
        private set
    @Volatile var sourceH = 0
        private set

    /**
     * The height anime enhance sharpens this video up to (your screen's), or null when the video isn't noticeably
     * smaller than the screen (then there's nothing to sharpen).
     */
    fun enhanceTarget(): Int? {
        if (sourceW <= 0 || sourceH <= 0) return null
        val fit = screenFit(sourceW, sourceH)
        return if (fit > 1.15) (sourceH * fit).roundToInt() else null
    }

    /** HDR shown as SDR looks grey: more contrast and saturation, a little darker. */
    var hdrFix = false
        set(v) {
            field = v
            image.effect = if (v) javafx.scene.effect.ColorAdjust(0.0, 0.32, -0.04, 0.28) else null
        }

    init {
        style = "-fx-background-color: black;"
        children += image
        clip = Rectangle().also { it.widthProperty().bind(widthProperty()); it.heightProperty().bind(heightProperty()) }
    }

    val surface: CallbackVideoSurface = CallbackVideoSurface(
        object : BufferFormatCallback {
            override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat {
                sourceW = sourceWidth; sourceH = sourceHeight
                val (w, h) = fitToScreen(sourceWidth, sourceHeight)
                bufferW = w; bufferH = h
                return BufferFormat("BGRA", w, h, intArrayOf(w * 4), intArrayOf(h))
            }

            override fun newFormatSize(bufferWidth: Int, bufferHeight: Int, displayWidth: Int, displayHeight: Int) {
                // displayWidth/Height include the sample aspect ratio; scale them to our buffer.
                val sx = if (displayWidth > 0) displayWidth.toDouble() / max(1, bufferWidth) else 1.0
                val sy = if (displayHeight > 0) displayHeight.toDouble() / max(1, bufferHeight) else 1.0
                val ratio = (sx / sy).takeIf { it.isFinite() && it > 0 } ?: 1.0
                Platform.runLater {
                    displayW = bufferW * ratio
                    displayH = bufferH.toDouble()
                    requestLayout()
                }
            }

            override fun allocatedBuffers(buffers: Array<ByteBuffer>) {
                // Never expose VLC's reusable callback buffer directly to JavaFX. VLC can
                // overwrite it for the next frame while the GPU is still uploading the last one.
                val pixels = ByteBuffer.allocateDirect(bufferW * bufferH * 4)
                val pb = PixelBuffer(bufferW, bufferH, pixels, PixelFormat.getByteBgraPreInstance())
                displayPixels = pixels
                pixelBuffer = pb
                synchronized(frameLock) { latestFrame = ByteArray(bufferW * bufferH * 4); frameReady = false }
                Platform.runLater {
                    if (displayW == 0.0) { displayW = bufferW.toDouble(); displayH = bufferH.toDouble() }
                    image.image = WritableImage(pb)
                    requestLayout()
                }
            }
        },
        object : RenderCallback {
            override fun lock(mediaPlayer: MediaPlayer) = Unit
            override fun unlock(mediaPlayer: MediaPlayer) = Unit
            override fun display(mediaPlayer: MediaPlayer, nativeBuffers: Array<ByteBuffer>, bufferFormat: BufferFormat, displayWidth: Int, displayHeight: Int) {
                if (!closed) nativeBuffers.firstOrNull()?.let(::publishFrame)
            }
        },
        true,
        VideoSurfaceAdapters.getVideoSurfaceAdapter(),
    )

    /** Stop drawing before the player is released (its buffers are freed with it). */
    fun close() {
        closed = true
        pixelBuffer = null
        displayPixels = null
        synchronized(frameLock) { latestFrame = null; frameReady = false }
        image.image = null
    }

    /** How many times the video fits on the largest screen. */
    private fun screenFit(w: Int, h: Int): Double {
        val screens = runCatching { Screen.getScreens() }.getOrNull().orEmpty()
        val maxW = screens.maxOfOrNull { it.bounds.width * it.outputScaleX } ?: 1920.0
        val maxH = screens.maxOfOrNull { it.bounds.height * it.outputScaleY } ?: 1080.0
        return min(maxW / w, maxH / h)
    }

    private fun fitToScreen(w: Int, h: Int): Pair<Int, Int> {
        val fit = screenFit(w, h)
        // Anime enhance: VLC scales small video all the way to the screen (Lanczos), so nothing is stretched after.
        val scale = if (upscale && fit > 1.15) fit else min(1.0, fit)
        if (scale == 1.0) return w to h
        // Even sizes keep VLC's chroma converter happy.
        return ((w * scale).roundToInt() and 1.inv()).coerceAtLeast(2) to ((h * scale).roundToInt() and 1.inv()).coerceAtLeast(2)
    }

    // ---------- mpv draws here too (its software renderer, into a buffer of ours) ----------

    class Target(val buffer: ByteBuffer, val width: Int, val height: Int)
    @Volatile private var mpvTarget: Target? = null

    /**
     * The buffer mpv draws its next frame into (on mpv's render thread): the video's display size ([dispW]x[dispH],
     * aspect ratio applied), no larger than the screen, the width a multiple of 16 for mpv's fast path. A new one
     * when the size changes.
     */
    fun mpvBuffer(srcW: Int, srcH: Int, dispW: Int, dispH: Int): Target {
        sourceW = srcW; sourceH = srcH
        var (w, h) = fitToScreen(dispW, dispH)
        w = (w and 15.inv()).coerceAtLeast(16); h = h.coerceAtLeast(2)
        mpvTarget?.takeIf { it.width == w && it.height == h && pixelBuffer != null }?.let { return it }
        val buffer = ByteBuffer.allocateDirect(w * h * 4 + 64).alignedSlice(64).limit(w * h * 4) as ByteBuffer
        val target = Target(buffer.slice(), w, h)
        // mpv renders into target.buffer; JavaFX displays a separate buffer populated
        // from a frame snapshot on the FX thread.
        val pixels = ByteBuffer.allocateDirect(w * h * 4)
        val pb = PixelBuffer(w, h, pixels, PixelFormat.getByteBgraPreInstance())
        closed = false
        displayPixels = pixels
        synchronized(frameLock) { latestFrame = ByteArray(w * h * 4); frameReady = false }
        pixelBuffer = pb
        mpvTarget = target
        bufferW = w; bufferH = h
        Platform.runLater {
            displayW = w.toDouble(); displayH = h.toDouble()
            image.image = WritableImage(pb)
            requestLayout()
        }
        return target
    }

    /** mpv finished a frame in the buffer: show it (at most one repaint queued, as for VLC). */
    fun mpvFrame() {
        if (closed) return
        mpvTarget?.buffer?.let(::publishFrame)
    }

    /**
     * Snapshot the decoder-owned frame before it can be reused. Only the JavaFX thread
     * writes the PixelBuffer backing memory, preventing horizontal tearing/corruption
     * when the decoder produces another frame during a GPU upload.
     */
    private fun publishFrame(source: ByteBuffer) {
        if (closed) return
        val size = bufferW * bufferH * 4
        if (size <= 0 || source.capacity() < size) return
        try {
            synchronized(frameLock) {
                val snapshot = latestFrame ?: return
                source.duplicate().apply { clear(); limit(size) }.get(snapshot)
                frameReady = true
            }
        } catch (_: Throwable) {
            return
        }
        scheduleFrameUpload()
    }

    private fun scheduleFrameUpload() {
        if (!pending.compareAndSet(false, true)) return
        Platform.runLater {
            val pb = pixelBuffer
            val pixels = displayPixels
            var copied = false
            if (!closed && pb != null && pb === pixelBuffer && pixels != null) {
                synchronized(frameLock) {
                    val frame = latestFrame
                    if (frameReady && frame != null && frame.size == pixels.capacity()) {
                        pixels.duplicate().apply { clear() }.put(frame)
                        frameReady = false
                        copied = true
                    }
                }
                if (copied) pb.updateBuffer { null }
            }
            pending.set(false)
            // If a newer frame arrived during the upload, schedule exactly one more pulse.
            if (synchronized(frameLock) { frameReady }) scheduleFrameUpload()
        }
    }

    /** A new player is about to draw: forget the last one's buffer. */
    fun reset() {
        mpvTarget = null
        pixelBuffer = null
        displayPixels = null
        synchronized(frameLock) { latestFrame = null; frameReady = false }
        pending.set(false)
        closed = false
    }

    override fun layoutChildren() {
        val w = width
        val h = height
        if (displayW <= 0 || displayH <= 0 || w <= 0 || h <= 0) return
        val aspect = zoom.aspect ?: (displayW / displayH)
        val (iw, ih) = when (zoom) {
            Zoom.STRETCH -> w to h
            Zoom.ORIGINAL -> displayW to displayH
            Zoom.FILL -> max(w / aspect, h).let { hh -> hh * aspect to hh }
            else -> min(w / aspect, h).let { hh -> hh * aspect to hh }
        }
        image.fitWidth = iw
        image.fitHeight = ih
        image.relocate((w - iw) / 2, (h - ih) / 2)
    }
}
