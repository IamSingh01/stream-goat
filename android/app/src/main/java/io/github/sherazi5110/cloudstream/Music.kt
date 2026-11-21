package io.github.sherazi5110.cloudstream

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.sherazi5110.cloudstream.player.Vlc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer

/**
 * Music from YouTube (via NewPipe, which the app already uses for YouTube): search songs, play the audio only with
 * the built-in VLC (no video, no ads, little data), a queue made of the search results, and a history.
 */
object Music {
    data class Track(val url: String, val title: String, val artist: String, val thumbnail: String?, val seconds: Long)

    var current by mutableStateOf<Track?>(null)
        private set
    var playing by mutableStateOf(false)
        private set
    var loading by mutableStateOf(false)
        private set
    var position by mutableLongStateOf(0L)
        private set
    var length by mutableLongStateOf(0L)
        private set
    var error by mutableStateOf<String?>(null)
    private var queue: List<Track> = emptyList()
    /** YouTube's trending music. */
    var popular by mutableStateOf<List<Track>>(emptyList())
        private set
    /** Songs like the one playing (or the last one played): YouTube's related videos. */
    var suggestions by mutableStateOf<List<Track>>(emptyList())
        private set
    private var suggestionsFor: String? = null

    private fun StreamInfoItem.track() = Track(url, name, uploaderName.orEmpty().removeSuffix(" - Topic"),
        thumbnails.maxByOrNull { it.width }?.url, duration)

    /** Trending music on YouTube (its "Music" chart; the general trending list when there isn't one). */
    suspend fun loadPopular(): List<Track> = withContext(Dispatchers.IO) {
        val yt = ServiceList.YouTube
        val kiosks = yt.kioskList
        val id = kiosks.availableKiosks.firstOrNull { it.contains("music", true) } ?: kiosks.defaultKioskId
        val url = kiosks.getListLinkHandlerFactoryByType(id).fromId(id).url
        org.schabi.newpipe.extractor.kiosk.KioskInfo.getInfo(yt, url).relatedItems.filterIsInstance<StreamInfoItem>()
            .map { it.track() }.also { popular = it }
    }

    /** Suggestions from [track] (the last played song when the tab opens). */
    suspend fun loadSuggestions(track: Track) = withContext(Dispatchers.IO) {
        if (suggestionsFor == track.url && suggestions.isNotEmpty()) return@withContext
        val info = StreamInfo.getInfo(ServiceList.YouTube, track.url)
        setSuggestions(track.url, info)
    }

    private fun setSuggestions(from: String, info: StreamInfo) {
        val related = info.relatedItems.filterIsInstance<StreamInfoItem>().filter { it.url != from && it.duration in 60..900 }
        if (related.isNotEmpty()) { suggestions = related.map { it.track() }; suggestionsFor = from }
    }

    private val player: MediaPlayer by lazy {
        MediaPlayer(Vlc.lib).apply {
            setEventListener { e ->
                when (e.type) {
                    MediaPlayer.Event.Playing -> { playing = true; loading = false }
                    MediaPlayer.Event.Paused, MediaPlayer.Event.Stopped -> playing = false
                    MediaPlayer.Event.TimeChanged -> Music.position = e.timeChanged
                    MediaPlayer.Event.LengthChanged -> Music.length = e.lengthChanged
                    MediaPlayer.Event.EndReached -> App.scope.launch(Dispatchers.Main) { next() }
                    MediaPlayer.Event.EncounteredError -> { loading = false; playing = false; error = "Couldn't play this song" }
                }
            }
        }
    }

    /** YouTube Music songs for [query] (falls back to all of YouTube when Music has nothing). */
    suspend fun search(query: String): List<Track> = withContext(Dispatchers.IO) {
        val yt = ServiceList.YouTube
        fun run(filter: String): List<Track> {
            val handler = yt.searchQHFactory.fromQuery(query, listOf(filter), "")
            return SearchInfo.getInfo(yt, handler).relatedItems.filterIsInstance<StreamInfoItem>().map { s ->
                Track(s.url, s.name, s.uploaderName.orEmpty().removeSuffix(" - Topic"), s.thumbnails.maxByOrNull { it.width }?.url, s.duration)
            }
        }
        runCatching { run(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS) }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: run(YoutubeSearchQueryHandlerFactory.VIDEOS)
    }

    /** Plays [track]; the rest of [list] after it plays next. */
    fun play(track: Track, list: List<Track> = listOf(track)) {
        queue = list
        current = track; loading = true; error = null; position = 0; length = track.seconds * 1000
        WebHistory.songs.add(HistoryStore.Entry(track.url, track.title, track.artist, track.thumbnail))
        App.scope.launch {
            val audio = runCatching {
                withContext(Dispatchers.IO) {
                    StreamInfo.getInfo(ServiceList.YouTube, track.url).also { runCatching { setSuggestions(track.url, it) } }
                        .audioStreams.maxByOrNull { it.averageBitrate }?.content
                }
            }.getOrNull()
            if (current != track) return@launch
            if (audio == null) { loading = false; error = "Couldn't play \"${track.title}\""; return@launch }
            withContext(Dispatchers.Main) {
                runCatching {
                    player.stop()
                    val m = Media(Vlc.lib, Uri.parse(audio)).apply { addOption(":no-video") }
                    player.media = m
                    m.release()      // the player keeps its own reference
                    player.play()
                }.onFailure { loading = false; error = "Couldn't play \"${track.title}\"" }
            }
        }
    }

    fun toggle() { if (player.isPlaying) player.pause() else if (current != null) player.play() }
    fun seekTo(ms: Long) { player.time = ms }
    /** The next song in the list; at the end, carries on with a suggestion (like YouTube Music's autoplay). */
    fun next() {
        val i = queue.indexOf(current)
        queue.getOrNull(i + 1)?.let { play(it, queue); return }
        val more = suggestions.filter { s -> queue.none { it.url == s.url } }
        more.firstOrNull()?.let { play(it, queue + more) } ?: run { playing = false }
    }
    fun previous() { val i = queue.indexOf(current); if (position > 3000 || i <= 0) seekTo(0) else play(queue[i - 1], queue) }
    fun stop() { player.stop(); current = null; playing = false }
    /** Videos take over the sound: music pauses when a video starts. */
    fun pauseForVideo() { if (playing) player.pause() }
}
