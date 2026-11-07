package dev.kamol1dn.syllable.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.kamol1dn.syllable.MainViewModel
import dev.kamol1dn.syllable.recognition.RecognitionEvent

@Composable
fun ListenScreen(vm: MainViewModel, onBack: () -> Unit, onShowLyrics: () -> Unit) {
    val context = LocalContext.current
    val event by vm.recognition.collectAsStateWithLifecycle()

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.startListening()
    }
    fun start() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            vm.startListening()
        } else {
            permission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    // Opening this screen starts listening right away, like tapping the Shazam button.
    LaunchedEffect(Unit) { if (event == null) start() }
    DisposableEffect(Unit) { onDispose { vm.stopListening() } }

    // Once a song is found, its cover becomes the background.
    val cover = rememberCoverBitmap((event as? RecognitionEvent.Found)?.track?.coverUrl)
    ArtBackground(if (event is RecognitionEvent.Found) cover else null) {
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            val wide = isWideLayout(maxWidth, maxHeight)
            val maxH = maxHeight
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.padding(8.dp)) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White) }
                }
                AnimatedContent(
                    targetState = event,
                    contentKey = { it is RecognitionEvent.Found },
                    transitionSpec = {
                        (fadeIn(tween(400)) + scaleIn(spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessLow), initialScale = 0.9f)) togetherWith
                            (fadeOut(tween(200)) + scaleOut(tween(250), targetScale = 1.05f))
                    },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    label = "listen",
                ) { e ->
                    when (e) {
                        is RecognitionEvent.Found -> ResultCard(
                            e, wide, maxH,
                            onLyrics = { vm.showRecognizedLyrics(e.track); onShowLyrics() },
                            onAgain = ::start,
                        )
                        else -> ListeningButton(e, wide, maxH, onTap = {
                            if (e is RecognitionEvent.Listening) vm.stopListening() else start()
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun ListeningButton(e: RecognitionEvent?, wide: Boolean, maxH: Dp, onTap: () -> Unit) {
    val listening = e is RecognitionEvent.Listening
    val level by animateFloatAsState(
        (e as? RecognitionEvent.Listening)?.level ?: 0f,
        spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium),
        label = "lvl",
    )
    val t = rememberInfiniteTransition(label = "pulse")
    val ring by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart), label = "ring")
    val idle by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2400), RepeatMode.Reverse), label = "idle")
    val ringSize = if (wide) (maxH - 72.dp).coerceIn(160.dp, 280.dp) else 280.dp

    val title = when (e) {
        is RecognitionEvent.Listening -> "Listening…"
        RecognitionEvent.NoMatch -> "No match found"
        is RecognitionEvent.Failed -> e.message
        RecognitionEvent.Matching -> "Matching…"
        else -> "Tap to identify"
    }
    val subtitle = when (e) {
        is RecognitionEvent.Listening -> "Hold your phone near the music"
        RecognitionEvent.NoMatch -> "Get closer to the speaker and try again"
        else -> " "
    }

    @Composable
    fun Texts(align: Alignment.Horizontal, textAlign: TextAlign) {
        Column(horizontalAlignment = align) {
            AnimatedContent(
                targetState = title,
                transitionSpec = { (fadeIn() + slideInVertically { it / 3 }) togetherWith (fadeOut() + slideOutVertically { -it / 3 }) },
                label = "title",
            ) { s ->
                Text(s, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, textAlign = textAlign)
            }
            Text(subtitle, color = Color.White.copy(alpha = 0.65f), modifier = Modifier.padding(top = 6.dp), textAlign = textAlign)
        }
    }

    @Composable
    fun RecordButton() {
        val interaction = remember { MutableInteractionSource() }
        Box(Modifier.size(ringSize), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val base = size.minDimension / 2 * 0.57f
                if (listening) {
                    for (k in 0 until 3) {
                        val p = (ring + k / 3f) % 1f
                        drawCircle(
                            Color.White.copy(alpha = (1 - p) * (0.25f + 0.25f * level)),
                            radius = base + (size.minDimension / 2 - base) * p,
                            style = Stroke(width = (2f + 3f * (1 - p)).dp.toPx()),
                        )
                    }
                    drawCircle(Color.White.copy(alpha = 0.12f + 0.1f * level), radius = base * (1.05f + level * 0.45f))
                } else {
                    // Gentle idle halo so the button invites a tap.
                    drawCircle(Color.White.copy(alpha = 0.06f + 0.05f * idle), radius = base * (1.08f + 0.06f * idle))
                }
            }
            Box(
                Modifier.pressScale(interaction)
                    .graphicsLayer { val s = 1f + level * 0.07f; scaleX = s; scaleY = s }
                    .size(ringSize * 0.57f).clip(CircleShape)
                    .background(Brush.linearGradient(listOf(Accent, AccentViolet)))
                    .clickable(interactionSource = interaction, indication = null, onClick = onTap),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.GraphicEq, "Listen", tint = Color.White, modifier = Modifier.size(ringSize * 0.3f))
            }
        }
    }

    if (wide) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 32.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) { Texts(Alignment.Start, TextAlign.Start) }
            RecordButton()
        }
    } else {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Texts(Alignment.CenterHorizontally, TextAlign.Center)
            Spacer(Modifier.height(56.dp))
            RecordButton()
        }
    }
}

@Composable
private fun ResultCard(e: RecognitionEvent.Found, wide: Boolean, maxH: Dp, onLyrics: () -> Unit, onAgain: () -> Unit) {
    val t = e.track
    // The cover pops in with the card, the text follows a moment later.
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val artScale by animateFloatAsState(if (shown) 1f else 0.82f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessLow), label = "art")
    val textAlpha by animateFloatAsState(if (shown) 1f else 0f, tween(500, delayMillis = 150), label = "text")
    val artSize = if (wide) (maxH - 88.dp).coerceIn(140.dp, 260.dp) else 260.dp

    @Composable
    fun Art() {
        AsyncImage(
            model = t.coverUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(artSize).graphicsLayer { scaleX = artScale; scaleY = artScale }
                .clip(RoundedCornerShape(18.dp)).background(Color.White.copy(alpha = 0.08f)),
        )
    }

    @Composable
    fun Info(align: Alignment.Horizontal, textAlign: TextAlign) {
        Column(Modifier.graphicsLayer { alpha = textAlpha; translationY = (1 - textAlpha) * 24f }, horizontalAlignment = align) {
            Text(t.title, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, textAlign = textAlign)
            Text(t.artist, color = Color.White.copy(alpha = 0.75f), fontSize = 18.sp, textAlign = textAlign)
            t.album?.let { Text(it, color = Color.White.copy(alpha = 0.5f), fontSize = 14.sp, textAlign = textAlign) }
            Spacer(Modifier.height(if (wide) 20.dp else 32.dp))
            Button(onClick = onLyrics, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp)) {
                Icon(Icons.Rounded.Lyrics, null)
                Spacer(Modifier.width(8.dp))
                Text("Lyrics", fontWeight = FontWeight.Bold, fontSize = 17.sp)
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = onAgain, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp)) {
                Text("Listen again", color = Color.White)
            }
        }
    }

    if (wide) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Art()
            Spacer(Modifier.width(32.dp))
            Box(Modifier.widthIn(max = 380.dp).verticalScroll(rememberScrollState())) { Info(Alignment.Start, TextAlign.Start) }
        }
    } else {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Art()
            Spacer(Modifier.height(24.dp))
            Info(Alignment.CenterHorizontally, TextAlign.Center)
        }
    }
}
