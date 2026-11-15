package dev.kamol1dn.syllable.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.activity.compose.LocalActivity
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.flow.drop
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kamol1dn.syllable.MainViewModel
import dev.kamol1dn.syllable.recognition.RecognitionEvent

@Composable
fun ListenScreen(vm: MainViewModel, onBack: () -> Unit, onShowLyrics: () -> Unit, entry: Int = 0) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val event by vm.recognition.collectAsStateWithLifecycle()
    var micDenied by rememberSaveable { mutableStateOf(false) }
    var canAskAgain by rememberSaveable { mutableStateOf(true) }

    fun hasMic() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            micDenied = false
            vm.startListening()
        } else {
            micDenied = true
            canAskAgain = activity?.let { ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.RECORD_AUDIO) } ?: false
        }
    }
    fun start() {
        when {
            hasMic() -> { micDenied = false; vm.startListening() }
            micDenied && !canAskAgain -> openAppInfo(context)
            else -> permission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    // Coming back from app settings with the mic turned on starts listening.
    LifecycleResumeEffect(Unit) {
        if (micDenied && hasMic()) { micDenied = false; vm.startListening() }
        onPauseOrDispose { }
    }
    // Opening this screen starts listening right away, like tapping the Shazam button.
    LaunchedEffect(entry) { if (vm.recognition.value == null && !micDenied) start() }
    // Rotating keeps recording; leaving the screen stops it.
    DisposableEffect(Unit) { onDispose { if (activity?.isChangingConfigurations != true) vm.stopListening() } }

    // A short buzz when a song is found or nothing is.
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(Unit) {
        var prev: RecognitionEvent? = vm.recognition.value
        snapshotFlow { event }.drop(1).collect { e ->
            when {
                e is RecognitionEvent.Found && prev !is RecognitionEvent.Found -> haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                e == RecognitionEvent.NoMatch || e is RecognitionEvent.Failed -> haptic.performHapticFeedback(HapticFeedbackType.Reject)
                else -> Unit
            }
            prev = e
        }
    }

    // A match goes straight to its lyrics; the view model has already put the song on screen.
    LaunchedEffect(Unit) {
        snapshotFlow { event }.collect { if (it is RecognitionEvent.Found) onShowLyrics() }
    }

    ArtBackground(null) {
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            val wide = isWideLayout(maxWidth, maxHeight)
            val maxH = maxHeight
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.padding(8.dp)) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White) }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    // The found song keeps the "matching" look for the moment the screen takes to change.
                    val e = event.let { if (it is RecognitionEvent.Found) RecognitionEvent.Matching else it }
                    ListeningButton(e, wide, maxH, micDenied, canAskAgain, onTap = {
                        if (e is RecognitionEvent.Listening || e is RecognitionEvent.Matching) vm.stopListening() else start()
                    })
                }
            }
        }
    }
}

@Composable
private fun ListeningButton(e: RecognitionEvent?, wide: Boolean, maxH: Dp, micDenied: Boolean, canAskAgain: Boolean, onTap: () -> Unit) {
    // The rings keep moving while the song is looked up.
    val listening = e is RecognitionEvent.Listening || e is RecognitionEvent.Matching
    val showDenied = micDenied && !listening
    val level by animateFloatAsState(
        (e as? RecognitionEvent.Listening)?.level ?: 0f,
        spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium),
        label = "lvl",
    )
    val t = rememberInfiniteTransition(label = "pulse")
    val ring by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart), label = "ring")
    val idle by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2400), RepeatMode.Reverse), label = "idle")
    val ringSize = if (wide) (maxH - 72.dp).coerceIn(160.dp, 280.dp) else 280.dp

    val title = when {
        showDenied -> "Mic access is off"
        e is RecognitionEvent.Listening -> "Listening…"
        e == RecognitionEvent.Matching -> "Matching…"
        e == RecognitionEvent.NoMatch -> "No match found"
        e is RecognitionEvent.Failed -> when (e.reason) {
            RecognitionEvent.Failed.Reason.MIC -> "Can't use the mic"
            RecognitionEvent.Failed.Reason.OFFLINE -> "No internet connection"
            RecognitionEvent.Failed.Reason.SERVICE -> "Can't reach Shazam"
        }
        else -> "Tap to identify a song"
    }
    val subtitle = when {
        showDenied && canAskAgain -> "Syllable needs the mic to hear the song around you."
        showDenied -> "Turn on Microphone in app settings to identify songs."
        e is RecognitionEvent.Listening -> "Hold your phone near the music. Tap to stop."
        e == RecognitionEvent.Matching -> "Looking up the song"
        e == RecognitionEvent.NoMatch -> "Move closer to the speaker, then tap to try again."
        e is RecognitionEvent.Failed -> when (e.reason) {
            RecognitionEvent.Failed.Reason.MIC -> "Another app might be using it. Close it and tap to try again."
            RecognitionEvent.Failed.Reason.OFFLINE -> "Check your connection and tap to try again."
            RecognitionEvent.Failed.Reason.SERVICE -> "Try again in a moment."
        }
        else -> " "
    }

    @Composable
    fun Texts(align: Alignment.Horizontal, textAlign: TextAlign) {
        Column(horizontalAlignment = align) {
            // One stable node that TalkBack reads out whenever the status changes.
            Column(
                horizontalAlignment = align,
                modifier = Modifier.clearAndSetSemantics {
                    contentDescription = if (subtitle.isBlank()) title else "$title. $subtitle"
                    liveRegion = LiveRegionMode.Polite
                },
            ) {
                AnimatedContent(
                    targetState = title,
                    transitionSpec = { (fadeIn() + slideInVertically { it / 3 }) togetherWith (fadeOut() + slideOutVertically { -it / 3 }) },
                    label = "title",
                ) { s ->
                    Text(s, color = Color.White, style = MaterialTheme.typography.headlineMedium, textAlign = textAlign)
                }
                Text(subtitle, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.padding(top = 6.dp), textAlign = textAlign)
            }
            if (showDenied) {
                SecondaryButton(if (canAskAgain) "Allow mic" else "Open settings", onTap, Modifier.padding(top = 16.dp))
            }
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
                    .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onTap)
                    .semantics { contentDescription = if (listening) "Stop listening" else "Identify song" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.GraphicEq, null, tint = Color.White, modifier = Modifier.size(ringSize * 0.3f))
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
