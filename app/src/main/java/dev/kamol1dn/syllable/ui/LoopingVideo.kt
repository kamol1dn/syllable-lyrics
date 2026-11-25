package dev.kamol1dn.syllable.ui

import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File

/**
 * Plays the video [file] silently in a loop, cropped to fill its box. It fades in once its first
 * frame is there, so a file that turns out not to play leaves whatever is underneath as it was.
 * It stops while the app is in the background.
 */
@Composable
fun LoopingVideo(file: File, modifier: Modifier = Modifier, maxAlpha: Float = 1f) {
    var ready by remember(file) { mutableStateOf(false) }
    val alpha by animateFloatAsState(
        if (ready) maxAlpha else 0f, tween(500, easing = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)), label = "video",
    )
    val holder = remember(file) { VideoHolder(file) { ready = true } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(holder, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> holder.resume()
                Lifecycle.Event.ON_STOP -> holder.pause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            holder.release()
        }
    }
    key(file) {
        AndroidView(
            factory = { context ->
                TextureView(context).apply {
                    isOpaque = false
                    holder.transform = { setTransform(it) }
                    surfaceTextureListener = holder
                }
            },
            modifier = modifier.graphicsLayer { this.alpha = alpha },
            onRelease = { it.surfaceTextureListener = null },
        )
    }
}

private class VideoHolder(private val file: File, private val onFirstFrame: () -> Unit) : TextureView.SurfaceTextureListener {
    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var viewW = 0
    private var viewH = 0
    private var shown = false
    /** Sets the view's transform. The view gives this when it is made. */
    var transform: ((Matrix) -> Unit)? = null

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        viewW = width
        viewH = height
        val s = Surface(texture).also { surface = it }
        runCatching {
            player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setSurface(s)
                isLooping = true
                setVolume(0f, 0f)
                setOnVideoSizeChangedListener { _, _, _ -> fit() }
                setOnPreparedListener { fit(); it.start() }
                setOnErrorListener { _, _, _ -> true }
                prepareAsync()
            }
        }
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
        viewW = width
        viewH = height
        fit()
    }

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        release()
        return true
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
        if (!shown) {
            shown = true
            onFirstFrame()
        }
    }

    /** The texture is stretched to the view by default. This scales it back so the video fills the view and is cropped. */
    private fun fit() {
        val p = player ?: return
        val vw = runCatching { p.videoWidth }.getOrDefault(0)
        val vh = runCatching { p.videoHeight }.getOrDefault(0)
        if (vw <= 0 || vh <= 0 || viewW <= 0 || viewH <= 0) return
        val scale = maxOf(viewW.toFloat() / vw, viewH.toFloat() / vh)
        val m = Matrix()
        m.setScale(vw * scale / viewW, vh * scale / viewH, viewW / 2f, viewH / 2f)
        transform?.invoke(m)
    }

    fun pause() { runCatching { player?.takeIf { it.isPlaying }?.pause() } }
    fun resume() { runCatching { if (shown) player?.start() } }

    fun release() {
        runCatching { player?.release() }
        player = null
        runCatching { surface?.release() }
        surface = null
    }
}
