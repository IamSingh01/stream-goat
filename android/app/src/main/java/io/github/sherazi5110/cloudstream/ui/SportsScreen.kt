package io.github.sherazi5110.cloudstream.ui

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stadium
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.sherazi5110.cloudstream.Sports
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What the Sports tab shows; kept while the app runs, so coming back to the tab is instant. */
private object SportsState {
    var matches by mutableStateOf<List<Sports.Match>>(emptyList())
    var loadedAt = 0L
    var loading by mutableStateOf(false)
    var failed by mutableStateOf(false)
    var filter by mutableStateOf("All")
    val news = mutableStateListOf<Sports.News>()
    var newsFor: String? = null
    var newsAt = 0L
    var newsLoading by mutableStateOf(false)
    /** The match or story open in a pop-up. */
    var openMatch by mutableStateOf<Sports.Match?>(null)
    var reading by mutableStateOf<Pair<String, String>?>(null)   // title to address

    suspend fun refresh() {
        loading = true
        val got = runCatching { Sports.matches() }
        got.onSuccess { if (it.isNotEmpty() || matches.isEmpty()) matches = it; loadedAt = System.currentTimeMillis(); failed = false }
            .onFailure { failed = matches.isEmpty() }
        loading = false
    }

    suspend fun loadNews(force: Boolean = false) {
        val f = filter
        if (!force && newsFor == f && System.currentTimeMillis() - newsAt < 15 * 60_000L && news.isNotEmpty()) return
        newsLoading = true
        val got = runCatching { Sports.news(f.takeIf { it != "All" }) }.getOrDefault(emptyList())
        if (filter == f) { news.clear(); news.addAll(got); newsFor = f; newsAt = System.currentTimeMillis() }
        newsLoading = false
    }
}

private val liveRed = Color(0xFFE53935)

/** Sports: live scores for every sport (banner, live now, coming up, results) and sports news. */
@Composable
fun SportsScreen() {
    val s = SportsState
    val scope = rememberCoroutineScope()
    // Scores refresh every 30 seconds while the tab is open.
    LaunchedEffect(Unit) {
        while (true) {
            if (System.currentTimeMillis() - s.loadedAt > 25_000L) s.refresh()
            delay(30_000L)
        }
    }
    LaunchedEffect(s.filter) { s.loadNews() }

    val all = s.matches.filter { s.filter == "All" || it.group == s.filter }
    val live = all.filter { it.live }.sortedBy { it.start }
    val now = System.currentTimeMillis()
    val upcoming = all.filter { it.upcoming && it.start > now - 3 * 3600_000L }.sortedBy { it.start }
    val results = all.filter { it.finished }.sortedByDescending { it.start }
    val edge = if (isWide) 28.dp else 14.dp
    val cardWidth = if (isWide) 300.dp else 262.dp

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp + LocalBottomInset.current)) {
        item(key = "top") {
            Column(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 14.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = edge), verticalAlignment = Alignment.CenterVertically) {
                    Text("Sports", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    if (s.loading) Text("Updating…", style = MaterialTheme.typography.labelMedium, color = Palette.muted)
                    else PillButton("Refresh", Icons.Filled.Refresh, primary = false, compact = true) { scope.launch { s.refresh(); s.loadNews(force = true) } }
                }
                LazyRow(contentPadding = PaddingValues(horizontal = edge, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf("All") + Sports.groups) { g ->
                        val on = g == s.filter
                        val count = if (g == "All") s.matches.count { it.live } else s.matches.count { it.live && it.group == g }
                        Text(if (count > 0) "$g · $count live" else g,
                            style = MaterialTheme.typography.labelLarge, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                            color = if (on) Color.Black else Color.White,
                            modifier = Modifier.card(RoundedCornerShape(50), focusScale = 1.05f) { s.filter = g }
                                .background(if (on) Color.White else Palette.card, RoundedCornerShape(50))
                                .border(1.dp, if (on) Color.Transparent else Palette.outline, RoundedCornerShape(50))
                                .padding(horizontal = 14.dp, vertical = 8.dp))
                    }
                }
            }
        }
        item(key = "banner") {
            val featured = live.ifEmpty { upcoming.take(5) }.take(8)
            when {
                featured.isNotEmpty() -> Banner(featured, Modifier.padding(horizontal = edge))
                s.loading && s.matches.isEmpty() -> Spinner(Modifier.fillMaxWidth().height(220.dp))
                s.failed -> EmptyState("Couldn't load scores", "Check the internet connection, then try again.", button = "Try again") { scope.launch { s.refresh() } }
                else -> EmptyState("Nothing on right now", "No ${if (s.filter == "All") "" else s.filter + " "}matches today.")
            }
        }
        if (live.isNotEmpty()) item(key = "live") { MatchRow("Live now", live, edge, cardWidth) }
        if (upcoming.isNotEmpty()) item(key = "up") { MatchRow("Coming up", upcoming, edge, cardWidth) }
        if (results.isNotEmpty()) item(key = "res") { MatchRow("Results", results, edge, cardWidth) }
        item(key = "newsTitle") {
            SectionTitle(if (s.filter == "All") "Sports news" else "${s.filter} news", Modifier.padding(start = edge, end = edge, top = 22.dp, bottom = 8.dp))
        }
        if (s.newsLoading && s.news.isEmpty()) item(key = "newsSpin") { Spinner(Modifier.fillMaxWidth().height(120.dp)) }
        items(s.news, key = { "n|" + it.link }) { n -> NewsRow(n, Modifier.padding(horizontal = edge, vertical = 4.dp)) }
        if (!s.newsLoading && s.news.isEmpty()) item(key = "newsNone") {
            Text("No news right now.", color = Palette.muted, modifier = Modifier.padding(horizontal = edge))
        }
    }

    s.openMatch?.let { m -> MatchDialog(m) { s.openMatch = null } }
    s.reading?.let { (title, url) -> ReaderDialog(title, url) { s.reading = null } }
}

// ---------------------------------------------------------------- banner

@Composable
private fun Banner(list: List<Sports.Match>, modifier: Modifier) {
    val pager = rememberPagerState { list.size }
    // Slides by itself every 7 seconds.
    LaunchedEffect(list.size) {
        while (list.size > 1) { delay(7000); runCatching { pager.animateScrollToPage((pager.currentPage + 1) % list.size) } }
    }
    Column(modifier) {
        HorizontalPager(pager, pageSpacing = 12.dp, modifier = Modifier.fillMaxWidth()) { i ->
            val m = list.getOrNull(i) ?: return@HorizontalPager
            BannerCard(m)
        }
        if (list.size > 1) Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.Center) {
            repeat(list.size) { i ->
                Box(Modifier.padding(3.dp).size(if (i == pager.currentPage) 18.dp else 6.dp, 6.dp).clip(CircleShape)
                    .background(if (i == pager.currentPage) Color.White else Color.White.copy(alpha = 0.35f)))
            }
        }
    }
}

private fun sportColors(group: String): List<Color> = when (group) {
    "Cricket" -> listOf(Color(0xFF0F3D2E), Color(0xFF1C5A43))
    "Football" -> listOf(Color(0xFF1B2A57), Color(0xFF2C3E7A))
    "Basketball" -> listOf(Color(0xFF4A2412), Color(0xFF7A3B17))
    "American football" -> listOf(Color(0xFF2D1B47), Color(0xFF4A2C70))
    "Tennis" -> listOf(Color(0xFF2F4A12), Color(0xFF4B6E1B))
    "Ice hockey" -> listOf(Color(0xFF123A4A), Color(0xFF1D5A70))
    "MMA" -> listOf(Color(0xFF4A1212), Color(0xFF6E1B1B))
    "F1" -> listOf(Color(0xFF3A0D0D), Color(0xFF5A1515))
    else -> listOf(Color(0xFF1E293B), Color(0xFF334155))
}

@Composable
private fun BannerCard(m: Sports.Match) {
    val wide = isWide
    Column(
        Modifier.fillMaxWidth().heightIn(min = if (wide) 250.dp else 210.dp)
            .card(RoundedCornerShape(18.dp), focusScale = 1.02f) { SportsState.openMatch = m }
            .background(Brush.linearGradient(sportColors(m.group)), RoundedCornerShape(18.dp))
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(18.dp))
            .padding(horizontal = if (wide) 28.dp else 16.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("${m.league} · ${m.title}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        if (m.sides.size == 2) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BigSide(m.sides[0], Modifier.weight(1f))
            CenterScore(m, Modifier.weight(if (wide) 1.4f else 1.2f))
            BigSide(m.sides[1], Modifier.weight(1f))
        } else Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            StatusLine(m)
            m.sides.take(3).forEachIndexed { i, side ->
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", color = Palette.muted, modifier = Modifier.width(22.dp))
                    side.logo?.let { NetImage(it, Modifier.size(22.dp), contentScale = ContentScale.Fit, placeholder = false) }
                    Text(side.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp, end = 12.dp))
                    side.score?.let { Text(it, color = Palette.muted) }
                }
            }
        }
        Spacer(Modifier.weight(1f, fill = false))
        m.location?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.65f), modifier = Modifier.padding(top = 10.dp), maxLines = 1) }
    }
}

@Composable
private fun BigSide(side: Sports.Side, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(if (isWide) 84.dp else 56.dp), contentAlignment = Alignment.Center) {
            if (side.logo != null) NetImage(side.logo, Modifier.fillMaxSize(), contentScale = ContentScale.Fit, placeholder = false)
            else Text(side.short.take(3), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Text(side.name, style = if (isWide) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun StatusLine(m: Sports.Match) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (m.live) Text("LIVE", fontWeight = FontWeight.Black, fontSize = 13.sp, color = Color.White,
            modifier = Modifier.background(liveRed, RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 2.dp))
        Text(if (m.upcoming) startText(m.start) else m.status, fontWeight = FontWeight.SemiBold, maxLines = 2, textAlign = TextAlign.Center,
            modifier = Modifier.padding(start = if (m.live) 8.dp else 0.dp))
    }
}

@Composable
private fun CenterScore(m: Sports.Match, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        StatusLine(m)
        if (!m.upcoming) {
            val a = m.sides[0].score.orEmpty(); val b = m.sides[1].score.orEmpty()
            // Cricket and tennis scores are long ("265 & 75/1 (22 ov)", "6-3 3-6 1-1"): one line each.
            if (a.length > 4 || b.length > 4) {
                Text(a, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
                Text(b, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 2.dp))
            } else Text("$a  –  $b", fontSize = if (isWide) 40.sp else 30.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

// ---------------------------------------------------------------- rows

@Composable
private fun MatchRow(title: String, list: List<Sports.Match>, edge: androidx.compose.ui.unit.Dp, width: androidx.compose.ui.unit.Dp) {
    Column(Modifier.padding(top = 18.dp)) {
        SectionTitle("$title (${list.size})", Modifier.padding(horizontal = edge))
        LazyRow(contentPadding = PaddingValues(horizontal = edge, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(list.take(60), key = { it.id }) { m -> MatchCard(m, Modifier.width(width)) }
        }
    }
}

@Composable
private fun MatchCard(m: Sports.Match, modifier: Modifier) {
    Column(
        modifier.card(RoundedCornerShape(14.dp)) { SportsState.openMatch = m }
            .background(Palette.card, RoundedCornerShape(14.dp)).border(1.dp, Palette.outline, RoundedCornerShape(14.dp))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (m.live) Box(Modifier.size(8.dp).background(liveRed, CircleShape))
            Text(if (m.upcoming) startText(m.start) else m.status, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                color = if (m.live) liveRed else Palette.accent, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = if (m.live) 6.dp else 0.dp))
        }
        Text("${m.league} · ${m.title}", style = MaterialTheme.typography.bodySmall, color = Palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
        val done = m.finished && m.sides.any { it.winner }
        m.sides.take(if (m.sides.size > 2) 3 else 2).forEach { side ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                    if (side.logo != null) NetImage(side.logo, Modifier.fillMaxSize(), contentScale = ContentScale.Fit, placeholder = false)
                    else Text(side.short.take(3), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Palette.muted)
                }
                val dim = done && !side.winner
                Text(side.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (dim) Palette.muted else Color.White, modifier = Modifier.weight(1f).padding(horizontal = 10.dp))
                if (!m.upcoming) side.score?.let {
                    Text(it, fontWeight = FontWeight.Bold, color = if (dim) Palette.muted else Color.White, maxLines = 1,
                        modifier = Modifier.widthIn(max = 130.dp), overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun NewsRow(n: Sports.News, modifier: Modifier) {
    Row(
        modifier.fillMaxWidth().card(RoundedCornerShape(12.dp), focusScale = 1.02f) { SportsState.reading = n.title to n.link }
            .background(Palette.card, RoundedCornerShape(12.dp)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).background(Palette.surfaceHigh, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            Text(n.source?.take(1)?.uppercase() ?: "N", fontWeight = FontWeight.Black, color = Palette.accent)
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(n.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(n.source, agoText(n.time)).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Palette.muted,
                modifier = Modifier.padding(top = 4.dp))
        }
    }
}

// ---------------------------------------------------------------- pop-ups

@Composable
private fun MatchDialog(m: Sports.Match, onClose: () -> Unit) {
    SimpleDialog(m.league, onClose, confirm = if (m.link != null) "Match center" else null, onConfirm = {
        onClose(); m.link?.let { SportsState.reading = "${m.league}: ${m.title}" to it }
    }) {
        Text(m.title, style = MaterialTheme.typography.bodyMedium, color = Palette.muted)
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth()) { BannerCard(m) }
        Spacer(Modifier.height(12.dp))
        m.sides.filter { it.sets.isNotEmpty() }.forEach { side ->
            Text("${side.name}: ${side.sets.joinToString("  ")}", style = MaterialTheme.typography.bodyMedium)
        }
        Text(if (m.upcoming) "Starts " + startText(m.start) else "Started " + startText(m.start), style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp))
        m.notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.muted, modifier = Modifier.padding(top = 4.dp)) }
    }
}

/** A news story (or a match center) in a pop-up, with a button to open it in the browser. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ReaderDialog(title: String, url: String, onClose: () -> Unit) {
    val context = LocalContext.current
    var web by remember { mutableStateOf<WebView?>(null) }
    var loading by remember { mutableStateOf(true) }
    Dialog(onDismissRequest = { if (web?.canGoBack() == true) web?.goBack() else onClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().padding(if (isWide) 28.dp else 8.dp).clip(RoundedCornerShape(18.dp)).background(Palette.surface)) {
            Row(Modifier.fillMaxWidth().background(Palette.surfaceHigh).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Stadium, null, tint = Palette.accent)
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 10.dp))
                PillButton("Browser", Icons.Filled.OpenInBrowser, primary = false, compact = true) {
                    com.lagradost.cloudstream3.CloudStreamApp.openBrowser(web?.url ?: url, context.findActivity())
                }
                Spacer(Modifier.width(8.dp))
                PillButton("Close", null, compact = true) { onClose() }
            }
            Box(Modifier.fillMaxSize()) {
                AndroidView(factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(-1, -1)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        setBackgroundColor(android.graphics.Color.WHITE)
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, u: String?) { loading = false }
                            // Links to apps (intent:, market:) aren't web pages: stay on the story.
                            override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest) =
                                request.url.scheme !in setOf("http", "https")
                        }
                        loadUrl(url)
                        web = this
                    }
                }, onRelease = { it.stopLoading(); it.destroy() }, modifier = Modifier.fillMaxSize())
                if (loading) Spinner(Modifier.align(Alignment.Center))
            }
        }
    }
}

@Composable
private fun Icon(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String?, tint: Color) =
    androidx.compose.material3.Icon(icon, desc, tint = tint)

private fun startText(t: Long): String {
    if (t <= 0) return ""
    val zone = java.time.ZoneId.systemDefault()
    val d = java.time.Instant.ofEpochMilli(t).atZone(zone)
    val today = java.time.LocalDate.now(zone)
    val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(t))
    return when (d.toLocalDate()) {
        today -> time
        today.plusDays(1) -> "Tomorrow $time"
        today.minusDays(1) -> "Yesterday $time"
        else -> d.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM")) + " $time"
    }
}

private fun agoText(t: Long): String? {
    if (t <= 0) return null
    val min = (System.currentTimeMillis() - t) / 60_000
    return when {
        min < 1 -> "just now"
        min < 60 -> "$min min ago"
        min < 24 * 60 -> "${min / 60} h ago"
        else -> "${min / (24 * 60)} d ago"
    }
}
