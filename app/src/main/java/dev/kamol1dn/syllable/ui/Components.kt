package dev.kamol1dn.syllable.ui

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.lerp
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.sin

val Accent = Color(0xFFFF4E8A)
val AccentViolet = Color(0xFF6A3CFF)

private val Scheme: ColorScheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    secondary = AccentViolet,
    background = Color(0xFF0B0712),
    surface = Color(0xFF16101F),
    surfaceVariant = Color(0xFF241B30),
    onSurface = Color.White,
    onBackground = Color.White,
)

@Composable
fun SyllableTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}

data class ArtColors(val a: Color, val b: Color, val c: Color)

private val DefaultColors = ArtColors(Color(0xFF3B1E54), Color(0xFF7A1F4D), Color(0xFF1C2A5C))

private fun Color.darken(f: Float) = lerp(this, Color.Black, f)

suspend fun extractColors(bitmap: Bitmap?): ArtColors = withContext(Dispatchers.Default) {
    if (bitmap == null || bitmap.isRecycled) return@withContext DefaultColors
    runCatching {
        val p = Palette.from(bitmap).maximumColorCount(16).generate()
        val dom = p.getDominantColor(0xFF3B1E54.toInt())
        val vib = p.getVibrantColor(p.getLightVibrantColor(dom))
        val mut = p.getDarkMutedColor(p.getMutedColor(dom))
        ArtColors(Color(dom).darken(0.45f), Color(vib).darken(0.4f), Color(mut).darken(0.35f))
    }.getOrDefault(DefaultColors)
}

/** Slowly drifting colour blobs from the artwork, with the blurred artwork itself on Android 12+. */
@Composable
fun ArtBackground(artwork: Bitmap?, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    var colors by remember { mutableStateOf(DefaultColors) }
    LaunchedEffect(artwork) { colors = extractColors(artwork) }
    val a by animateColorAsState(colors.a, tween(1200), label = "a")
    val b by animateColorAsState(colors.b, tween(1200), label = "b")
    val c by animateColorAsState(colors.c, tween(1200), label = "c")
    val t = rememberInfiniteTransition(label = "bg")
    val phase by t.animateFloat(0f, (2 * Math.PI).toFloat(), infiniteRepeatable(tween(24_000, easing = LinearEasing), RepeatMode.Restart), label = "phase")

    Box(modifier.fillMaxSize().background(a)) {
        Box(
            Modifier.fillMaxSize().drawBehind {
                val w = size.width
                val h = size.height
                val r = maxOf(w, h) * 0.75f
                fun blob(color: Color, cx: Float, cy: Float) = drawCircle(
                    Brush.radialGradient(listOf(color, color.copy(alpha = 0f)), Offset(cx, cy), r), r, Offset(cx, cy),
                )
                blob(b, w * (0.3f + 0.25f * cos(phase)), h * (0.25f + 0.15f * sin(phase)))
                blob(c, w * (0.75f + 0.2f * sin(phase * 1.3f)), h * (0.7f + 0.18f * cos(phase)))
                blob(b.copy(alpha = 0.7f), w * (0.5f + 0.3f * sin(phase + 2f)), h * (0.95f + 0.1f * cos(phase * 0.7f)))
            },
        )
        if (artwork != null && Build.VERSION.SDK_INT >= 31) {
            Image(
                bitmap = remember(artwork) { artwork.asImageBitmap() },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = 0.35f
                        scaleX = 1.6f; scaleY = 1.6f
                        rotationZ = phase * 57.3f / 6f
                    }
                    .blur(90.dp()),
            )
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.15f), Color.Black.copy(alpha = 0.45f)))))
        content()
    }
}

private fun Int.dp() = androidx.compose.ui.unit.Dp(this.toFloat())
