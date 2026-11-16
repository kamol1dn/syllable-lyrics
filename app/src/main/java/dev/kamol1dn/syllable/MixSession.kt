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

    /**
     * The run with the song [spotifyId] joined on where the player was heard to bring it in, for a
     * mix nothing was known about beforehand. The last song was at [atLastMs] of its own time when
     * the new one was at [atNewMs] of its own. The new song's lines follow from where it is, and
     * the last song's lines that hadn't begun yet are left out, since it is on its way out.
     */
    fun addHeard(spotifyId: String, title: String, lyrics: Lyrics, atLastMs: Long, atNewMs: Long): MixSession? =
        add(spotifyId, title, lyrics, MixTransition(songs.last().spotifyId, spotifyId, atLastMs, atNewMs, HEARD_OVERLAP_MS, 1f, false))

    companion object {
        /**
         * How long the overlap of a mix that was only heard counts as lasting from the change of
         * song. Its real length isn't known; this one keeps the lines that had begun and no others.
         */
        private const val HEARD_OVERLAP_MS = 1_000L

        /** A run that so far is only the song [spotifyId], with its [lyrics]. Null when they have no timing. */
        fun start(spotifyId: String, title: String, lyrics: Lyrics): MixSession? {
            if (lyrics.sync == SyncType.NONE) return null
            // A song that was itself the end of an earlier run starts over as part 0.
            val own = if (lyrics.lines.any { it.part != 0 }) lyrics.copy(lines = lyrics.lines.map { it.copy(part = 0) }) else lyrics
            return MixSession(lyrics, listOf(Song(spotifyId, TextMatch.normalize(title), 0, null)), own)
        }
    }
}

/** A new song that is already this far in when the player announces it was playing under the old one. */
private const val HEARD_UNDER_MS = 250L
/** A song that is left with at least this much to go didn't simply end. */
private const val CUT_SHORT_MS = 1_000L
/** Mixes come towards the end of a song. A song left with more than this to go was skipped. */
private const val MIX_TAIL_MS = 60_000L

/**
 * Whether a change of song looks like the player mixing one song into the next, for a mix nothing
 * was known about beforehand. It shows in one of two ways: the new song was already [newAtMs] in
 * when it was announced, having come in under the old one, or the old one was left with
 * [oldLeftMs] still to go near its end (null when its length isn't known). A song skipped by hand
 * starts the next from its top, mostly far from the end, and a song that ends has nothing left.
 */
fun looksMixed(newAtMs: Long, oldLeftMs: Long?): Boolean =
    newAtMs >= HEARD_UNDER_MS || (oldLeftMs != null && oldLeftMs in CUT_SHORT_MS..MIX_TAIL_MS)
