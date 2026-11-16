package dev.kamol1dn.syllable.ui

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import androidx.compose.ui.unit.sp
import dev.kamol1dn.syllable.UpNext
import dev.kamol1dn.syllable.lyrics.LyricLine
import dev.kamol1dn.syllable.lyrics.LyricWord
import dev.kamol1dn.syllable.lyrics.Lyrics
import dev.kamol1dn.syllable.lyrics.SyncType
import dev.kamol1dn.syllable.lyrics.wordGroups
import kotlinx.coroutines.delay
import java.text.BreakIterator
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** The size and weight of the lyrics, shared with the preview in settings. */
fun lyricsTextStyle(textScale: Float) = TextStyle(
    fontSize = (30 * textScale).sp,
    lineHeight = (36 * textScale).sp,
    fontWeight = FontWeight.Bold,
    letterSpacing = 0.sp,
    color = Color.White,
)

/** A row in the list: a sung line, or an instrumental gap shown as three dots. */
private sealed interface Row {
    val startMs: Long
    val endMs: Long
    data class Line(val line: LyricLine, override val startMs: Long, override val endMs: Long) : Row
    data class Gap(override val startMs: Long, override val endMs: Long) : Row
    /** Where the lyrics came from, as the last thing in the list. Takes over once the last line is done. */
    data class Credits(val lyrics: Lyrics, override val startMs: Long = Long.MAX_VALUE) : Row {
        override val endMs = Long.MAX_VALUE
    }
}

/**
 * When a line stops being sung. Line-timed sources often give no end, so the line runs to the
 * next one; over a long instrumental that would keep it lit for half a minute, so such a line is
 * given a generous reading time instead.
 */
private fun sungEnd(lyrics: Lyrics, l: LyricLine, next: LyricLine?): Long {
    if (lyrics.sync == SyncType.WORD || next == null || l.endMs < next.startMs) return l.endMs
    val reading = l.startMs + max(7_000L, l.text.length * 110L)
    return if (next.startMs - reading >= INTERLUDE_MS) reading else l.endMs
}

private fun buildRows(lyrics: Lyrics): List<Row> {
    if (lyrics.sync == SyncType.NONE) return lyrics.lines.map<LyricLine, Row> { Row.Line(it, -1, -1) } + Row.Credits(lyrics)
    val rows = mutableListOf<Row>()
    val first = lyrics.lines.firstOrNull() ?: return rows
    if (first.startMs >= INTERLUDE_MS) rows += Row.Gap(0, first.startMs)
    lyrics.lines.forEachIndexed { i, l ->
        val next = lyrics.lines.getOrNull(i + 1)
        val end = sungEnd(lyrics, l, next)
        rows += Row.Line(l, l.startMs, end)
        if (next != null && next.startMs - end >= INTERLUDE_MS) rows += Row.Gap(end, next.startMs)
    }
    val lastEnd = maxOf(lyrics.lines.maxOf { it.endMs }, lyrics.lines.last().startMs)
    rows += Row.Credits(lyrics, lastEnd + 1_500)
    return rows
}

/**
 * One auto-scroll step. The list jumps by [delta] instantly and every row is drawn displaced by the
 * same amount, then each row springs back to its place a little after the row above it, so the
 * lyrics move as a soft wave instead of a rigid block.
 */
private class Kick(val delta: Float, val startNanos: Long, val anchor: Int)

private const val STAGGER_S = 0.042f
private const val KICK_LIFETIME_NS = 2_500_000_000L

/** Damped spring from 1 to 0 (slightly under-damped, settles in roughly 0.6 s). */
private fun springBack(t: Float): Float {
    if (t <= 0f) return 1f
    val z = 0.8f
    val w0 = 10.5f
    val wd = w0 * sqrt(1 - z * z)
    return exp(-z * w0 * t) * (cos(wd * t) + (z * w0 / wd) * sin(wd * t))
}

private fun kickOffset(kicks: List<Kick>, index: Int, frameNanos: Long): Float {
    var sum = 0f
    for (k in kicks) {
        val delayS = (index - k.anchor + 1).coerceIn(0, 10) * STAGGER_S
        val t = (frameNanos - k.startNanos) / 1e9f - delayS
        sum += k.delta * springBack(t)
    }
    return sum
}

/** The song position and the frame time, as states that only draw code reads. */
private class Clock(val pos: State<Long>, val frame: State<Long>)

@Composable
fun LyricsView(
    lyrics: Lyrics,
    position: () -> Long,
    onSeek: ((LyricLine) -> Unit)?,
    textScale: Float,
    blurInactive: Boolean,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    horizontalPadding: Dp = 24.dp,
    anchorFraction: Float = 0.3f,
    upNext: UpNext? = null,
) {
    val rows = remember(lyrics) { buildRows(lyrics) }
    val synced = lyrics.sync != SyncType.NONE

    // Frame-accurate position and frame time; read only in draw/layer lambdas and derived states.
    val pos = remember { mutableLongStateOf(position()) }
    val frame = remember { mutableLongStateOf(0L) }
    LaunchedEffect(position) {
        while (true) withFrameNanos { t ->
            frame.longValue = t
            pos.longValue = position()
        }
    }
    val clock = remember { Clock(pos, frame) }
    val kicks = remember { mutableStateOf(emptyList<Kick>()) }

    // A row is current from its start until the next row starts.
    val active by remember(rows) {
        derivedStateOf {
            if (!synced) -1 else {
                val p = pos.longValue
                var idx = -1
                for (i in rows.indices) {
                    if (rows[i].startMs <= p) idx = i else break
                }
                idx
            }
        }
    }

    // Earlier lines whose singing runs on over the current one (overlapping singers, background vocals).
    val stillSung by remember(rows) {
        derivedStateOf {
            val a = active
            if (a <= 0) emptySet() else {
                val p = pos.longValue
                ((a - 1) downTo maxOf(0, a - 2)).filter { i ->
                    val r = rows[i]
                    r is Row.Line && r.startMs <= p && p < maxOf(r.line.leadEnd(), r.line.bgEnd())
                }.toSet()
            }
        }
    }
    // Keep an older line in view while its main vocal is still going.
    val scrollTarget by remember(rows) {
        derivedStateOf {
            val p = pos.longValue
            stillSung.filter { (rows[it] as Row.Line).line.leadEnd() > p }.minOrNull() ?: active
        }
    }

    val listState = rememberLazyListState()
    val dragged by listState.interactionSource.collectIsDraggedAsState()
    var lastUserScroll by remember { mutableLongStateOf(0L) }
    LaunchedEffect(dragged) {
        if (dragged) {
            lastUserScroll = System.currentTimeMillis()
            kicks.value = emptyList() // the finger owns the list now
        }
    }
    var userBrowsing by remember { mutableStateOf(false) }
    LaunchedEffect(lastUserScroll) {
        if (lastUserScroll == 0L) return@LaunchedEffect
        userBrowsing = true
        delay(3_500)
        userBrowsing = false
    }

    BoxWithConstraints(
        modifier
            // Fade lines out at the top and bottom edges instead of cutting them off.
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.14f to Color.Black,
                        0.82f to Color.Black,
                        1f to Color.Transparent,
                    ),
                    blendMode = BlendMode.DstIn,
                )
            },
    ) {
        val viewport = with(LocalDensity.current) { maxHeight.toPx() }
        LaunchedEffect(scrollTarget, userBrowsing, viewport) {
            if (!synced || userBrowsing || dragged) return@LaunchedEffect
            val target = max(scrollTarget, 0)
            val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == target }
            if (info == null) {
                kicks.value = emptyList()
                listState.scrollToItem(target)
                return@LaunchedEffect
            }
            // Item offsets are measured below the top content padding, which already places the
            // current line about a third of the way down, so the target offset is 0.
            val delta = info.offset.toFloat()
            if (abs(delta) < 1f) return@LaunchedEffect
            withFrameNanos { now ->
                val consumed = listState.dispatchRawDelta(delta)
                if (consumed != 0f) {
                    kicks.value = kicks.value.filter { now - it.startNanos < KICK_LIFETIME_NS } +
                        Kick(consumed, now, target)
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = contentPadding.calculateTopPadding() + maxHeight * anchorFraction,
                bottom = contentPadding.calculateBottomPadding() + maxHeight * 0.65f,
            ),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            itemsIndexed(rows, key = { i, _ -> i }) { i, row ->
                val isActive = i == active || i in stillSung
                val distance = if (active < 0) 0 else abs(i - active)
                Box(
                    Modifier.graphicsLayer {
                        translationY = kickOffset(kicks.value, i, frame.longValue)
                    },
                ) {
                    when (row) {
                        is Row.Gap -> GapDots(row, clock, isActive, textScale, horizontalPadding)
                        is Row.Credits -> Column {
                            CreditsView(row.lyrics, isActive, horizontalPadding)
                            AnimatedVisibility(
                                upNext != null,
                                enter = fadeIn(tween(600)) + expandVertically(),
                                exit = fadeOut() + shrinkVertically(),
                            ) {
                                // Keeps the last song's text while fading out.
                                val shown = remember { mutableStateOf(upNext) }
                                if (upNext != null) shown.value = upNext
                                shown.value?.let { UpNextView(it, isActive, textScale, horizontalPadding) }
                            }
                        }
                        is Row.Line -> LineView(
                            line = row.line,
                            endMs = row.endMs,
                            sync = lyrics.sync,
                            isActive = isActive,
                            isPast = active >= 0 && i < active && !isActive,
                            distance = distance,
                            clock = clock,
                            textScale = textScale,
                            blur = blurInactive && synced && !userBrowsing,
                            horizontalPadding = horizontalPadding,
                            onClick = if (synced && onSeek != null) ({ onSeek(row.line) }) else null,
                        )
                    }
                }
            }
        }
    }
}

private val LineFade = CubicBezierEasing(0.61f, 1f, 0.88f, 1f)
private val SineInOut = CubicBezierEasing(0.37f, 0f, 0.63f, 1f)

/** How long a line keeps animating after it ends, so its last word can settle. */
private const val SETTLE_MS = 900L


@Composable
private fun LineView(
    line: LyricLine,
    endMs: Long,
    sync: SyncType,
    isActive: Boolean,
    isPast: Boolean,
    distance: Int,
    clock: Clock,
    textScale: Float,
    blur: Boolean,
    horizontalPadding: Dp,
    onClick: (() -> Unit)?,
) {
    val synced = sync != SyncType.NONE
    // Only lines being sung (or just finished) run per-frame animation; the rest are plain text.
    var settling by remember { mutableStateOf(false) }
    LaunchedEffect(isActive) {
        if (isActive) settling = true else if (settling) {
            delay(SETTLE_MS)
            settling = false
        }
    }
    val animated = isActive || (settling && isPast)

    val lineAlpha = animateFloatAsState(
        when {
            !synced || isActive -> 1f
            isPast -> LINE_SUNG
            else -> LINE_NOT_SUNG
        },
        tween(200, easing = LineFade),
        label = "lineAlpha",
    )
    val blurTarget = if (blur && !isActive && Build.VERSION.SDK_INT >= 31) min(distance * 1.25f, 6.8f) else 0f
    val blurDp = animateFloatAsState(blurTarget, tween(300), label = "blur")
    val density = LocalDensity.current

    val end = line.oppositeAligned
    val style = lyricsTextStyle(textScale).copy(textAlign = if (end) TextAlign.End else TextAlign.Start)
    val lineSync = if (line.lineTimed && sync == SyncType.WORD) SyncType.LINE else sync
    val bgStyle = style.copy(
        fontSize = style.fontSize * 0.7f,
        lineHeight = style.lineHeight * 0.7f,
        fontWeight = FontWeight.SemiBold,
    )
    Box(
        Modifier
            .fillMaxWidth()
            // Not clipped to a shape: the glow of a sung word has to be able to spill past the line.
            .then(if (onClick != null) Modifier.clickable(onClickLabel = "Play from here", onClick = onClick) else Modifier)
            // One node per line for TalkBack, instead of one per word.
            .clearAndSetSemantics {
                contentDescription = if (line.background.isEmpty()) line.text else "${line.text}, ${line.backgroundText}"
            }
            .padding(horizontal = horizontalPadding, vertical = 9.dp)
            .graphicsLayer {
                alpha = lineAlpha.value
                val r = with(density) { blurDp.value.dp.toPx() }
                val blurred = r > 0.5f && Build.VERSION.SDK_INT >= 31
                renderEffect = if (blurred) BlurEffect(r, r, TileMode.Decal) else null
                // A line that is fading but not blurred (it just finished) still has glows settling,
                // so it fades without a buffer that would crop them.
                compositingStrategy = if (blurred) CompositingStrategy.Auto else CompositingStrategy.ModulateAlpha
            },
    ) {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = if (end) Alignment.End else Alignment.Start,
        ) {
            when (lineSync) {
                SyncType.WORD -> {
                    val groups = remember(line) { wordGroups(line.words) }
                    BalancedFlow(end) {
                        groups.forEach { WordGroup(it, clock, style, animated, isPast) }
                    }
                    if (line.background.isNotEmpty()) {
                        val bgGroups = remember(line) { wordGroups(line.background) }
                        Spacer(Modifier.height(2.dp))
                        // Dimmed without its own buffer, which would cut the glow off at the row's edge.
                        BalancedFlow(
                            end,
                            Modifier.graphicsLayer {
                                alpha = 0.8f
                                compositingStrategy = CompositingStrategy.ModulateAlpha
                            },
                        ) {
                            bgGroups.forEach { WordGroup(it, clock, bgStyle, animated, isPast) }
                        }
                    }
                }
                SyncType.LINE -> WholeLine(line, endMs, clock, style, isActive, isPast, end)
                SyncType.NONE -> Text(
                    line.text,
                    style = style.copy(lineBreak = LineBreak.Heading, color = Color.White.copy(alpha = 0.9f)),
                )
            }
        }
    }
}

/**
 * A line with line timing only. Nothing is guessed about its words: the whole line grows a
 * little while it is sung and fills from top to bottom over its length.
 */
@Composable
private fun WholeLine(line: LyricLine, endMs: Long, clock: Clock, style: TextStyle, isActive: Boolean, isPast: Boolean, end: Boolean) {
    val scale = animateFloatAsState(if (isActive) 1.05f else 1f, tween(200, easing = SineInOut), label = "lineScale")
    Text(
        line.text,
        style = style.copy(lineBreak = LineBreak.Heading),
        // Room on the far side for the active line to grow into.
        modifier = Modifier.fillMaxWidth(0.95f)
            .graphicsLayer {
                scaleX = scale.value; scaleY = scale.value
                transformOrigin = TransformOrigin(if (end) 1f else 0f, 0.5f)
                if (isActive) {
                    compositingStrategy = CompositingStrategy.Offscreen
                } else {
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                    alpha = if (isPast) LIT else UNLIT
                }
            }
            .drawWithContent {
                drawContent()
                if (!isActive) return@drawWithContent
                val span = (endMs - line.startMs).coerceAtLeast(1)
                val pct = ((clock.pos.value - line.startMs).toFloat() / span).coerceIn(0f, 1f)
                fillMask(pct, vertical = true)
            },
    )
}

/** Keeps what has been sung at [LIT] and the rest at [UNLIT], with a soft edge at [fill] (0 to 1 across the text). */
private fun ContentDrawScope.fillMask(fill: Float, vertical: Boolean = false) {
    if (fill >= 1f) {
        drawRect(Color.White.copy(alpha = LIT), blendMode = BlendMode.DstIn)
        return
    }
    if (fill <= -FADE) {
        drawRect(Color.White.copy(alpha = UNLIT), blendMode = BlendMode.DstIn)
        return
    }
    // Where the fade runs off an edge it starts or ends part-way, so the edge keeps its slope.
    fun tone(t: Float) = Color.White.copy(alpha = LIT + (UNLIT - LIT) * t.coerceIn(0f, 1f))
    // Gradient stops must rise, also in the last instant before the text is full.
    val a = fill.coerceIn(0f, 0.999f)
    val b = max(a + 0.0005f, min(fill + FADE, 1f))
    val from = tone(-fill / FADE)
    val to = tone((1f - fill) / FADE)
    val stops = arrayOf(0f to from, a to from, b to to, 1f to to)
    drawRect(
        if (vertical) Brush.verticalGradient(*stops) else Brush.horizontalGradient(*stops),
        blendMode = BlendMode.DstIn,
    )
}

/**
 * Lays words out like wrapped text, but with the rows evened out: instead of filling each row
 * and leaving one stray word on the last, it uses the narrowest width that still fits the line
 * in the same number of rows.
 */
@Composable
private fun BalancedFlow(alignEnd: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val maxW = constraints.maxWidth
        val items = measurables.map { it.measure(Constraints(maxWidth = maxW)) }
        fun rowCount(limit: Int): Int {
            var rows = 1
            var x = 0
            for (p in items) {
                if (x > 0 && x + p.width > limit) {
                    rows++
                    x = 0
                }
                x += p.width
            }
            return rows
        }
        var limit = maxW
        val wanted = rowCount(maxW)
        if (wanted > 1 && maxW != Constraints.Infinity) {
            var lo = items.maxOf { it.width }
            var hi = maxW
            while (lo < hi) {
                val mid = (lo + hi) / 2
                if (rowCount(mid) <= wanted) hi = mid else lo = mid + 1
            }
            limit = hi
        }
        val rows = mutableListOf(mutableListOf<Placeable>())
        var x = 0
        for (p in items) {
            if (x > 0 && x + p.width > limit) {
                rows += mutableListOf<Placeable>()
                x = 0
            }
            rows.last() += p
            x += p.width
        }
        val width = if (maxW != Constraints.Infinity) maxW else rows.maxOf { r -> r.sumOf { it.width } }
        val height = rows.sumOf { r -> r.maxOfOrNull { it.height } ?: 0 }
        layout(width, height.coerceIn(constraints.minHeight, constraints.maxHeight)) {
            var y = 0
            for (r in rows) {
                var rx = if (alignEnd) width - r.sumOf { it.width } else 0
                for (p in r) {
                    p.place(rx, y)
                    rx += p.width
                }
                y += r.maxOfOrNull { it.height } ?: 0
            }
        }
    }
}

/** "From …" plus the people who made the sync, linked to their pages. */
@Composable
private fun CreditsView(lyrics: Lyrics, active: Boolean, horizontalPadding: Dp) {
    val alpha by animateFloatAsState(if (active) 0.8f else 0.65f, tween(700), label = "credits")
    val link = TextLinkStyles(SpanStyle(color = Color.White.copy(alpha = 0.85f), fontWeight = FontWeight.SemiBold))
    val text = buildAnnotatedString {
        append(
            when (lyrics.sync) {
                SyncType.WORD -> "Word timing"
                SyncType.LINE -> "Line timing"
                SyncType.NONE -> "Plain lyrics"
            },
        )
        append(" from ")
        append(lyrics.source)
        lyrics.credits.forEachIndexed { i, c ->
            append(if (i == 0) " · " else ", ")
            append(c.label)
            append(" ")
            if (c.url != null) withLink(LinkAnnotation.Url(c.url, link)) { append(c.name) } else append(c.name)
        }
    }
    Text(
        text,
        style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, color = Color.White.copy(alpha = alpha)),
        modifier = Modifier.padding(horizontal = horizontalPadding, vertical = 28.dp),
    )
}

/**
 * The song the player has queued next and how it opens, under the credits. It comes forward once the
 * current song has no lines left, so the wait for the next song isn't an empty screen.
 */
@Composable
private fun UpNextView(next: UpNext, active: Boolean, textScale: Float, horizontalPadding: Dp) {
    val alpha by animateFloatAsState(if (active) 1f else 0.6f, tween(700), label = "upNext")
    val lyric = lyricsTextStyle(textScale)
    Column(
        Modifier
            .fillMaxWidth()
            .graphicsLayer { this.alpha = alpha }
            .padding(horizontal = horizontalPadding)
            .padding(bottom = 28.dp),
    ) {
        Text(
            "UP NEXT",
            style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp, color = Color.White.copy(alpha = 0.6f)),
        )
        Text(
            next.title,
            style = lyric.copy(fontSize = lyric.fontSize * 0.7f, lineHeight = lyric.lineHeight * 0.7f, color = Color.White.copy(alpha = 0.9f)),
            modifier = Modifier.padding(top = 6.dp),
        )
        if (next.artist.isNotBlank()) {
            Text(next.artist, style = TextStyle(fontSize = 14.sp, lineHeight = 19.sp, color = Color.White.copy(alpha = 0.65f)))
        }
        if (next.preview.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            next.preview.forEach { line ->
                Text(
                    line,
                    style = lyric.copy(fontSize = lyric.fontSize * 0.7f, lineHeight = lyric.lineHeight * 0.7f, color = Color.White.copy(alpha = 0.32f)),
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
        }
    }
}

/** The syllables of one word, kept together so a line only wraps between words. */
@Composable
private fun WordGroup(g: List<LyricWord>, clock: Clock, style: TextStyle, animated: Boolean, isPast: Boolean) {
    Row {
        g.forEach { w ->
            if (w.endMs - w.startMs >= EMPHASIS_MS && !isRtl(w.text)) HeldWord(w, clock, style, animated, isPast)
            else Syllable(w, clock, style, animated, isPast)
        }
    }
}

private fun LyricLine.leadEnd(): Long = words.lastOrNull()?.endMs ?: endMs
private fun LyricLine.bgEnd(): Long = background.lastOrNull()?.endMs ?: Long.MIN_VALUE

private fun isRtl(text: String) = text.any {
    val d = Character.getDirectionality(it)
    d == Character.DIRECTIONALITY_RIGHT_TO_LEFT || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC
}

/** Splits into what a reader sees as letters, so accents and emoji stay whole. */
private fun letters(text: String): List<String> {
    val it = BreakIterator.getCharacterInstance()
    it.setText(text)
    val out = ArrayList<String>(text.length)
    var a = it.first()
    var b = it.next()
    while (b != BreakIterator.DONE) {
        out += text.substring(a, b)
        a = b
        b = it.next()
    }
    return out
}

private val canGlow = Build.VERSION.SDK_INT >= 31

/**
 * A blurred copy of the text behind it. A blur is cut off at the edge of its layer, which for a
 * layer the size of the text shows as a pale box around each word. So the layer gets [bleed] of
 * empty room on every side to fade out in, without the text taking any more space in the line.
 */
private fun Modifier.glow(bleed: Dp, block: GraphicsLayerScope.() -> Unit) = this
    .layout { measurable, constraints ->
        val pad = bleed.roundToPx()
        val p = measurable.measure(constraints.offset(2 * pad, 2 * pad))
        layout(p.width - 2 * pad, p.height - 2 * pad) { p.place(-pad, -pad) }
    }
    .graphicsLayer(block)
    .padding(bleed)

/** Waiting words sit slightly small and low; sung ones rest at full size. */
private fun Modifier.restPose(isPast: Boolean, em: Float) = graphicsLayer {
    val s = if (isPast) 1f else REST_SCALE
    scaleX = s; scaleY = s
    translationY = if (isPast) 0f else REST_LIFT * em
}

private fun Modifier.restTone(isPast: Boolean) = graphicsLayer {
    compositingStrategy = CompositingStrategy.ModulateAlpha
    alpha = if (isPast) LIT else UNLIT
}

/**
 * One word or syllable. In a line being sung it swells, lifts, glows and fills left to right;
 * anywhere else it is plain text.
 */
@Composable
private fun Syllable(word: LyricWord, clock: Clock, style: TextStyle, animated: Boolean, isPast: Boolean) {
    val density = LocalDensity.current
    val em = with(density) { style.fontSize.toPx() }
    if (!animated) {
        Text(word.text, style = style, softWrap = false, maxLines = 1, modifier = Modifier.restPose(isPast, em).restTone(isPast))
        return
    }
    val motion = remember(word) { WordMotion(word.startMs, word.endMs) }
    val glowRadius = with(density) { 5.dp.toPx() }
    Box(
        Modifier.graphicsLayer {
            motion.step(clock.frame.value, clock.pos.value)
            scaleX = motion.scale; scaleY = motion.scale
            translationY = motion.lift * em
        },
    ) {
        if (canGlow) {
            Text(
                word.text, style = style, softWrap = false, maxLines = 1,
                modifier = Modifier.glow(16.dp) {
                    motion.step(clock.frame.value, clock.pos.value)
                    alpha = (motion.glow * 0.35f).coerceIn(0f, 1f)
                    renderEffect = BlurEffect(glowRadius, glowRadius, TileMode.Decal)
                },
            )
        }
        Text(
            word.text, style = style, softWrap = false, maxLines = 1,
            modifier = Modifier
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    motion.step(clock.frame.value, clock.pos.value)
                    fillMask(motion.fill)
                },
        )
    }
}

/**
 * A word held for a second or more, drawn letter by letter: the letter being sung swells and
 * glows and the ones around it lean in. It is laid out as letters whether or not it is being
 * sung, so the line doesn't shift when it lights up.
 */
@Composable
private fun HeldWord(word: LyricWord, clock: Clock, style: TextStyle, animated: Boolean, isPast: Boolean) {
    val density = LocalDensity.current
    val em = with(density) { style.fontSize.toPx() }
    val body = word.text.trimEnd()
    val parts = remember(word) { letters(body) }
    val space = word.text.substring(body.length)
    if (!animated || parts.isEmpty()) {
        Row(Modifier.restPose(isPast, em).restTone(isPast)) {
            parts.forEach { Text(it, style = style, softWrap = false, maxLines = 1) }
            if (space.isNotEmpty()) Text(space, style = style, softWrap = false, maxLines = 1)
        }
        return
    }
    val group = remember(word) { WordMotion(word.startMs, word.endMs) }
    val motion = remember(word) { LetterMotion(word.startMs, word.endMs, parts.size) }
    val glowRadius = with(density) { 9.dp.toPx() }
    Row(
        Modifier.graphicsLayer {
            group.step(clock.frame.value, clock.pos.value)
            scaleX = group.scale; scaleY = group.scale
            translationY = group.lift * em
        },
    ) {
        parts.forEachIndexed { i, letter ->
            Box(
                Modifier.graphicsLayer {
                    motion.step(clock.frame.value, clock.pos.value)
                    val s = motion.scale(i)
                    scaleX = s; scaleY = s
                    translationY = motion.lift(i) * em
                },
            ) {
                if (canGlow) {
                    Text(
                        letter, style = style, softWrap = false, maxLines = 1,
                        modifier = Modifier.glow(28.dp) {
                            motion.step(clock.frame.value, clock.pos.value)
                            alpha = (motion.glow(i) * 1.85f).coerceIn(0f, 1f)
                            renderEffect = BlurEffect(glowRadius, glowRadius, TileMode.Decal)
                        },
                    )
                }
                Text(
                    letter, style = style, softWrap = false, maxLines = 1,
                    modifier = Modifier
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            motion.step(clock.frame.value, clock.pos.value)
                            fillMask(motion.fill(i))
                        },
                )
            }
        }
        if (space.isNotEmpty()) Text(space, style = style, softWrap = false, maxLines = 1)
    }
}

/** An instrumental gap: three dots that pop up and brighten one after another, then fold away. */
@Composable
private fun GapDots(row: Row.Gap, clock: Clock, active: Boolean, textScale: Float, horizontalPadding: Dp) {
    AnimatedVisibility(
        visible = active,
        enter = expandVertically(spring(stiffness = Spring.StiffnessLow)) + fadeIn(tween(300)),
        exit = shrinkVertically(tween(400)) + fadeOut(tween(140)),
    ) {
        val dotSize = 13.dp * textScale
        val em = with(LocalDensity.current) { (30 * textScale * 1.3f).sp.toPx() }
        // The dots share the gap in thirds and finish a little before the next line.
        val dots = remember(row) {
            val dotsEnd = max(row.startMs + 300, row.endMs - DOTS_END_EARLY_MS)
            val third = (dotsEnd - row.startMs) / 3
            List(3) { DotMotion(row.startMs + third * it, if (it == 2) dotsEnd else row.startMs + third * (it + 1)) }
        }
        Row(
            Modifier
                .padding(horizontal = horizontalPadding + 4.dp, vertical = 16.dp)
                .graphicsLayer {
                    val now = clock.pos.value
                    val intro = ((now - row.startMs) / 300f).coerceIn(0f, 1f)
                    val leaving = (now - (row.endMs - DOTS_HIDE_EARLY_MS)) / 400f
                    val s = if (leaving > 0f) dotsExitScale(leaving) else 1 - (1 - intro) * (1 - intro)
                    scaleX = s; scaleY = s
                    transformOrigin = TransformOrigin(0f, 0.5f)
                },
            horizontalArrangement = Arrangement.spacedBy(dotSize * 0.6f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            dots.forEach { dot ->
                Box(
                    Modifier
                        .size(dotSize)
                        .graphicsLayer {
                            dot.step(clock.frame.value, clock.pos.value)
                            alpha = dot.opacity
                            scaleX = dot.scale; scaleY = dot.scale
                            translationY = dot.lift * em
                        }
                        .clip(CircleShape)
                        .background(Color.White),
                )
            }
        }
    }
    if (!active) Spacer(Modifier.height(4.dp))
}
