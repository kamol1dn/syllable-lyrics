package dev.kamol1dn.syllable

import android.app.Application
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.kamol1dn.syllable.lyrics.Lyrics
import dev.kamol1dn.syllable.lyrics.LyricsRepository
import dev.kamol1dn.syllable.lyrics.TrackQuery
import dev.kamol1dn.syllable.media.NowPlaying
import dev.kamol1dn.syllable.recognition.RecognitionEvent
import dev.kamol1dn.syllable.recognition.RecognizedTrack
import dev.kamol1dn.syllable.recognition.Recognizer
import dev.kamol1dn.syllable.spotify.SpotifyLyricsProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
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
}

@OptIn(ExperimentalCoroutinesApi::class)
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

    /** Per-song nudge (ms), reset when the song changes. */
    private val _nudge = MutableStateFlow(0L)
    val nudge: StateFlow<Long> = _nudge.asStateFlow()

    /** Recognised-song clock pause state. */
    private val _clockPaused = MutableStateFlow(false)
    val clockPaused: StateFlow<Boolean> = _clockPaused.asStateFlow()
    private var pausedAtPosition = 0L

    private val retry = MutableStateFlow(0)

    val nowPlaying: StateFlow<NowPlaying?> = media.nowPlaying

    val track: StateFlow<TrackInfo?> = combine(_mode, media.nowPlaying, _recognized) { mode, np, rec ->
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
            if (t == null) return@flatMapLatest flowOf(LyricsState.Idle)
            _nudge.value = 0
            repo.load(
                TrackQuery(t.title, t.artist, t.album, t.durationMs, t.spotifyId),
                skipCache = r > 0,
            ).map { p ->
                when (p) {
                    is LyricsRepository.Progress.Found -> LyricsState.Loaded(p.lyrics, p.final)
                    LyricsRepository.Progress.NotFound -> LyricsState.NotFound
                }
            }.onStart { emit(LyricsState.Loading) }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, LyricsState.Idle)

    init {
        media.refresh()
    }

    fun onResume() = media.refresh()

    /** Current song position in ms, including sync offsets. Called every frame. */
    fun positionMs(now: Long = SystemClock.elapsedRealtime()): Long {
        val base = when (_mode.value) {
            Mode.RECOGNIZED -> {
                val r = _recognized.value ?: return 0
                if (_clockPaused.value) pausedAtPosition else r.positionMs + (now - r.anchorRealtime)
            }
            Mode.NOW_PLAYING -> media.nowPlaying.value?.livePosition(now) ?: 0
        }
        return base + settings.state.value.syncOffsetMs + _nudge.value
    }

    fun nudge(deltaMs: Long) { _nudge.value += deltaMs }

    fun seekToLyric(ms: Long) {
        val target = ms - settings.state.value.syncOffsetMs - _nudge.value
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

    fun setMode(m: Mode) { _mode.value = m }

    fun retryLyrics() { retry.value++ }

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
        if (_recognition.value is RecognitionEvent.Listening) _recognition.value = null
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
        _mode.value = Mode.RECOGNIZED
    }

    fun clearCache() = repo.clearCache()
}
