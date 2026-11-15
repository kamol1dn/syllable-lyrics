package dev.kamol1dn.syllable.ui

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * The motion of sung words, matched to the default ("Spring") look of Spicy Lyrics, the Spotify
 * mod: every word, letter and dot has a target that follows how far through it the song is, and a
 * damped spring that chases the target. The numbers below are that look's numbers; the code is
 * this app's own. Nothing here touches Compose, so it can be tested on its own.
 */

/** Text that has been sung, and text still to come, as white at this alpha. */
internal const val LIT = 0.85f
internal const val UNLIT = 0.35f

/** Whole-line opacity on top of that: lines to come, and lines already sung. */
internal const val LINE_NOT_SUNG = 0.51f
internal const val LINE_SUNG = 0.497f

/** The soft edge between sung and unsung is this share of the word (or of the line's height). */
internal const val FADE = 0.2f

/** A word or syllable held at least this long is drawn letter by letter. */
internal const val EMPHASIS_MS = 1_000L

/** Letters of a held word finish this long before the word does. */
private const val EMPHASIS_TAIL_MS = 250L

/** A gap this long between lines is an interlude, shown as dots. */
internal const val INTERLUDE_MS = 3_000L

/** The dots finish this long before the next line, and start folding away just after. */
internal const val DOTS_END_EARLY_MS = 550L
internal const val DOTS_HIDE_EARLY_MS = 500L

/** A natural cubic spline through a few points, read between its first and last x. */
internal class Spline(vararg points: Pair<Float, Float>) {
    private val xs = FloatArray(points.size) { points[it].first }
    private val ys = FloatArray(points.size) { points[it].second }
    private val m = FloatArray(points.size) // second derivatives; zero at both ends

    init {
        val n = xs.size
        if (n > 2) {
            // Thomas algorithm over the inner points.
            val c = FloatArray(n)
            val d = FloatArray(n)
            for (i in 1 until n - 1) {
                val h0 = xs[i] - xs[i - 1]
                val h1 = xs[i + 1] - xs[i]
                val diag = 2 * (h0 + h1) - h0 * c[i - 1]
                val rhs = 6 * ((ys[i + 1] - ys[i]) / h1 - (ys[i] - ys[i - 1]) / h0)
                c[i] = h1 / diag
                d[i] = (rhs - h0 * d[i - 1]) / diag
            }
            for (i in n - 2 downTo 1) m[i] = d[i] - c[i] * m[i + 1]
        }
    }

    fun at(x: Float): Float {
        val t = x.coerceIn(xs.first(), xs.last())
        var i = 0
        while (i < xs.size - 2 && t > xs[i + 1]) i++
        val h = xs[i + 1] - xs[i]
        val a = (xs[i + 1] - t) / h
        val b = (t - xs[i]) / h
        return a * ys[i] + b * ys[i + 1] + ((a * a * a - a) * m[i] + (b * b * b - b) * m[i + 1]) * h * h / 6
    }
}

/** A damped spring, stepped exactly for any frame time. [hz] is its natural frequency. */
internal class Spring(start: Float, hz: Float, private val damping: Float) {
    private val w = hz * 2 * PI.toFloat()
    private var velocity = 0f
    var value = start
        private set
    var goal = start

    fun snap(to: Float) {
        value = to
        goal = to
        velocity = 0f
    }

    fun step(dt: Float): Float {
        if (dt <= 0f) return value
        val o = value - goal
        if (damping >= 1f) {
            val e = exp(-w * dt)
            val k = velocity + w * o
            value = goal + (o + k * dt) * e
            velocity = (velocity - k * w * dt) * e
        } else {
            val wd = w * sqrt(1 - damping * damping)
            val e = exp(-damping * w * dt)
            val c = cos(wd * dt)
            val s = sin(wd * dt)
            val k = (velocity + damping * w * o) / wd
            value = goal + e * (o * c + k * s)
            velocity = e * (velocity * c - (o * wd + damping * w * k) * s)
        }
        return value
    }

    val settled: Boolean get() = abs(value - goal) < 5e-4f && abs(velocity) < 1e-2f
}

// Targets over a word's own progress, 0 to 1. Offsets are in em; negative is up.
private val WordScale = Spline(0f to 0.95f, 0.7f to 1.0505f, 1f to 1f)
private val WordLift = Spline(0f to 0.01f, 0.9f to -1f / 60, 1f to 0f)
private val WordGlow = Spline(0f to 0f, 0.15f to 1f, 0.6f to 1f, 1f to 0f)
private val LetterScale = Spline(0f to 0.95f, 0.7f to 1.175f, 1f to 1f)
private val LetterLift = Spline(0f to 0.01f, 0.9f to -1f / 56, 1f to 0f)
private val DotScale = Spline(0f to 0.75f, 0.7f to 1.05f, 1f to 1f)
private val DotLift = Spline(0f to 0f, 0.9f to -0.12f, 1f to 0f)
private val DotOpacity = Spline(0f to 0.35f, 0.6f to 1f, 1f to 1f)

internal const val REST_SCALE = 0.95f
internal const val REST_LIFT = 0.01f
private const val SUNG_LETTER_GLOW = 0.2f

private fun progress(p: Long, start: Long, end: Long): Float = when {
    p <= start -> 0f
    p >= end -> 1f
    else -> (p - start).toFloat() / (end - start)
}

/** Seconds since the last step, with long pauses (a seek, the app coming back) cut short. */
private fun frameSeconds(frame: Long, last: Long): Float = ((frame - last) / 1e9f).coerceIn(0f, 0.1f)

/** How far the lit edge is across a piece of text: -[FADE] is all unlit, 1 is all lit. */
private fun fillAt(pct: Float) = -FADE + (1 + FADE) * pct

/**
 * One word or syllable: it swells and lifts while it is sung, glows through the middle of it,
 * and fills left to right. Waiting words sit slightly small and low; sung ones rest at full size.
 */
internal class WordMotion(private val start: Long, private val end: Long) {
    private val scaleSpring = Spring(REST_SCALE, 0.88f, 0.64f)
    private val liftSpring = Spring(REST_LIFT, 1.45f, 0.4f)
    private val glowSpring = Spring(0f, 1.18f, 0.56f)
    private var last = Long.MIN_VALUE

    val scale get() = scaleSpring.value
    val lift get() = liftSpring.value
    val glow get() = glowSpring.value
    var fill = -FADE
        private set

    /** Safe to call from several places in one frame: only the first call of a frame moves anything. */
    fun step(frame: Long, p: Long) {
        if (frame == last) return
        val pct = progress(p, start, end)
        fill = fillAt(pct)
        val sung = p >= end
        scaleSpring.goal = if (sung) 1f else WordScale.at(pct)
        liftSpring.goal = if (sung) 0f else WordLift.at(pct)
        glowSpring.goal = if (sung) 0f else WordGlow.at(pct)
        if (last == Long.MIN_VALUE) {
            // First frame on screen: a word already sung starts at rest instead of springing up.
            if (sung) {
                scaleSpring.snap(1f); liftSpring.snap(0f); glowSpring.snap(0f)
            }
        } else {
            val dt = frameSeconds(frame, last)
            scaleSpring.step(dt); liftSpring.step(dt); glowSpring.step(dt)
        }
        last = frame
    }
}

/**
 * The letters of a held word. The letter being sung swells, lifts and glows, its sung neighbours
 * lean in with it, and the fill moves through the word one letter at a time.
 */
internal class LetterMotion(private val start: Long, end: Long, val count: Int) {
    private val lettersEnd = maxOf(start + count, end - EMPHASIS_TAIL_MS)
    private val wordEnd = end
    private val scaleSprings = Array(count) { Spring(REST_SCALE, 0.88f, 0.64f) }
    private val liftSprings = Array(count) { Spring(REST_LIFT, 1.45f, 0.4f) }
    private val glowSprings = Array(count) { Spring(0f, 1.18f, 0.56f) }
    private val fills = FloatArray(count) { -FADE }
    private var last = Long.MIN_VALUE

    fun scale(i: Int) = scaleSprings[i].value
    /** Letters lift twice as far as whole words. */
    fun lift(i: Int) = liftSprings[i].value * 2
    fun glow(i: Int) = glowSprings[i].value
    fun fill(i: Int) = fills[i]

    private fun letterStart(i: Int) = start + (lettersEnd - start) * i / count

    fun step(frame: Long, p: Long) {
        if (frame == last) return
        val wordSung = p >= wordEnd
        val wordActive = p > start && !wordSung
        var active = -1
        var activePct = 0f
        if (wordActive) {
            for (i in 0 until count) {
                val s = letterStart(i)
                val e = letterStart(i + 1)
                if (p >= s && p < e) {
                    active = i
                    activePct = progress(p, s, e)
                    break
                }
            }
        }
        val first = last == Long.MIN_VALUE
        val dt = if (first) 0f else frameSeconds(frame, last)
        for (i in 0 until count) {
            val letterSung = p >= letterStart(i + 1)
            val letterWaiting = p < letterStart(i)
            var scale = REST_SCALE
            var lift = REST_LIFT
            var glow = 0f
            when {
                wordSung -> { scale = 1f; lift = 0f }
                !wordActive || letterWaiting -> Unit
                active != -1 -> {
                    val d = abs(i - active).toFloat()
                    val near = 1 / (1 + d.pow(2.8f))
                    scale = REST_SCALE + (LetterScale.at(activePct) - REST_SCALE) * near
                    lift = REST_LIFT + (LetterLift.at(activePct) - REST_LIFT) * near
                    glow = WordGlow.at(activePct) / (1 + d * 0.9f)
                }
                letterSung -> glow = WordGlow.at(SUNG_LETTER_GLOW)
            }
            fills[i] = when {
                wordSung || letterSung -> 1f
                i == active -> fillAt(sin(activePct * PI.toFloat() / 2))
                else -> -FADE
            }
            scaleSprings[i].goal = scale
            liftSprings[i].goal = lift
            glowSprings[i].goal = glow
            if (first) {
                if (wordSung) {
                    scaleSprings[i].snap(1f); liftSprings[i].snap(0f); glowSprings[i].snap(0f)
                }
            } else {
                scaleSprings[i].step(dt); liftSprings[i].step(dt); glowSprings[i].step(dt)
            }
        }
        last = frame
    }
}

/** One of the three interlude dots: it pops up and brightens during its third of the gap. */
internal class DotMotion(private val start: Long, private val end: Long) {
    private val scaleSpring = Spring(DotScale.at(0f), 0.7f, 0.6f)
    private val liftSpring = Spring(0f, 1.25f, 0.4f)
    private val opacitySpring = Spring(DotOpacity.at(0f), 1f, 0.5f)
    private var last = Long.MIN_VALUE

    val scale get() = scaleSpring.value
    val lift get() = liftSpring.value
    val opacity get() = opacitySpring.value.coerceIn(0f, 1f)

    fun step(frame: Long, p: Long) {
        if (frame == last) return
        val pct = progress(p, start, end)
        scaleSpring.goal = DotScale.at(pct)
        liftSpring.goal = DotLift.at(pct)
        opacitySpring.goal = DotOpacity.at(pct)
        if (last == Long.MIN_VALUE) {
            if (pct >= 1f) {
                scaleSpring.snap(1f); liftSpring.snap(0f); opacitySpring.snap(1f)
            }
        } else {
            val dt = frameSeconds(frame, last)
            scaleSpring.step(dt); liftSpring.step(dt); opacitySpring.step(dt)
        }
        last = frame
    }
}

/**
 * The size of the dot group as it leaves, [t] from 0 to 1: a small swell, then it shrinks to
 * nothing just before the next line starts.
 */
internal fun dotsExitScale(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    val swell = 0.19f
    val turn = 0.56f
    return if (x < turn) 1 + swell * sin(x / turn * PI.toFloat() / 2)
    else (1 + swell) * (1 - ((x - turn) / (1 - turn)).pow(2))
}
