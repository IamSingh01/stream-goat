package com.lagradost.cloudstream3.linux.ui

import com.lagradost.cloudstream3.linux.BrandIdentity

import javafx.animation.AnimationTimer
import javafx.scene.canvas.Canvas
import javafx.scene.image.Image
import javafx.scene.image.ImageView
import javafx.scene.image.PixelFormat
import javafx.scene.image.WritableImage
import javafx.scene.layout.Pane
import javafx.scene.layout.StackPane
import javafx.scene.paint.Color
import javafx.scene.shape.Rectangle
import javafx.scene.shape.StrokeLineCap
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.sin

/**
 * The RoarStream lion emblem supplied by Aman Singh.
 */
class Logo(private val size: Double) : StackPane() {
    private val view = ImageView().apply { fitWidth = size; fitHeight = size; isSmooth = true; isPreserveRatio = true }

    init {
        styleClass += "brand-logo"
        children += view
        style = "-fx-background-color: black; -fx-background-radius: ${size * 0.24};"
        setMinSize(size, size); setPrefSize(size, size); setMaxSize(size, size)
        isMouseTransparent = true
        refresh()
        Theme.onChange { refresh() }
    }

    /** Drawn at twice its size, so it stays sharp on high-density screens. */
    private fun refresh() {
        view.image = Brand.mark(ceil(size * 1.5).toInt().coerceAtLeast(16))
    }

    companion object {
        const val DEFAULT_ACCENT = "#3b82f6"

        /** The logo as an image (black tile + goat), e.g. for the window icon. */
        fun image(size: Double): Image = Brand.tile(size.toInt())
    }
}

/** The logo artwork supplied in resources/ui/icon.png. */
object Brand {
    private val markSrc: Image by lazy { Image(Brand::class.java.getResourceAsStream("/ui/icon.png")) }
    val word: Image by lazy { Image(Brand::class.java.getResourceAsStream("/ui/icon.png")) }
    private val cache = HashMap<String, Image>()

    val rainbow = listOf("#ff5f6d", "#ffc371", "#4ade80", "#38bdf8", "#a78bfa", "#f472b6").map { Color.web(it) }

    private fun accent(): Color = runCatching { Color.web(Theme.accent) }.getOrDefault(Color.web(Logo.DEFAULT_ACCENT))

    /** The supplied RoarStream lion emblem, kept in its original black-and-white colours. */
    fun mark(px: Int): Image = Image(
        Brand::class.java.getResourceAsStream(BrandIdentity.LOGO_RESOURCE),
        px.toDouble(), px.toDouble(), true, true
    )

    /** The app/window icon uses the same supplied emblem without recolouring or cropping. */
    fun tile(px: Int): Image = mark(px)

    private fun paint(src: Image, rainbowStyle: Boolean, accent: Color): Image {
        val wd = src.width.toInt(); val ht = src.height.toInt()
        val px = IntArray(wd * ht)
        src.pixelReader.getPixels(0, 0, wd, ht, PixelFormat.getIntArgbInstance(), px, 0, wd)
        for (y in 0 until ht) for (x in 0 until wd) {
            val i = y * wd + x
            val a = (px[i] ushr 24) and 0xff
            if (a == 0) continue
            val c = if (!rainbowStyle) accent else {
                val t = ((x + y).toDouble() / (wd + ht)) * (rainbow.size - 1)
                val k = t.toInt().coerceAtMost(rainbow.size - 2)
                rainbow[k].interpolate(rainbow[k + 1], t - k)
            }
            px[i] = (a shl 24) or ((c.red * 255).toInt() shl 16) or ((c.green * 255).toInt() shl 8) or (c.blue * 255).toInt()
        }
        return WritableImage(wd, ht).apply { pixelWriter.setPixels(0, 0, wd, ht, PixelFormat.getIntArgbInstance(), px, 0, wd) }
    }

    /** A second colour that goes with [accent] for the waves: the accent turned 50° round the colour wheel. */
    fun companion(accent: Color): Color = Color.hsb((accent.hue + 50) % 360, minOf(1.0, accent.saturation * 1.05), accent.brightness)
}

/** Glowing wavy lines on black in the accent colour and its companion, drifting (the startup screen). */
class Waves : Pane() {
    private val canvas = Canvas()
    private var t = 0.0
    private var last = 0L
    var fade = 0.0
    private val timer = object : AnimationTimer() {
        private var drawn = 0L
        override fun handle(now: Long) {
            if (last != 0L) t += (now - last) / 1e9 * (2 * PI / 9.0)
            last = now
            // About 40 pictures a second is smooth for slow-moving lines and halves the work on 120/144 Hz screens.
            if (now - drawn < 24_000_000L) return
            drawn = now
            draw()
        }
    }

    init {
        style = "-fx-background-color: black;"
        children += canvas
        clip = Rectangle().also { it.widthProperty().bind(widthProperty()); it.heightProperty().bind(heightProperty()) }
        canvas.widthProperty().bind(widthProperty()); canvas.heightProperty().bind(heightProperty())
    }

    fun start() = timer.start()
    fun stop() = timer.stop()

    private fun draw() {
        val g = canvas.graphicsContext2D
        val w = canvas.width; val h = canvas.height
        g.clearRect(0.0, 0.0, w, h)
        if (w <= 0 || h <= 0) return
        val accent = runCatching { Color.web(Theme.accent) }.getOrDefault(Color.web(Logo.DEFAULT_ACCENT))
        val second = Brand.companion(accent)
        // Light enough for slow computers: fewer lines and points (it looks the same at this speed).
        val lines = 16
        val step = maxOf(12.0, w / 48)
        g.lineCap = StrokeLineCap.ROUND
        for (i in 0 until lines) {
            val f = i / (lines - 1.0)
            val base = h * (-0.15 + 1.3 * f)
            val c = accent.interpolate(second, (0.5 + 0.5 * sin(t + i * 0.4)) * 0.8)
            val crest = 0.55 + 0.45 * sin(t * 2 + i * 0.9)
            val bright = c.interpolate(Color.WHITE, 0.25 * crest)
            g.beginPath()
            var x = -step
            var first = true
            while (x <= w + step) {
                val u = x / w
                val y = base - (u - 0.5) * h * 0.42 + h * 0.045 * sin(u * 7 + t + i * 0.33) +
                    h * 0.025 * sin(u * 13 - t * 2 + i * 0.71) + h * 0.012 * sin(u * 23 + t * 3 + i * 1.3)
                if (first) { g.moveTo(x, y); first = false } else g.lineTo(x, y)
                x += step
            }
            if (i % 2 == 0) { g.stroke = Color(c.red, c.green, c.blue, 0.16 * fade); g.lineWidth = 8.0; g.stroke() }
            g.stroke = Color(bright.red, bright.green, bright.blue, 0.95 * fade); g.lineWidth = 2.2; g.stroke()
        }
    }
}
