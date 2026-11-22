package io.github.sherazi5110.cloudstream

import android.content.Intent
import android.os.Bundle
import android.graphics.Color
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.lagradost.cloudstream3.CommonActivity
import io.github.sherazi5110.cloudstream.ui.AppRoot
import io.github.sherazi5110.cloudstream.ui.CloudStreamTheme
import io.github.sherazi5110.cloudstream.ui.Nav
import io.github.sherazi5110.cloudstream.ui.PendingRepo
import io.github.sherazi5110.cloudstream.ui.Tab

/**
 * The app's screen. It is the official app's MainActivity type (an AppCompatActivity), since extensions' settings
 * screens expect one (supportFragmentManager) and some check `activity is MainActivity`.
 */
class MainActivity : com.lagradost.cloudstream3.MainActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Light icons on dark bars, whatever the system theme.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        CommonActivity.activity = this
        handle(intent)
        // The intro plays once per launch (not after rotation or returning to the app). It must never hold the
        // app up: besides its own clocks, this timer ends it and redraws the window, whatever the animation does.
        intro.value = savedInstanceState == null && Settings.startupAnimation
        if (intro.value) android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ endIntro() }, INTRO_LIMIT_MS)
        setContent {
            io.github.sherazi5110.cloudstream.ui.TvScale {
                CloudStreamTheme {
                    AppRoot()
                    // Over every screen, the player included.
                    io.github.sherazi5110.cloudstream.ui.GlitchDialog()
                    io.github.sherazi5110.cloudstream.ui.GitHubReportQr()
                    if (intro.value) io.github.sherazi5110.cloudstream.ui.IntroScreen { endIntro() }
                }
            }
        }
    }

    private val intro = androidx.compose.runtime.mutableStateOf(false)
    private val shake by lazy { ShakeToReport(this) }

    /**
     * Holding OK on a title opens its menu (Mark as watched, Hide, Remove…). The remote keeps sending OK while it's
     * held: those repeats went to the menu's first entry as soon as it opened, and letting go picked it (holding OK
     * on History marked a title watched). The repeats are dropped, so the menu waits for you to choose.
     */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val ok = event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
            event.keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER || event.keyCode == android.view.KeyEvent.KEYCODE_BUTTON_A
        if (ok && event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount > 0) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onResume() {
        super.onResume()
        shake.start()
    }

    override fun onPause() {
        shake.stop()
        super.onPause()
    }

    private fun endIntro() {
        if (!intro.value) return
        intro.value = false
        window?.decorView?.let { it.invalidate(); it.requestLayout() }
    }

    override fun onStop() {
        // Leaving the app during the intro: come back to the app itself.
        endIntro()
        // Google Drive backup: save what changed before the app is put away.
        CloudBackup.soon()
        super.onStop()
    }

    private companion object {
        /** The intro takes about 3.7 s; after this it's gone no matter what. */
        const val INTRO_LIMIT_MS = 6000L
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onDestroy() {
        if (CommonActivity.activity === this) CommonActivity.activity = null
        super.onDestroy()
    }

    /** cloudstreamrepo://… and https://cs.repo/… links open the Add repository dialog. */
    private fun handle(intent: Intent?) {
        val data = intent?.data?.toString() ?: return
        if (intent.action != Intent.ACTION_VIEW) return
        PendingRepo.url = data
        Nav.select(Tab.EXTENSIONS)
    }
}
