package dev.kamol1dn.syllable

import android.app.Application
import dev.kamol1dn.syllable.lyrics.LyricsRepository
import dev.kamol1dn.syllable.media.AudioOutputTracker
import dev.kamol1dn.syllable.media.MediaSessionTracker
import dev.kamol1dn.syllable.spotify.SpotifyAuth
import dev.kamol1dn.syllable.ui.warmDefaultCover

class SyllableApp : Application() {
    lateinit var settings: Settings
        private set
    lateinit var media: MediaSessionTracker
        private set
    lateinit var lyrics: LyricsRepository
        private set
    lateinit var audio: AudioOutputTracker
        private set

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        media = MediaSessionTracker(this)
        audio = AudioOutputTracker(this)
        lyrics = LyricsRepository(this, settings)
        SpotifyAuth.init(this)
        // Blur the stand-in background now, so the first screen doesn't wait for it.
        Thread(::warmDefaultCover).start()
    }
}
