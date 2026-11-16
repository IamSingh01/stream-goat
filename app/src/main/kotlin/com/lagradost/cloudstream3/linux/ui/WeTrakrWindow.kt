package com.lagradost.cloudstream3.linux.ui

import com.lagradost.cloudstream3.linux.WeTrakr
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.ProgressIndicator
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent
import javafx.scene.layout.VBox
import javafx.scene.paint.Color
import javafx.stage.Modality
import javafx.stage.Stage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** "Connect WeTrakr": a code to enter on wetrakr.com/activate, then the app waits for the approval. */
object WeTrakrWindow {
    fun connect(owner: Stage, done: () -> Unit) {
        val stage = Stage().apply { initOwner(owner); initModality(Modality.WINDOW_MODAL); title = "Connect WeTrakr"; icons.setAll(owner.icons) }
        val status = label("Getting a code…", "muted").apply { isWrapText = true }
        val code = label("", "h1").apply { style = "-fx-font-size: 40px; -fx-text-fill: -cs-accent;" }
        val open = button("Open wetrakr.com/activate", Icons.OPEN, "btn-accent") {}.apply { isDisable = true }
        val copy = button("Copy code", null, "btn-ghost") { Clipboard.getSystemClipboard().setContent(ClipboardContent().apply { putString(code.text) }) }.apply { isDisable = true }
        val root = VBox(14.0,
            label("Connect WeTrakr", "h3"),
            label("Sign in on WeTrakr and approve Stream Goat: what you watch here then shows on WeTrakr and is marked watched " +
                "once you've seen 80% of it. Only a sign-in token is kept on this computer.", "muted").apply { isWrapText = true },
            code, hbox(10.0, open, copy),
            hbox(10.0, ProgressIndicator().apply { setPrefSize(18.0, 18.0) }, status),
            hbox(10.0, spacer(), button("Cancel", null, "btn-ghost") { stage.close() }),
        ).apply { styleClass += "glitch-window"; pad(22.0, 24.0, 18.0, 24.0); prefWidth = 480.0; alignment = Pos.TOP_LEFT }
        stage.scene = Scene(root, Color.web(Theme.background)).apply {
            stylesheets += WeTrakrWindow::class.java.getResource("/ui/theme.css")!!.toExternalForm()
        }
        var job: Job? = null
        stage.setOnHidden { job?.cancel() }
        job = ui.launch {
            try {
                val c = WeTrakr.start()
                code.text = c.userCode
                open.isDisable = false; copy.isDisable = false
                open.setOnAction { openUrl(c.url) }
                status.text = "Enter the code at ${c.url.removePrefix("https://")} and approve. Waiting…"
                val until = System.currentTimeMillis() + c.expiresIn * 1000L
                while (System.currentTimeMillis() < until) {
                    delay(c.interval * 1000L)
                    if (WeTrakr.poll(c)) { Toasts.show("WeTrakr connected"); stage.close(); done(); return@launch }
                }
                status.text = "The code expired. Close this and try again."
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { status.text = "Couldn't connect: ${e.message}" }
        }
        stage.show()
    }
}
