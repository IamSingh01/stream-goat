package io.github.sherazi5110.cloudstream.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Message
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import io.github.sherazi5110.cloudstream.AdBlock
import io.github.sherazi5110.cloudstream.App
import io.github.sherazi5110.cloudstream.HistoryStore
import io.github.sherazi5110.cloudstream.Playable
import io.github.sherazi5110.cloudstream.WebHistory
import kotlinx.coroutines.launch
import org.json.JSONObject

/** A video address seen while a page loaded (the page's own player asked for it). */
/** A video request seen on a page, with the headers the page sent for it (sites check them before serving the real video). */
data class FoundVideo(val url: String, val page: String, val title: String, val headers: Map<String, String> = emptyMap())

/**
 * The Browse tab's state, kept while the app runs: the web page stays as it was when you switch tabs and come back.
 */
object Browser {
    var view: WebView? = null
    var url by mutableStateOf("")
    var title by mutableStateOf("")
    var progress by mutableIntStateOf(100)
    var canBack by mutableStateOf(false)
    var canForward by mutableStateOf(false)
    var blockedHere by mutableIntStateOf(0)
    /** A page player that went full screen (the page hands us its video view). */
    var full by mutableStateOf<android.view.View?>(null)
    var fullDone: WebChromeClient.CustomViewCallback? = null
    /** The browser's user agent, sent along when the app's player plays a video found on a page. */
    var userAgent = ""
    val videos = mutableStateListOf<FoundVideo>()

    private val media = Regex("""\.(m3u8|mpd|mp4|webm|mkv)(\?|$)""", RegexOption.IGNORE_CASE)
    private val notVideo = Regex("""(thumb|preview|poster|sprite|\.vtt|\.srt)""", RegexOption.IGNORE_CASE)

    fun isMedia(url: String) = media.containsMatchIn(url) && !notVideo.containsMatchIn(url)

    /** Text typed in the address bar: a site, or a search (DuckDuckGo). */
    fun target(text: String): String {
        val t = text.trim()
        return when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            ' ' !in t && '.' in t -> "https://$t"
            else -> "https://duckduckgo.com/?q=" + java.net.URLEncoder.encode(t, "UTF-8")
        }
    }

    fun open(text: String) { view?.loadUrl(target(text)) ?: run { url = target(text) } }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowseScreen() {
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf(Browser.url) }
    var showVideos by remember { mutableStateOf(false) }
    val home = Browser.url.isBlank()
    BackHandler(enabled = Browser.canBack) { Browser.view?.goBack() }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconAction(Icons.AutoMirrored.Filled.ArrowBack, "Back") { Browser.view?.goBack() }
            IconAction(Icons.AutoMirrored.Filled.ArrowForward, "Forward") { Browser.view?.goForward() }
            IconAction(if (home) Icons.Filled.Home else Icons.Filled.Refresh, "Reload") { Browser.view?.reload() }
            OutlinedTextField(address, { address = it }, singleLine = true, shape = RoundedCornerShape(50),
                placeholder = { Text("Site address or search") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { Browser.open(address) }),
                modifier = Modifier.weight(1f))
            if (!home) {
                val saved = WebHistory.bookmarks.contains(Browser.url)
                IconAction(if (saved) Icons.Filled.Star else Icons.Filled.StarBorder, if (saved) "Remove saved site" else "Save site") {
                    if (saved) WebHistory.bookmarks.remove(Browser.url)
                    else WebHistory.bookmarks.add(HistoryStore.Entry(Browser.url, Browser.title.ifBlank { host(Browser.url) }, host(Browser.url), siteIcon(Browser.url)))
                }
                IconAction(Icons.Filled.Close, "Close page") {
                    Browser.view?.loadUrl("about:blank"); Browser.url = ""; address = ""; Browser.videos.clear()
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp)) {
                androidx.compose.material3.Icon(Icons.Filled.Shield, "Ad blocker",
                    tint = if (AdBlock.enabled) Palette.accent else Palette.muted)
                Text(if (AdBlock.enabled) "${Browser.blockedHere}" else "off", style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(start = 4.dp))
            }
        }
        if (Browser.progress < 100) LinearProgressIndicator(progress = { Browser.progress / 100f }, modifier = Modifier.fillMaxWidth().height(2.dp))

        Box(Modifier.weight(1f).fillMaxWidth()) {
            // The web view only exists while a page is open: an empty one painted the whole screen black.
            if (!home) AndroidView(
                factory = { ctx -> Browser.view ?: newWebView(ctx) { address = it }.also { Browser.view = it; if (Browser.url.isNotBlank()) it.loadUrl(Browser.url) } },
                modifier = Modifier.fillMaxSize(),
                onRelease = { (it.parent as? android.view.ViewGroup)?.removeView(it) },
            )
            if (home) BrowseHome { Browser.open(it); address = it }
            if (Browser.videos.isNotEmpty() && !home) {
                PillButton("Play in Stream Goat (${Browser.videos.size})", Icons.Filled.PlayArrow,
                    Modifier.align(Alignment.BottomEnd).padding(16.dp + LocalBottomInset.current)) { showVideos = true }
            }
        }
    }
    Browser.full?.let { v ->
        BackHandler { Browser.fullDone?.onCustomViewHidden(); Browser.full = null }
        AndroidView({ (v.parent as? android.view.ViewGroup)?.removeView(v); v },
            Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black))
    }
    if (showVideos) SimpleDialog("Videos on this page", { showVideos = false }) {
        Hint("Found while the page played. Pick one to play it in Stream Goat's player (no ads, subtitles and speed controls).")
        Browser.videos.forEach { v ->
            Text(v.url.substringAfterLast('/').substringBefore('?').ifBlank { v.url }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).card(RoundedCornerShape(10.dp)) {
                    showVideos = false
                    scope.launch { WebMediaApi.play(v) }
                }.background(Palette.surface, RoundedCornerShape(10.dp)).padding(12.dp))
        }
    }
}

/** Site logos, saved on the device as pages show them (no outside favicon service, so no one sees what you visit). */
private fun iconFile(url: String) = java.io.File(java.io.File(io.github.sherazi5110.cloudstream.App.context.filesDir, "site_icons").apply { mkdirs() },
    host(url).replace(Regex("[^a-zA-Z0-9.-]"), "_") + ".png")
private fun saveIcon(url: String, icon: Bitmap) = runCatching {
    val f = iconFile(url)
    if (f.isFile && f.length() > 0 && icon.width < 32) return@runCatching
    f.outputStream().use { icon.compress(Bitmap.CompressFormat.PNG, 100, it) }
    // A saved site picks up its logo once the page has shown it.
    WebHistory.bookmarks.items.filter { host(it.id) == host(url) && it.image == null }.forEach {
        android.os.Handler(android.os.Looper.getMainLooper()).post { WebHistory.bookmarks.update(it.copy(image = "file://" + f.path)) }
    }
}
fun siteIcon(url: String): String? = iconFile(url).takeIf { it.isFile && it.length() > 0 }?.let { "file://" + it.path }

private fun host(url: String) = runCatching { java.net.URI(url).host.removePrefix("www.") }.getOrDefault(url)

@SuppressLint("SetJavaScriptEnabled")
private fun newWebView(ctx: android.content.Context, onUrl: (String) -> Unit): WebView = WebView(ctx).apply {
    setBackgroundColor(android.graphics.Color.rgb(14, 14, 18))
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.mediaPlaybackRequiresUserGesture = false
    // Look like Chrome: some sites serve a blank page or a decoy video to Android's "web view".
    settings.userAgentString = settings.userAgentString.replace("; wv)", ")").replace(Regex("Version/\\S+ "), "")
    Browser.userAgent = settings.userAgentString
    // Pop-ups and new windows (the usual way ad sites open themselves) are refused.
    settings.setSupportMultipleWindows(true)
    settings.javaScriptCanOpenWindowsAutomatically = false
    // Video players are often embedded from another site and need their own cookies to load.
    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
    webChromeClient = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView, p: Int) { Browser.progress = p }
        override fun onReceivedTitle(view: WebView, t: String?) { Browser.title = t.orEmpty() }
        override fun onReceivedIcon(view: WebView, icon: Bitmap?) {
            val url = view.url ?: return
            if (icon == null) return
            io.github.sherazi5110.cloudstream.App.scope.launch(kotlinx.coroutines.Dispatchers.IO) { saveIcon(url, icon) }
        }
        override fun onShowCustomView(v: android.view.View, callback: CustomViewCallback) { Browser.full = v; Browser.fullDone = callback }
        override fun onHideCustomView() { Browser.full = null; Browser.fullDone = null }
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
            if (AdBlock.enabled) AdBlock.blockedCount.incrementAndGet()
            return false
        }
    }
    webViewClient = object : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val u = request.url
            if (AdBlock.blocks(u.host)) {
                AdBlock.blockedCount.incrementAndGet()
                view.post { Browser.blockedHere++ }
                return WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
            }
            val s = u.toString()
            val sent = request.requestHeaders.filterKeys { it.equals("Referer", true) || it.equals("Origin", true) }
            if (Browser.isMedia(s)) view.post {
                if (Browser.videos.none { it.url == s }) Browser.videos += FoundVideo(s, Browser.url, Browser.title, sent)
                // A page that plays a video goes in "Continue on the web" on Home.
                if (Browser.url.isNotBlank() && WebHistory.watching.items.firstOrNull()?.id != Browser.url)
                    WebHistory.watching.add(HistoryStore.Entry(Browser.url, Browser.title.ifBlank { host(Browser.url) }, host(Browser.url), siteIcon(Browser.url)))
            }
            return null
        }
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val scheme = request.url.scheme.orEmpty()
            // intent:// and app links that ads use to jump out of the browser; blocked hosts on the main frame too.
            return scheme != "http" && scheme != "https" || AdBlock.blocks(request.url.host)
        }
        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            if (url == "about:blank") return
            if (url != Browser.url) { Browser.videos.clear(); Browser.blockedHere = 0 }
            Browser.url = url; onUrl(url)
        }
        override fun onPageFinished(view: WebView, url: String) {
            Browser.canBack = view.canGoBack(); Browser.canForward = view.canGoForward()
            if (url == "about:blank") return
            val css = AdBlock.hideCss()
            if (css.isNotEmpty()) view.evaluateJavascript(
                "(function(){var s=document.createElement('style');s.textContent=" + JSONObject.quote(css) + ";document.head&&document.head.appendChild(s);})()", null)
            WebHistory.sites.add(HistoryStore.Entry(url, view.title?.ifBlank { null } ?: host(url), host(url)))
        }
    }
}

/** Bookmarks and recent sites, shown before a page is opened. */
@Composable
private fun BrowseHome(open: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().background(Palette.background).padding(horizontal = 16.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp + LocalBottomInset.current)) {
        item {
            Text("Browse", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 8.dp))
            Hint("Type a site's address or a search above. Ads, pop-ups and trackers are blocked (open-source lists, " +
                "updated automatically). When a page plays a video, \"Play in Stream Goat\" plays it in the app's own player.")
        }
        item {
            SectionTitle("Saved sites", Modifier.padding(top = 16.dp))
            if (WebHistory.bookmarks.items.isEmpty())
                Hint("Open a site and tap the star next to the address to save it here.")
            else androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
                WebHistory.bookmarks.items.forEach { e -> SiteCard(e, open) }
            }
        }
        if (WebHistory.sites.items.isNotEmpty()) {
            item { SectionTitle("History", Modifier.padding(top = 16.dp), action = "Clear") { WebHistory.sites.clear(); WebHistory.watching.clear() } }
            items(WebHistory.sites.items.take(60), key = { "h" + it.id }) { e -> SiteRow(e, open) { WebHistory.sites.remove(e.id) } }
        }
    }
}

/** A saved site: its logo (or first letter) and title. Long-press (hold OK on a remote) to remove it. */
@Composable
private fun SiteCard(e: HistoryStore.Entry, open: (String) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Column(Modifier.width(132.dp).card(RoundedCornerShape(14.dp), onLongClick = { menu = true }) { open(e.id) }
            .background(Palette.surface, RoundedCornerShape(14.dp)).padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            val logo = e.image ?: siteIcon(e.id)
            Box(Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(Palette.panel), contentAlignment = Alignment.Center) {
                if (logo != null) NetImage(logo, Modifier.size(40.dp))
                else Text(host(e.id).take(1).uppercase(), style = MaterialTheme.typography.headlineSmall)
            }
            Text(e.title.ifBlank { host(e.id) }, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
            Text(host(e.id), style = MaterialTheme.typography.bodySmall, color = Palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        androidx.compose.material3.DropdownMenu(menu, { menu = false }) {
            androidx.compose.material3.DropdownMenuItem({ Text("Remove") }, { menu = false; WebHistory.bookmarks.remove(e.id) })
        }
    }
}

@Composable
private fun SiteRow(e: HistoryStore.Entry, open: (String) -> Unit, remove: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).card(RoundedCornerShape(10.dp)) { open(e.id) }
        .background(Palette.surface, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(e.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(e.subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }
        Spacer(Modifier.width(8.dp))
        IconAction(Icons.Filled.Close, "Remove") { remove() }
    }
}

/** Plays a video found on a web page in the app's player (with the page as referer, as the site expects). */
object WebMediaApi : MainAPI() {
    override var name = "Browser"
    override var mainUrl = "browser://"
    override val supportedTypes = setOf(TvType.Movie)

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val o = JSONObject(data)
        val url = o.getString("url")
        val type = if (url.contains(".m3u8", true)) ExtractorLinkType.M3U8 else if (url.contains(".mpd", true)) ExtractorLinkType.DASH else ExtractorLinkType.VIDEO
        callback(newExtractorLink("Browser", host(o.optString("page")).ifBlank { "Web page" }, url, type) {
            val h = o.optJSONObject("headers")
            val sent = h?.keys()?.asSequence()?.associateWith { h.getString(it) }.orEmpty()
            headers = sent.filterKeys { !it.equals("Referer", true) }
            referer = sent.entries.firstOrNull { it.key.equals("Referer", true) }?.value ?: o.optString("page")
            quality = com.lagradost.cloudstream3.utils.Qualities.Unknown.value
        })
        return true
    }

    suspend fun play(v: FoundVideo) {
        val headers = JSONObject(v.headers)
        if (Browser.userAgent.isNotBlank()) headers.put("User-Agent", Browser.userAgent)
        CookieManager.getInstance().getCookie(v.url)?.let { headers.put("Cookie", it) }
        val data = JSONObject().put("url", v.url).put("page", v.page).put("headers", headers).toString()
        val title = v.title.ifBlank { host(v.page) }
        val response: LoadResponse = newMovieLoadResponse(title, v.page, TvType.Movie, data)
        Playback.play(this, response, Playable(title, data))
    }
}

/** On Home: the web pages you were watching a video on, to go back to them in the Browse tab. */
@Composable
fun WebContinueRow(edge: androidx.compose.ui.unit.Dp) {
    val items = WebHistory.watching.items
    if (items.isEmpty() || io.github.sherazi5110.cloudstream.ui.ProfileUi.current.kids) return
    Column(Modifier.padding(top = 12.dp)) {
        SectionTitle("Continue on the web", Modifier.padding(horizontal = edge))
        TvRowScroll { androidx.compose.foundation.lazy.LazyRow(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = edge, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(items.toList(), key = { "w" + it.id }) { e ->
                var menu by remember { mutableStateOf(false) }
                Box {
                    Row(Modifier.width(260.dp).card(RoundedCornerShape(14.dp), onLongClick = { menu = true }) {
                        Nav.tab = Tab.BROWSE; Browser.open(e.id)
                    }.background(Palette.surface, RoundedCornerShape(14.dp)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        val logo = e.image ?: siteIcon(e.id)
                        Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(Palette.panel), contentAlignment = Alignment.Center) {
                            if (logo != null) NetImage(logo, Modifier.size(30.dp))
                            else Text(host(e.id).take(1).uppercase(), style = MaterialTheme.typography.titleLarge)
                        }
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(e.title, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(host(e.id), style = MaterialTheme.typography.bodySmall, color = Palette.muted, maxLines = 1)
                        }
                        androidx.compose.material3.Icon(Icons.Filled.PlayArrow, null, tint = Palette.accent)
                    }
                    androidx.compose.material3.DropdownMenu(menu, { menu = false }) {
                        androidx.compose.material3.DropdownMenuItem({ Text("Remove") }, { menu = false; WebHistory.watching.remove(e.id) })
                    }
                }
            }
        } }
    }
}
