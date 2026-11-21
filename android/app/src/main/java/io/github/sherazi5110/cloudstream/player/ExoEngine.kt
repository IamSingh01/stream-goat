package io.github.sherazi5110.cloudstream.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app

/**
 * ExoPlayer (Media3), the player of the official CloudStream app: extensions are written and tested against it.
 * It fetches the source itself through the app's network client (the extension's headers and cookies, DNS over
 * HTTPS), and reads HLS, DASH and inline (data:) playlists natively, so sources like NetMirror's show their real
 * length, start at once and seek where you ask.
 */
@OptIn(UnstableApi::class)
class ExoEngine(context: Context) : PlaybackEngine {
    override val kind = EngineKind.EXO
    override var listener: PlaybackEngine.Listener? = null
    private val main = Handler(Looper.getMainLooper())
    private val appContext = context.applicationContext
    override var opening = false
        private set
    private var playerView: PlayerView? = null
    override val view: View? get() = playerView
    private var ended = false
    private var startedOnce = false
    private var request: OpenRequest? = null
    private val extraSubs = mutableListOf<Uri>()
    private var loudness: android.media.audiofx.LoudnessEnhancer? = null
    private var loudnessSession = C.AUDIO_SESSION_ID_UNSET
    private var volumePercent = 100

    private val player: ExoPlayer = run {
        val s = io.github.sherazi5110.cloudstream.Settings
        val memMb = s.bufferMemoryMb.takeIf { it > 0 } ?: if (io.github.sherazi5110.cloudstream.Images.lowMemoryDevice) 48 else 160
        val ahead = (s.bufferAheadSec * 1000).coerceAtLeast(15_000)
        val startMs = s.startBufferMs.coerceIn(500, 20_000)
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(minOf(15_000, ahead), ahead, startMs, maxOf(startMs, 2_000))
            .setTargetBufferBytes(memMb * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
        val renderers = DefaultRenderersFactory(appContext).setEnableDecoderFallback(true)
        ExoPlayer.Builder(appContext, renderers).setLoadControl(load).build().apply {
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    when (state) {
                        Player.STATE_BUFFERING -> listener?.onBuffering(true)
                        Player.STATE_READY -> {
                            opening = false
                            listener?.onBuffering(false)
                            duration.takeIf { it != C.TIME_UNSET && it > 0 }?.let { listener?.onLength(it) }
                        }
                        Player.STATE_ENDED -> { ended = true; listener?.onEnded() }
                    }
                }
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying) { startedOnce = true; listener?.onPlaying(); tick() }
                    else if (playbackState == Player.STATE_READY) listener?.onPaused()
                }
                override fun onPlayerError(error: PlaybackException) {
                    opening = false
                    android.util.Log.w("player", "ExoPlayer: ${error.errorCodeName}: ${error.message}", error)
                    listener?.onError("${error.errorCodeName}: ${error.message}")
                }
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (videoSize.width > 0 && videoSize.height > 0) listener?.onVideoSize(videoSize.width, videoSize.height)
                }
                override fun onAudioSessionIdChanged(audioSessionId: Int) { applyVolume() }
            })
        }
    }

    /** ExoPlayer has no time events: the position is read twice a second while playing. */
    private val ticker = object : Runnable {
        override fun run() {
            listener?.onTime(player.currentPosition)
            if (player.isPlaying) main.postDelayed(this, 500)
        }
    }
    private fun tick() { main.removeCallbacks(ticker); main.post(ticker) }

    private fun dataSourceFactory(headers: Map<String, String>): DefaultDataSource.Factory {
        val ua = headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value ?: USER_AGENT
        val http = OkHttpDataSource.Factory(app.baseClient)
            .setUserAgent(ua)
            .setDefaultRequestProperties(headers.filterKeys { !it.equals("User-Agent", true) })
        // DefaultDataSource adds data:, file: and content: addresses on top of the network.
        return DefaultDataSource.Factory(appContext, http)
    }

    private fun subtitleMime(uri: Uri): String {
        val path = (uri.lastPathSegment ?: uri.toString()).lowercase().substringBefore('?')
        return when {
            path.endsWith(".vtt") || path.endsWith(".webvtt") -> MimeTypes.TEXT_VTT
            path.endsWith(".ass") || path.endsWith(".ssa") -> MimeTypes.TEXT_SSA
            path.endsWith(".ttml") || path.endsWith(".xml") || path.endsWith(".dfxp") -> MimeTypes.APPLICATION_TTML
            else -> MimeTypes.APPLICATION_SUBRIP
        }
    }

    private fun mediaItem(r: OpenRequest): MediaItem {
        // ExoPlayer fetches the source itself (the original address with its headers), unless it's a local file.
        val uri = if (r.url.startsWith("file:")) r.url else r.original
        val subs = (r.subtitles + extraSubs).distinct().mapIndexed { i, u ->
            MediaItem.SubtitleConfiguration.Builder(u).setMimeType(subtitleMime(u)).setId("sub$i")
                .setSelectionFlags(if (u == extraSubs.lastOrNull()) C.SELECTION_FLAG_DEFAULT else 0).build()
        }
        return MediaItem.Builder().setUri(uri).apply {
            when {
                r.hls || uri.startsWith("data:", true) && "mpegurl" in uri.substringBefore(',').lowercase() -> setMimeType(MimeTypes.APPLICATION_M3U8)
                r.dash -> setMimeType(MimeTypes.APPLICATION_MPD)
            }
            if (subs.isNotEmpty()) setSubtitleConfigurations(subs)
        }.build()
    }

    override fun open(r: OpenRequest) {
        // Subtitle files added to the previous source don't belong to this one.
        if (r.original != request?.original) extraSubs.clear()
        openSource(r)
    }

    private fun openSource(r: OpenRequest) {
        request = r
        ended = false
        opening = true
        val source = DefaultMediaSourceFactory(dataSourceFactory(r.headers)).createMediaSource(mediaItem(r))
        player.setMediaSource(source, r.startMs.coerceAtLeast(0))
        player.prepare()
        player.playWhenReady = true
        player.setPlaybackSpeed(r.rate)
        volumePercent = r.volume
        applyVolume()
        setZoom(r.zoom)
    }

    override fun play() { player.play() }
    override fun pause() { player.pause() }
    override fun seekTo(ms: Long) { player.seekTo(ms); listener?.onTime(ms) }
    override fun setRate(rate: Float) { player.setPlaybackSpeed(rate) }

    override fun setVolume(percent: Int) { volumePercent = percent; applyVolume() }

    /** Up to 100 % with the player's volume; above that, Android's loudness enhancer on its audio session. */
    private fun applyVolume() {
        player.volume = (volumePercent / 100f).coerceAtMost(1f)
        val boostMb = ((volumePercent - 100).coerceAtLeast(0) * 12)   // 200 % ≈ +12 dB
        runCatching {
            if (boostMb == 0) { loudness?.enabled = false; return }
            val session = player.audioSessionId.takeIf { it != C.AUDIO_SESSION_ID_UNSET } ?: return
            // A new audio session (another output, a new source) needs its own enhancer.
            if (loudness != null && loudnessSession != session) { loudness?.release(); loudness = null }
            val l = loudness ?: android.media.audiofx.LoudnessEnhancer(session).also { loudness = it; loudnessSession = session }
            l.setTargetGain(boostMb)
            l.enabled = true
        }
    }

    override fun setZoom(z: Zoom) {
        playerView?.resizeMode = when (z) {
            Zoom.FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            Zoom.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
    }

    /** ExoPlayer can't shift audio or subtitles in time: sync works with VLC and mpv. */
    override fun setDelays(audioMs: Long, subtitleMs: Long) = Unit

    private fun tracksOf(type: Int): List<Pair<Tracks.Group, Int>> = player.currentTracks.groups
        .filter { it.type == type }.flatMap { g -> (0 until g.length).filter { g.isTrackSupported(it) }.map { g to it } }

    private fun idOf(group: Tracks.Group, index: Int) = player.currentTracks.groups.indexOf(group) * 1000 + index

    private fun nameOf(g: Tracks.Group, i: Int, n: Int): String {
        val f = g.getTrackFormat(i)
        val lang = f.language?.takeIf { it != "und" }?.let { java.util.Locale.forLanguageTag(it).displayLanguage }
        return listOfNotNull(f.label, lang.takeIf { l -> l != null && f.label?.contains(l) != true },
            f.channelCount.takeIf { it > 2 }?.let { if (it == 6) "5.1" else if (it == 8) "7.1" else "$it ch" })
            .joinToString(" · ").ifBlank { "Track $n" }
    }

    override fun audioTracks() = tracksOf(C.TRACK_TYPE_AUDIO).mapIndexed { n, (g, i) -> TrackInfo(idOf(g, i), nameOf(g, i, n + 1)) }
    override fun audioTrack() = tracksOf(C.TRACK_TYPE_AUDIO).firstOrNull { (g, i) -> g.isTrackSelected(i) }?.let { (g, i) -> idOf(g, i) } ?: -1
    override fun setAudio(id: Int) = select(C.TRACK_TYPE_AUDIO, id)

    override fun subtitleTracks() = listOf(TrackInfo(-1, "Off")) +
        tracksOf(C.TRACK_TYPE_TEXT).mapIndexed { n, (g, i) -> TrackInfo(idOf(g, i), nameOf(g, i, n + 1)) }
    override fun subtitleTrack() = if (player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)) -1
        else tracksOf(C.TRACK_TYPE_TEXT).firstOrNull { (g, i) -> g.isTrackSelected(i) }?.let { (g, i) -> idOf(g, i) } ?: -1
    override fun setSubtitle(id: Int) = select(C.TRACK_TYPE_TEXT, id)

    private fun select(type: Int, id: Int) {
        val params = player.trackSelectionParameters.buildUpon()
        if (id < 0) { player.trackSelectionParameters = params.setTrackTypeDisabled(type, true).build(); return }
        val group = player.currentTracks.groups.getOrNull(id / 1000) ?: return
        player.trackSelectionParameters = params.setTrackTypeDisabled(type, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, id % 1000)).build()
    }

    /** ExoPlayer takes subtitle files with the source: it reopens at the same place with the file added. */
    override fun addSubtitle(uri: Uri) {
        val r = request ?: return
        if (uri in extraSubs) return
        extraSubs += uri
        val pos = player.currentPosition
        val playing = player.playWhenReady
        openSource(r.copy(startMs = pos))
        player.playWhenReady = playing
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build()
    }

    override fun reallyEnded() = ended

    override fun createView(context: Context): View = PlayerView(context).apply {
        useController = false
        setShutterBackgroundColor(android.graphics.Color.BLACK)
        setKeepContentOnPlayerReset(true)
        subtitleView?.setApplyEmbeddedStyles(true)
        subtitleView?.setFractionalTextSize(0.0533f * (io.github.sherazi5110.cloudstream.Settings.textScale.coerceIn(0.8f, 1.5f)))
    }

    override fun attach(view: View, hdr: Boolean) {
        val v = view as? PlayerView ?: return
        playerView?.takeIf { it !== v }?.player = null
        playerView = v
        v.player = player
        request?.zoom?.let { setZoom(it) }
    }

    override fun detach(view: View) { if (view === playerView) { playerView?.player = null; playerView = null } }

    override fun release() {
        listener = null
        main.removeCallbacksAndMessages(null)
        playerView?.player = null
        playerView = null
        runCatching { loudness?.release() }
        player.release()
    }
}
