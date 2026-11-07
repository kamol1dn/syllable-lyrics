package dev.kamol1dn.syllable.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
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

    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0xFF2A0F3D), Color(0xFF0E1A4A), Color(0xFF07060C))),
        ),
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.padding(8.dp)) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White) }
            }
            AnimatedContent(
                targetState = event?.let { it::class } ,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                modifier = Modifier.weight(1f).fillMaxWidth(),
                label = "listen",
            ) { _ ->
                when (val e = event) {
                    is RecognitionEvent.Found -> ResultCard(
                        e,
                        onLyrics = { vm.showRecognizedLyrics(e.track); onShowLyrics() },
                        onAgain = ::start,
                    )
                    else -> ListeningButton(e, onTap = {
                        if (e is RecognitionEvent.Listening) vm.stopListening() else start()
                    })
                }
            }
        }
    }
}

@Composable
private fun ListeningButton(e: RecognitionEvent?, onTap: () -> Unit) {
    val listening = e is RecognitionEvent.Listening
    val level by animateFloatAsState((e as? RecognitionEvent.Listening)?.level ?: 0f, tween(120), label = "lvl")
    val t = rememberInfiniteTransition(label = "pulse")
    val ring by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart), label = "ring")

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            when (e) {
                is RecognitionEvent.Listening -> "Listening…"
                RecognitionEvent.NoMatch -> "Couldn't recognise that"
                is RecognitionEvent.Failed -> e.message
                RecognitionEvent.Matching -> "Matching…"
                else -> "Tap to identify"
            },
            color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center,
        )
        Text(
            when (e) {
                is RecognitionEvent.Listening -> "Hold your phone near the music"
                RecognitionEvent.NoMatch -> "Get closer to the speaker and try again"
                else -> " "
            },
            color = Color.White.copy(alpha = 0.65f), modifier = Modifier.padding(top = 6.dp),
        )
        Spacer(Modifier.height(56.dp))
        Box(Modifier.size(280.dp), contentAlignment = Alignment.Center) {
            if (listening) {
                Canvas(Modifier.fillMaxSize()) {
                    val base = size.minDimension / 2 * 0.55f
                    for (k in 0 until 3) {
                        val p = (ring + k / 3f) % 1f
                        drawCircle(
                            Color.White.copy(alpha = (1 - p) * 0.35f),
                            radius = base + (size.minDimension / 2 - base) * p,
                            style = Stroke(width = 3.dp.toPx()),
                        )
                    }
                    drawCircle(Color.White.copy(alpha = 0.12f), radius = base * (1.05f + level * 0.5f))
                }
            }
            Box(
                Modifier.size(160.dp).clip(CircleShape)
                    .background(Brush.linearGradient(listOf(Accent, AccentViolet)))
                    .clickable(onClick = onTap),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.GraphicEq, "Listen", tint = Color.White, modifier = Modifier.size(84.dp))
            }
        }
    }
}

@Composable
private fun ResultCard(e: RecognitionEvent.Found, onLyrics: () -> Unit, onAgain: () -> Unit) {
    val t = e.track
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        AsyncImage(
            model = t.coverUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(260.dp).clip(RoundedCornerShape(18.dp)).background(Color.White.copy(alpha = 0.08f)),
        )
        Spacer(Modifier.height(24.dp))
        Text(t.title, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center)
        Text(t.artist, color = Color.White.copy(alpha = 0.75f), fontSize = 18.sp, textAlign = TextAlign.Center)
        t.album?.let { Text(it, color = Color.White.copy(alpha = 0.5f), fontSize = 14.sp, textAlign = TextAlign.Center) }
        Spacer(Modifier.height(32.dp))
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
