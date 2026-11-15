package com.lagradost.cloudstream3.linux.ui

import com.lagradost.cloudstream3.linux.Glitch
import com.lagradost.cloudstream3.linux.Os
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.CheckBox
import javafx.scene.control.TextArea
import javafx.scene.image.Image
import javafx.scene.image.ImageView
import javafx.scene.image.WritableImage
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent
import javafx.scene.layout.VBox
import javafx.scene.paint.Color
import javafx.stage.Modality
import javafx.stage.Stage
import javafx.stage.StageStyle

/**
 * "Report a glitch" (F12, or ⌘⇧R on a Mac; also the profile menu and Settings → About) and the report shown after
 * the app didn't close properly. Its own small window, so it also opens over the full-screen player.
 */
object GlitchWindow {
    private var open: Stage? = null

    /** Takes a picture of the window as it is, then asks what went wrong. */
    fun report(owner: Stage) {
        if (open != null) return
        val shot = runCatching { owner.scene.snapshot(null) }.getOrNull()
        show(owner, shot, crash = null)
    }

    private fun toBuffered(img: WritableImage): java.awt.image.BufferedImage {
        val w = img.width.toInt(); val h = img.height.toInt()
        val px = IntArray(w * h)
        img.pixelReader.getPixels(0, 0, w, h, javafx.scene.image.PixelFormat.getIntArgbInstance(), px, 0, w)
        return java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB).apply { setRGB(0, 0, w, h, px, 0, w) }
    }

    /** Settings → About: no picture (it would only show Settings); the last steps say where it happened. */
    fun reportWithoutPicture(owner: Stage) { if (open == null) show(owner, null, crash = null) }

    /** A problem the app found itself (a working stream that wouldn't play): [details] lead the report. */
    fun reportProblem(owner: Stage, details: String) { if (open == null) show(owner, null, crash = null, extra = details) }

    /** The last run didn't close properly: shows what's known about it. */
    fun showCrash(owner: Stage, report: String) = show(owner, null, report)

    private fun show(owner: Stage, shot: WritableImage?, crash: String?, extra: String? = null) {
        val stage = Stage(StageStyle.DECORATED).apply {
            initOwner(owner)
            initModality(Modality.WINDOW_MODAL)
            title = if (crash != null) "Stream Goat closed unexpectedly" else "Report a glitch"
            icons.setAll(owner.icons)
        }
        open = stage
        stage.setOnHidden { open = null; if (crash != null) Glitch.dismissPending() }

        val what = TextArea().apply {
            promptText = if (crash != null) "What were you doing when it happened? (optional)" else "What went wrong? e.g. \"The play button is hidden behind the title\""
            isWrapText = true; prefRowCount = 3
            styleClass += "glitch-text"
        }
        val keep = CheckBox(if (Os.isMac) "Save the screenshot to Downloads and copy it (paste it on GitHub with ⌘V)"
            else "Save the screenshot to Downloads and copy it (paste it on GitHub with Ctrl+V)").apply { isSelected = shot != null; isWrapText = true }
        fun report(saved: String?) = crash ?: ((extra?.let { it.trim() + "\n\n" } ?: "") + Glitch.reportText(saved))
        val details = TextArea(report(null)).apply {
            isEditable = false; isWrapText = false; prefRowCount = 9
            styleClass += "glitch-details"
            isVisible = false; isManaged = false
        }
        val showDetails = button("Show what the app adds", null, "btn-ghost", "btn-small") {}
        showDetails.setOnAction {
            details.isVisible = !details.isVisible; details.isManaged = details.isVisible
            showDetails.text = if (details.isVisible) "Hide details" else "Show what the app adds"
            stage.sizeToScene()
        }

        fun send() {
            var saved: String? = null
            if (shot != null && keep.isSelected) runCatching {
                val file = Glitch.screenshotFile()
                javax.imageio.ImageIO.write(toBuffered(shot), "png", file)
                Clipboard.getSystemClipboard().setContent(ClipboardContent().apply { putImage(shot); putFiles(listOf(file)) })
                saved = "Downloads/Stream Goat/${file.name}"
            }.onFailure { Toasts.show("Couldn't save the screenshot: ${it.message}") }
            openUrl(Glitch.issueUrl(what.text.trim(), report(saved), saved != null, crash = crash != null))
            stage.close()
        }

        val children = mutableListOf<javafx.scene.Node>(
            label(if (crash != null) "Stream Goat closed unexpectedly last time" else "Report a glitch", "h3"),
            label(if (crash != null) "Here's what the app knows about it. Send it on GitHub (it's filled in: you press Submit there), so it can be fixed. Nothing is sent by the app itself."
                else "Say what looks wrong. GitHub opens in your browser with the details below filled in; paste the screenshot there and press Submit. Nothing is sent by the app itself.", "muted").apply { isWrapText = true },
            what,
        )
        if (shot != null) {
            val view = ImageView(shot as Image).apply { isPreserveRatio = true; fitWidth = 520.0; fitHeight = 260.0; isSmooth = true }
            children += VBox(view).apply { alignment = Pos.CENTER; styleClass += "glitch-shot" }
            children += keep
        }
        children += showDetails
        children += details
        children += hbox(10.0,
            button("Copy details", null, "btn-ghost") {
                Clipboard.getSystemClipboard().setContent(ClipboardContent().apply {
                    putString((what.text.trim().takeIf { it.isNotBlank() }?.let { "$it\n\n" } ?: "") + report(null))
                })
                Toasts.show("Copied")
            },
            spacer(),
            button(if (crash != null) "Close" else "Cancel", null, "btn-ghost") { stage.close() },
            button("Send on GitHub", Icons.BUG, "btn-primary") { send() },
        )
        val root = VBox(12.0, *children.toTypedArray()).apply {
            styleClass += "glitch-window"
            pad(22.0, 24.0, 18.0, 24.0)
            prefWidth = 580.0
        }
        stage.scene = Scene(root, Color.web(Theme.background)).apply {
            stylesheets += GlitchWindow::class.java.getResource("/ui/theme.css")!!.toExternalForm()
            setOnKeyPressed { if (it.code == javafx.scene.input.KeyCode.ESCAPE) stage.close() }
        }
        stage.show()
        what.requestFocus()
    }
}
