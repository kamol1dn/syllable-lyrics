package dev.kamol1dn.syllable.ui

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kamol1dn.syllable.lyrics.LyricLine
import dev.kamol1dn.syllable.lyrics.LyricWord
import dev.kamol1dn.syllable.lyrics.Lyrics
import dev.kamol1dn.syllable.lyrics.SyncType
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

private const val DIM = 0.32f
private const val PAST = 0.45f

/** A row in the list: a sung line, or an instrumental gap shown as breathing dots. */
private sealed interface Row {
    val startMs: Long
    val endMs: Long
    data class Line(val line: LyricLine, override val startMs: Long, override val endMs: Long) : Row
    data class Gap(override val startMs: Long, override val endMs: Long) : Row
    /** Where the lyrics came from, as the last thing in the list. Never becomes active. */
    data class Credits(val lyrics: Lyrics) : Row {
        override val startMs = Long.MAX_VALUE
        override val endMs = Long.MAX_VALUE
    }
}

private fun buildRows(lyrics: Lyrics): List<Row> {
    if (lyrics.sync == SyncType.NONE) return lyrics.lines.map<LyricLine, Row> { Row.Line(it, -1, -1) } + Row.Credits(lyrics)
    val rows = mutableListOf<Row>()
    val first = lyrics.lines.firstOrNull() ?: return rows
    if (first.startMs > 4_000) rows += Row.Gap(0, first.startMs)
    lyrics.lines.forEachIndexed { i, l ->
        val next = lyrics.lines.getOrNull(i + 1)
        rows += Row.Line(l, l.startMs, l.endMs)
        if (next != null && next.startMs - l.endMs > 5_000) rows += Row.Gap(l.endMs + 400, next.startMs)
    }
    rows += Row.Credits(lyrics)
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

@Composable
fun LyricsView(
    lyrics: Lyrics,
    position: () -> Long,
    onSeek: ((Long) -> Unit)?,
    textScale: Float,
    blurInactive: Boolean,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    horizontalPadding: Dp = 24.dp,
    anchorFraction: Float = 0.3f,
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
    val kicks = remember { mutableStateOf(emptyList<Kick>()) }

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

    val stillSung by remember(rows) {
        derivedStateOf {
            val a = active
            if (a <= 0) emptySet() else {
                val p = pos.longValue
                ((a - 1) downTo maxOf(0, a - 4)).filter { i ->
                    val r = rows[i]
                    r is Row.Line && r.startMs <= p && p < r.endMs
                }.toSet()
            }
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
                        0.07f to Color.Black,
                        0.82f to Color.Black,
                        1f to Color.Transparent,
                    ),
                    blendMode = BlendMode.DstIn,
                )
            },
    ) {
        val viewport = with(LocalDensity.current) { maxHeight.toPx() }
        LaunchedEffect(active, userBrowsing, viewport) {
            if (!synced || userBrowsing || dragged) return@LaunchedEffect
            val target = max(active, 0)
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
                        is Row.Gap -> GapDots(row, pos, isActive, horizontalPadding)
                        is Row.Credits -> CreditsView(row.lyrics, horizontalPadding)
                        is Row.Line -> LineView(
                            line = row.line,
                            synced = synced,
                            isActive = isActive,
                            isPast = active >= 0 && i < active && !isActive,
                            distance = distance,
                            pos = pos,
                            textScale = textScale,
                            blur = blurInactive && synced && !userBrowsing,
                            horizontalPadding = horizontalPadding,
                            onClick = if (synced && onSeek != null) ({ onSeek(row.line.startMs) }) else null,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LineView(
    line: LyricLine,
    synced: Boolean,
    isActive: Boolean,
    isPast: Boolean,
    distance: Int,
    pos: State<Long>,
    textScale: Float,
    blur: Boolean,
    horizontalPadding: Dp,
    onClick: (() -> Unit)?,
) {
    val scale = animateFloatAsState(
        if (isActive || !synced) 1f else 0.955f,
        spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessLow),
        label = "scale",
    )
    // Inactive lines fade with the layer alpha; the active line draws its own per-word alpha.
    val alpha = animateFloatAsState(
        when {
            !synced -> 0.9f
            isActive -> 1f
            isPast -> PAST
            else -> DIM
        },
        tween(if (isActive) 120 else 700),
        label = "alpha",
    )
    val blurTarget = if (blur && !isActive && Build.VERSION.SDK_INT >= 31) distance.coerceAtMost(5) * 1.1f else 0f
    val blurDp = animateFloatAsState(blurTarget, tween(500), label = "blur")
    val density = LocalDensity.current

    val end = line.oppositeAligned
    val align = if (end) TextAlign.End else TextAlign.Start
    val style = TextStyle(
        fontSize = (30 * textScale).sp,
        lineHeight = (38 * textScale).sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = (-0.3).sp,
        color = Color.White,
        textAlign = align,
    )
    val bgStyle = style.copy(
        fontSize = (19 * textScale).sp,
        lineHeight = (25 * textScale).sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.sp,
    )
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = horizontalPadding, vertical = 9.dp)
            .graphicsLayer {
                scaleX = scale.value; scaleY = scale.value
                transformOrigin = TransformOrigin(if (end) 1f else 0f, 0.5f)
                this.alpha = if (isActive) 1f else alpha.value
                val r = with(density) { blurDp.value.dp.toPx() }
                renderEffect = if (r > 0.5f && Build.VERSION.SDK_INT >= 31) BlurEffect(r, r, TileMode.Decal) else null
            },
    ) {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = if (end) Alignment.End else Alignment.Start,
        ) {
            val arrangement = if (end) Arrangement.End else Arrangement.Start
            if (!isActive || !synced) {
                Text(line.text, style = style)
            } else {
                FlowRow(horizontalArrangement = arrangement) {
                    line.words.forEach { w -> WordView(w, pos, style) }
                }
            }
            if (line.background.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                if (!isActive || !synced) {
                    Text(line.backgroundText, style = bgStyle, modifier = Modifier.graphicsLayer { this.alpha = 0.8f })
                } else {
                    FlowRow(horizontalArrangement = arrangement) {
                        line.background.forEach { w -> WordView(w, pos, bgStyle) }
                    }
                }
            }
        }
    }
}

/** "Lyrics from …" plus the people who made the sync, linked to their pages. */
@Composable
private fun CreditsView(lyrics: Lyrics, horizontalPadding: Dp) {
    val link = TextLinkStyles(SpanStyle(color = Color.White.copy(alpha = 0.85f), fontWeight = FontWeight.SemiBold))
    val text = buildAnnotatedString {
        append("Lyrics from ")
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
        style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, color = Color.White.copy(alpha = 0.5f)),
        modifier = Modifier.padding(horizontal = horizontalPadding, vertical = 28.dp),
    )
}

/**
 * One word: a soft left-to-right "sung" wipe and a small lift. Held words (sung for a long time)
 * also swell slightly and glow while they last.
 */
@Composable
private fun WordView(word: LyricWord, pos: State<Long>, style: TextStyle) {
    val density = LocalDensity.current
    val feather = with(density) { 16.dp.toPx() }
    val lift = with(density) { 3.dp.toPx() }
    val held = word.endMs - word.startMs >= 900 && word.text.trim().length >= 2
    val glowRadius = with(density) { 7.dp.toPx() }

    Box {
        if (held && Build.VERSION.SDK_INT >= 31) {
            Text(
                text = word.text,
                style = style,
                softWrap = false,
                modifier = Modifier.graphicsLayer {
                    val p = progress(word, pos.value)
                    val g = swell(p)
                    alpha = 0.75f * g
                    val s = 1f + 0.035f * g
                    scaleX = s; scaleY = s
                    transformOrigin = TransformOrigin(0.5f, 1f)
                    translationY = -lift * easeOutCubic(p)
                    renderEffect = BlurEffect(glowRadius, glowRadius, TileMode.Decal)
                },
            )
        }
        Text(
            text = word.text,
            style = style,
            softWrap = false,
            modifier = Modifier
                .graphicsLayer {
                    val p = progress(word, pos.value)
                    translationY = -lift * easeOutCubic(p)
                    if (held) {
                        val s = 1f + 0.035f * swell(p)
                        scaleX = s; scaleY = s
                        transformOrigin = TransformOrigin(0.5f, 1f)
                    }
                    compositingStrategy = CompositingStrategy.Offscreen
                }
                .drawWithContent {
                    drawContent()
                    val p = progress(word, pos.value)
                    if (p >= 1f) return@drawWithContent
                    val dim = Color.White.copy(alpha = DIM)
                    if (p <= 0f) {
                        drawRect(dim, blendMode = BlendMode.DstIn)
                        return@drawWithContent
                    }
                    val w = size.width
                    val edge = p * (w + feather) - feather / 2
                    val a = ((edge - feather / 2) / w).coerceIn(0f, 1f)
                    val b = ((edge + feather / 2) / w).coerceIn(0f, 1f)
                    drawRect(
                        Brush.horizontalGradient(
                            0f to Color.White,
                            a to Color.White,
                            max(b, a + 0.0001f).coerceAtMost(1f) to dim,
                            1f to dim,
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                },
        )
    }
}

private fun progress(w: LyricWord, p: Long): Float = when {
    p <= w.startMs -> 0f
    p >= w.endMs -> 1f
    else -> (p - w.startMs).toFloat() / (w.endMs - w.startMs)
}

private fun easeOutCubic(t: Float): Float {
    val u = 1 - t
    return 1 - u * u * u
}

/** Rises quickly while a held word is sung, then relaxes once it is done. */
private fun swell(p: Float): Float = when {
    p <= 0f || p >= 1f -> 0f
    p < 0.25f -> easeOutCubic(p / 0.25f)
    p < 0.8f -> 1f
    else -> 1f - (p - 0.8f) / 0.2f
}

@Composable
private fun GapDots(row: Row.Gap, pos: State<Long>, active: Boolean, horizontalPadding: Dp) {
    AnimatedVisibility(
        visible = active,
        enter = expandVertically(spring(stiffness = Spring.StiffnessLow)) + fadeIn(tween(400)),
        exit = shrinkVertically(tween(450)) + fadeOut(tween(250)),
    ) {
        Row(
            Modifier
                .padding(horizontal = horizontalPadding + 4.dp, vertical = 16.dp)
                .graphicsLayer {
                    val now = pos.value
                    val span = (row.endMs - row.startMs).coerceAtLeast(1)
                    val elapsed = now - row.startMs
                    val remaining = row.endMs - now
                    // Slow breathing, then a quick shrink right before the next line starts.
                    val breathe = 1f + 0.08f * sin(elapsed / 1000f * PI.toFloat() * 1.1f)
                    val outro = (remaining / 450f).coerceIn(0f, 1f)
                    val intro = (elapsed / 350f).coerceIn(0f, 1f)
                    val s = breathe * easeOutCubic(intro) * (0.3f + 0.7f * outro)
                    scaleX = s; scaleY = s
                    alpha = outro
                    transformOrigin = TransformOrigin(0f, 0.5f)
                    if (span <= 0) alpha = 0f
                },
            horizontalArrangement = Arrangement.spacedBy(11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(3) { i ->
                Box(
                    Modifier
                        .size(13.dp)
                        .graphicsLayer {
                            val span = (row.endMs - row.startMs).coerceAtLeast(1)
                            val p = ((pos.value - row.startMs).toFloat() / span).coerceIn(0f, 1f)
                            val local = (p * 3 - i).coerceIn(0f, 1f)
                            alpha = DIM + (1 - DIM) * local
                            val s = 0.85f + 0.15f * local
                            scaleX = s; scaleY = s
                        }
                        .clip(CircleShape)
                        .background(Color.White),
                )
            }
        }
    }
    if (!active) Spacer(Modifier.height(4.dp))
}
