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

private const val MIX_TAG = "SyllableMix"
/** Stands for "mixed playlists aren't followed" where a playlist id is expected. No id looks like it. */
private const val MIX_OFF = "off"
/** A reported position this close to where the mix says the next song should be is believed. */
private const val MIX_TRUST_MS = 3_000L
/** How long the player gets after a change of song to report a position that fits the mix. */
private const val MIX_GRACE_MS = 1_500L
/** If the outgoing song still plays on its own this long after the overlap, it isn't being mixed. */
private const val MIX_LATE_MS = 2_500L
/** How long a tap on a line of the next song waits for the player to get there. */
private const val MIX_SEEK_WAIT_MS = 5_000L
/** How long a change of song waits for the player to say where the new song is. */
private const val MIX_REPORT_WAIT_MS = 1_500L
/** A new song reported this close to where the old one would be by now still has the old one's position. */
private const val MIX_SAME_MS = 1_000L
/** A song isn't mixed out this early on. A position like that is the player winding back before it moves on. */
private const val MIX_EARLIEST_MS = 5_000L

/**
 * A change of song that may be the player mixing, with nothing known about it beforehand. Until
 * the new song (track key [toKey]) has said where it is, the list of [run] stays on screen and
 * keeps the time of [before], the song that was playing, so nothing moves if the list then goes on.
 */
private class MixWatch(val run: MixSession, val before: NowPlaying, val toKey: String) {
    /** Where [before] would be by now, on its own clock. */
    fun songMs(now: Long): Long = before.positionMs + ((now - before.updateRealtime) * before.speed).toLong()

    /** The same on the list's clock. */
    fun listMs(now: Long): Long = songMs(now) + run.songs.last().shiftMs

    /** Whether the position [np] reports is still the one of [before], carried over to the new song's name. */
    fun isCarriedOver(np: NowPlaying, now: Long): Boolean =
        abs(np.positionMs + ((now - np.updateRealtime) * np.speed).toLong() - songMs(now)) < MIX_SAME_MS
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

    /** The run of mixed songs on screen as one list, if the song that is playing is part of one. */
    private val mixSession = MutableStateFlow<MixSession?>(null)
    private val mixWatch = MutableStateFlow<MixWatch?>(null)
    private var pendingSeek: PendingSeek? = null
    private var seekJob: Job? = null
    private var mixCheck: Job? = null
    /** The song whose last moments the screen spent on the next one, until the player has moved on from it. */
    private var prerolledFrom: String? = null
    /** The [MixTransition.pair] the player turned out not to mix, so it isn't joined again while its songs play. */
    private var notMixed: String? = null
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

    /** The lyrics of the song on screen by itself. */
    private val ownLyrics: StateFlow<LyricsState> = combine(
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
            // A song that may be mixed in keeps the list it arrived under until that is settled.
            watch != null && t.key == watch.toKey -> LyricsState.Loaded(watch.run.lyrics, final = true, key = watch.run.anchor)
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
                    val into = joinedNext(np)
                    if (into != null) {
                        // The next song's lines already follow in the same list, so there is
                        // nothing to move on to. Unless the player doesn't mix after all, and this
                        // song is still going by itself well after it should have faded out.
                        val gone = into.startAMs + into.durationMs + MIX_LATE_MS
                        if (np.isPlaying && pos > gone && left > 500) {
                            endMixSession("still playing alone at $pos", unmixed = into)
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
            combine(ownLyrics, _upNext, mix.status, settings.state.map { it.mixTransitions }.distinctUntilChanged()) { _, _, _, _ -> }
                .collect { updateMixSession() }
        }
        // Whether the player really mixes two songs only shows when it moves from one to the other.
        // That is looked at first, while the run is still as it was for the song before.
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

    /** How the song that is playing runs into the next one, when that one's lines are joined on in the list. */
    private fun joinedNext(np: NowPlaying): MixTransition? {
        val run = mixSession.value ?: return null
        val at = run.indexOf(np.spotifyTrackId, np.title)
        return if (at >= 0) run.songs.getOrNull(at + 1)?.into else null
    }

    /**
     * Brings the run of mixed songs up to date with what is playing and what is queued: starts
     * one from the song that is playing, and joins the next song on once its transition and its
     * lyrics are known. Cheap, and safe to call whenever any of that may have changed.
     */
    private fun updateMixSession() {
        val np = nowPlaying.value
        val id = np?.spotifyTrackId
        if (np == null || id == null || !settings.state.value.mixTransitions) {
            mixSession.value = null
            return
        }
        val next = _upNext.value?.takeIf { it.forKey == np.trackKey }
        var run = mixSession.value
        var at = run?.indexOf(id, np.title) ?: -1
        // The queue was changed, and the song that was joined on won't be the next one after all.
        if (run != null && at >= 0 && at < run.songs.lastIndex && next?.spotifyId != null &&
            !run.songs[at + 1].matches(next.spotifyId, next.title)
        ) at = -1
        if (run == null || at < 0) {
            // A new run starts from the song's own lyrics, once they are in.
            val own = (ownLyrics.value as? LyricsState.Loaded)?.takeIf { it.final && lastLoaded?.first == np.trackKey }?.lyrics
            run = own?.let { MixSession.start(id, np.title, it) }
            at = 0
        }
        val nextId = next?.spotifyId
        if (run != null && at == run.songs.lastIndex && nextId != null) {
            val t = (mix.find(run.songs[at].spotifyId, nextId) ?: mix.find(id, nextId))?.takeIf { it.pair != notMixed }
            val words = if (t == null) null else repo.peek(TrackQuery(next.title, next.artist, spotifyTrackId = nextId))
            if (t != null && words != null) {
                run.add(nextId, next.title, words, t)?.let {
                    Log.d(MIX_TAG, "joined on: overlap from ${t.startAMs} for ${t.durationMs}, next from ${t.startBMs}, ${it.songs.size} songs in the run")
                    run = it
                }
            }
        }
        // A run of one song is just that song.
        mixSession.value = run?.takeIf { it.songs.size > 1 }
    }

    /**
     * Drops the run, so the song on screen is shown by itself again. [unmixed] is the transition
     * the player turned out not to follow, which then isn't joined again while its songs play.
     */
    private fun endMixSession(why: String, unmixed: MixTransition? = null) {
        if (mixSession.value == null) return
        Log.d(MIX_TAG, "run ended: $why")
        if (unmixed != null) notMixed = unmixed.pair
        mixSession.value = null
        updateMixSession()
    }

    /**
     * The player went from [before] to [np]. If that is the step a mixed run expects, the run
     * goes on only if the new song is about where the transition says it would be: a song that
     * was skipped to by hand, or that the player started from the top instead of mixing, is not
     * the continuation the list shows.
     */
    private fun songChanged(before: NowPlaying, np: NowPlaying) {
        mixCheck?.cancel()
        mixWatch.value = null
        val prerolled = prerolledFrom == before.trackKey
        prerolledFrom = null
        val run = mixSession.value
        val to = run?.indexOf(np.spotifyTrackId, np.title) ?: -1
        if (run == null || to < 0) {
            // Not a song the list has. It starts over from it, unless the player is mixing into it.
            if (!prerolled) watchForMix(before, np, run)
            return
        }
        val from = run.indexOf(before.spotifyTrackId, before.title)
        if (pendingSeek?.index == to) return // sent there by a tap, which sets the position itself
        val into = run.songs[to].into
        if (from < 0 || to != from + 1 || into == null) {
            endMixSession("moved to another song of the run")
            return
        }
        val now = SystemClock.elapsedRealtime()
        val left = before.livePosition(now)
        if (left < into.startAMs - MIX_TRUST_MS) {
            endMixSession("skipped at $left, before the overlap at ${into.startAMs}")
            return
        }
        // The first report after a change of song isn't always right, so it gets a moment.
        mixCheck = viewModelScope.launch {
            delay(MIX_GRACE_MS)
            val later = nowPlaying.value?.takeIf { it.trackKey == np.trackKey } ?: return@launch
            val t = SystemClock.elapsedRealtime()
            val expected = into.startBMs + (left - into.startAMs) + (t - now)
            val reported = later.livePosition(t)
            Log.d(MIX_TAG, "player moved on: next song expected at $expected, reported $reported")
            if (later.isPlaying && abs(reported - expected) > MIX_TRUST_MS) endMixSession("the player didn't mix", unmixed = into)
        }
    }

    /**
     * The player went from [before] to a song the list on screen ([shown], if it is a run) doesn't
     * have. Spotify also mixes songs on the spot, with no transition to read beforehand, and then
     * the only sign is how the change of song looks: see [looksMixed]. The list holds still until
     * the new song has said where it is. Then it either goes on into that song's lines, from where
     * the two songs are, or makes way for them as for any other song.
     */
    private fun watchForMix(before: NowPlaying, np: NowPlaying, shown: MixSession?) {
        if (!settings.state.value.mixTransitions || _mode.value != Mode.NOW_PLAYING) return
        val beforeId = before.spotifyTrackId ?: return
        val id = np.spotifyTrackId ?: return
        val since = SystemClock.elapsedRealtime()
        fun no(why: String) {
            Log.d(MIX_TAG, "song changed at ${before.livePosition(since)} of ${before.durationMs}, list starts over: $why")
        }
        // A player that stops to load the new song was sent there by hand.
        if (!before.isPlaying || !np.isPlaying) return no("not playing through")
        val run = (
            if (shown != null) {
                shown.takeIf { it.indexOf(beforeId, before.title) == it.songs.lastIndex }
            } else {
                repo.peek(TrackQuery(before.title, before.artist, spotifyTrackId = beforeId))?.let { MixSession.start(beforeId, before.title, it) }
            }
        ) ?: return no("nothing to go on from")
        val words = repo.peek(TrackQuery(np.title, np.artist, spotifyTrackId = id)) ?: return no("the new song's lyrics aren't in yet")
        val watch = MixWatch(run, before, np.trackKey)
        if (watch.songMs(since) < MIX_EARLIEST_MS) return no("too early in the song")
        mixWatch.value = watch
        mixCheck = viewModelScope.launch {
            try {
                // The new song's name tends to arrive before its position does.
                val later = withTimeoutOrNull(MIX_REPORT_WAIT_MS) {
                    nowPlaying.first {
                        it == null || it.trackKey != np.trackKey || !it.isPlaying ||
                            (it.positionFresh && !watch.isCarriedOver(it, SystemClock.elapsedRealtime()))
                    }
                }?.takeIf { it.trackKey == np.trackKey && it.isPlaying }
                val now = SystemClock.elapsedRealtime()
                val newAt = later?.livePosition(now)
                // Both as they were when the song changed.
                val newAtChange = newAt?.let { it - (now - since) }
                val left = before.durationMs.takeIf { it > 0 }?.let { it - watch.songMs(since) }
                val joined = if (newAt != null && newAtChange != null && looksMixed(newAtChange, left)) {
                    run.addHeard(id, np.title, words, watch.songMs(now), newAt)
                } else null
                Log.d(
                    MIX_TAG,
                    "song changed at ${watch.songMs(since)} of ${before.durationMs}, new song " +
                        (if (newAtChange == null) "gave no position" else "at $newAtChange") +
                        if (joined != null) ": mixed, ${joined.songs.size} songs in the run" else ": list starts over",
                )
                if (joined != null) mixSession.value = joined
            } finally {
                if (mixWatch.value === watch) mixWatch.value = null
            }
            updateMixSession()
        }
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
                    // A song that may be mixed in hasn't said where it is yet, so the list keeps its time.
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
