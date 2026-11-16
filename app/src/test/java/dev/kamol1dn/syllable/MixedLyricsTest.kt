package dev.kamol1dn.syllable

import dev.kamol1dn.syllable.lyrics.LyricLine
import dev.kamol1dn.syllable.lyrics.LyricWord
import dev.kamol1dn.syllable.lyrics.Lyrics
import dev.kamol1dn.syllable.lyrics.SyncType
import dev.kamol1dn.syllable.spotify.MixTransition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MixedLyricsTest {
    private fun line(start: Long, end: Long, vararg words: String): LyricLine {
        val step = (end - start) / words.size
        return LyricLine(start, end, words.mapIndexed { i, w -> LyricWord("$w ", start + i * step, start + (i + 1) * step) })
    }

    private val a = Lyrics(
        listOf(
            line(190_000, 194_000, "long", "before"),
            line(198_000, 202_000, "sung", "into", "the", "mix"),
            line(203_500, 208_500, "runs", "past", "the", "end"),
            line(204_500, 206_000, "too", "late"),
            line(210_000, 212_000, "never", "heard"),
        ),
        SyncType.WORD, "first",
    )
    private val b = Lyrics(
        listOf(line(5_000, 8_000, "skipped"), line(11_000, 13_000, "comes", "in"), line(150_000, 152_000, "later", "on")),
        SyncType.WORD, "second",
    )
    private val c = Lyrics(listOf(line(1_000, 3_000, "third", "song")), SyncType.WORD, "third")

    // The first song is mixed out from 200 s, over the second from its 10 s mark, for 5 s.
    private val ab = MixTransition("A", "B", 200_000, 10_000, 5_000, 1f, false)
    // The second runs into the third from 160 s, which starts at its top, for 4 s.
    private val bc = MixTransition("B", "C", 160_000, 0, 4_000, 1f, false)

    private val run = MixSession.start("A", "First", a)!!.add("B", "Second", b, ab)!!

    @Test
    fun theNextSongsLinesFollowOnTheFirstSongsClock() {
        assertEquals(
            listOf("long before", "sung into the mix", "comes in", "runs past the end", "later on"),
            run.lyrics.lines.map { it.text },
        )
        assertEquals(listOf(0, 0, 1, 0, 1), run.lyrics.lines.map { it.part })
        // 11 s of the second song is 1 s into the overlap, which starts at 200 s of the first.
        val comesIn = run.lyrics.lines[2]
        assertEquals(201_000L, comesIn.startMs)
        assertEquals(201_000L, comesIn.words.first().startMs)
        assertEquals(190_000L, run.songs[1].shiftMs)
    }

    @Test
    fun whatIsNeverPlayedIsLeftOut() {
        val texts = run.lyrics.lines.map { it.text }
        // Before the second song comes in, and after the first has faded out.
        assertTrue("skipped" !in texts && "never heard" !in texts)
        // A line that would only begin in the overlap's last second is too faint to show.
        assertTrue("too late" !in texts)
        // One that has begun is kept whole.
        assertEquals(4, run.lyrics.lines.first { it.text.startsWith("runs") }.words.size)
    }

    @Test
    fun theRunKeepsGrowingFromItsEnd() {
        val longer = run.add("C", "Third", c, bc)!!
        assertEquals(run.lyrics.lines, longer.lyrics.lines.dropLast(1))
        val third = longer.lyrics.lines.last()
        assertEquals(2, third.part)
        // 160 s of the second song is 350 s on the list's clock, where the third starts from its top.
        assertEquals(350_000L, longer.songs[2].shiftMs)
        assertEquals(351_000L, third.startMs)
        assertSame(run.anchor, longer.anchor)
        assertEquals("third", longer.lyrics.source)
    }

    @Test
    fun findsASongByIdOrTitle() {
        assertEquals(1, run.indexOf("B", "anything"))
        assertEquals(0, run.indexOf(null, "FIRST!"))
        assertEquals(1, run.indexOf("relinked", "Second"))
        assertEquals(-1, run.indexOf("X", "Other"))
    }

    @Test
    fun lineTimedSongsLightUpAsWholeLinesAmongWordTimedOnes() {
        val byLine = Lyrics(b.lines.map { LyricLine(it.startMs, it.endMs, listOf(LyricWord(it.text, it.startMs, it.endMs))) }, SyncType.LINE, "second")
        val mixed = MixSession.start("A", "First", a)!!.add("B", "Second", byLine, ab)!!.lyrics
        assertEquals(SyncType.WORD, mixed.sync)
        assertTrue(mixed.lines.filter { it.part == 1 }.all { it.lineTimed })
        assertTrue(mixed.lines.filter { it.part == 0 }.none { it.lineTimed })
    }

    @Test
    fun needsTimingOnBothSides() {
        assertNull(MixSession.start("A", "First", a.copy(sync = SyncType.NONE)))
        assertNull(MixSession.start("A", "First", a)!!.add("B", "Second", b.copy(sync = SyncType.NONE), ab))
    }

    @Test
    fun aSongThatEndedARunStartsTheNextAsItsFirstPart() {
        val again = MixSession.start("B", "Second", Lyrics(run.lyrics.lines.filter { it.part == 1 }, SyncType.WORD, "second"))!!
        assertTrue(again.lyrics.lines.all { it.part == 0 })
    }
}
