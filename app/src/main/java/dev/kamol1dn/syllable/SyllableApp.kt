package dev.kamol1dn.syllable

import android.app.Application
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.platform.WindowRecomposerPolicy
import androidx.compose.ui.platform.createLifecycleAwareWindowRecomposer
import dev.kamol1dn.syllable.lyrics.LyricsRepository
import dev.kamol1dn.syllable.media.AudioOutputTracker
import dev.kamol1dn.syllable.media.MediaSessionTracker
import dev.kamol1dn.syllable.spotify.MixStore
import dev.kamol1dn.syllable.spotify.SpotifyAuth
import dev.kamol1dn.syllable.ui.warmDefaultCover

private object OwnAnimationSpeed : MotionDurationScale {
    override val scaleFactor = 1f
}

class SyllableApp : Application() {
    lateinit var settings: Settings
        private set
    lateinit var media: MediaSessionTracker
        private set
    lateinit var lyrics: LyricsRepository
        private set
    lateinit var audio: AudioOutputTracker
        private set
    lateinit var mix: MixStore
        private set

    @OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
    override fun onCreate() {
        super.onCreate()
        // The lyrics are the app, and they are motion. With animations turned off in the phone's
        // developer or accessibility settings, Compose would snap every fade and slide, so the
        // app keeps its own animation speed instead of following that setting.
        WindowRecomposerPolicy.setFactory { root -> root.createLifecycleAwareWindowRecomposer(OwnAnimationSpeed) }
        settings = Settings(this)
        media = MediaSessionTracker(this)
        audio = AudioOutputTracker(this)
        lyrics = LyricsRepository(this, settings)
        SpotifyAuth.init(this)
        mix = MixStore(this)
        // Blur the stand-in background now, so the first screen doesn't wait for it.
        Thread(::warmDefaultCover).start()
    }
}
