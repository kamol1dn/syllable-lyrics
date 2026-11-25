package dev.kamol1dn.syllable

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.kamol1dn.syllable.lyrics.Lyrics
import dev.kamol1dn.syllable.lyrics.LyricsRepository
import dev.kamol1dn.syllable.lyrics.SyncType
import dev.kamol1dn.syllable.lyrics.TextMatch
import dev.kamol1dn.syllable.lyrics.catching
import dev.kamol1dn.syllable.lyrics.TrackQuery
import dev.kamol1dn.syllable.media.AudioOutput
import dev.kamol1dn.syllable.media.NowPlaying
import dev.kamol1dn.syllable.media.QueuedTrack
import dev.kamol1dn.syllable.recognition.RecognitionEvent
import dev.kamol1dn.syllable.recognition.RecognizedTrack
import dev.kamol1dn.syllable.recognition.Recognizer
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import dev.kamol1dn.syllable.spotify.SpotifyAuth
import android.util.Log
import dev.kamol1dn.syllable.lyrics.LyricLine
import dev.kamol1dn.syllable.spotify.MixStore
import dev.kamol1dn.syllable.spotify.MixTransition
import java.io.File
import dev.kamol1dn.syllable.spotify.SpotifyCover
import dev.kamol1dn.syllable.spotify.SpotifyLyricsProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
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
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

enum class Mode { NOW_PLAYING, RECOGNIZED }

/** The outcome of pressing the detect button on the lyrics. */
enum class ListenNote { RESYNCED, NEW_SONG, NO_MATCH }

private const val CHECK_MS = 60_000L
private const val END_LEAD_MS = 6_000L
private const val AFTER_END_CHECK_MS = 12_000L
private const val AFTER_END_WINDOW_MS = 10 * 60_000L
/** The automatic check takes a song to be somewhere else only when it hears it this far from the lyrics. */
private const val AUTO_FAR_MS = 5_000L
/** And only when two checks in a row agree on where, to within this. */
private const val AUTO_AGREE_MS = 1_500L
/** The current song's own search goes first; the next song's lyrics can wait this long. */
private const val LOOK_AHEAD_DELAY_MS = 1_500L
private const val UP_NEXT_PREVIEW_LINES = 6
/** How long a cover fetched ahead may stand in while the player hasn't sent the new song's own. */
private const val EARLY_ART_MS = 6_000L

/**
 * The song the player has queued after [forKey]. Its opening lines and cover are filled in as
 * they arrive, so both are ready before the song starts.
 */
data class UpNext(
    val forKey: String,
    val title: String,
    val artist: String,
    val spotifyId: String? = null,
    val preview: List<String> = emptyList(),
    val coverUrl: String? = null,
    val cover: Bitmap? = null,
)

/**
 * The last moments of the song [fromKey], when the screen has already moved on to [next], the song
 * queued after it. Songs mostly end without words, so the next one's lyrics can be in place before
 * it starts.
 */
private class Preroll(val fromKey: String, val next: TrackInfo)

/** A song has to stay this long before its Canvas is looked for. */
private const val CANVAS_DELAY_MS = 1_200L

private const val MIX_TAG = "SyllableMix"
/** Stands for "mixed playlists aren't followed" where a playlist id is expected. No id looks like it. */
private const val MIX_OFF = "off"
/** A new song found this close to where a saved transition has it is the player following that transition. */
private const val MIX_TRUST_MS = 3_000L
/** A join the player is this close to is left as it is. */
private const val MIX_EXACT_MS = 40L
/** If the outgoing song still plays on its own this long after the overlap, it isn't being mixed. */
private const val MIX_LATE_MS = 2_500L
/** How long a tap on a line of the next song waits for the player to get there. */
private const val MIX_SEEK_WAIT_MS = 5_000L
/** How long a change of song waits for the player to say where the new song is. */
private const val MIX_REPORT_WAIT_MS = 1_500L
/** A song left with more than this to go before its end, or before its mix, was skipped by hand. */
private const val MIX_EARLIEST_MS = 25_000L
/** A song with no more than this left when it gives way has ended by itself. */
private const val ENDING_MS = 3_000L
/** A change of song this soon after the app's own next button was pressed is that press. */
private const val OWN_SKIP_MS = 3_000L

/**
 * A change of song in a run, for the moment until the new song (track key [toKey]) has said where
 * it is. Until then the [list] that was on screen stays, and keeps the time of [before], the song
 * that was playing, whose own time is [shiftMs] behind the list's. So nothing moves when the list
 * then goes on into the new song. A song skipped by hand stopped at [stoppedAt], and the list with it.
 */
private class MixWatch(
    val list: MixSession, private val shiftMs: Long, val before: NowPlaying, val toKey: String, private val stoppedAt: Long?,
) {
    /** Where [before] would be by now on its own clock, had it gone on as it was. */
    fun songMs(now: Long): Long =
        if (before.isPlaying) before.positionMs + ((now - before.updateRealtime) * before.speed).toLong() else before.positionMs

    /** Where the list on screen is meanwhile, on its own clock. */
    fun listMs(now: Long): Long = songMs(stoppedAt ?: now) + shiftMs

    /** Whether [np] still reports the position of [before], under the new song's name. */
    fun isCarriedOver(np: NowPlaying): Boolean = np.positionMs == before.positionMs && np.updateRealtime == before.updateRealtime
}

/**
 * A tap on a line of the next song of a mixed run: the player is sent on to that song (number
 * [index] of the run) and then to [targetMs] in it. Until it is there the lyrics hold at [listMs],
 * the tapped line, instead of following the player through the jump.
 */
private class PendingSeek(val index: Int, val targetMs: Long, val listMs: Long)

/** How long before a song's end the screen moves on to the next one. */
private const val PREROLL_MS = 1_500L
/** If the next song still hasn't started this long after the hand-off, the queue was wrong. */
private const val PREROLL_GIVE_UP_MS = 6_000L

/** A cover fetched ahead for the song [key]. It is shown while the player still reports [previous], the last song's. */
private class EarlyArt(val key: String, val bitmap: Bitmap, val previous: Bitmap?)

/** Timing applied to the lyrics. [output] is what it is saved for: the device playing, or with [mic] the microphone listening. */
data class Timing(val offsetMs: Long, val output: AudioOutput?, val isDefault: Boolean, val mic: Boolean = false)

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
    /**
     * [key] says whether the screen should treat these as the lyrics it already shows. It only
     * differs from [lyrics] for a run of mixed songs, whose list grows while it stays the same list.
     */
    data class Loaded(val lyrics: Lyrics, val final: Boolean, val key: Any = lyrics) : LyricsState
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
    private val spotifyCatalog = SpotifyLyricsProvider()

    private val _mode = MutableStateFlow(Mode.NOW_PLAYING)
    val mode: StateFlow<Mode> = _mode.asStateFlow()

    private val _recognized = MutableStateFlow<RecognizedTrack?>(null)
    val recognized: StateFlow<RecognizedTrack?> = _recognized.asStateFlow()

    private val _recognition = MutableStateFlow<RecognitionEvent?>(null)
    val recognition: StateFlow<RecognitionEvent?> = _recognition.asStateFlow()
    private var recognitionJob: Job? = null

    /** A quiet listen is running on the lyrics screen: the automatic check, or the detect button. */
    private val _relistening = MutableStateFlow(false)
    val relistening: StateFlow<Boolean> = _relistening.asStateFlow()
    private var relistenJob: Job? = null

    /** What the last press of the detect button on the lyrics did, shown for a moment. */
    private val _listenNote = MutableStateFlow<ListenNote?>(null)
    val listenNote: StateFlow<ListenNote?> = _listenNote.asStateFlow()

    /** The mic may only be used while the app is on screen. */
    private val foreground = MutableStateFlow(false)

    /** Where the last automatic check heard the song, when that was far from the lyrics. */
    private var heardElsewhere: RecognizedTrack? = null

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
     * output device. A song found with the mic uses the one saved for that microphone.
     */
    val timing: StateFlow<Timing> = combine(
        _mode, output, settings.state, _recognized.map { it?.mic }.distinctUntilChanged(),
    ) { mode, out, s, heardBy ->
        val mic = heardBy ?: AudioOutput.PhoneMic
        if (mode == Mode.RECOGNIZED) Timing(s.micTimings[mic.key]?.offsetMs ?: 0L, mic, isDefault = mic.key !in s.micTimings, mic = true)
        else Timing(s.offsetFor(out), out, isDefault = out.key !in s.deviceTimings)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, Timing(0, null, true))

    private val earlyArt = MutableStateFlow<EarlyArt?>(null)
    private val preroll = MutableStateFlow<Preroll?>(null)

    /** The run of songs on screen as one list, if the song that is playing is part of one. */
    private val mixSession = MutableStateFlow<MixSession?>(null)
    /** The same, and also when it is so far only the song that is playing. Changed through [setRun]. */
    private var mixRun: MixSession? = null
    private val mixWatch = MutableStateFlow<MixWatch?>(null)
    private var pendingSeek: PendingSeek? = null
    private var seekJob: Job? = null
    private var mixCheck: Job? = null
    /** The song whose last moments the screen spent on the next one, until the player has moved on from it. */
    private var prerolledFrom: String? = null
    /** The [MixTransition.pair] the player turned out not to mix, so it isn't planned for again while its songs play. */
    private var notMixed: String? = null
    private val mix: MixStore = graph.mix
    val mixStatus: StateFlow<MixStore.Status> = mix.status
    val canvasStatus: StateFlow<String?> = graph.canvas.status

    /**
     * Covers fetched ahead for the last few queued songs, by title. The queue moves on a moment
     * before the next song's title arrives, so the one song in [upNext] can't be relied on then.
     */
    private val aheadCovers = object : LinkedHashMap<String, UpNext>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, UpNext>?) = size > 4
    }

    /** The cover address of the song that is playing, when it was fetched ahead. Songs of one album share it. */
    private var playingCoverUrl: String? = null

    val track: StateFlow<TrackInfo?> = combine(_mode, nowPlaying, _recognized, earlyArt, preroll) { mode, np, rec, early, pre ->
        when {
            mode == Mode.RECOGNIZED && rec != null -> TrackInfo(
                "rec|${rec.artist}|${rec.title}", rec.title, rec.artist, rec.album, null, rec.coverUrl, null, rec.spotifyTrackId,
            )
            np != null && pre != null && pre.fromKey == np.trackKey -> pre.next
            np != null -> {
                // Players send a new song's title before its cover. When the cover was fetched
                // ahead, it changes together with the title instead of a moment later.
                val ahead = early?.takeIf { it.key == np.trackKey && (np.artwork == null || np.artwork === it.previous) }
                TrackInfo(
                    np.trackKey, np.title, np.artist, np.album, ahead?.bitmap ?: np.artwork, null,
                    np.durationMs.takeIf { it > 0 }, np.spotifyTrackId,
                )
            }
            else -> null
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * The Spotify Canvas of the song on screen, once it has been found and downloaded. Looked for
     * a moment after the song appears, so songs that are skipped through cost nothing.
     */
    val canvas: StateFlow<File?> = combine(
        track.map { it?.spotifyId }.distinctUntilChanged(),
        settings.state.map { it.canvas }.distinctUntilChanged(),
        SpotifyAuth.state.map { it.signedIn }.distinctUntilChanged(),
        foreground,
    ) { id, on, signedIn, fg -> id.takeIf { on && signedIn && fg } }
        .distinctUntilChanged()
        .transformLatest { id ->
            if (id == null) return@transformLatest emit(null)
            if (!graph.canvas.isKnown(id)) {
                emit(null)
                delay(CANVAS_DELAY_MS)
            }
            emit(graph.canvas.find(id))
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The lyrics of the song on screen by itself. */
    private val ownLyrics: StateFlow<LyricsState> = combine(
        track.map { t -> t?.let { Triple(it.key, it, Unit) } }.distinctUntilChanged { a, b -> a?.first == b?.first },
        retry,
    ) { t, r -> t?.second to r }
        .flatMapLatest { (t, r) ->
            // lastLoad is kept through a brief null, so the same song coming back keeps its cache.
            if (t == null) return@flatMapLatest flowOf(LyricsState.Idle)
            val prev = lastLoad
            lastLoad = t.key to r.second
            val sameSong = prev?.first == t.key
            val isRetry = sameSong && prev.second != r.second && r.first == t.key
            // A reload keeps the current words on screen until the fresh result arrives.
            val kept = lastLoaded?.takeIf { isRetry && it.first == t.key }?.second
            val query = TrackQuery(t.title, t.artist, t.album, t.durationMs, t.spotifyId)
            // Lyrics fetched ahead of time go straight on screen, without the searching state in between.
            val ready = if (isRetry) null else repo.peek(query)
            if (ready != null) {
                return@flatMapLatest flowOf(LyricsState.Loaded(ready, final = true).also { lastLoaded = t.key to it })
            }
            repo.load(query, skipCache = isRetry).map { p ->
                when (p) {
                    is LyricsRepository.Progress.Found -> LyricsState.Loaded(p.lyrics, p.final).also { lastLoaded = t.key to it }
                    LyricsRepository.Progress.NotFound -> kept?.copy(final = true) ?: LyricsState.NotFound
                    LyricsRepository.Progress.Offline -> kept?.copy(final = true) ?: LyricsState.Offline
                }
            }.onStart { emit(kept?.copy(final = false) ?: LyricsState.Loading) }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, LyricsState.Idle)

    /**
     * The lyrics on screen. A song from a run of mixed songs shows the whole run as one list, under
     * the key of the lyrics the run began with, so the screen keeps the list where it is while
     * songs are joined on and while the player moves from one to the next.
     */
    val lyrics: StateFlow<LyricsState> = combine(ownLyrics, mixSession, mixWatch, track, _mode) { own, run, watch, t, mode ->
        when {
            t == null || mode != Mode.NOW_PLAYING -> own
            // A new song keeps the list it arrived under until it has said where it is.
            watch != null && t.key == watch.toKey -> LyricsState.Loaded(watch.list.lyrics, final = true, key = watch.list.anchor)
            run != null && run.indexOf(t.spotifyId, t.title) >= 0 -> LyricsState.Loaded(run.lyrics, final = true, key = run.anchor)
            else -> own
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LyricsState.Idle)

    private val _upNext = MutableStateFlow<UpNext?>(null)
    /**
     * What plays after the song on screen. Null for players that don't share their queue, and
     * when the next song's lines already follow in the same list.
     */
    val upNext: StateFlow<UpNext?> = combine(_upNext, track, _mode, mixSession) { next, t, mode, run ->
        val joined = run != null && t != null && run.indexOf(t.spotifyId, t.title).let { it >= 0 && it < run.songs.lastIndex }
        next?.takeIf { mode == Mode.NOW_PLAYING && t?.key == it.forKey && !joined }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val connectivity = app.getSystemService(ConnectivityManager::class.java)
    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            // VALIDATED rather than onAvailable, which fires before DNS works.
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) && lyrics.value == LyricsState.Offline) retryLyrics()
        }
    }

    private val _update = MutableStateFlow<AppUpdate?>(null)
    /** A newer version found on GitHub when the app opened, until it is put off or skipped. */
    val update: StateFlow<AppUpdate?> = _update.asStateFlow()

    /** Puts the update off until the app is next opened. */
    fun dismissUpdate() {
        _update.value = null
    }

    /** Doesn't mention this version again. A later one still is. */
    fun skipUpdate() {
        val version = _update.value?.version ?: return
        settings.update { it.copy(skippedVersion = version) }
        _update.value = null
    }

    private val _cacheStats = MutableStateFlow<Pair<Int, Long>?>(null)
    /** Saved songs and their size on disk, once [refreshCacheStats] has run. */
    val cacheStats: StateFlow<Pair<Int, Long>?> = _cacheStats.asStateFlow()

    init {
        runCatching { connectivity?.registerDefaultNetworkCallback(netCallback) }
        if (settings.state.value.checkUpdates) {
            viewModelScope.launch {
                _update.value = UpdateCheck.newerThan(BuildConfig.VERSION_NAME)
                    ?.takeIf { it.version != settings.state.value.skippedVersion }
            }
        }
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
        // A song found with the mic has no player to follow, so keep checking the mic: about once
        // a minute to stay in time, and more often around the end, when the next song starts.
        viewModelScope.launch {
            combine(_mode, foreground, _clockPaused, settings.state.map { it.autoListen }.distinctUntilChanged()) { m, fg, paused, auto ->
                m == Mode.RECOGNIZED && fg && !paused && auto
            }.distinctUntilChanged().collectLatest { on ->
                while (on) {
                    delay(nextCheckDelayMs())
                    if (!hasMic()) break
                    launchRelisten(manual = false)?.join()
                }
            }
        }
        // The player's queue says what plays next, so its lyrics can be fetched while the current
        // song is still going and are on screen the moment it starts.
        viewModelScope.launch {
            combine(
                media.nowPlaying.map { it?.trackKey }.distinctUntilChanged(),
                media.upcoming,
                foreground,
            ) { key, upcoming, fg -> if (fg && key != null) key to upcoming else null }
                .distinctUntilChanged()
                .collectLatest { if (it != null) lookAhead(it.first, it.second) }
        }
        // When the queued song starts, its cover fetched ahead takes over from the last song's.
        viewModelScope.launch {
            var lastKey: String? = null
            var lastArt: Bitmap? = null
            nowPlaying.collectLatest { np ->
                if (np == null) return@collectLatest
                if (np.trackKey != lastKey) {
                    val next = aheadCovers[TextMatch.normalize(np.title)]
                    // The same address is the same album, and that cover is on screen already.
                    val cover = next?.cover?.takeIf { next.coverUrl != playingCoverUrl }
                    earlyArt.value = cover?.let { EarlyArt(np.trackKey, it, lastArt) }
                    lastKey = np.trackKey
                    playingCoverUrl = next?.coverUrl
                }
                lastArt = np.artwork
                if (earlyArt.value?.key == np.trackKey) {
                    delay(EARLY_ART_MS)
                    if (np.artwork != null) earlyArt.value = null
                }
            }
        }
        // Shortly before a song ends, once its words are over, the screen moves on to the queued
        // song, so its lyrics are waiting at the first line when it starts.
        viewModelScope.launch {
            combine(nowPlaying.map { it?.trackKey }.distinctUntilChanged(), _mode, foreground) { key, mode, fg ->
                key.takeIf { mode == Mode.NOW_PLAYING && fg }
            }.distinctUntilChanged().collectLatest { key ->
                preroll.value = null
                if (key == null) return@collectLatest
                var since = 0L
                while (true) {
                    val np = nowPlaying.value?.takeIf { it.trackKey == key && it.durationMs > 0 } ?: return@collectLatest
                    val pos = np.livePosition()
                    val left = np.durationMs - pos
                    val next = joinedNext(np)
                    val into = next?.into
                    if (next != null && into != null) {
                        // The next song's lines already follow in the same list, so there is
                        // nothing to move on to. Unless a saved transition isn't played after
                        // all, and this song is still going by itself well after it should have
                        // faded out: then the next song is to follow at its end instead.
                        val gone = into.startAMs + into.durationMs + MIX_LATE_MS
                        if (next.join == MixSession.Join.SAVED && np.isPlaying && pos > gone && left > 500) {
                            unplan(into, "still playing alone at $pos")
                        } else {
                            delay((gone - pos).coerceIn(250, 1_000))
                        }
                    } else if (preroll.value == null) {
                        if (np.isPlaying && left <= PREROLL_MS && wordsAreOver()) {
                            preroll.value = prerollFrom(np)?.also { prerolledFrom = np.trackKey }
                            since = SystemClock.elapsedRealtime()
                        }
                        delay((left - PREROLL_MS).coerceIn(50, 1_000))
                    } else if (
                        // A position back near zero is how players announce the next song, so only
                        // one further in counts as winding back.
                        (left > PREROLL_MS + 1_500 && np.livePosition() > 3_000) ||
                        SystemClock.elapsedRealtime() - since > PREROLL_MS + PREROLL_GIVE_UP_MS
                    ) {
                        // The song was wound back, or something else than the queued song followed.
                        preroll.value = null
                        prerolledFrom = null
                        return@collectLatest
                    } else {
                        delay(250)
                    }
                }
            }
        }
        // A run of mixed songs is put together as its parts arrive: the song's own lyrics, what is
        // queued next and its lyrics, and the playlist's transitions.
        viewModelScope.launch {
            combine(ownLyrics, _upNext, mix.status, settings.state.map { it.continuousLyrics }.distinctUntilChanged()) { _, _, _, _ -> }
                .collect { updateMixSession() }
        }
        // How one song runs into the next only shows when the player moves on. That is looked at
        // first, while the run is still as it was for the song before.
        viewModelScope.launch {
            var last: NowPlaying? = null
            var key: String? = null
            nowPlaying.collect { np ->
                val before = last
                if (np != null) last = np
                if (np?.trackKey == key) return@collect
                key = np?.trackKey
                if (np != null && before != null && before.trackKey != np.trackKey) songChanged(before, np)
                updateMixSession()
            }
        }
        // Following mixed playlists needs their transitions. Spotify's player says which playlist
        // is playing, so that one is read when it starts. Turning the setting off forgets them all.
        viewModelScope.launch {
            combine(
                settings.state.map { it.continuousLyrics }.distinctUntilChanged(),
                SpotifyAuth.state.map { it.signedIn }.distinctUntilChanged(),
                foreground,
                media.nowPlaying.map { it?.spotifyPlaylistId }.distinctUntilChanged(),
            ) { on, signedIn, fg, playlist -> if (on && signedIn) playlist.takeIf { fg } else MIX_OFF }
                .distinctUntilChanged()
                .collectLatest { playlist -> if (playlist == MIX_OFF) mix.clear() else if (playlist != null) mix.read(playlist) }
        }
        media.refresh()
        // A route change (headphones picked in the output switcher, say) usually comes with a
        // pause or a new track, so check the output again whenever playback changes.
        viewModelScope.launch {
            media.nowPlaying.map { it?.isPlaying to it?.trackKey }.distinctUntilChanged().collect { graph.audio.refresh() }
        }
    }

    /** Whether the song on screen has no lines left to sing. True as well when it has no timed lyrics. */
    private fun wordsAreOver(): Boolean {
        val end = (lyrics.value as? LyricsState.Loaded)?.lyrics?.takeIf { it.sync != SyncType.NONE }
            ?.lines?.maxOfOrNull { it.endMs } ?: return true
        return positionMs() > end + 300
    }

    /** The queued song as the screen shows it ahead of time, or null when its lyrics aren't in yet. */
    private fun prerollFrom(np: NowPlaying): Preroll? {
        val next = _upNext.value?.takeIf { it.forKey == np.trackKey } ?: return null
        if (repo.peek(TrackQuery(next.title, next.artist, spotifyTrackId = next.spotifyId)) == null) return null
        // The key the player's own report of the song will have, so nothing reloads when it starts.
        val key = "${np.packageName}|${next.artist}|${next.title}"
        val cover = next.cover?.takeIf { next.coverUrl != playingCoverUrl } ?: np.artwork
        return Preroll(np.trackKey, TrackInfo(key, next.title, next.artist, null, cover, null, null, next.spotifyId))
    }

    /** The song that follows the one playing in the list on screen, when one is joined on. */
    private fun joinedNext(np: NowPlaying): MixSession.Song? {
        val run = mixSession.value ?: return null
        val at = run.indexOf(np.spotifyTrackId, np.title)
        return if (at >= 0) run.songs.getOrNull(at + 1) else null
    }

    private fun setRun(run: MixSession?) {
        mixRun = run
        // A run of one song is just that song.
        mixSession.value = run?.takeIf { it.songs.size > 1 }
    }

    /**
     * Brings the run up to date with what is playing and what is queued: starts one from the song
     * that is playing, and joins the next song on once its lyrics are in, so its lines follow in
     * the list ahead of time. Cheap, and safe to call whenever any of that may have changed.
     */
    private fun updateMixSession() {
        // A change of song is being looked at, and settles the run itself.
        if (mixWatch.value != null) return
        val np = nowPlaying.value
        val id = np?.spotifyTrackId
        if (np == null || id == null || !settings.state.value.continuousLyrics) {
            setRun(null)
            return
        }
        val next = _upNext.value?.takeIf { it.forKey == np.trackKey }
        val nextId = next?.spotifyId
        // The transition saved for going from the song number [at] of [run] into the next one.
        fun savedInto(run: MixSession, at: Int, nextId: String) =
            (mix.find(run.songs[at].spotifyId, nextId) ?: mix.find(id, nextId))?.takeIf { it.pair != notMixed }
        var run = mixRun
        var at = run?.indexOf(id, np.title) ?: -1
        if (run != null && at >= 0 && at == run.songs.lastIndex - 1) {
            val planned = run.songs[at + 1]
            val changed = next != null && nextId != null && !planned.matches(nextId, next.title)
            val saved = planned.join == MixSession.Join.AFTER && savedInto(run, at, nextId ?: planned.spotifyId) != null
            // The queue was changed, and the song that was joined on won't be the next one after
            // all. Or its transition has been read since, and it is joined on again by that.
            if (changed || saved) run = run.upTo(at)
            if (run == null) at = -1
        }
        val own = (ownLyrics.value as? LyricsState.Loaded)?.takeIf { it.final && lastLoaded?.first == np.trackKey }?.lyrics
        // A new run starts from the song's own lyrics, once they are in. A song that is still by
        // itself starts over when its lyrics were loaded again.
        if (run == null || at < 0 || (run.songs.size == 1 && own != null && run.anchor !== own)) {
            run = own?.let { MixSession.start(id, np.title, it) }
            at = 0
        }
        val last = run
        // Never the song that is playing itself: it could not be told from the one after it, and
        // just after a change of song the player's queue may still name it as the next one.
        if (last != null && at == last.songs.lastIndex && nextId != null && !last.songs[at].matches(nextId, next.title)) {
            val words = repo.peek(TrackQuery(next.title, next.artist, spotifyTrackId = nextId))
            if (words != null) {
                // A transition saved in the playlist says where the next song comes in. Without
                // one it is taken to follow when this song ends, until the player shows otherwise.
                val saved = savedInto(last, at, nextId)
                val joined = when {
                    saved != null -> last.add(nextId, next.title, words, saved)
                    np.durationMs > 0 -> last.addAfter(nextId, next.title, words, np.durationMs)
                    else -> null
                }
                if (joined != null) {
                    Log.d(
                        MIX_TAG,
                        "next song joined on, ${joined.songs.size} in the run: " +
                            if (saved != null) "overlap from ${saved.startAMs} for ${saved.durationMs}, next from ${saved.startBMs}" else "after this one",
                    )
                    run = joined
                }
            }
        }
        setRun(run)
    }

    /** Drops the run, so the song on screen is shown by itself again and a new run starts from it. */
    private fun endMixSession(why: String) {
        if (mixRun == null) return
        Log.d(MIX_TAG, "run ended: $why")
        setRun(null)
        updateMixSession()
    }

    /**
     * Takes back the join of the next song by the saved transition [into], which the player turned
     * out not to play. The next song is then joined on again to follow when this one ends, and
     * the transition isn't planned for again while its songs play.
     */
    private fun unplan(into: MixTransition, why: String) {
        Log.d(MIX_TAG, "saved transition not played: $why")
        notMixed = into.pair
        val run = mixRun
        setRun(run?.upTo(run.songs.lastIndex - 1))
        updateMixSession()
    }

    /**
     * The player went from [before] to [np]. The list on screen goes on into the new song's
     * lines, whether the player mixed the two or the old one ended. So it does when the new one
     * was skipped to by hand, if that is asked for in the settings: the old song's line then stops
     * where the song did. Otherwise a skip starts the list over. The list holds still until the new song has said where it is, and the song is then joined on from
     * there: see [joinAt]. Going back to a song the list has been through starts the list over,
     * and so does a song whose lyrics aren't in or have no timing.
     */
    private fun songChanged(before: NowPlaying, np: NowPlaying) {
        mixCheck?.cancel()
        mixWatch.value = null
        val prerolled = prerolledFrom == before.trackKey
        prerolledFrom = null
        val shown = mixRun ?: return
        if (_mode.value != Mode.NOW_PLAYING) return
        val id = np.spotifyTrackId
        val from = shown.indexOf(before.spotifyTrackId, before.title)
        val to = shown.indexOf(id, np.title)
        // Sent there by a tap on one of its lines, which sets the position itself.
        if (to >= 0 && pendingSeek?.index == to) return
        val since = SystemClock.elapsedRealtime()
        fun over(why: String) {
            Log.d(MIX_TAG, "song changed at ${before.livePosition(since)} of ${before.durationMs}, list starts over: $why")
            setRun(null)
        }
        if (prerolled) return over("the screen had moved on to the next song already")
        if (id == null || from < 0) return over("not a song the list can go on to")
        if (to >= 0 && to != from + 1) return over("back to a song the list has been through")
        val base = shown.upTo(from) ?: return over("too far back in the list")
        // What was joined on ahead of time, when the new song is the one that was expected.
        val planned = shown.takeIf { to == from + 1 }
        // A mix or an ending comes in a song's last moments. Leaving it well before those, or by
        // the app's own next button, is a skip by hand. One made later only shows by the new
        // song stopping to load, which is seen below.
        val due = planned?.songs?.last()?.takeIf { it.join == MixSession.Join.SAVED }?.into?.startAMs
            ?: before.durationMs.takeIf { it > 0 }
        val early = since - media.ownSkipAt < OWN_SKIP_MS || (due != null && before.livePosition(since) < due - MIX_EARLIEST_MS)
        val carryOnSkip = settings.state.value.continuousOnSkip
        if (early && !carryOnSkip) return over("skipped by hand")
        val words = repo.peek(TrackQuery(np.title, np.artist, spotifyTrackId = id))
        if (words == null) {
            // With nothing to join it on again from, what was planned stays as it is.
            if (planned == null || early) over("the new song's lyrics aren't in yet")
            return
        }
        val watch = MixWatch(shown, base.songs.last().shiftMs, before, np.trackKey, since.takeIf { early })
        mixWatch.value = watch
        mixCheck = viewModelScope.launch {
            try {
                // The new song's name tends to arrive before its position does.
                val fresh = withTimeoutOrNull(MIX_REPORT_WAIT_MS) {
                    nowPlaying.first { it == null || it.trackKey != np.trackKey || (it.positionFresh && !watch.isCarriedOver(it)) }
                }
                // After the wait, what the player last said has to do.
                val later = (fresh ?: nowPlaying.value)?.takeIf { it.trackKey == np.trackKey }
                if (later != null) {
                    val now = SystemClock.elapsedRealtime()
                    // A song that plays on has been going all along. One that stops to load was
                    // sent to by hand, and the old song stopped where it was then,
                    // unless the old song was at its end anyway, and the next one is just slow to start.
                    val left = before.durationMs - watch.songMs(since)
                    val byHand = early || (!later.isPlaying && (before.durationMs <= 0 || left > ENDING_MS))
                    val atOld = watch.songMs(if (byHand) since else now)
                    val atNew = later.livePosition(now)
                    val joined = if (byHand && !carryOnSkip) null else joinAt(base, planned, id, np.title, words, atOld, atNew, byHand)
                    Log.d(
                        MIX_TAG,
                        "song changed at ${watch.songMs(since)} of ${before.durationMs}, new song at $atNew" +
                            (if (later.isPlaying) "" else " and loading") + (if (byHand) ", by hand" else "") + ": " + when {
                            joined == null -> "nothing of it to go on to, list starts over"
                            joined === planned -> "as planned"
                            joined.songs.last().join == MixSession.Join.SAVED -> "saved transition, moved to fit"
                            else -> "list goes on from there, ${joined.songs.size} songs in the run"
                        },
                    )
                    setRun(joined)
                }
            } finally {
                if (mixWatch.value === watch) mixWatch.value = null
            }
            updateMixSession()
        }
    }

    /**
     * The run [base] with the song [id] joined on, given that the last song was at [atOldMs] of
     * its own time when the new one was at [atNewMs] of its own. A saved transition that was
     * [planned] for and that the player is following stays, moved by however little the player is
     * off it, so the lines of both songs still alternate through the overlap. Anything else is
     * joined where the songs were found to be, and a song skipped [byHand] ends right there.
     */
    private fun joinAt(
        base: MixSession, planned: MixSession?, id: String, title: String, words: Lyrics,
        atOldMs: Long, atNewMs: Long, byHand: Boolean,
    ): MixSession? {
        val plan = planned?.songs?.last()
        val saved = plan?.into?.takeIf { plan.join == MixSession.Join.SAVED }
        if (plan != null && saved != null && !byHand) {
            val off = base.songs.last().shiftMs + atOldMs - atNewMs - plan.shiftMs
            if (abs(off) <= MIX_EXACT_MS) return planned
            if (abs(off) <= MIX_TRUST_MS) return base.add(id, title, words, saved.copy(startAMs = saved.startAMs + off)) ?: planned
        }
        return base.addHeard(id, title, words, atOldMs, atNewMs, cut = byHand)
    }

    /**
     * Plays from a line of the next song of the run: on to that song, then to [targetMs] in it.
     * The lyrics wait at [listMs], the line, until the player is there.
     */
    private fun skipInto(run: MixSession, index: Int, targetMs: Long, listMs: Long) {
        val seek = PendingSeek(index, targetMs, listMs)
        pendingSeek = seek
        seekJob?.cancel()
        seekJob = viewModelScope.launch {
            try {
                withTimeoutOrNull(MIX_SEEK_WAIT_MS) {
                    media.next()
                    nowPlaying.first { np -> np != null && run.indexOf(np.spotifyTrackId, np.title) == index }
                    // A player asked to seek in the instant it changes song tends not to hear it.
                    delay(250)
                    media.seekTo(targetMs)
                    nowPlaying.first { np -> np != null && abs(np.livePosition() - targetMs) < MIX_TRUST_MS }
                }
            } finally {
                if (pendingSeek === seek) pendingSeek = null
            }
        }
    }

    /** Reads the transitions of the playlist that is playing again, for when they were just changed in Spotify. */
    fun refreshMix() {
        val playlist = media.nowPlaying.value?.spotifyPlaylistId ?: return
        viewModelScope.launch { mix.read(playlist, force = true) }
    }

    /** Gets the lyrics of the songs queued after the song [key], and shows the first of them as up next. */
    private suspend fun lookAhead(key: String, upcoming: List<QueuedTrack>) {
        val next = upcoming.firstOrNull()
        if (next == null) {
            _upNext.value = null
            return
        }
        fun UpNext.isNext() = forKey == key && title == next.title && artist == next.artist
        if (_upNext.value?.isNext() != true) _upNext.value = UpNext(key, next.title, next.artist, next.spotifyTrackId)
        if (_upNext.value?.cover == null) {
            val url = next.coverUrl
                ?: next.spotifyTrackId?.let { SpotifyCover.url(it) }
                ?: catching { spotifyCatalog.findMatch(TrackQuery(next.title, next.artist)) }.getOrNull()?.coverUrl
            val cover = url?.let { loadCover(it) }
            android.util.Log.d("SyllableQueue", "next cover: ${if (cover != null) "${cover.width} px" else "none"} (player link ${next.coverUrl != null}, Spotify id ${next.spotifyTrackId != null})")
            if (cover != null) {
                _upNext.update { u -> if (u?.isNext() == true) u.copy(coverUrl = url, cover = cover) else u }
                aheadCovers[TextMatch.normalize(next.title)] = UpNext(key, next.title, next.artist, coverUrl = url, cover = cover)
            }
        }
        delay(LOOK_AHEAD_DELAY_MS)
        upcoming.take(2).forEachIndexed { i, t ->
            val found = repo.prefetch(TrackQuery(t.title, t.artist, spotifyTrackId = t.spotifyTrackId))
            if (i == 0 && found != null) {
                val preview = found.lines.asSequence().map { it.text }.filter { it.isNotBlank() }.take(UP_NEXT_PREVIEW_LINES).toList()
                _upNext.update { u -> if (u?.isNext() == true) u.copy(preview = preview) else u }
            }
        }
    }

    /** Downloads a cover as a software bitmap, so the background can read its pixels. */
    private suspend fun loadCover(url: String): Bitmap? = catching {
        val app = getApplication<Application>()
        val request = ImageRequest.Builder(app).data(url).allowHardware(false).build()
        (app.imageLoader.execute(request) as? SuccessResult)?.image?.toBitmap()
    }.getOrNull()

    fun onResume() {
        foreground.value = true
        media.refresh()
        graph.audio.refresh()
    }

    fun onPause() {
        foreground.value = false
        // The mic is only for the screen the user is looking at.
        relistenJob?.cancel()
    }

    fun refreshOutput() = graph.audio.refresh()

    /** Current song position in ms, including sync offsets. Called every frame. */
    fun positionMs(now: Long = SystemClock.elapsedRealtime()): Long {
        val base = when (_mode.value) {
            Mode.RECOGNIZED -> {
                val r = _recognized.value ?: return 0
                if (_clockPaused.value) pausedAtPosition else r.positionMs + (now - r.anchorRealtime)
            }
            Mode.NOW_PLAYING -> {
                val np = nowPlaying.value
                val run = mixSession.value
                val at = if (np != null && run != null) run.indexOf(np.spotifyTrackId, np.title) else -1
                val seek = pendingSeek
                val watch = mixWatch.value
                when {
                    np == null -> 0
                    // Ahead of the next song the clock counts down to its start.
                    preroll.value?.fromKey == np.trackKey -> np.livePosition(now) - np.durationMs
                    // A new song that hasn't said where it is yet: the list keeps its time.
                    watch != null && watch.toKey == np.trackKey -> watch.listMs(now)
                    // A tapped line of the next song holds until the player has got there.
                    seek != null && run != null -> seek.listMs
                    // A run of mixed songs is one list on the first song's clock.
                    run != null && at >= 0 -> np.livePosition(now) + run.songs[at].shiftMs
                    else -> np.livePosition(now)
                }
            }
        }
        return base + timing.value.offsetMs
    }

    /** Moves the lyrics earlier (positive) or later. Saved for the current device or microphone, so it sticks. */
    fun nudge(deltaMs: Long) {
        val now = timing.value
        if (now.mic) {
            settings.setMicTiming(now.output ?: AudioOutput.PhoneMic, now.offsetMs + deltaMs)
        } else {
            val out = output.value
            settings.setTiming(out, settings.state.value.offsetFor(out) + deltaMs)
        }
    }

    /** Back to the default timing for the current device or microphone. */
    fun resetTiming() {
        val now = timing.value
        if (now.mic) settings.forgetMicTiming((now.output ?: AudioOutput.PhoneMic).key) else settings.forgetTiming(output.value.key)
    }

    /** Plays from [line], a line of the lyrics on screen. */
    fun seekToLyric(line: LyricLine) {
        val offset = timing.value.offsetMs
        if (_mode.value == Mode.RECOGNIZED) {
            val r = _recognized.value ?: return
            val target = line.startMs - offset
            _recognized.value = r.copy(positionMs = target, anchorRealtime = SystemClock.elapsedRealtime())
            if (_clockPaused.value) pausedAtPosition = target
            return
        }
        // Ahead of a song the lines on screen belong to one that hasn't started yet.
        if (preroll.value != null || mixWatch.value != null) return
        val np = nowPlaying.value ?: return
        val run = mixSession.value
        val at = run?.indexOf(np.spotifyTrackId, np.title) ?: -1
        if (run == null || at < 0) {
            media.seekTo(line.startMs - offset)
            return
        }
        // In a run of mixed songs the line may be the next song's, which is then played from there.
        val song = run.songs.getOrNull(line.part) ?: return
        val target = line.startMs - song.shiftMs - offset
        when (line.part) {
            at -> media.seekTo(target)
            at + 1 -> skipInto(run, at + 1, target, line.startMs)
            // A song that is over. Going back through a mix isn't something the player does.
            else -> Unit
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
        relistenJob?.cancel()
        _recognized.value = null
        _clockPaused.value = false
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

    /** Listens from the listen screen. A match goes straight to its lyrics. */
    fun startListening() {
        recognitionJob?.cancel()
        relistenJob?.cancel()
        _recognition.value = RecognitionEvent.Listening(0f, 0)
        recognitionJob = viewModelScope.launch {
            Recognizer.listen().collect { ev ->
                _recognition.value = ev
                if (ev is RecognitionEvent.Found) {
                    showRecognizedLyrics(ev.track)
                    launch { useSpotifyCover(ev.track) }
                }
            }
        }
    }

    private fun hasMic() = ContextCompat.checkSelfPermission(getApplication(), Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

    /**
     * The detect button on the lyrics: listens again without leaving them. The same song gets its
     * timing corrected, a different one takes over. The automatic check does the same for a
     * different song, but leaves the same song's timing as it is: see [applyRelisten]. Returns false when the mic isn't allowed, so
     * the caller can open the listen screen, which asks for it.
     */
    fun relisten(): Boolean {
        if (!hasMic()) return false
        launchRelisten(manual = true)
        return true
    }

    private fun launchRelisten(manual: Boolean): Job? {
        if (recognitionJob?.isActive == true) return null
        relistenJob?.takeIf { it.isActive }?.let { return it }
        return viewModelScope.launch {
            _relistening.value = true
            if (manual) _listenNote.value = null
            val note = try {
                val found = Recognizer.listen().filterIsInstance<RecognitionEvent.Found>().firstOrNull()?.track
                // With no match the lyrics simply carry on from what was heard last time.
                if (found == null) ListenNote.NO_MATCH else applyRelisten(found, manual)
            } finally {
                _relistening.value = false
            }
            if (manual) {
                _listenNote.value = note
                delay(2_500)
                _listenNote.value = null
            }
        }.also { relistenJob = it }
    }

    private fun applyRelisten(found: RecognizedTrack, manual: Boolean): ListenNote {
        val cur = _recognized.value
        val elsewhere = heardElsewhere
        heardElsewhere = null
        if (cur == null || _mode.value != Mode.RECOGNIZED || !sameSong(cur, found)) {
            showRecognizedLyrics(found)
            viewModelScope.launch { useSpotifyCover(found) }
            return ListenNote.NEW_SONG
        }
        val now = SystemClock.elapsedRealtime()
        fun RecognizedTrack.at() = positionMs + (now - anchorRealtime)
        val shown = if (_clockPaused.value) pausedAtPosition else cur.at()
        // A second match of the same song is no better than the first, and can be well off: a
        // chorus is heard as any of its repeats. So the automatic check leaves the timing alone.
        // Only when two checks in a row hear the song at the same place far from the lyrics has
        // it really moved, started again on repeat for one.
        val moved = abs(found.at() - shown) > AUTO_FAR_MS
        val agreed = moved && elsewhere != null && abs(found.at() - elsewhere.at()) < AUTO_AGREE_MS
        if (manual || agreed) {
            _clockPaused.value = false
            _recognized.value = cur.copy(positionMs = found.positionMs, anchorRealtime = found.anchorRealtime, mic = found.mic)
        } else if (moved) {
            heardElsewhere = found
        }
        return ListenNote.RESYNCED
    }

    private fun sameSong(a: RecognizedTrack, b: RecognizedTrack) =
        TextMatch.normalize(a.title) == TextMatch.normalize(b.title) && TextMatch.normalize(a.artist) == TextMatch.normalize(b.artist)

    /** How long to wait before the next automatic check. */
    private fun nextCheckDelayMs(): Long {
        val end = (lyrics.value as? LyricsState.Loaded)?.lyrics
            ?.takeIf { it.sync != SyncType.NONE }?.lines?.lastOrNull()?.endMs
            ?: return CHECK_MS
        val left = end - positionMs()
        return when {
            left > CHECK_MS + END_LEAD_MS -> CHECK_MS
            // Wake up a little after the last line instead of in the middle of it.
            left > 0 -> left + END_LEAD_MS
            // The song is ending or over: the next one can start any moment.
            left > -AFTER_END_WINDOW_MS -> AFTER_END_CHECK_MS
            else -> CHECK_MS
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
        val match = catching {
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

    /** Puts a song found with the mic on screen. */
    private fun showRecognizedLyrics(track: RecognizedTrack) {
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
