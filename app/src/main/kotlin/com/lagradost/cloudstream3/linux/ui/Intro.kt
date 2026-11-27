package com.lagradost.cloudstream3.linux.ui

import com.lagradost.cloudstream3.linux.Settings
import javafx.animation.Animation
import javafx.animation.FadeTransition
import javafx.animation.Interpolator
import javafx.animation.KeyFrame
import javafx.animation.KeyValue
import javafx.animation.ParallelTransition
import javafx.animation.PauseTransition
import javafx.animation.ScaleTransition
import javafx.animation.SequentialTransition
import javafx.animation.Timeline
import javafx.animation.TranslateTransition
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Group
import javafx.scene.Node
import javafx.scene.effect.DropShadow
import javafx.scene.effect.GaussianBlur
import javafx.scene.image.Image
import javafx.scene.image.ImageView
import javafx.scene.layout.Pane
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.scene.paint.Color
import javafx.scene.paint.CycleMethod
import javafx.scene.paint.RadialGradient
import javafx.scene.paint.Stop
import javafx.scene.shape.Circle
import javafx.scene.shape.SVGPath
import javafx.scene.transform.Rotate
import javafx.util.Duration
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * A picture-by-picture animation of the goat artwork: each frame shows for [frameMs], blending into the next
 * over [fadeMs]. Loops until stopped (or plays once).
 */
class FlipBook(private val frames: List<Image>, size: Double, private val frameMs: Double, private val fadeMs: Double = 120.0,
               private val loop: Boolean = true) : StackPane() {
    private val back = ImageView().apply { fitWidth = size; fitHeight = size; isSmooth = true }
    private val front = ImageView().apply { fitWidth = size; fitHeight = size; isSmooth = true }
    private var index = 0
    private val ticker = Timeline(KeyFrame(Duration.millis(frameMs), { next() })).apply { cycleCount = Animation.INDEFINITE }

    init {
        children.addAll(back, front)
        setMinSize(size, size); setMaxSize(size, size)
        isMouseTransparent = true
        front.image = frames.firstOrNull()
    }

    /** Plays only while it's on screen (a looping animation left running would keep the CPU busy). */
    fun playWhileShown(): FlipBook = apply {
        sceneProperty().addListener { _, _, s -> if (s == null) stop() else play() }
        if (scene != null) play()
    }

    fun play() = ticker.play()
    fun stop() = ticker.stop()

    /** Shows frame [i] at once (for still pictures). */
    fun show(i: Int) { index = i.mod(frames.size); front.image = frames[index]; front.opacity = 1.0 }

    private fun next() {
        if (frames.size < 2) return
        if (!loop && index == frames.lastIndex) { ticker.stop(); return }
        index = (index + 1) % frames.size
        back.image = front.image
        front.image = frames[index]
        front.opacity = 0.0
        FadeTransition(Duration.millis(fadeMs), front).apply { toValue = 1.0 }.play()
    }
}

/**
 * The startup screen: a short intro (about 3 seconds) while extensions load. Syed Ali Hamza's goat chews its grass
 * (the eating animation, in the logo's colours) while an arc of stars sweeps over it, then the name and credit
 * fade in. A click or a key skips it; Settings → Profiles → Startup animation turns it off (then it's a still splash).
 */
/**
 * The startup screen (about 2.5 seconds): glowing wavy lines in the theme's colours drift across black, the logo
 * rises in on its black tile, then the name. A click or any key skips it; Settings → Appearance turns it off.
 */
class SplashScreen(animate: Boolean = Settings.startupAnimation) : StackPane() {
    private val statusLabel = label("Starting…", "splash-status")
    private var onDone: (() -> Unit)? = null
    private var animation: javafx.animation.Animation? = null
    private val waves = Waves()

    /** True once the intro has played (or was skipped / is off). */
    var finished = !animate
        private set

    init {
        styleClass += "splash"
        style = "-fx-background-color: black;"
        isFocusTraversable = true
        val logo = Logo(210.0).apply { opacity = 0.0 }
        // The supplied artwork is the emblem, not a wordmark: show it once.
        val credit = label("Created by ${AppInfo.CREDIT}", "splash-credit")
        val words = vbox(10.0, credit).apply { alignment = Pos.CENTER; opacity = 0.0 }
        val centre = vbox(28.0, logo, words).apply { alignment = Pos.CENTER; isMouseTransparent = true }
        val bottom = vbox(8.0, statusLabel).apply { alignment = Pos.BOTTOM_CENTER; isMouseTransparent = true; padding = javafx.geometry.Insets(0.0, 0.0, 28.0, 0.0) }
        children.addAll(waves, centre, bottom)
        if (animate) {
            waves.start()
            val anim = object : javafx.animation.Transition() {
                init { cycleDuration = Duration.millis(2600.0) }
                override fun interpolate(f: Double) {
                    val ms = f * 2600
                    waves.fade = (ms / 900).coerceIn(0.0, 1.0)
                    val l = ((ms - 250) / 800).coerceIn(0.0, 1.0); val e = 1 - (1 - l) * (1 - l)
                    logo.opacity = e; logo.scaleX = 0.82 + 0.18 * e; logo.scaleY = logo.scaleX; logo.translateY = 30 * (1 - e)
                    val wv = ((ms - 900) / 700).coerceIn(0.0, 1.0); words.opacity = wv; words.translateY = 14 * (1 - wv)
                }
            }
            anim.setOnFinished { finish() }
            animation = anim
            anim.play()
            setOnMouseClicked { skip() }
            setOnKeyPressed { skip() }
        } else { waves.fade = 1.0; logo.opacity = 1.0; words.opacity = 1.0 }
    }

    private fun skip() {
        animation?.stop()
        finish()
    }

    private fun finish() {
        if (finished) return
        finished = true
        waves.stop()
        onDone?.invoke()
    }

    /** Runs [block] once the intro has finished (now, if it already has). */
    fun whenFinished(block: () -> Unit) {
        if (finished) block() else onDone = block
    }

    fun status(text: String) {
        statusLabel.text = text
    }
}
