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
import dev.kamol1dn.syllable.lyrics.mixedLyrics
import dev.kamol1dn.syllable.spotify.MixStore
import dev.kamol1dn.syllable.spotify.MixTransition
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
import kotlin.math.abs

enum class Mode { NOW_PLAYING, RECOGNIZED }

/** The outcome of pressing the detect button on the lyrics. */
enum class ListenNote { RESYNCED, NEW_SONG, NO_MATCH }

private const val CHECK_MS = 60_000L
private const val END_LEAD_MS = 6_000L
private const val AFTER_END_CHECK_MS = 12_000L
private const val AFTER_END_WINDOW_MS = 10 * 60_000L
private const val AUTO_RESYNC_MS = 600L
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

/**
 * A mixed playlist running the song [fromKey] into the next one. From a moment before the overlap
 * the screen shows the next song with [lyrics], which has the outgoing song's last lines among its
 * own, and both follow one clock: the next song's.
 *
 * Players report a change of song in the middle of all this, each in its own way and not always
 * with a position that can be believed. So the clock runs on by itself from where the mix says the
 * next song must be, and takes the player's word again whenever the two agree.
 */
private class MixHandoff(
    val fromKey: String,
    val transition: MixTransition,
    private val nextTitle: String,
    val lyrics: Lyrics,
    private var anchorB: Long,
    private var anchorAt: Long,
) {
    /** Whether [track] is the song being mixed in. */
    fun isFor(spotifyId: String?, title: String) =
        spotifyId == transition.trackBId || TextMatch.normalize(title) == nextTitle

    /** What the player's own report says about where the next song is. */
    fun reported(np: NowPlaying, now: Long): Long =
        if (np.trackKey == fromKey) transition.positionB(np.livePosition(now)) else np.livePosition(now)

    /** Where the next song is now. */
    fun position(np: NowPlaying, now: Long): Long {
        val said = reported(np, now)
        if (!np.isPlaying) {
            if (abs(said - anchorB) <= MIX_TRUST_MS) anchorB = said
            anchorAt = now
            return anchorB
        }
        val expected = anchorB + ((now - anchorAt) * transition.speedB).toLong()
        if (abs(said - expected) > MIX_TRUST_MS) return expected
        anchorB = said
        anchorAt = now
        return said
    }

    /** The next song's position at which the outgoing one has faded out. */
    val overlapEndB: Long get() = transition.positionB(transition.startAMs + transition.durationMs)
}

private const val MIX_TAG = "SyllableMix"
/** Stands for "mixed playlists aren't followed" where a playlist id is expected. No id looks like it. */
private const val MIX_OFF = "off"
/** The screen moves on this long before the overlap, which is about what the slide takes. */
private const val MIX_LEAD_MS = 700L
/** A reported position this close to the expected one is believed. */
private const val MIX_TRUST_MS = 1_500L
/** If the outgoing song still plays on its own this long after the overlap, it isn't being mixed. */
private const val MIX_LATE_MS = 4_000L
/** After the overlap the player's own clock takes over again, once it has had time to settle. */
private const val MIX_SETTLE_MS = 8_000L
/** A player that reports another position for this long after moving on is believed over the mix. */
private const val MIX_DISAGREE_MS = 4_500L

/** How long before a song's end the screen moves on to the next one. */
private const val PREROLL_MS = 1_500L
/** If the next song still hasn't started this long after the hand-off, the queue was wrong. */
private const val PREROLL_GIVE_UP_MS = 6_000L

/** A cover fetched ahead for the song [key]. It is shown while the player still reports [previous], the last song's. */
private class EarlyArt(val key: String, val bitmap: Bitmap, val previous: Bitmap?)

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

    private val earlyArt = MutableStateFlow<EarlyArt?>(null)
    private val preroll = MutableStateFlow<Preroll?>(null)

    /** The mix in progress, if any. Set before [preroll], so the lyrics are there when the screen moves on. */
    private var mixHandoff: MixHandoff? = null
    private var mixJob: Job? = null
    private val mix: MixStore = graph.mix
    val mixStatus: StateFlow<MixStore.Status> = mix.status

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
            val isRetry = sameSong && prev.second != r.second && r.first == t.key
            if (!sameSong) _nudge.value = 0
            // A reload keeps the current words on screen until the fresh result arrives.
            val kept = lastLoaded?.takeIf { isRetry && it.first == t.key }?.second
            val query = TrackQuery(t.title, t.artist, t.album, t.durationMs, t.spotifyId)
            // Lyrics fetched ahead of time go straight on screen, without the searching state in between.
            // A song being mixed in comes with the outgoing song's last lines among its own.
            val ready = if (isRetry) null else mixHandoff?.takeIf { it.isFor(t.spotifyId, t.title) }?.lyrics ?: repo.peek(query)
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

    private val _upNext = MutableStateFlow<UpNext?>(null)
    /** What plays after the song on screen. Null for players that don't share their queue. */
    val upNext: StateFlow<UpNext?> = combine(_upNext, track, _mode) { next, t, mode ->
        next?.takeIf { mode == Mode.NOW_PLAYING && t?.key == it.forKey }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

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
                    if (preroll.value == null) {
                        // A mixed playlist starts the next song over this one, well before its end.
                        val t = mixFor(np)
                        val untilMix = if (t != null && pos < t.startAMs + t.durationMs) t.startAMs - MIX_LEAD_MS - pos else null
                        if (np.isPlaying && untilMix != null && untilMix <= 0) {
                            if (!startMix(np, t!!)) delay(400)
                        } else if (np.isPlaying && left <= PREROLL_MS && wordsAreOver()) {
                            preroll.value = prerollFrom(np)
                            since = SystemClock.elapsedRealtime()
                        }
                        delay(minOf(left - PREROLL_MS, untilMix ?: Long.MAX_VALUE).coerceIn(50, 1_000))
                    } else if (mixHandoff != null) {
                        delay(250) // a mix looks after itself, see startMix
                    } else if (
                        // A position back near zero is how players announce the next song, so only
                        // one further in counts as winding back.
                        (left > PREROLL_MS + 1_500 && np.livePosition() > 3_000) ||
                        SystemClock.elapsedRealtime() - since > PREROLL_MS + PREROLL_GIVE_UP_MS
                    ) {
                        // The song was wound back, or something else than the queued song followed.
                        preroll.value = null
                        return@collectLatest
                    } else {
                        delay(250)
                    }
                }
            }
        }
        // Following mixed playlists needs their transitions. Spotify's player says which playlist
        // is playing, so that one is read when it starts. Turning the setting off forgets them all.
        viewModelScope.launch {
            combine(
                settings.state.map { it.mixTransitions }.distinctUntilChanged(),
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

    /** The saved transition from the song that is playing into the queued one, when mixed playlists are followed. */
    private fun mixFor(np: NowPlaying): MixTransition? {
        if (!settings.state.value.mixTransitions) return null
        val a = np.spotifyTrackId ?: return null
        val b = _upNext.value?.takeIf { it.forKey == np.trackKey }?.spotifyId ?: return null
        return mix.find(a, b)
    }

    /**
     * Moves the screen on to the song that [t] mixes in. False when that song's lyrics aren't in
     * yet, in which case nothing has changed.
     */
    private fun startMix(np: NowPlaying, t: MixTransition): Boolean {
        val next = _upNext.value?.takeIf { it.forKey == np.trackKey } ?: return false
        val pre = prerollFrom(np) ?: return false
        val incoming = repo.peek(TrackQuery(next.title, next.artist, spotifyTrackId = next.spotifyId)) ?: return false
        // The song on screen may itself have been mixed in, with the lines of the one before among its own.
        val outgoing = (lyrics.value as? LyricsState.Loaded)?.lyrics?.let { l -> l.copy(lines = l.lines.filterNot { it.outgoing }) }
        val now = SystemClock.elapsedRealtime()
        val handoff = MixHandoff(
            np.trackKey, t, TextMatch.normalize(next.title),
            mixedLyrics(outgoing, incoming, t.startAMs, t.startBMs, t.durationMs, t.speedB),
            anchorB = t.positionB(np.livePosition(now)), anchorAt = now,
        )
        Log.d(MIX_TAG, "mix starts: A at ${np.livePosition(now)}, overlap from ${t.startAMs} for ${t.durationMs}, B from ${t.startBMs}")
        mixHandoff = handoff
        preroll.value = pre
        mixJob?.cancel()
        mixJob = viewModelScope.launch { watchMix(handoff) }
        return true
    }

    /** Ends [handoff] when the mix is over, or calls it off when the player turns out not to be mixing. */
    private suspend fun watchMix(handoff: MixHandoff) {
        val t = handoff.transition
        var movedOn = false
        var disagreeSince = 0L
        try {
            while (true) {
                delay(200)
                val np = nowPlaying.value ?: return
                val now = SystemClock.elapsedRealtime()
                if (np.trackKey == handoff.fromKey) {
                    val a = np.livePosition(now)
                    // Wound back, or still going by itself well after it should have faded out.
                    val woundBack = a in 3_000 until t.startAMs - 3_000
                    val notMixing = np.isPlaying && a > t.startAMs + t.durationMs + MIX_LATE_MS && a < np.durationMs - 500
                    if (woundBack || notMixing) {
                        Log.d(MIX_TAG, "mix called off: A at $a, ${if (woundBack) "wound back" else "still playing alone"}")
                        preroll.value = null
                        return
                    }
                } else if (handoff.isFor(np.spotifyTrackId, np.title)) {
                    if (!movedOn) {
                        movedOn = true
                        Log.d(MIX_TAG, "player moved on: B expected at ${handoff.position(np, now)}, reported ${handoff.reported(np, now)}, overlap ends at ${handoff.overlapEndB}")
                    }
                    val at = handoff.position(np, now)
                    if (at > handoff.overlapEndB + MIX_SETTLE_MS) return
                    // A player that keeps insisting on another position knows better: most likely
                    // it didn't mix, and started the song from the top.
                    if (abs(handoff.reported(np, now) - at) <= MIX_TRUST_MS || !np.isPlaying) disagreeSince = 0L
                    else if (disagreeSince == 0L) disagreeSince = now
                    else if (now - disagreeSince > MIX_DISAGREE_MS) {
                        Log.d(MIX_TAG, "player disagrees: reported ${handoff.reported(np, now)}, expected $at")
                        return
                    }
                } else {
                    return // some other song
                }
            }
        } finally {
            if (mixHandoff === handoff) mixHandoff = null
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
                val mixing = mixHandoff
                when {
                    np == null -> 0
                    // During a mix the lyrics follow the next song, wherever the player says it is.
                    mixing != null && (np.trackKey == mixing.fromKey || mixing.isFor(np.spotifyTrackId, np.title)) ->
                        mixing.position(np, now)
                    // Ahead of the next song the clock counts down to its start.
                    preroll.value?.fromKey == np.trackKey -> np.livePosition(now) - np.durationMs
                    else -> np.livePosition(now)
                }
            }
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
        // The lines on screen belong to the song that hasn't started yet.
        if (_mode.value == Mode.NOW_PLAYING && (preroll.value != null || mixHandoff != null)) return
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
        relistenJob?.cancel()
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
     * timing corrected, a different one takes over. Returns false when the mic isn't allowed, so
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
        if (cur == null || _mode.value != Mode.RECOGNIZED || !sameSong(cur, found)) {
            showRecognizedLyrics(found)
            viewModelScope.launch { useSpotifyCover(found) }
            return ListenNote.NEW_SONG
        }
        val now = SystemClock.elapsedRealtime()
        val shown = if (_clockPaused.value) pausedAtPosition else cur.positionMs + (now - cur.anchorRealtime)
        val heard = found.positionMs + (now - found.anchorRealtime)
        // Each match is only good to a fraction of a second, so the automatic check leaves small
        // differences alone instead of making the lyrics twitch every minute.
        if (manual || abs(heard - shown) > AUTO_RESYNC_MS) {
            _clockPaused.value = false
            _recognized.value = cur.copy(positionMs = found.positionMs, anchorRealtime = found.anchorRealtime)
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
