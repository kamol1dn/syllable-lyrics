package dev.kamol1dn.syllable

import dev.kamol1dn.syllable.lyrics.Lyrics
import dev.kamol1dn.syllable.lyrics.SyncType
import dev.kamol1dn.syllable.lyrics.TextMatch
import dev.kamol1dn.syllable.lyrics.joinMixed
import dev.kamol1dn.syllable.spotify.MixTransition

/**
 * A run of songs from a mixed playlist, shown as one song that keeps going: when one is mixed
 * into the next, the lyrics don't change over, the next song's lines just follow in the same
 * list. Only the cover, the name and the background say that the song has changed.
 *
 * All lines are on one clock, the first song's. Each later song is [Song.shiftMs] ahead of it:
 * the list's time is that song's own position plus its shift. A song comes in where its
 * transition says, so its lines from before that point aren't in the list.
 *
 * Objects of this class don't change. [add] gives a new one with one more song at the end and the
 * same lines up to there, so the list on screen only grows.
 */
class MixSession private constructor(
    /** The first song's own lyrics, as they were on screen before anything was joined to them. */
    val anchor: Lyrics,
    val songs: List<Song>,
    val lyrics: Lyrics,
) {
    class Song(
        val spotifyId: String,
        private val titleKey: String,
        val shiftMs: Long,
        /** How the song before runs into this one. Null for the first song. */
        val into: MixTransition?,
    ) {
        /** By id, or by title, since Spotify may play the same song under another id. */
        fun matches(spotifyId: String?, title: String) =
            spotifyId == this.spotifyId || TextMatch.normalize(title) == titleKey
    }

    /** Which song of the run this is, or -1. The latest match counts, in case a song comes twice. */
    fun indexOf(spotifyId: String?, title: String): Int = songs.indexOfLast { it.matches(spotifyId, title) }

    /**
     * The run with the song [spotifyId] joined on, which [transition] mixes the last song into.
     * Null when its [lyrics] have no timing to line up.
     */
    fun add(spotifyId: String, title: String, lyrics: Lyrics, transition: MixTransition): MixSession? {
        val last = songs.last()
        // At the start of the overlap both songs are at the same moment of the list's clock.
        val shift = last.shiftMs + transition.startAMs - transition.startBMs
        val joined = joinMixed(
            soFar = this.lyrics,
            incoming = lyrics,
            part = songs.size,
            startBMs = transition.startBMs,
            shiftMs = shift,
            overlapEndMs = last.shiftMs + transition.startAMs + transition.durationMs,
        ) ?: return null
        return MixSession(anchor, songs + Song(spotifyId, TextMatch.normalize(title), shift, transition), joined)
    }

    companion object {
        /** A run that so far is only the song [spotifyId], with its [lyrics]. Null when they have no timing. */
        fun start(spotifyId: String, title: String, lyrics: Lyrics): MixSession? {
            if (lyrics.sync == SyncType.NONE) return null
            // A song that was itself the end of an earlier run starts over as part 0.
            val own = if (lyrics.lines.any { it.part != 0 }) lyrics.copy(lines = lyrics.lines.map { it.copy(part = 0) }) else lyrics
            return MixSession(lyrics, listOf(Song(spotifyId, TextMatch.normalize(title), 0, null)), own)
        }
    }
}
