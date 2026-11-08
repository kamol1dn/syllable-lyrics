package dev.kamol1dn.syllable

import android.app.Application
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.kamol1dn.syllable.lyrics.Lyrics
import dev.kamol1dn.syllable.lyrics.LyricsRepository
import dev.kamol1dn.syllable.lyrics.SyncType
import dev.kamol1dn.syllable.lyrics.TrackQuery
import dev.kamol1dn.syllable.media.AudioOutput
import dev.kamol1dn.syllable.media.NowPlaying
import dev.kamol1dn.syllable.recognition.RecognitionEvent
import dev.kamol1dn.syllable.recognition.RecognizedTrack
import dev.kamol1dn.syllable.recognition.Recognizer
import dev.kamol1dn.syllable.spotify.SpotifyAuth
import dev.kamol1dn.syllable.spotify.SpotifyLyricsProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class Mode { NOW_PLAYING, RECOGNIZED }

/** Timing applied to the lyrics. [output] is the device it is saved for, or null for a mic song. */
data class Timing(val offsetMs: Long, val output: AudioOutput?, val isDefault: Boolean)

data class TrackInfo(
    val key: String,
    val title: String,
    val artist: String,
    val album: String?,
    val artwork: Bitmap?,
    val coverUrl: String?,
    val durationMs: Long?,
    val spotifyId: String?,
)

sealed interface LyricsState {
    data object Idle : LyricsState
    data object Loading : LyricsState
    data class Loaded(val lyrics: Lyrics, val final: Boolean) : LyricsState
    data object NotFound : LyricsState
    /** Every source failed with a network error. Retried by itself when the connection comes back. */
    data object Offline : LyricsState
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app as SyllableApp
    val settings: Settings = graph.settings
    val media = graph.media
    private val repo: LyricsRepository = graph.lyrics

    private val _mode = MutableStateFlow(Mode.NOW_PLAYING)
    val mode: StateFlow<Mode> = _mode.asStateFlow()

    private val _recognized = MutableStateFlow<RecognizedTrack?>(null)
    val recognized: StateFlow<RecognizedTrack?> = _recognized.asStateFlow()

    private val _recognition = MutableStateFlow<RecognitionEvent?>(null)
    val recognition: StateFlow<RecognitionEvent?> = _recognition.asStateFlow()
    private var recognitionJob: Job? = null

    /** Timing nudge (ms) for a song found with the mic, reset when the song changes. */
    private val _nudge = MutableStateFlow(0L)

    /** Recognised-song clock pause state. */
    private val _clockPaused = MutableStateFlow(false)
    val clockPaused: StateFlow<Boolean> = _clockPaused.asStateFlow()
    private var pausedAtPosition = 0L

    /** The song a reload was asked for, plus a count so each press counts as new. */
    private val retry = MutableStateFlow<Pair<String?, Int>>(null to 0)
    private var lastLoad: Pair<String, Int>? = null
    private var lastLoaded: Pair<String, LyricsState.Loaded>? = null

    /** The phone's playing song when a song found with the mic was put on screen. A different one takes over. */
    private var playingKeyAtSwitch: String? = null
    private fun playingKey() = media.nowPlaying.value?.takeIf { it.isPlaying }?.trackKey

    /** The player's song. Held for a moment after it disappears, so a gap between songs doesn't flash the empty screen. */
    val nowPlaying: StateFlow<NowPlaying?> = media.nowPlaying
        .transformLatest { if (it == null) delay(1500); emit(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, media.nowPlaying.value)

    /** Where the music is playing: the phone's own audio route, or another device when casting. */
    val output: StateFlow<AudioOutput> = combine(
        graph.audio.current,
        media.nowPlaying.map { np -> np?.takeIf { it.isRemote }?.packageName }.distinctUntilChanged(),
    ) { local, remotePackage ->
        if (remotePackage != null) AudioOutput.remote(remotePackage, media.appName(remotePackage)) else local
    }.stateIn(viewModelScope, SharingStarted.Eagerly, graph.audio.current.value)

    /**
     * The timing in use right now. For music playing on this phone it is the offset saved for the
     * output device. A song found with the mic is heard directly, so it only uses its own nudge.
     */
    val timing: StateFlow<Timing> = combine(_mode, output, settings.state, _nudge) { mode, out, s, nudge ->
        if (mode == Mode.RECOGNIZED) Timing(nudge, null, isDefault = nudge == 0L)
        else Timing(s.offsetFor(out), out, isDefault = out.key !in s.deviceTimings)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, Timing(0, null, true))

    val track: StateFlow<TrackInfo?> = combine(_mode, nowPlaying, _recognized) { mode, np, rec ->
        when {
            mode == Mode.RECOGNIZED && rec != null -> TrackInfo(
                "rec|${rec.artist}|${rec.title}", rec.title, rec.artist, rec.album, null, rec.coverUrl, null, rec.spotifyTrackId,
            )
            np != null -> TrackInfo(
                np.trackKey, np.title, np.artist, np.album, np.artwork, null,
                np.durationMs.takeIf { it > 0 }, np.spotifyTrackId,
            )
            else -> null
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val lyrics: StateFlow<LyricsState> = combine(
        track.map { t -> t?.let { Triple(it.key, it, Unit) } }.distinctUntilChanged { a, b -> a?.first == b?.first },
        retry,
    ) { t, r -> t?.second to r }
        .flatMapLatest { (t, r) ->
            // lastLoad is kept through a brief null, so the same song coming back keeps its nudge and cache.
            if (t == null) return@flatMapLatest flowOf(LyricsState.Idle)
            val prev = lastLoad
            lastLoad = t.key to r.second
            val sameSong = prev?.first == t.key
            val isRetry = sameSong && prev?.second != r.second && r.first == t.key
            if (!sameSong) _nudge.value = 0
            // A reload keeps the current words on screen until the fresh result arrives.
            val kept = lastLoaded?.takeIf { isRetry && it.first == t.key }?.second
            repo.load(
                TrackQuery(t.title, t.artist, t.album, t.durationMs, t.spotifyId),
                skipCache = isRetry,
            ).map { p ->
                when (p) {
                    is LyricsRepository.Progress.Found -> LyricsState.Loaded(p.lyrics, p.final).also { lastLoaded = t.key to it }
                    LyricsRepository.Progress.NotFound -> kept?.copy(final = true) ?: LyricsState.NotFound
                    LyricsRepository.Progress.Offline -> kept?.copy(final = true) ?: LyricsState.Offline
                }
            }.onStart { emit(kept?.copy(final = false) ?: LyricsState.Loading) }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, LyricsState.Idle)

    private val connectivity = app.getSystemService(ConnectivityManager::class.java)
    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            // VALIDATED rather than onAvailable, which fires before DNS works.
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) && lyrics.value == LyricsState.Offline) retryLyrics()
        }
    }

    private val _cacheStats = MutableStateFlow<Pair<Int, Long>?>(null)
    /** Saved songs and their size on disk, once [refreshCacheStats] has run. */
    val cacheStats: StateFlow<Pair<Int, Long>?> = _cacheStats.asStateFlow()

    init {
        runCatching { connectivity?.registerDefaultNetworkCallback(netCallback) }
        // A new song on the phone takes over from a song found with the mic.
        viewModelScope.launch {
            media.nowPlaying.map { np -> np?.takeIf { it.isPlaying }?.trackKey }.distinctUntilChanged().collect { key ->
                if (key != null && key != playingKeyAtSwitch && _mode.value == Mode.RECOGNIZED) _mode.value = Mode.NOW_PLAYING
            }
        }
        // Search again when sources or the Spotify sign-in change and the current result could get better.
        viewModelScope.launch {
            combine(
                settings.state.map { listOf(it.useSpicy, it.spicyKey, it.useSpotify, it.useMusixmatch, it.useNetEase) }.distinctUntilChanged(),
                SpotifyAuth.state.map { it.signedIn }.distinctUntilChanged(),
            ) { a, b -> a to b }
                .drop(1).debounce(600) // the key field changes on every keystroke
                .collect {
                    val cur = lyrics.value
                    if (cur is LyricsState.NotFound || cur is LyricsState.Offline ||
                        (cur is LyricsState.Loaded && cur.lyrics.sync != SyncType.WORD)
                    ) retryLyrics()
                }
        }
        media.refresh()
        // A route change (headphones picked in the output switcher, say) usually comes with a
        // pause or a new track, so check the output again whenever playback changes.
        viewModelScope.launch {
            media.nowPlaying.map { it?.isPlaying to it?.trackKey }.distinctUntilChanged().collect { graph.audio.refresh() }
        }
    }

    fun onResume() {
        media.refresh()
        graph.audio.refresh()
    }

    fun refreshOutput() = graph.audio.refresh()

    /** Current song position in ms, including sync offsets. Called every frame. */
    fun positionMs(now: Long = SystemClock.elapsedRealtime()): Long {
        val base = when (_mode.value) {
            Mode.RECOGNIZED -> {
                val r = _recognized.value ?: return 0
                if (_clockPaused.value) pausedAtPosition else r.positionMs + (now - r.anchorRealtime)
            }
            Mode.NOW_PLAYING -> nowPlaying.value?.livePosition(now) ?: 0
        }
        return base + timing.value.offsetMs
    }

    /** Moves the lyrics earlier (positive) or later. Saved for the current device, so it sticks. */
    fun nudge(deltaMs: Long) {
        if (_mode.value == Mode.RECOGNIZED) {
            _nudge.value += deltaMs
        } else {
            val out = output.value
            settings.setTiming(out, settings.state.value.offsetFor(out) + deltaMs)
        }
    }

    /** Back to the default timing for the current device, or no nudge for a mic song. */
    fun resetTiming() {
        if (_mode.value == Mode.RECOGNIZED) _nudge.value = 0 else settings.forgetTiming(output.value.key)
    }

    fun seekToLyric(ms: Long) {
        val target = ms - timing.value.offsetMs
        when (_mode.value) {
            Mode.NOW_PLAYING -> media.seekTo(target)
            Mode.RECOGNIZED -> {
                val r = _recognized.value ?: return
                _recognized.value = r.copy(positionMs = target, anchorRealtime = SystemClock.elapsedRealtime())
                if (_clockPaused.value) pausedAtPosition = target
            }
        }
    }

    fun toggleClock() {
        val r = _recognized.value ?: return
        if (_clockPaused.value) {
            _recognized.value = r.copy(positionMs = pausedAtPosition, anchorRealtime = SystemClock.elapsedRealtime())
            _clockPaused.value = false
        } else {
            pausedAtPosition = r.positionMs + (SystemClock.elapsedRealtime() - r.anchorRealtime)
            _clockPaused.value = true
        }
    }

    fun setMode(m: Mode) {
        if (m == Mode.RECOGNIZED) playingKeyAtSwitch = playingKey()
        _mode.value = m
    }

    /** Searches the current song again, skipping the cache once. Later songs still use the cache. */
    fun retryLyrics() {
        val k = track.value?.key
        retry.update { k to it.second + 1 }
    }

    /** Drops the song found with the mic and goes back to what the phone is playing. */
    fun clearRecognized() {
        resetRecognition()
        _recognized.value = null
        _clockPaused.value = false
        _nudge.value = 0
        playingKeyAtSwitch = null
        _mode.value = Mode.NOW_PLAYING
    }

    fun refreshCacheStats() {
        viewModelScope.launch(Dispatchers.IO) { _cacheStats.value = repo.cacheStats() }
    }

    override fun onCleared() {
        runCatching { connectivity?.unregisterNetworkCallback(netCallback) }
        super.onCleared()
    }

    fun startListening() {
        recognitionJob?.cancel()
        _recognition.value = RecognitionEvent.Listening(0f, 0)
        recognitionJob = viewModelScope.launch {
            Recognizer.listen().collect { ev ->
                _recognition.value = ev
                if (ev is RecognitionEvent.Found) launch { useSpotifyCover(ev.track) }
            }
        }
    }

    fun stopListening() {
        recognitionJob?.cancel()
        recognitionJob = null
        val v = _recognition.value
        if (v is RecognitionEvent.Listening || v == RecognitionEvent.Matching) _recognition.value = null
    }

    fun resetRecognition() {
        stopListening()
        _recognition.value = null
    }

    /**
     * Swaps Shazam's cover for the matching Spotify album image when signed in to Spotify.
     * Without a sign-in, Shazam's cover is used at its largest size.
     */
    private suspend fun useSpotifyCover(found: RecognizedTrack) {
        val match = runCatching {
            spotifyCatalog.findMatch(TrackQuery(found.title, found.artist, found.album))
        }.getOrNull()
        val cover = match?.coverUrl ?: largerShazamCover(found.coverUrl)
        if (cover == found.coverUrl && match == null) return
        fun RecognizedTrack.upgrade() =
            if (title == found.title && artist == found.artist) copy(coverUrl = cover, spotifyTrackId = match?.trackId) else this
        (_recognition.value as? RecognitionEvent.Found)?.let { _recognition.value = RecognitionEvent.Found(it.track.upgrade()) }
        _recognized.value = _recognized.value?.upgrade()
    }

    /** Shazam covers come from Apple's image CDN, which serves larger sizes on request. */
    private fun largerShazamCover(url: String?): String? =
        url?.replace(Regex("""/(\d{2,4})x(\d{2,4})(cc|bb)\.(jpg|png|webp)$"""), "/1000x1000$3.$4")

    private val spotifyCatalog = SpotifyLyricsProvider()

    /** "Lyrics" pressed on a recognition result. */
    fun showRecognizedLyrics(track: RecognizedTrack) {
        _clockPaused.value = false
        _recognized.value = track
        playingKeyAtSwitch = playingKey()
        _mode.value = Mode.RECOGNIZED
    }

    fun clearCache() {
        viewModelScope.launch(Dispatchers.IO) {
            repo.clearCache()
            _cacheStats.value = 0 to 0L
            retryLyrics()
        }
    }
}
