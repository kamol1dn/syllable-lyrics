package dev.kamol1dn.syllable

import dev.kamol1dn.syllable.ui.DotMotion
import dev.kamol1dn.syllable.ui.LetterMotion
import dev.kamol1dn.syllable.ui.Spline
import dev.kamol1dn.syllable.ui.Spring
import dev.kamol1dn.syllable.ui.WordMotion
import dev.kamol1dn.syllable.ui.dotsExitScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricMotionTest {
    private val frameNs = 16_666_667L

    @Test
    fun splinePassesThroughItsPoints() {
        val s = Spline(0f to 0.95f, 0.7f to 1.0505f, 1f to 1f)
        assertEquals(0.95f, s.at(0f), 1e-4f)
        assertEquals(1.0505f, s.at(0.7f), 1e-4f)
        assertEquals(1f, s.at(1f), 1e-4f)
        assertEquals(0.95f, s.at(-1f), 1e-4f) // clamped outside its range
        assertTrue(s.at(0.4f) in 0.95f..1.07f)
    }

    @Test
    fun springSettlesOnItsGoalForAnyFrameTime() {
        for (dt in listOf(1 / 120f, 1 / 60f, 1 / 20f)) {
            for (damping in listOf(0.4f, 0.64f, 1f)) {
                val s = Spring(0f, 1.2f, damping)
                s.goal = 1f
                var peak = 0f
                repeat((6 / dt).toInt()) { peak = maxOf(peak, s.step(dt)) }
                assertEquals(1f, s.value, 2e-3f)
                assertTrue(s.settled)
                // Only an underdamped spring overshoots.
                assertEquals(damping < 1f, peak > 1.001f)
            }
        }
    }

    @Test
    fun wordFillsSwellsAndComesToRest() {
        val m = WordMotion(1_000, 2_000)
        var frame = 0L
        fun runTo(ms: Long, from: Long) {
            var p = from
            while (p <= ms) { m.step(frame, p); frame += frameNs; p += 16 }
        }
        m.step(frame, 0); frame += frameNs
        assertEquals(-0.2f, m.fill, 1e-4f)
        assertEquals(0.95f, m.scale, 1e-3f)
        runTo(1_700, 1_000)
        assertTrue("half filled", m.fill > 0.5f && m.fill < 1f)
        assertTrue("swelling", m.scale > 0.97f)
        assertTrue("lifted", m.lift < 0.01f)
        runTo(5_000, 1_700)
        assertEquals(1f, m.fill, 1e-4f)
        assertEquals(1f, m.scale, 2e-3f)
        assertEquals(0f, m.lift, 2e-3f)
        assertEquals(0f, m.glow, 2e-2f)
    }

    @Test
    fun wordAlreadySungStartsAtRest() {
        val m = WordMotion(1_000, 2_000)
        m.step(0, 9_000)
        assertEquals(1f, m.scale, 1e-4f)
        assertEquals(1f, m.fill, 1e-4f)
    }

    @Test
    fun sameFrameStepsOnlyOnce() {
        val a = WordMotion(0, 1_000)
        val b = WordMotion(0, 1_000)
        a.step(0, 0); b.step(0, 0)
        a.step(frameNs, 500); a.step(frameNs, 500); a.step(frameNs, 500)
        b.step(frameNs, 500)
        assertEquals(b.scale, a.scale, 0f)
    }

    @Test
    fun heldWordFillsLetterByLetter() {
        val m = LetterMotion(0, 2_250, 4) // letters cover 0..2000, 500 ms each
        var frame = 0L
        var p = 0L
        while (p <= 750) { m.step(frame, p); frame += frameNs; p += 16 }
        assertEquals(1f, m.fill(0), 1e-4f)
        assertTrue(m.fill(1) > -0.2f && m.fill(1) < 1f)
        assertEquals(-0.2f, m.fill(2), 1e-4f)
        assertTrue("sung letters swell, waiting ones don't", m.scale(0) > 0.96f && m.scale(1) > 0.96f && m.scale(1) > m.scale(3))
        assertEquals("waiting letters stay at rest", 0.95f, m.scale(3), 1e-3f)
        while (p <= 6_000) { m.step(frame, p); frame += frameNs; p += 16 }
        for (i in 0 until 4) {
            assertEquals(1f, m.fill(i), 1e-4f)
            assertEquals(1f, m.scale(i), 3e-3f)
        }
    }

    @Test
    fun dotsBrightenThenFoldAway() {
        val d = DotMotion(0, 1_000)
        var frame = 0L
        var p = 0L
        d.step(frame, 0)
        assertEquals(0.35f, d.opacity, 1e-3f)
        while (p <= 4_000) { d.step(frame, p); frame += frameNs; p += 16 }
        assertEquals(1f, d.opacity, 1e-2f)
        assertEquals(1f, d.scale, 1e-2f)
        assertEquals(1f, dotsExitScale(0f), 1e-4f)
        assertTrue(dotsExitScale(0.5f) > 1f)
        assertEquals(0f, dotsExitScale(1f), 1e-4f)
    }
}
