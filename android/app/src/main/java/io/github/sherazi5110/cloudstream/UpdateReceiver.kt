package io.github.sherazi5110.cloudstream

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller

/** Hears back from Android's installer: shows its confirmation screen, or says why the update didn't install. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = (if (android.os.Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else intent.getParcelableExtra(Intent.EXTRA_INTENT)) ?: return
                // Android's "Install update?" screen. If it can't open (the app went to the background), say so
                // instead of nothing happening (the update then looked installed, and every check offered it again).
                runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onFailure { App.toast("Android didn't show the install screen. Keep Stream Goat open and try the update again.") }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit   // the app is replaced and restarts
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                val why = if (status == PackageInstaller.STATUS_FAILURE_CONFLICT || "signature" in msg.lowercase() || "conflict" in msg.lowercase())
                    "This update is signed differently from the installed copy, so Android won't install it over it. Make a backup, uninstall, then install the new version (needed once)."
                    else if (status == PackageInstaller.STATUS_FAILURE_ABORTED) "Update cancelled."
                    // Google Play Protect stopped it (see the website: it flags apps that install their own updates).
                    else if (status == PackageInstaller.STATUS_FAILURE_BLOCKED) "Google Play Protect blocked the update. Turn off \"Scan apps with " +
                        "Play Protect\" (Play Store → profile → Play Protect → ⚙), install the update, then decide whether to turn it back on."
                    else if (status == PackageInstaller.STATUS_FAILURE_STORAGE) "Not enough space for the update: free some storage and try again."
                    else "The update didn't install: $msg"
                App.toast(why)
                android.util.Log.w("StreamGoat", "update not installed: status $status $msg")
            }
        }
    }

    companion object { const val ACTION = "io.github.sherazi5110.cloudstream.UPDATE_STATUS" }
}
