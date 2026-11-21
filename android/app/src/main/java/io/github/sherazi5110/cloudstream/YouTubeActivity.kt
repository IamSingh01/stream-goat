package io.github.sherazi5110.cloudstream

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.webkit.JavascriptInterface
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * YouTube, the way TizenTube does it: YouTube's own TV app (youtube.com/tv) with its ads removed before they're shown.
 * Signing in is YouTube's own: it shows a code to enter at youtube.com/activate on a phone, like on a TV, and the
 * account stays signed in here (its cookies). Runs in its own full-screen window (a plain web view, nothing drawn
 * over it), with the remote's arrows and OK working as on a TV; Back goes back inside YouTube, hold Back to leave.
 * On touch screens: tap to pick, swipe to move around (swipes become the remote's arrows), and the whole screen is
 * used whatever its shape (the TV app keeps itself 16:9 otherwise).
 */
class YouTubeActivity : Activity() {
    private lateinit var web: WebView
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null

    companion object {
        const val URL = "https://www.youtube.com/tv"
        /**
         * YouTube serves its TV app to TV browsers. This is a Samsung (Tizen) TV's, as TizenTube uses: with Cobalt's
         * (YouTube's own TV browser) YouTube expects Cobalt-only features and says "not supported on this device".
         */
        const val TV_AGENT = "Mozilla/5.0 (SMART-TV; Linux; Tizen 6.0) AppleWebKit/537.36 (KHTML, like Gecko) 85.0.4183.93/6.0 TV Safari/537.36"
        /** Ad and tracking servers the TV app would otherwise contact. */
        private val blockedHosts = setOf("googleads.g.doubleclick.net", "pagead2.googlesyndication.com", "static.doubleclick.net",
            "www.googleadservices.com", "ad.doubleclick.net", "securepubads.g.doubleclick.net", "imasdk.googleapis.com")
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        web = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            isFocusable = true; isFocusableInTouchMode = true
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.userAgentString = TV_AGENT
            settings.setSupportMultipleWindows(false)
            // YouTube's "Exit YouTube?" → Exit calls window.close(), which a main window ignores: it calls this instead.
            addJavascriptInterface(object {
                @JavascriptInterface fun exit() { runOnUiThread { finish() } }
            }, "StreamGoatYouTube")
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            val script = runCatching { assets.open("youtube/adblock.js").bufferedReader().readText() }.getOrDefault("")
            if (script.isNotEmpty() && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))
                WebViewCompat.addDocumentStartJavaScript(this, script, setOf("https://www.youtube.com", "https://youtube.com", "https://m.youtube.com"))
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val host = request.url.host.orEmpty()
                    val path = request.url.path.orEmpty()
                    if (host in blockedHosts || path.startsWith("/pagead/") || path.startsWith("/api/stats/ads") || path.startsWith("/ptracking"))
                        return WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
                    return null
                }
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val scheme = request.url.scheme.orEmpty()
                    return scheme != "http" && scheme != "https"
                }
                override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                    // Without the document-start feature (old web views): as early as possible instead.
                    if (script.isNotEmpty() && !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) view.evaluateJavascript(script, null)
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                    customView = view; customCallback = callback
                    root.addView(view, FrameLayout.LayoutParams(-1, -1))
                }
                override fun onHideCustomView() {
                    customView?.let { root.removeView(it) }; customView = null; customCallback = null
                }
                // YouTube's "Exit YouTube?" → Yes closes the window.
                override fun onCloseWindow(window: WebView?) { finish() }
            }
        }
        root.addView(web, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        if (savedInstanceState != null) web.restoreState(savedInstanceState) else web.loadUrl(URL)
        web.requestFocus()
        Music.pauseForVideo()
        // Android 13+ back gesture (with predictive back, Android 16 sends no Back key for it): YouTube's own back.
        if (android.os.Build.VERSION.SDK_INT >= 33)
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { youTubeBack() }
    }

    /** Back inside YouTube: closes the full-screen video, else YouTube's own back (Escape, as on a TV). */
    private fun youTubeBack() {
        if (customView != null) { customCallback?.onCustomViewHidden(); return }
        web.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE))
        web.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ESCAPE))
    }

    // ---------- touch: taps go to YouTube, swipes become the arrow keys ----------
    private var downX = 0f; private var downY = 0f; private var lastX = 0f; private var lastY = 0f
    private var swiping = false
    private val slop by lazy { ViewConfiguration.get(this).scaledTouchSlop * 1.5f }
    /** How far a finger moves for one arrow press. */
    private val step by lazy { resources.displayMetrics.density * 64 }

    private fun arrow(code: Int) {
        web.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        web.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (customView != null || ev.pointerCount > 1) return super.dispatchTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = ev.x; downY = ev.y; swiping = false }
            MotionEvent.ACTION_MOVE -> {
                if (!swiping && (kotlin.math.abs(ev.x - downX) > slop || kotlin.math.abs(ev.y - downY) > slop)) {
                    swiping = true; lastX = downX; lastY = downY
                    // The page doesn't get this touch as a tap.
                    val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
                    super.dispatchTouchEvent(cancel); cancel.recycle()
                }
                if (swiping) {
                    // Finger up = move down the page, finger left = move right (like scrolling a list).
                    while (kotlin.math.abs(ev.y - lastY) >= step) {
                        val up = ev.y < lastY; arrow(if (up) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP); lastY += if (up) -step else step
                    }
                    while (kotlin.math.abs(ev.x - lastX) >= step) {
                        val left = ev.x < lastX; arrow(if (left) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT); lastX += if (left) -step else step
                    }
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (swiping) { swiping = false; return true }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); web.saveState(outState) }

    /** Back: YouTube's own back (Escape, as on a TV); held down: leave YouTube. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount >= 15) { finish(); return true }   // about a second
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled && event.eventTime - event.downTime < 900) youTubeBack()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onPause() { super.onPause(); CookieManager.getInstance().flush() }

    override fun onDestroy() {
        (web.parent as? android.view.ViewGroup)?.removeView(web)
        web.destroy()
        super.onDestroy()
    }
}
