package dev.kamol1dn.syllable

import dev.kamol1dn.syllable.lyrics.Lyrics
import dev.kamol1dn.syllable.lyrics.SyncType
import dev.kamol1dn.syllable.lyrics.TextMatch
import dev.kamol1dn.syllable.lyrics.joinMixed
import dev.kamol1dn.syllable.spotify.MixTransition

/**
 * A run of songs shown as one song that keeps going: when the player moves from one to the next,
 * the lyrics don't change over, the next song's lines just follow in the same list. Only the
 * cover, the name and the background say that the song has changed.
 *
 * All lines are on one clock, the first song's. Each later song is [Song.shiftMs] ahead of it:
 * the list's time is that song's own position plus its shift. A song that is mixed in comes in
 * part-way, so its lines from before that point aren't in the list.
 *
 * Objects of this class don't change. Joining a song on gives a new one with one more song at the
 * end and the same lines up to there, so the list on screen only grows. The last join can be taken
 * back with [upTo] and made again, for when the player does something else than was planned.
 */
class MixSession private constructor(
    /** The first song's own lyrics, as they were on screen before anything was joined to them. */
    val anchor: Lyrics,
    val songs: List<Song>,
    val lyrics: Lyrics,
    /** The run as it was before its last song was joined on. */
    private val before: MixSession?,
) {
    /** How a song came to follow the one before it. */
    enum class Join {
        /** The song the run began with. */
        FIRST,
        /** By a transition saved in the playlist, known before it happens. */
        SAVED,
        /** Planned for when the song before has ended, there being nothing that says otherwise. */
        AFTER,
        /** Where the player was found to be when it moved on. */
        HEARD,
    }

    class Song(
        val spotifyId: String,
        private val titleKey: String,
        val shiftMs: Long,
        /** How the song before runs into this one. Null for the first song. */
        val into: MixTransition?,
        val join: Join,
    ) {
        /** By id, or by title, since Spotify may play the same song under another id. */
        fun matches(spotifyId: String?, title: String) =
            spotifyId == this.spotifyId || TextMatch.normalize(title) == titleKey
    }

    /** Which song of the run this is, or -1. The latest match counts, in case a song comes twice. */
    fun indexOf(spotifyId: String?, title: String): Int = songs.indexOfLast { it.matches(spotifyId, title) }

    /**
     * The run as it was when its song number [index] was the last one. Only the last join can be
     * taken back, so this is null for any song before the second to last.
     */
    fun upTo(index: Int): MixSession? = when (index) {
        songs.lastIndex -> this
        songs.lastIndex - 1 -> before
        else -> null
    }

    /**
     * The run with the song [spotifyId] joined on, which [transition] mixes the last song into.
     * Null when its [lyrics] have no timing to line up, or none of them are left to sing.
     */
    fun add(spotifyId: String, title: String, lyrics: Lyrics, transition: MixTransition): MixSession? =
        join(spotifyId, title, lyrics, transition, Join.SAVED)

    /**
     * The run with the song [spotifyId] joined on from its top, to start when the last song, which
     * is [lastDurationMs] long, has ended. This is the plan while nothing is known of a mix.
     */
    fun addAfter(spotifyId: String, title: String, lyrics: Lyrics, lastDurationMs: Long): MixSession? =
        join(spotifyId, title, lyrics, MixTransition(songs.last().spotifyId, spotifyId, lastDurationMs, 0, NO_OVERLAP_MS, 1f, false), Join.AFTER)

    /**
     * The run with the song [spotifyId] joined on where the player was found to be when it moved
     * on to it: the last song was at [atLastMs] of its own time and the new one at [atNewMs] of
     * its own. That covers a mix nothing was known about, a song that simply ended, and a skip.
     * The new song's lines follow from where it is, and the last song's lines that hadn't begun
     * yet are left out. With [cut] the last song stopped there and then, as when it is skipped by
     * hand, so the line it was in the middle of ends too instead of being sung out.
     */
    fun addHeard(spotifyId: String, title: String, lyrics: Lyrics, atLastMs: Long, atNewMs: Long, cut: Boolean = false): MixSession? =
        join(spotifyId, title, lyrics, MixTransition(songs.last().spotifyId, spotifyId, atLastMs, atNewMs, NO_OVERLAP_MS, 1f, false), Join.HEARD, cut)

    private fun join(spotifyId: String, title: String, lyrics: Lyrics, t: MixTransition, how: Join, cut: Boolean = false): MixSession? {
        val last = songs.last()
        // At the start of the overlap both songs are at the same moment of the list's clock.
        val shift = last.shiftMs + t.startAMs - t.startBMs
        val joined = joinMixed(
            soFar = this.lyrics,
            incoming = lyrics,
            part = songs.size,
            startBMs = t.startBMs,
            shiftMs = shift,
            overlapEndMs = last.shiftMs + t.startAMs + t.durationMs,
            // A song found already playing may be in the middle of a line.
            underWay = how == Join.HEARD,
            cutAtMs = if (cut) last.shiftMs + t.startAMs else null,
        ) ?: return null
        // One step back is all that is ever needed, and keeping more would keep every list there was.
        val back = if (before == null) this else MixSession(anchor, songs, this.lyrics, null)
        return MixSession(anchor, songs + Song(spotifyId, TextMatch.normalize(title), shift, t, how), joined, back)
    }

    companion object {
        /**
         * The overlap of a join that has none to speak of: the songs follow each other, or how
         * long they overlap isn't known. This keeps the last song's lines up to the join and none after.
         */
        private const val NO_OVERLAP_MS = 1_000L

        /** A run that so far is only the song [spotifyId], with its [lyrics]. Null when they have no timing. */
        fun start(spotifyId: String, title: String, lyrics: Lyrics): MixSession? {
            if (lyrics.sync == SyncType.NONE) return null
            // A song that was itself the end of an earlier run starts over as part 0.
            val own = if (lyrics.lines.any { it.part != 0 }) lyrics.copy(lines = lyrics.lines.map { it.copy(part = 0) }) else lyrics
            return MixSession(lyrics, listOf(Song(spotifyId, TextMatch.normalize(title), 0, null, Join.FIRST)), own, null)
        }
    }
}
