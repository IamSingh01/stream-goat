package io.github.sherazi5110.cloudstream.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import io.github.sherazi5110.cloudstream.HistoryStore
import io.github.sherazi5110.cloudstream.Music
import io.github.sherazi5110.cloudstream.WebHistory
import kotlinx.coroutines.launch

/** Kept while the app runs, so the results are still there when you come back to the tab. */
private object MusicSearch {
    var query by mutableStateOf("")
    var results by mutableStateOf<List<Music.Track>>(emptyList())
    var searching by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    /** What shows when there's no search: For you, Popular or Recently played. */
    var view by mutableStateOf("foryou")
    var loading by mutableStateOf(false)
}

/** The Music tab: search YouTube songs and play them (audio only), with recently played. */
@Composable
fun MusicScreen() {
    val scope = rememberCoroutineScope()
    fun search() {
        val q = MusicSearch.query.trim()
        if (q.isEmpty()) return
        MusicSearch.searching = true; MusicSearch.error = null
        scope.launch {
            runCatching { Music.search(q) }
                .onSuccess { MusicSearch.results = it; if (it.isEmpty()) MusicSearch.error = "Nothing found for \"$q\"" }
                .onFailure { MusicSearch.error = "Search failed: ${it.message}" }
            MusicSearch.searching = false
        }
    }
    // Popular and suggestions load when the tab opens (once; suggestions follow the last song played).
    androidx.compose.runtime.LaunchedEffect(Unit) {
        MusicSearch.loading = true
        runCatching { if (Music.popular.isEmpty()) Music.loadPopular() }
        (Music.current ?: WebHistory.songs.items.firstOrNull()?.let { Music.Track(it.id, it.title, it.subtitle, it.image, 0) })
            ?.let { runCatching { Music.loadSuggestions(it) } }
        MusicSearch.loading = false
        if (Music.suggestions.isEmpty() && MusicSearch.view == "foryou") MusicSearch.view = "popular"
    }
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(bottom = 96.dp + LocalBottomInset.current)) {
        item {
            Text("Music", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 8.dp))
            OutlinedTextField(MusicSearch.query, { MusicSearch.query = it }, singleLine = true, shape = RoundedCornerShape(50),
                placeholder = { Text("Songs, artists, albums…") }, leadingIcon = { Icon(Icons.Filled.Search, null) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { search() }),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            if (MusicSearch.searching) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            MusicSearch.error?.let { Hint(it) }
        }
        val results = MusicSearch.results
        if (results.isNotEmpty()) {
            item { SectionTitle("Results", Modifier.padding(top = 12.dp)) }
            items(results, key = { "r" + it.url }) { t -> TrackRow(t.title, t.artist, t.thumbnail, Music.current?.url == t.url) { Music.play(t, results) } }
        } else {
            item {
                androidx.compose.foundation.layout.FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    listOf("foryou" to "For you", "popular" to "Popular", "recent" to "Recently played").forEach { (id, label) ->
                        androidx.compose.material3.FilterChip(MusicSearch.view == id, { MusicSearch.view = id }, { Text(label) })
                    }
                }
                if (MusicSearch.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            }
            val list = when (MusicSearch.view) {
                "foryou" -> Music.suggestions
                "popular" -> Music.popular
                else -> WebHistory.songs.items.take(80).map { e -> Music.Track(e.id, e.title, e.subtitle, e.image, 0) }
            }
            if (list.isEmpty() && !MusicSearch.loading) item {
                Hint(when (MusicSearch.view) {
                    "foryou" -> "Play a song and suggestions like it show up here."
                    "popular" -> "Couldn't load popular music right now."
                    else -> "Songs you play show up here."
                })
            }
            if (MusicSearch.view == "recent" && list.isNotEmpty()) item { SectionTitle("", Modifier, action = "Clear") { WebHistory.songs.clear() } }
            items(list, key = { MusicSearch.view + it.url }) { t -> TrackRow(t.title, t.artist, t.thumbnail, Music.current?.url == t.url) { Music.play(t, list) } }
        }
    }
}

@Composable
private fun TrackRow(title: String, artist: String, image: String?, now: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).card(RoundedCornerShape(10.dp), focusScale = 1.02f) { onClick() }
        .background(if (now) Palette.accent.copy(alpha = 0.25f) else Palette.surface, RoundedCornerShape(10.dp)).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        NetImage(image, Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(artist, style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }
    }
}

/** The bar above the tabs while a song plays (on every tab). */
@Composable
fun MiniPlayer(modifier: Modifier = Modifier) {
    val t = Music.current ?: return
    var open by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp).background(Palette.panel, RoundedCornerShape(16.dp))) {
        if (Music.length > 0) LinearProgressIndicator(progress = { (Music.position.toFloat() / Music.length).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 0.dp))
        if (open) Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            NetImage(t.thumbnail, Modifier.size(220.dp).clip(RoundedCornerShape(12.dp)))
            Text(t.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
            Text(t.artist, style = MaterialTheme.typography.bodyMedium, color = Palette.muted)
            if (Music.length > 0) {
                var drag by remember { mutableStateOf<Float?>(null) }
                androidx.compose.material3.Slider(value = drag ?: (Music.position.toFloat() / Music.length).coerceIn(0f, 1f),
                    onValueChange = { drag = it }, onValueChangeFinished = { drag?.let { Music.seekTo((it * Music.length).toLong()) }; drag = null },
                    modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth()) {
                    Text(clock(Music.position), style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.weight(1f))
                    Text(clock(Music.length), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            NetImage(t.thumbnail, Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)).card(RoundedCornerShape(6.dp)) { open = !open })
            Column(Modifier.weight(1f).padding(start = 10.dp).clickable { open = !open }) {
                Text(t.title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(Music.error ?: if (Music.loading) "Loading…" else t.artist, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
            IconAction(Icons.Filled.SkipPrevious, "Previous") { Music.previous() }
            IconAction(if (Music.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Play/pause") { Music.toggle() }
            IconAction(Icons.Filled.SkipNext, "Next") { Music.next() }
            Spacer(Modifier.width(4.dp))
            IconAction(if (open) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess, if (open) "Close player" else "Open player") { open = !open }
            IconAction(Icons.Filled.Close, "Stop") { Music.stop() }
        }
    }
}

private fun clock(ms: Long): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }
