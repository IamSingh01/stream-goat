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
 * Static startup screen. The animated wave intro has been removed completely.
 * It remains visible while installed extensions are loaded.
 */
class SplashScreen : StackPane() {
    private val statusLabel = label("Starting…", "splash-status")

    // There is no intro animation to wait for; startup continues as soon as extensions load.
    val finished: Boolean = true

    init {
        styleClass += "splash"
        style = "-fx-background-color: black;"
        val logo = Logo(280.0)
        val credit = label("Created by ${AppInfo.CREDIT}", "splash-credit")
        val centre = vbox(28.0, logo, credit).apply {
            alignment = Pos.CENTER
            isMouseTransparent = true
        }
        val bottom = vbox(8.0, statusLabel).apply {
            alignment = Pos.BOTTOM_CENTER
            isMouseTransparent = true
            padding = javafx.geometry.Insets(0.0, 0.0, 28.0, 0.0)
        }
        children.addAll(centre, bottom)
    }

    fun whenFinished(block: () -> Unit) = block()

    fun status(text: String) {
        statusLabel.text = text
    }
}
