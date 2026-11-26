package com.lagradost.cloudstream3.linux.ui

import com.lagradost.cloudstream3.linux.CloudBackup
import com.lagradost.cloudstream3.linux.Os
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Google Drive sign-in on Windows/Linux: the code to enter at google.com/device (a button opens the page),
 * then the backup found in the account is offered for restoring, or this computer's is saved there.
 */
class CloudSignInSheet(private val onDone: () -> Unit) : StackPane(), Disposable {
    private val content = VBox(12.0)
    private var job: Job? = null

    init {
        val scrim = javafx.scene.layout.Region().apply { styleClass += "scrim"; onClick { close() } }
        val header = hbox(14.0, label("Sign in with Google", "h2"), spacer(), button(null, Icons.CLOSE, "btn-ghost") { close() }.tooltip("Close"))
        val sheet = VBox(16.0, header, content).apply {
            styleClass += "sheet"
            pad(24.0, 26.0, 24.0, 26.0)
            maxWidth = 620.0
            maxHeight = javafx.scene.layout.Region.USE_PREF_SIZE
            setOnMouseClicked { it.consume() }
        }
        children.addAll(scrim, sheet)
        StackPane.setAlignment(sheet, Pos.CENTER)
        content.children.setAll(label("Getting a code from Google…", "muted"))
        job = ui.launch {
            val s = runCatching { CloudBackup.startSignIn() }.getOrElse { show("Couldn't sign in: ${it.message}"); return@launch }
            content.children.setAll(
                label("Open this page in your browser (on this computer or your phone):", "muted"),
                hbox(10.0, label(s.url.removePrefix("https://").removePrefix("www."), "h3"),
                    button("Open the page", Icons.OPEN, "btn-accent", "btn-small") { Os.open(s.url) }),
                label("and enter this code:", "muted"),
                hbox(10.0, label(s.userCode, "h1").apply { style = "-fx-font-family: monospace; -fx-text-fill: -cs-accent;" },
                    button("Copy", Icons.COPY, "btn-ghost", "btn-small") {
                        Clipboard.getSystemClipboard().setContent(ClipboardContent().apply { putString(s.userCode) })
                        Toasts.show("Code copied")
                    }),
                label("Then choose your Google account and allow Stream Goat to keep its backup in your Drive. " +
                    "Stream Goat only sees the files it made there. This moves on by itself.", "muted").apply { isWrapText = true },
            )
            runCatching { CloudBackup.finishSignIn(s) }.onFailure { show("Couldn't sign in: ${it.message}"); return@launch }
            Toasts.show("Signed in as ${CloudBackup.state.value.email ?: "your Google account"}")
            close()
            afterSignIn(onDone)
        }
    }

    private fun show(text: String) {
        content.children.setAll(label(text, "muted").apply { isWrapText = true })
    }

    override fun dispose() { job?.cancel() }

    private fun close() {
        dispose()
        Nav.window.closeOverlay()
        onDone()
    }

    companion object {
        /** This computer joins the shared backup: what the other devices have comes here, what's here goes there. */
        fun afterSignIn(onDone: () -> Unit) {
            ui.launch {
                runCatching { CloudBackup.backupNow() }.onSuccess { Toasts.show("Synced with your other devices") }
                    .onFailure { Toasts.show("Couldn't sync: ${it.message}") }
                onDone()
            }
        }

        private fun label(r: CloudBackup.Remote): String =
            (if (r.shared) "All your devices" + (r.device?.let { " (last from $it)" } ?: "") else (r.device ?: "A device") + (if (r.mine) " (this computer, old)" else " (old)")) + " · saved " +
                (if (r.time > 0) java.time.Instant.ofEpochMilli(r.time).atZone(java.time.ZoneId.systemDefault())
                    .format(java.time.format.DateTimeFormatter.ofPattern("d MMM, HH:mm")) else r.modified) +
                (r.app?.let { " · Stream Goat $it" } ?: "")

        /** Every device's backup: pick the one to restore here. */
        fun offerRestore(list: List<CloudBackup.Remote>, onDone: () -> Unit) {
            val labels = list.map(::label)
            val pick = javafx.scene.control.ChoiceDialog(labels.first(), labels).apply {
                headerText = "Restore a backup"
                contentText = "Every signed-in device shares one backup and stays in sync with it. Restore:"
            }.showAndWait().orElse(null) ?: return
            val remote = list[labels.indexOf(pick)]
            val alert = javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.CONFIRMATION,
                "${label(remote)}.\n\nRestore it? This replaces this computer's " + (if (remote.shared) "" else "settings, ") + "profiles, " +
                    "My list, history, repositories and extensions with the backup's.",
                javafx.scene.control.ButtonType.OK, javafx.scene.control.ButtonType.CANCEL).apply { headerText = "Restore this backup?" }
            if (alert.showAndWait().orElse(null) != javafx.scene.control.ButtonType.OK) return
            Toasts.show("Restoring…")
            ui.launch {
                runCatching { CloudBackup.restore(remote) }
                    .onSuccess { got -> Toasts.show("Restored${if (got.isNotEmpty()) " with ${got.size} extension(s)" else ""}. Restart the app to finish.", seconds = 10.0) }
                    .onFailure { Toasts.show("Couldn't restore: ${it.message}") }
                onDone()
            }
        }
    }
}

/** Settings → Storage → Google Drive backup rows. */
fun cloudBackupRows(row: (String, String?, Node) -> Node, refresh: () -> Unit): List<Node> {
    if (!CloudBackup.configured) return listOf(row("Google Drive backup", "Google sign-in isn't set up in this build of the app.", javafx.scene.layout.Region()))
    val s = CloudBackup.state.value
    if (!s.signedIn) return listOf(row("Google Drive backup",
        "Sign in with the same Google account on every device (phone, TV, computer) and they stay in sync: My list, history, where you " +
            "stopped, watched marks, extensions, Live TV and playlists. One backup in a \"Stream Goat backups\" folder in your Google Drive: " +
            "Stream Goat only sees the files it made, never your other files.",
        button("Sign in with Google", Icons.CLOUD_UPLOAD, "btn-accent", "btn-small") { Nav.window.showOverlay(CloudSignInSheet(refresh)) }))
    val last = if (s.lastBackup <= 0) "not yet" else java.time.Instant.ofEpochMilli(s.lastBackup).atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("d MMM, HH:mm"))
    return listOf(
        row("Google Drive: ${s.email ?: "signed in"}", "Last sync: $last" + (s.error?.let { ". Problem: $it" } ?: ""),
            hbox(8.0,
                button("Sync now", Icons.CLOUD_UPLOAD, "btn-ghost", "btn-small") {
                    ui.launch {
                        runCatching { CloudBackup.backupNow() }.onSuccess { Toasts.show("Synced with Google Drive") }
                            .onFailure { Toasts.show("Couldn't sync: ${it.message}") }
                        refresh()
                    }
                },
                button("Restore", Icons.CLOUD_DOWNLOAD, "btn-ghost", "btn-small") {
                    ui.launch {
                        runCatching { CloudBackup.backups() }.onSuccess { list ->
                            if (list.isEmpty()) Toasts.show("There's no backup in this Google account yet") else CloudSignInSheet.offerRestore(list, refresh)
                        }.onFailure { Toasts.show("Couldn't reach Google Drive: ${it.message}") }
                    }
                },
                button("Sign out", null, "btn-ghost", "btn-small") {
                    ui.launch { CloudBackup.signOut(); Toasts.show("Signed out. The backup stays in your Google Drive."); refresh() }
                },
            )),
        row("Keep in sync automatically", "Every device signed in to this account shares one backup: changes from each are merged, when " +
            "the app starts or comes back, every 5 minutes and when you leave it. Each device keeps its own settings.", Switch(CloudBackup.auto) { CloudBackup.auto = it }),
    )
}
