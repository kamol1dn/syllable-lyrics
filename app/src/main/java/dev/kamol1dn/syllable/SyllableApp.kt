package dev.kamol1dn.syllable

import android.app.Application
import dev.kamol1dn.syllable.lyrics.LyricsRepository
import dev.kamol1dn.syllable.media.MediaSessionTracker
import dev.kamol1dn.syllable.spotify.SpotifyAuth

class SyllableApp : Application() {
    lateinit var settings: Settings
        private set
    lateinit var media: MediaSessionTracker
        private set
    lateinit var lyrics: LyricsRepository
        private set

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        media = MediaSessionTracker(this)
        lyrics = LyricsRepository(this, settings)
        SpotifyAuth.init(this)
    }
}
