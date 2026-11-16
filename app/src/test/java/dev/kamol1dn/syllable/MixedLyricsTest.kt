package dev.kamol1dn.syllable

import dev.kamol1dn.syllable.lyrics.LyricLine
import dev.kamol1dn.syllable.lyrics.LyricWord
import dev.kamol1dn.syllable.lyrics.Lyrics
import dev.kamol1dn.syllable.lyrics.SyncType
import dev.kamol1dn.syllable.lyrics.mixedLyrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MixedLyricsTest {
    private fun line(start: Long, end: Long, vararg words: String): LyricLine {
        val step = (end - start) / words.size
        return LyricLine(start, end, words.mapIndexed { i, w -> LyricWord("$w ", start + i * step, start + (i + 1) * step) })
    }

    // The outgoing song is mixed out from 200 s, over the incoming song from its 10 s mark, for 5 s.
    private val outgoing = Lyrics(
        listOf(
            line(190_000, 194_000, "long", "gone"),
            line(198_000, 202_000, "still", "sung", "at", "start"),
            line(203_000, 204_000, "inside"),
            line(203_500, 208_500, "runs", "past", "the", "end"),
            line(204_500, 206_000, "too", "late"),
            line(210_000, 212_000, "never", "heard"),
        ),
        SyncType.WORD, "test",
    )
    private val incoming = Lyrics(
        listOf(line(5_000, 8_000, "before"), line(11_000, 13_000, "during"), line(20_000, 22_000, "after")),
        SyncType.WORD, "test",
    )

    @Test
    fun putsTheOutgoingLinesOnTheIncomingClock() {
        val mixed = mixedLyrics(outgoing, incoming, 200_000, 10_000, 5_000, 1f)
        assertEquals(SyncType.WORD, mixed.sync)
        assertEquals(
            listOf("before", "still sung at start", "during", "inside", "runs past the end", "after"),
            mixed.lines.map { it.text },
        )
        val still = mixed.lines[1]
        assertTrue(still.outgoing && still.oppositeAligned)
        assertEquals(8_000L, still.startMs)
        assertEquals(12_000L, still.endMs)
        assertFalse(mixed.lines[2].outgoing)
        assertEquals(13_000L, mixed.lines[3].startMs)
    }

    @Test
    fun keepsALineWholeThatOutlastsItsSong() {
        val mixed = mixedLyrics(outgoing, incoming, 200_000, 10_000, 5_000, 1f)
        val last = mixed.lines.first { it.text.startsWith("runs") }
        assertEquals(4, last.words.size)
        assertEquals(18_500L, last.endMs)
        // A line that would only begin in the overlap's last second is left out.
        assertTrue(mixed.lines.none { it.text.startsWith("too") })
    }

    @Test
    fun followsTheIncomingSongsTempo() {
        val mixed = mixedLyrics(outgoing, incoming, 200_000, 10_000, 5_000, 1.1f)
        assertEquals(13_300L, mixed.lines.first { it.text == "inside" }.startMs)
    }

    @Test
    fun marksLineTimedLinesAmongWordTimedOnes() {
        val byLine = Lyrics(outgoing.lines.map { LyricLine(it.startMs, it.endMs, listOf(LyricWord(it.text, it.startMs, it.endMs))) }, SyncType.LINE, "test")
        val mixed = mixedLyrics(byLine, incoming, 200_000, 10_000, 5_000, 1f)
        assertEquals(SyncType.WORD, mixed.sync)
        assertTrue(mixed.lines.filter { it.outgoing }.all { it.lineTimed })
        assertTrue(mixed.lines.filterNot { it.outgoing }.none { it.lineTimed })
    }

    @Test
    fun leavesTheSongAloneWhenThereIsNothingToLineUp() {
        assertSame(incoming, mixedLyrics(null, incoming, 200_000, 10_000, 5_000, 1f))
        assertSame(incoming, mixedLyrics(outgoing.copy(sync = SyncType.NONE), incoming, 200_000, 10_000, 5_000, 1f))
        // No outgoing line reaches the overlap.
        assertSame(incoming, mixedLyrics(outgoing, incoming, 230_000, 10_000, 5_000, 1f))
    }
}
