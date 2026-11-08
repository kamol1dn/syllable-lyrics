package dev.kamol1dn.syllable.ui

import android.graphics.Bitmap
import android.util.LruCache
import kotlinx.coroutines.delay
import android.graphics.BitmapShader
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.floor
import kotlin.math.sin

/*
 * Moving album-art background in the style of Spicy Lyrics. The technique follows Kawarp
 * (github.com/better-lyrics/kawarp, MIT): blur a small copy of the cover with Kawase passes,
 * boost its saturation, then warp it every frame with two octaves of simplex noise.
 */

private const val BLUR_SIZE = 128
private const val BLUR_PASSES = 8
private const val SATURATION = 1.5f
private const val PLAYING_SPEED = 1f
private const val PAUSED_SPEED = 0.1f

/** Loads a cover image from [url] as a software bitmap, so its pixels can be read. */
@Composable
fun rememberCoverBitmap(url: String?, key: Any? = url): Bitmap? {
    val context = LocalContext.current
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var loadedFor by remember { mutableStateOf<Any?>(null) }
    LaunchedEffect(url, key) {
        if (url == null) { bitmap = null; loadedFor = null; return@LaunchedEffect }
        val req = ImageRequest.Builder(context).data(url).allowHardware(false).build()
        val result = (context.imageLoader.execute(req) as? SuccessResult)?.image?.toBitmap()
        if (result != null) {
            bitmap = result
            loadedFor = key
        } else if (loadedFor != key) {
            bitmap = null // the new song's cover failed, so don't keep showing the last song's
        }
    }
    return bitmap
}

/** Stand-in cover, blurred ahead of time so the first frame already shows it. */
private val DefaultCover: PreparedCover by lazy { prepareCover(defaultCover()) }

fun warmDefaultCover() {
    DefaultCover.luminance
}

/** Recently blurred covers, so a screen that comes back shows its background right away. */
private val preparedCovers = LruCache<Int, PreparedCover>(6)

/** Last background clock value, so the pattern carries on across screens. */
private var lastClock = 0f

/**
 * Full-screen background made from [artwork]. It drifts at full speed while [playing] and
 * slows almost to a stop when paused. Without artwork it uses the app's own colours.
 */
@Composable
fun ArtBackground(
    artwork: Bitmap?,
    modifier: Modifier = Modifier,
    playing: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val source = artwork?.takeIf { !it.isRecycled }
    var blurred by remember {
        mutableStateOf(source?.let { preparedCovers[it.generationId] } ?: if (artwork == null) DefaultCover else null)
    }
    LaunchedEffect(artwork) {
        val art = artwork?.takeIf { !it.isRecycled }
        if (art != null) preparedCovers[art.generationId]?.let { blurred = it; return@LaunchedEffect }
        // Players often send the new title a moment before its artwork; hold the current background through that gap.
        if (art == null && blurred != null && blurred !== DefaultCover) delay(1_500)
        blurred = if (art == null) {
            DefaultCover
        } else {
            withContext(Dispatchers.Default) { runCatching { prepareCover(art) }.getOrNull() }
                ?.also { preparedCovers.put(art.generationId, it) } ?: DefaultCover
        }
    }
    // Brighter covers get a darker veil so white lyrics stay readable.
    val veil by animateFloatAsState(0.15f + 0.45f * (blurred?.luminance ?: 0.3f), tween(1000), label = "veil")

    // Shared clock: speed eases toward its target like Kawarp's, so pausing glides to a halt.
    val time = remember { mutableFloatStateOf(lastClock) }
    val targetSpeed by rememberUpdatedState(if (playing) PLAYING_SPEED else PAUSED_SPEED)
    LaunchedEffect(Unit) {
        var speed = targetSpeed
        var last = 0L
        while (true) withFrameNanos { now ->
            if (last != 0L) {
                val dt = ((now - last) / 1e9f).coerceAtMost(0.1f)
                speed += (targetSpeed - speed) * 0.05f
                time.floatValue += dt * speed
                lastClock = time.floatValue
            }
            last = now
        }
    }

    Box(modifier.fillMaxSize().background(AppBg)) {
        Crossfade(blurred?.bitmap, animationSpec = tween(1000), label = "cover") { bmp ->
            if (bmp != null) {
                if (Build.VERSION.SDK_INT >= 33) FluidLayer(bmp, time) else DriftLayer(bmp, time)
            }
        }
        Box(
            Modifier.fillMaxSize().drawBehind {
                drawRect(
                    // A darker band at the top keeps the song title and source readable.
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = (veil + 0.2f).coerceAtMost(0.8f)),
                        0.22f to Color.Black.copy(alpha = veil),
                        1f to Color.Black.copy(alpha = (veil + 0.15f).coerceAtMost(0.8f)),
                    ),
                )
            },
        )
        content()
    }
}

@RequiresApi(33)
@Composable
private fun FluidLayer(bitmap: Bitmap, time: State<Float>) {
    val shader = remember { RuntimeShader(FLUID_SHADER) }
    val image = remember(bitmap) {
        BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            filterMode = BitmapShader.FILTER_MODE_LINEAR
        }
    }
    val brush = remember(shader) { ShaderBrush(shader) }
    Box(
        Modifier.fillMaxSize().drawBehind {
            shader.setInputShader("image", image)
            shader.setFloatUniform("size", size.width, size.height)
            shader.setFloatUniform("texSize", bitmap.width.toFloat(), bitmap.height.toFloat())
            shader.setFloatUniform("time", time.value)
            drawRect(brush)
        },
    )
}

/** Android 12 and older: no runtime shaders, so the blurred cover slowly turns and breathes instead. */
@Composable
private fun DriftLayer(bitmap: Bitmap, time: State<Float>) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    Image(
        bitmap = image,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        filterQuality = FilterQuality.High,
        modifier = Modifier.fillMaxSize().graphicsLayer {
            val t = time.value
            val s = 1.8f + 0.2f * sin(t * 0.21f)
            scaleX = s; scaleY = s
            rotationZ = t * 4f
        },
    )
}

private class PreparedCover(val bitmap: Bitmap, val luminance: Float)

/** Shrinks the cover, applies the Kawase blur passes and boosts saturation. */
private fun prepareCover(src: Bitmap): PreparedCover {
    val n = BLUR_SIZE
    val scaled = Bitmap.createScaledBitmap(src, n, n, true)
    val px = IntArray(n * n)
    scaled.getPixels(px, 0, n, 0, 0, n, n)
    var r = FloatArray(n * n) { (px[it] shr 16 and 0xFF) / 255f }
    var g = FloatArray(n * n) { (px[it] shr 8 and 0xFF) / 255f }
    var b = FloatArray(n * n) { (px[it] and 0xFF) / 255f }
    var r2 = FloatArray(n * n); var g2 = FloatArray(n * n); var b2 = FloatArray(n * n)

    // Bilinear sample at (x, y) in texel units, texel centres at i + 0.5, edges clamped.
    fun sample(ch: FloatArray, x: Float, y: Float): Float {
        val fx = (x - 0.5f).coerceIn(0f, n - 1f)
        val fy = (y - 0.5f).coerceIn(0f, n - 1f)
        val x0 = floor(fx).toInt(); val y0 = floor(fy).toInt()
        val x1 = minOf(x0 + 1, n - 1); val y1 = minOf(y0 + 1, n - 1)
        val tx = fx - x0; val ty = fy - y0
        val top = ch[y0 * n + x0] * (1 - tx) + ch[y0 * n + x1] * tx
        val bot = ch[y1 * n + x0] * (1 - tx) + ch[y1 * n + x1] * tx
        return top * (1 - ty) + bot * ty
    }

    for (pass in 0 until BLUR_PASSES) {
        val o = pass + 0.5f
        for (y in 0 until n) for (x in 0 until n) {
            val cx = x + 0.5f; val cy = y + 0.5f
            val i = y * n + x
            r2[i] = (sample(r, cx - o, cy - o) + sample(r, cx + o, cy - o) + sample(r, cx - o, cy + o) + sample(r, cx + o, cy + o)) * 0.25f
            g2[i] = (sample(g, cx - o, cy - o) + sample(g, cx + o, cy - o) + sample(g, cx - o, cy + o) + sample(g, cx + o, cy + o)) * 0.25f
            b2[i] = (sample(b, cx - o, cy - o) + sample(b, cx + o, cy - o) + sample(b, cx - o, cy + o) + sample(b, cx + o, cy + o)) * 0.25f
        }
        r = r2.also { r2 = r }; g = g2.also { g2 = g }; b = b2.also { b2 = b }
    }

    val out = IntArray(n * n)
    var lumaSum = 0f
    for (i in 0 until n * n) {
        lumaSum += 0.2126f * r[i] + 0.7152f * g[i] + 0.0722f * b[i]
        val gray = 0.299f * r[i] + 0.587f * g[i] + 0.114f * b[i]
        fun sat(c: Float) = ((gray + (c - gray) * SATURATION).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        out[i] = (0xFF shl 24) or (sat(r[i]) shl 16) or (sat(g[i]) shl 8) or sat(b[i])
    }
    return PreparedCover(Bitmap.createBitmap(out, n, n, Bitmap.Config.ARGB_8888), lumaSum / (n * n))
}

/** Stand-in cover in the app's colours, used before any song is known. */
private fun defaultCover(): Bitmap {
    val colors = listOf(
        Color(0xFF6A1E8C), Color(0xFF1C2A7C), Color(0xFFB0206A),
        Color(0xFF241050), Color(0xFF3B1E74), Color(0xFF0E1A4A),
        Color(0xFF8A2060), Color(0xFF2A0F3D), Color(0xFF4A2A9A),
    ).map { it.toArgb() }.toIntArray()
    return Bitmap.createBitmap(colors, 3, 3, Bitmap.Config.ARGB_8888)
}

private const val FLUID_SHADER = """
uniform shader image;
uniform float2 size;
uniform float2 texSize;
uniform float time;

float3 mod289v3(float3 x) { return x - floor(x * (1.0 / 289.0)) * 289.0; }
float2 mod289v2(float2 x) { return x - floor(x * (1.0 / 289.0)) * 289.0; }
float3 permute(float3 x) { return mod289v3(((x * 34.0) + 1.0) * x); }

// 2D simplex noise (Ashima Arts, MIT).
float snoise(float2 v) {
    const float4 C = float4(0.211324865405187, 0.366025403784439, -0.577350269189626, 0.024390243902439);
    float2 i = floor(v + dot(v, C.yy));
    float2 x0 = v - i + dot(i, C.xx);
    float2 i1 = (x0.x > x0.y) ? float2(1.0, 0.0) : float2(0.0, 1.0);
    float4 x12 = x0.xyxy + C.xxzz;
    x12.xy -= i1;
    i = mod289v2(i);
    float3 p = permute(permute(i.y + float3(0.0, i1.y, 1.0)) + i.x + float3(0.0, i1.x, 1.0));
    float3 m = max(0.5 - float3(dot(x0, x0), dot(x12.xy, x12.xy), dot(x12.zw, x12.zw)), 0.0);
    m = m * m;
    m = m * m;
    float3 x = 2.0 * fract(p * C.www) - 1.0;
    float3 h = abs(x) - 0.5;
    float3 ox = floor(x + 0.5);
    float3 a0 = x - ox;
    m *= 1.79284291400159 - 0.85373472095314 * (a0 * a0 + h * h);
    float3 g;
    g.x = a0.x * x0.x + h.x * x0.y;
    g.yz = a0.yz * x12.xz + h.yz * x12.yw;
    return 130.0 * dot(m, g);
}

float hash(float3 p) {
    p = fract(p * 0.1031);
    p += dot(p, p.zyx + 31.32);
    return fract((p.x + p.y) * p.z);
}

half4 main(float2 fragCoord) {
    float2 uv = fragCoord / size;
    float t = time * 0.05;
    float2 center = uv - 0.5;
    float centerWeight = 1.0 - smoothstep(0.0, 0.7, length(center));

    // Big slow blobs plus a finer layer of movement.
    float n1 = snoise(uv * 0.35 + float2(t, t * 0.7));
    float n2 = snoise(uv * 0.35 + float2(-t * 0.8, t * 0.5) + float2(50.0, 50.0));
    float n3 = snoise(uv * 0.9 + float2(t * 1.2, -t) + float2(100.0, 0.0));
    float n4 = snoise(uv * 0.9 + float2(-t, t * 1.1) + float2(0.0, 100.0));
    float2 warp = float2(n1 * 0.65 + n3 * 0.35, n2 * 0.65 + n4 * 0.35) * centerWeight;
    float2 w = clamp(uv + warp, 0.0, 1.0);

    half4 color = image.eval(w * texSize);
    color.rgb *= half(1.0 - dot(center, center) * 0.3);
    float noise = hash(float3(floor(fragCoord), floor(time * 600.0)));
    color.rgb += half((noise - 0.5) * 0.008);
    return color;
}
"""
