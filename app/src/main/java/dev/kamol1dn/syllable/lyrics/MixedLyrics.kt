package dev.kamol1dn.syllable.lyrics

/** An outgoing line that would only begin this close to the end of the overlap is too faint to show. */
private const val TOO_LATE_MS = 1_000L

/**
 * The lyrics for a song that another song is mixed into: [incoming]'s own lines, and among them
 * the lines [outgoing] still sings while the two play together, moved onto the incoming song's
 * clock. The overlap starts at [startAMs] in the outgoing song and [startBMs] in the incoming one,
 * lasts [durationMs], and the incoming song runs at [speedB] times its normal tempo meanwhile.
 *
 * The outgoing lines are marked [LyricLine.outgoing] and drawn on the other side, like a second
 * singer. A line that has begun is kept whole, even where its song fades out before the line is
 * over: half a line reads worse than a line that outlasts its voice by a moment. Without timing
 * on both sides there is nothing to line up, and [incoming] comes back as it is.
 */
fun mixedLyrics(
    outgoing: Lyrics?,
    incoming: Lyrics,
    startAMs: Long,
    startBMs: Long,
    durationMs: Long,
    speedB: Float,
): Lyrics {
    if (outgoing == null || outgoing.sync == SyncType.NONE || incoming.sync == SyncType.NONE) return incoming
    val endAMs = startAMs + durationMs
    fun onB(t: Long) = startBMs + ((t - startAMs) * speedB).toLong()
    fun List<LyricWord>.onB() = map { it.copy(startMs = onB(it.startMs), endMs = onB(it.endMs)) }

    val sync = if (outgoing.sync == SyncType.WORD || incoming.sync == SyncType.WORD) SyncType.WORD else SyncType.LINE
    val tail = outgoing.lines
        // Still being sung when the overlap starts, or begun during it.
        .filter { it.endMs > startAMs && it.startMs < endAMs - TOO_LATE_MS && it.words.isNotEmpty() }
        .map { line ->
            LyricLine(
                startMs = onB(line.startMs),
                endMs = onB(line.endMs),
                words = line.words.onB(),
                background = line.background.onB(),
                oppositeAligned = true,
                outgoing = true,
                lineTimed = sync == SyncType.WORD && outgoing.sync == SyncType.LINE,
            )
        }
    if (tail.isEmpty()) return incoming
    val own = if (sync == SyncType.WORD && incoming.sync == SyncType.LINE) incoming.lines.map { it.copy(lineTimed = true) } else incoming.lines
    // The sort keeps the incoming song's line first when two start together.
    return incoming.copy(lines = (own + tail).sortedBy { it.startMs }, sync = sync)
}
