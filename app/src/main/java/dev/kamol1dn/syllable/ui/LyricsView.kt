package dev.kamol1dn.syllable.ui

import android.os.Build
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kamol1dn.syllable.lyrics.LyricLine
import dev.kamol1dn.syllable.lyrics.LyricWord
import dev.kamol1dn.syllable.lyrics.Lyrics
import dev.kamol1dn.syllable.lyrics.SyncType
import kotlinx.coroutines.delay
import kotlin.math.max

private const val DIM = 0.32f

/** A row in the list: a sung line, or an instrumental gap shown as breathing dots. */
private sealed interface Row {
    val startMs: Long
    val endMs: Long
    data class Line(val line: LyricLine, override val startMs: Long, override val endMs: Long) : Row
    data class Gap(override val startMs: Long, override val endMs: Long) : Row
}

private fun buildRows(lyrics: Lyrics): List<Row> {
    if (lyrics.sync == SyncType.NONE) return lyrics.lines.map { Row.Line(it, -1, -1) }
    val rows = mutableListOf<Row>()
    val first = lyrics.lines.firstOrNull() ?: return rows
    if (first.startMs > 4_000) rows += Row.Gap(0, first.startMs)
    lyrics.lines.forEachIndexed { i, l ->
        val next = lyrics.lines.getOrNull(i + 1)
        rows += Row.Line(l, l.startMs, l.endMs)
        if (next != null && next.startMs - l.endMs > 5_000) rows += Row.Gap(l.endMs + 400, next.startMs)
    }
    return rows
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
) {
    val rows = remember(lyrics) { buildRows(lyrics) }
    val synced = lyrics.sync != SyncType.NONE

    // Frame-accurate position; read only in draw/layer lambdas and derived states.
    val pos = remember { mutableLongStateOf(position()) }
    LaunchedEffect(position) {
        while (true) withFrameMillis { pos.longValue = position() }
    }

    val active by remember(rows) {
        derivedStateOf {
            if (!synced) -1 else {
                val p = pos.longValue
                var idx = -1
                for (i in rows.indices) {
                    val r = rows[i]
                    if (r.startMs <= p) idx = i else break
                }
                idx
            }
        }
    }

    val listState = rememberLazyListState()
    val dragged by listState.interactionSource.collectIsDraggedAsState()
    var lastUserScroll by remember { mutableLongStateOf(0L) }
    LaunchedEffect(dragged) { if (dragged) lastUserScroll = System.currentTimeMillis() }
    var userBrowsing by remember { mutableStateOf(false) }
    LaunchedEffect(lastUserScroll) {
        if (lastUserScroll == 0L) return@LaunchedEffect
        userBrowsing = true
        delay(3_500)
        userBrowsing = false
    }

    BoxWithConstraints(modifier) {
        val viewport = with(LocalDensity.current) { maxHeight.toPx() }
        LaunchedEffect(active, userBrowsing, viewport) {
            if (!synced || userBrowsing || dragged) return@LaunchedEffect
            val target = max(active, 0)
            val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == target }
            // Item offsets are measured from below the top content padding, which already
            // places the current line about a third of the way down.
            val anchor = 0f
            if (info != null) {
                listState.animateScrollBy(
                    info.offset - anchor,
                    tween(durationMillis = 650, easing = FastOutSlowInEasing),
                )
            } else {
                listState.scrollToItem(target, -anchor.toInt())
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = contentPadding.calculateTopPadding() + maxHeight * 0.3f,
                bottom = contentPadding.calculateBottomPadding() + maxHeight * 0.6f,
            ),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            itemsIndexed(rows, key = { i, _ -> i }) { i, row ->
                val isActive = i == active
                val distance = if (active < 0) 0 else kotlin.math.abs(i - active)
                when (row) {
                    is Row.Gap -> GapDots(row, pos, isActive)
                    is Row.Line -> LineView(
                        line = row.line,
                        synced = synced,
                        isActive = isActive,
                        isPast = active >= 0 && i < active,
                        distance = distance,
                        pos = pos,
                        textScale = textScale,
                        blur = blurInactive && synced && !userBrowsing,
                        onClick = if (synced && onSeek != null) ({ onSeek(row.line.startMs) }) else null,
                    )
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
    onClick: (() -> Unit)?,
) {
    val scale by animateFloatAsState(if (isActive || !synced) 1f else 0.94f, tween(500), label = "scale")
    val blurRadius = if (blur && !isActive && Build.VERSION.SDK_INT >= 31) (distance.coerceAtMost(4) * 0.7f).dp else 0.dp
    val style = TextStyle(
        fontSize = (30 * textScale).sp,
        lineHeight = (38 * textScale).sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = (-0.3).sp,
        color = Color.White,
    )
    Box(
        Modifier
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 24.dp, vertical = 8.dp)
            .graphicsLayer {
                scaleX = scale; scaleY = scale
                transformOrigin = TransformOrigin(0f, 0.5f)
            }
            .blur(blurRadius),
    ) {
        if (!synced) {
            androidx.compose.material3.Text(line.text, style = style.copy(color = Color.White.copy(alpha = 0.9f)))
        } else if (!isActive) {
            androidx.compose.material3.Text(
                line.text,
                style = style.copy(color = Color.White.copy(alpha = if (isPast) 0.42f else DIM)),
            )
        } else {
            FlowRow {
                line.words.forEach { w -> WordView(w, pos, style) }
            }
        }
    }
}

/** One word with a soft left-to-right "sung" wipe, a small lift and glow while it is being sung. */
@Composable
private fun WordView(word: LyricWord, pos: State<Long>, style: TextStyle) {
    val density = LocalDensity.current
    val feather = with(density) { 14.dp.toPx() }
    val lift = with(density) { 2.5.dp.toPx() }
    androidx.compose.material3.Text(
        text = word.text,
        style = style,
        softWrap = false,
        modifier = Modifier
            .graphicsLayer {
                val p = progress(word, pos.value)
                translationY = -lift * easeOut(p)
                compositingStrategy = CompositingStrategy.Offscreen
            }
            .drawWithContent {
                drawContent()
                val p = progress(word, pos.value)
                val w = size.width
                val edge = p * (w + feather) - feather / 2
                val a = ((edge - feather / 2) / w).coerceIn(0f, 1f)
                val b = ((edge + feather / 2) / w).coerceIn(0f, 1f)
                val brush = when {
                    p >= 1f -> Brush.horizontalGradient(listOf(Color.White, Color.White))
                    p <= 0f -> Brush.horizontalGradient(listOf(Color.White.copy(alpha = DIM), Color.White.copy(alpha = DIM)))
                    else -> Brush.horizontalGradient(
                        0f to Color.White,
                        a to Color.White,
                        max(b, a + 0.0001f).coerceAtMost(1f) to Color.White.copy(alpha = DIM),
                        1f to Color.White.copy(alpha = DIM),
                    )
                }
                drawRect(brush = brush, blendMode = BlendMode.DstIn)
            },
    )
}

private fun progress(w: LyricWord, p: Long): Float = when {
    p <= w.startMs -> 0f
    p >= w.endMs -> 1f
    else -> (p - w.startMs).toFloat() / (w.endMs - w.startMs)
}

private fun easeOut(t: Float) = 1 - (1 - t) * (1 - t)

@Composable
private fun GapDots(row: Row.Gap, pos: State<Long>, active: Boolean) {
    if (!active) {
        Spacer(Modifier.height(8.dp))
        return
    }
    val breathe = rememberInfiniteTransition(label = "gap")
    val s by breathe.animateFloat(0.92f, 1.08f, infiniteRepeatable(tween(1200), RepeatMode.Reverse), label = "s")
    Row(
        Modifier.padding(horizontal = 28.dp, vertical = 14.dp)
            .graphicsLayer { scaleX = s; scaleY = s; transformOrigin = TransformOrigin(0f, 0.5f) },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { i ->
            Box(
                Modifier
                    .size(12.dp)
                    .graphicsLayer {
                        val span = (row.endMs - row.startMs).coerceAtLeast(1)
                        val p = ((pos.value - row.startMs).toFloat() / span).coerceIn(0f, 1f)
                        val local = (p * 3 - i).coerceIn(0f, 1f)
                        alpha = DIM + (1 - DIM) * local
                    }
                    .clip(CircleShape)
                    .background(Color.White),
            )
        }
    }
}
