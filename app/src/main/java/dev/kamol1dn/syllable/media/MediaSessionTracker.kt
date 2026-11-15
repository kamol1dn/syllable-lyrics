package dev.kamol1dn.syllable.media

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat
import dev.kamol1dn.syllable.SyllableApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Exists only so the user can grant notification access, which unlocks the active media sessions. */
class MediaNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        (application as? SyllableApp)?.media?.refresh()
    }
}

data class NowPlaying(
    val packageName: String,
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Long,
    val artwork: Bitmap?,
    val spotifyTrackId: String?,
    val isPlaying: Boolean,
    /** Position at [updateRealtime] and the playback speed, for extrapolating the live position. */
    val positionMs: Long,
    val updateRealtime: Long,
    val speed: Float,
    val canSeek: Boolean,
    /** Playing on another device through Cast or Spotify Connect, not from this phone. */
    val isRemote: Boolean = false,
) {
    fun livePosition(now: Long = SystemClock.elapsedRealtime()): Long {
        val p = if (isPlaying) positionMs + ((now - updateRealtime) * speed).toLong() else positionMs
        // A player that stops reporting at the end of a track would otherwise run past it.
        return if (durationMs > 0) p.coerceIn(0, durationMs) else p.coerceAtLeast(0)
    }

    val trackKey: String get() = "$packageName|$artist|$title"
}

/** Follows the most relevant active media session (any player that posts a media notification). */
class MediaSessionTracker(private val context: Context) {

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()

    private val _hasAccess = MutableStateFlow(false)
    val hasAccess: StateFlow<Boolean> = _hasAccess.asStateFlow()

    private val handler = Handler(Looper.getMainLooper())
    private val manager = context.getSystemService(MediaSessionManager::class.java)
    private val listenerComponent = ComponentName(context, MediaNotificationListener::class.java)
    private var controllers: List<MediaController> = emptyList()
    private var active: MediaController? = null
    private var listening = false

    /** The playback-state timestamp last seen for the current track, and when that track appeared. */
    private var trackKey: String? = null
    private var trackSince = 0L
    private var stateTimeBeforeTrack = 0L

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        attach(list.orEmpty())
    }

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            // Another session may have started playing; re-pick.
            pickActive()
            publish()
        }
        override fun onSessionDestroyed() = refresh()
    }

    fun checkAccess(): Boolean {
        val granted = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
        _hasAccess.value = granted
        return granted
    }

    fun refresh() {
        handler.post {
            if (!checkAccess()) {
                _nowPlaying.value = null
                return@post
            }
            try {
                if (!listening) {
                    manager.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent, handler)
                    listening = true
                }
                attach(manager.getActiveSessions(listenerComponent))
            } catch (_: SecurityException) {
                _hasAccess.value = false
            }
        }
    }

    private fun attach(list: List<MediaController>) {
        controllers.forEach { runCatching { it.unregisterCallback(callback) } }
        controllers = list
        controllers.forEach { it.registerCallback(callback, handler) }
        pickActive()
        publish()
    }

    private fun pickActive() {
        val playing = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        active = playing ?: active?.takeIf { a -> controllers.any { it.sessionToken == a.sessionToken } }
            ?: controllers.firstOrNull()
    }

    private fun publish() {
        val c = active
        val md = c?.metadata
        if (c == null || md == null) {
            _nowPlaying.value = null
            return
        }
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        if (title.isNullOrBlank()) {
            _nowPlaying.value = null
            return
        }
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: ""
        val st = c.playbackState
        val art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val mediaId = md.getString(MediaMetadata.METADATA_KEY_MEDIA_ID).orEmpty()
        val spotifyId = SPOTIFY_URI.find(mediaId)?.groupValues?.get(1)
        val prev = _nowPlaying.value
        val now = SystemClock.elapsedRealtime()
        val stateTime = st?.lastPositionUpdateTime ?: 0L

        // Players often announce the next track before they reset the position, so for a moment
        // the new title comes with the old track's position and the lyrics would jump to the end.
        // Until a fresh playback state arrives, a position reported well before the track
        // appeared counts from zero instead.
        val key = "${c.packageName}|$artist|$title"
        if (key != trackKey) {
            stateTimeBeforeTrack = if (trackKey != null && prev?.packageName == c.packageName) stateTime else 0L
            trackKey = key
            trackSince = now
        }
        val stale = stateTimeBeforeTrack != 0L && stateTime == stateTimeBeforeTrack &&
            stateTime < trackSince - STALE_STATE_MS && now - trackSince < STALE_WINDOW_MS
        if (!stale) stateTimeBeforeTrack = 0L
        _nowPlaying.value = NowPlaying(
            packageName = c.packageName,
            title = title,
            artist = artist,
            album = md.getString(MediaMetadata.METADATA_KEY_ALBUM),
            durationMs = md.getLong(MediaMetadata.METADATA_KEY_DURATION),
            // Keep the previous bitmap object when unchanged so the UI doesn't recompute colours.
            artwork = if (prev != null && prev.title == title && prev.artwork?.sameAs(art) == true) prev.artwork else art,
            spotifyTrackId = spotifyId,
            isPlaying = st?.state == PlaybackState.STATE_PLAYING,
            positionMs = if (stale) 0L else st?.position ?: 0L,
            updateRealtime = if (stale) trackSince else stateTime.takeIf { it > 0 } ?: now,
            speed = st?.playbackSpeed?.takeIf { it > 0f } ?: 1f,
            canSeek = st != null && (st.actions and PlaybackState.ACTION_SEEK_TO) != 0L,
            isRemote = c.playbackInfo.playbackType == MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE,
        )
    }

    /** The player's name as shown in the launcher, or its package name if it can't be read. */
    fun appName(packageName: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    fun playPause() {
        val c = active ?: return
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause()
        else c.transportControls.play()
    }

    fun next() = active?.transportControls?.skipToNext()
    fun previous() = active?.transportControls?.skipToPrevious()
    fun seekTo(ms: Long) = active?.transportControls?.seekTo(ms.coerceAtLeast(0))

    fun openPlayerApp(): Boolean {
        val pkg = active?.packageName ?: return false
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return true
    }

    private companion object {
        val SPOTIFY_URI = Regex("""spotify:track:([A-Za-z0-9]{22})""")
        /** A playback state this much older than the track it arrived with belongs to the track before. */
        const val STALE_STATE_MS = 1_000L
        /** Stop second-guessing the player if it never sends a new state. */
        const val STALE_WINDOW_MS = 4_000L
    }
}
