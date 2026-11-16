package dev.kamol1dn.syllable.lyrics

/** A line of the outgoing song that would only begin this close to the end of the overlap is too faint to show. */
private const val TOO_LATE_MS = 1_000L
/** A line of the incoming song that began this shortly before the song comes in is still worth showing. */
private const val LEAD_IN_MS = 300L

/**
 * Joins the lyrics of a song that is mixed in onto the end of [soFar], so the two read as one
 * song that simply goes on. [soFar] may itself be several songs joined this way; its last song is
 * the one being mixed out, and its lines keep their clock.
 *
 * The incoming song becomes part number [part]. It comes in at [startBMs] of its own time, which
 * is [shiftMs] earlier than the same moment on the list's clock, so its lines move by that much
 * and whatever it has before [startBMs] is left out, never being played. The outgoing song is
 * gone at [overlapEndMs] on the list's clock. A line of it that has begun by then is kept whole,
 * since half a line reads worse than a line that outlasts its voice by a moment. During the
 * overlap the lines of both songs simply alternate by time.
 *
 * With [underWay] the incoming song was found already playing at [startBMs] rather than planned
 * to come in there, so a line it is in the middle of is kept as well.
 *
 * Without timing on both sides there is nothing to line up, and null comes back. So it does when
 * the incoming song has no lines left to add, since then there is nothing to go on to.
 */
fun joinMixed(
    soFar: Lyrics,
    incoming: Lyrics,
    part: Int,
    startBMs: Long,
    shiftMs: Long,
    overlapEndMs: Long,
    underWay: Boolean = false,
): Lyrics? {
    if (soFar.sync == SyncType.NONE || incoming.sync == SyncType.NONE) return null
    fun List<LyricWord>.shifted() = map { it.copy(startMs = it.startMs + shiftMs, endMs = it.endMs + shiftMs) }

    val sync = if (soFar.sync == SyncType.WORD || incoming.sync == SyncType.WORD) SyncType.WORD else SyncType.LINE
    // Line-timed lines among word-timed ones are marked, so they still light up as a whole.
    val markOld = sync == SyncType.WORD && soFar.sync == SyncType.LINE
    val markNew = sync == SyncType.WORD && incoming.sync == SyncType.LINE
    val kept = soFar.lines
        .filterNot { it.part == part - 1 && it.startMs >= overlapEndMs - TOO_LATE_MS }
        .map { if (markOld) it.copy(lineTimed = true) else it }
    val added = incoming.lines
        .filter { (it.startMs >= startBMs - LEAD_IN_MS || (underWay && it.endMs > startBMs)) && it.words.isNotEmpty() }
        .map { line ->
            line.copy(
                startMs = line.startMs + shiftMs,
                endMs = line.endMs + shiftMs,
                words = line.words.shifted(),
                background = line.background.shifted(),
                part = part,
                lineTimed = markNew || line.lineTimed,
            )
        }
    if (added.isEmpty()) return null
    // The sort is stable, so where two lines start together the outgoing song's comes first.
    return Lyrics((kept + added).sortedBy { it.startMs }, sync, incoming.source, incoming.credits)
}
