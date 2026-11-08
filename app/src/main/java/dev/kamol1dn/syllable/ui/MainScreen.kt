package dev.kamol1dn.syllable.ui

import android.content.Intent
import android.graphics.Bitmap
import android.provider.Settings as AndroidSettings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import dev.kamol1dn.syllable.spotify.SpotifyAuth
import kotlinx.coroutines.delay
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kamol1dn.syllable.LyricsState
import dev.kamol1dn.syllable.MainViewModel
import dev.kamol1dn.syllable.Mode
import dev.kamol1dn.syllable.Timing
import dev.kamol1dn.syllable.TrackInfo
import dev.kamol1dn.syllable.lyrics.SyncType
import dev.kamol1dn.syllable.media.OutputKind

/** True when the window is wide enough to show the track panel beside the lyrics. */
fun isWideLayout(width: Dp, height: Dp) = width > height && width >= 560.dp

/** Shrinks slightly while pressed and springs back on release. */
fun Modifier.pressScale(interaction: MutableInteractionSource): Modifier = composed {
    val pressed by interaction.collectIsPressedAsState()
    val s by animateFloatAsState(
        if (pressed) 0.9f else 1f,
        spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMediumLow),
        label = "press",
    )
    graphicsLayer { scaleX = s; scaleY = s }
}

@Composable
fun MainScreen(vm: MainViewModel, onListen: () -> Unit, onSettings: () -> Unit, onSpotifyLogin: () -> Unit) {
    val context = LocalContext.current
    val track by vm.track.collectAsStateWithLifecycle()
    val lyricsState by vm.lyrics.collectAsStateWithLifecycle()
    val hasAccess by vm.media.hasAccess.collectAsStateWithLifecycle()

    // Artwork: from the media session, or downloaded for a recognised song.
    val remoteArt = rememberCoverBitmap(track?.coverUrl, track?.key)
    val artwork = track?.artwork ?: remoteArt
    var showSync by rememberSaveable { mutableStateOf(false) }
    val t = track

    val np by vm.nowPlaying.collectAsStateWithLifecycle()
    val mode by vm.mode.collectAsStateWithLifecycle()
    val clockPaused by vm.clockPaused.collectAsStateWithLifecycle()
    val playing = if (mode == Mode.RECOGNIZED) !clockPaused else np?.isPlaying ?: true
    val settings by vm.settings.state.collectAsStateWithLifecycle()

    // A song found with the mic has no player to say it ended, so offer the next step 30 s after the last line.
    val loaded = lyricsState as? LyricsState.Loaded
    val end = loaded?.lyrics?.takeIf { it.sync != SyncType.NONE }?.lines?.lastOrNull()?.endMs
    val songOver by produceState(false, mode, end) {
        value = false
        if (mode != Mode.RECOGNIZED || end == null) return@produceState
        while (true) {
            value = vm.positionMs() > end + 30_000
            delay(1_000)
        }
    }

    // Keep the screen on only while synced lyrics are actually playing.
    val view = LocalView.current
    val keepOn = settings.keepScreenOn && loaded != null && loaded.lyrics.sync != SyncType.NONE && playing && !songOver
    DisposableEffect(view, keepOn) {
        view.keepScreenOn = keepOn
        onDispose { view.keepScreenOn = false }
    }

    // The timing controls only make sense for synced lyrics.
    val canSync = when (val st = lyricsState) {
        is LyricsState.Loaded -> st.lyrics.sync != SyncType.NONE
        LyricsState.NotFound, LyricsState.Offline -> false
        else -> true // loading between songs: don't blink
    }
    LaunchedEffect(canSync) { if (!canSync) showSync = false }
    val notFound = lyricsState is LyricsState.NotFound || lyricsState is LyricsState.Offline

    ArtBackground(artwork, playing = playing) {
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            val wide = isWideLayout(maxWidth, maxHeight)
            val boxW = maxWidth
            val boxH = maxHeight
            AnimatedContent(
                targetState = t,
                contentKey = { it == null },
                transitionSpec = { fadeIn(tween(400, delayMillis = 100)) togetherWith fadeOut(tween(250)) },
                modifier = Modifier.fillMaxSize(),
                label = "layout",
            ) { shown ->
            when {
                shown == null -> Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Syllable", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp, modifier = Modifier.weight(1f))
                        ActionIcons(onListen, onSettings)
                    }
                    EmptyState(
                        hasAccess = hasAccess,
                        onGrant = { openNotificationAccess(context) },
                        onListen = onListen,
                        modifier = Modifier.weight(1f),
                    )
                }
                wide -> Row(Modifier.fillMaxSize()) {
                    val recognized by vm.recognized.collectAsStateWithLifecycle()
                    val np by vm.nowPlaying.collectAsStateWithLifecycle()
                    val chipsVisible = recognized != null
                    Column(
                        Modifier.width((boxW * 0.38f).coerceIn(280.dp, 420.dp)).fillMaxHeight()
                            .padding(start = 24.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
                    ) {
                        val artSize by animateDpAsState(
                            (boxH - 196.dp - (if (showSync) 90.dp else 0.dp) - (if (chipsVisible) 44.dp else 0.dp)).coerceIn(56.dp, 240.dp),
                            spring(stiffness = Spring.StiffnessLow),
                            label = "art",
                        )
                        Row(verticalAlignment = Alignment.Top) {
                            Artwork(artwork, artSize, MaterialTheme.shapes.medium, if (np != null) ({ vm.media.openPlayerApp() }) else null)
                            Spacer(Modifier.weight(1f))
                            Column { ActionIcons(onListen, onSettings) }
                        }
                        Spacer(Modifier.height(14.dp))
                        TrackText(shown, lyricsState, titleSize = 20.sp)
                        ModeChips(vm, Modifier.padding(top = 10.dp))
                        Spacer(Modifier.weight(1f))
                        SyncSection(vm, showSync, compact = true)
                        Controls(vm, showSync, canSync, notFound, onToggleSync = { showSync = !showSync }, onListen = onListen)
                    }
                    LyricsArea(
                        vm, lyricsState, onListen, onSpotifyLogin, songOver,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        horizontalPadding = 20.dp,
                        anchorFraction = 0.26f,
                    )
                }
                else -> Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Artwork(artwork, 52.dp, MaterialTheme.shapes.small, if (np != null) ({ vm.media.openPlayerApp() }) else null)
                        Spacer(Modifier.width(12.dp))
                        TrackText(shown, lyricsState, titleSize = 17.sp, modifier = Modifier.weight(1f))
                        ActionIcons(onListen, onSettings)
                    }
                    ModeChips(vm, Modifier.padding(start = 20.dp, top = 8.dp))
                    LyricsArea(vm, lyricsState, onListen, onSpotifyLogin, songOver, modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp))
                    SyncSection(vm, showSync, compact = false)
                    Controls(vm, showSync, canSync, notFound, onToggleSync = { showSync = !showSync }, onListen = onListen)
                }
            }
            }
        }
    }
}

@Composable
private fun ActionIcons(onListen: () -> Unit, onSettings: () -> Unit) {
    IconButton(onClick = onListen) { Icon(Icons.Rounded.GraphicEq, "Identify song", tint = Color.White) }
    IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, "Settings", tint = Color.White) }
}

@Composable
private fun Artwork(artwork: Bitmap?, size: Dp, shape: Shape, onClick: (() -> Unit)?) {
    Box(
        Modifier.size(size).clip(shape).background(Color.White.copy(alpha = 0.12f))
            .semantics { contentDescription = "Album art" }
            .then(if (onClick != null) Modifier.clickable(onClickLabel = "Open player app", role = Role.Button, onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(artwork, animationSpec = tween(600), label = "art") { art ->
            if (art != null) {
                Image(remember(art) { art.asImageBitmap() }, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.MusicNote, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(size * 0.45f))
                }
            }
        }
    }
}

/** Title and artist; slides to the new song when the track changes. */
@Composable
private fun TrackText(track: TrackInfo, lyricsState: LyricsState, titleSize: androidx.compose.ui.unit.TextUnit, modifier: Modifier = Modifier) {
    Column(modifier) {
        AnimatedContent(
            targetState = track,
            contentKey = { it.key },
            transitionSpec = {
                (slideInVertically(spring(stiffness = Spring.StiffnessLow)) { it / 2 } + fadeIn(tween(350))) togetherWith
                    (slideOutVertically(tween(250)) { -it / 2 } + fadeOut(tween(200)))
            },
            label = "track",
        ) { tr ->
            Column {
                Text(tr.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = titleSize, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(tr.artist, color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        SourceLabel(lyricsState)
    }
}

@Composable
private fun ModeChips(vm: MainViewModel, modifier: Modifier) {
    val recognized by vm.recognized.collectAsStateWithLifecycle()
    val np by vm.nowPlaying.collectAsStateWithLifecycle()
    val mode by vm.mode.collectAsStateWithLifecycle()
    AnimatedVisibility(recognized != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (np != null) ModeChip("Now playing", mode == Mode.NOW_PLAYING) { vm.setMode(Mode.NOW_PLAYING) }
            ModeChip("Identified", mode == Mode.RECOGNIZED) { vm.setMode(Mode.RECOGNIZED) }
            if (mode == Mode.RECOGNIZED) {
                IconButton(onClick = vm::clearRecognized, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Rounded.Close, "Close identified song", tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun LyricsArea(
    vm: MainViewModel,
    lyricsState: LyricsState,
    onListen: () -> Unit,
    onSpotifyLogin: () -> Unit,
    songOver: Boolean,
    modifier: Modifier,
    horizontalPadding: Dp = 24.dp,
    anchorFraction: Float = 0.3f,
) {
    val np by vm.nowPlaying.collectAsStateWithLifecycle()
    val mode by vm.mode.collectAsStateWithLifecycle()
    val settings by vm.settings.state.collectAsStateWithLifecycle()
    val spotify by SpotifyAuth.state.collectAsStateWithLifecycle()
    AnimatedContent(
        targetState = lyricsState,
        contentKey = { if (it is LyricsState.Loaded) it.lyrics else it::class },
        transitionSpec = {
            (fadeIn(tween(500, delayMillis = 120)) + slideInVertically(spring(stiffness = Spring.StiffnessLow)) { it / 14 }) togetherWith
                fadeOut(tween(220))
        },
        modifier = modifier,
        label = "lyrics",
    ) { s ->
        when (s) {
            is LyricsState.Loaded -> Box(Modifier.fillMaxSize()) {
                LyricsView(
                    lyrics = s.lyrics,
                    position = vm::positionMs,
                    onSeek = if (mode == Mode.RECOGNIZED || np?.canSeek == true) vm::seekToLyric else null,
                    textScale = settings.textScale,
                    blurInactive = settings.blurInactive,
                    modifier = Modifier.fillMaxSize(),
                    horizontalPadding = horizontalPadding,
                    anchorFraction = anchorFraction,
                )
                // The lyrics stay underneath, so scrolling and tap-to-seek still work.
                AnimatedVisibility(
                    songOver, Modifier.align(Alignment.BottomCenter),
                    enter = fadeIn() + slideInVertically { it / 3 }, exit = fadeOut(),
                ) {
                    Column(
                        Modifier.padding(24.dp).clip(MaterialTheme.shapes.medium).background(Color.Black.copy(alpha = 0.35f)).padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("That's the end of the song", color = Color.White, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = onListen, colors = ButtonDefaults.buttonColors(containerColor = AccentButton, contentColor = Color.White)) {
                                Text("Identify another")
                            }
                            OutlineButton(onClick = vm::clearRecognized) { Text("Close") }
                        }
                    }
                }
            }
            LyricsState.Loading, LyricsState.Idle -> Searching(horizontalPadding)
            LyricsState.NotFound -> NotFound(
                spotifyOn = settings.useSpotify,
                signedIn = spotify.signedIn,
                onRetry = vm::retryLyrics,
                onListen = onListen,
                onSpotifyLogin = onSpotifyLogin,
                onUseSpotify = {
                    vm.settings.update { it.copy(useSpotify = true) }
                    if (!spotify.signedIn) onSpotifyLogin()
                },
            )
            LyricsState.Offline -> Offline(onRetry = vm::retryLyrics)
        }
    }
}

@Composable
private fun SyncSection(vm: MainViewModel, show: Boolean, compact: Boolean) {
    val timing by vm.timing.collectAsStateWithLifecycle()
    AnimatedVisibility(
        show,
        enter = expandVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
        exit = shrinkVertically(tween(250)) + fadeOut(tween(200)),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            SyncBar(timing.offsetMs, compact, onNudge = vm::nudge)
            TimingTarget(timing, onReset = vm::resetTiming)
        }
    }
}

/** Says which device the timing is saved for, so it's clear it will come back next time. */
@Composable
private fun TimingTarget(timing: Timing, onReset: () -> Unit) {
    val out = timing.output
    val icon = when (out?.kind) {
        null -> Icons.Rounded.Mic
        OutputKind.PHONE -> Icons.Rounded.PhoneAndroid
        OutputKind.WIRED -> Icons.Rounded.Headphones
        OutputKind.USB -> Icons.Rounded.Usb
        OutputKind.BLUETOOTH -> Icons.Rounded.Bluetooth
        OutputKind.REMOTE -> Icons.Rounded.Cast
    }
    val label = when {
        out == null -> "Timing for this song"
        timing.isDefault && out.kind == OutputKind.BLUETOOTH -> "${out.name} · Bluetooth default"
        timing.isDefault -> out.name
        else -> "Saved for ${out.name}"
    }
    Row(
        Modifier.padding(top = 6.dp).heightIn(min = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Crossfade(label, label = "timingTarget") { text ->
            Text(
                text, color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 240.dp),
            )
        }
        AnimatedVisibility(!timing.isDefault, enter = fadeIn(), exit = fadeOut()) {
            Text(
                "Reset",
                color = Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 4.dp).clip(RoundedCornerShape(50))
                    .clickable(onClick = onReset).padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun Controls(
    vm: MainViewModel,
    showSync: Boolean,
    canSync: Boolean,
    notFound: Boolean,
    onToggleSync: () -> Unit,
    onListen: () -> Unit,
) {
    val np by vm.nowPlaying.collectAsStateWithLifecycle()
    val mode by vm.mode.collectAsStateWithLifecycle()
    val clockPaused by vm.clockPaused.collectAsStateWithLifecycle()
    val timing by vm.timing.collectAsStateWithLifecycle()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        SideSlot(visible = canSync) {
            IconButton(onClick = onToggleSync) {
                // Pink while open, or while a timing change is in use.
                Icon(
                    Icons.Rounded.Tune, if (showSync) "Hide timing" else "Adjust timing",
                    tint = if (showSync || !timing.isDefault) Accent else Color.White.copy(alpha = 0.8f),
                )
            }
        }
        val player = np
        if (mode == Mode.NOW_PLAYING && player != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.media.previous() }) { Icon(Icons.Rounded.SkipPrevious, "Previous", tint = Color.White, modifier = Modifier.size(32.dp)) }
                PlayButton(playing = player.isPlaying, playLabel = "Play", pauseLabel = "Pause") { vm.media.playPause() }
                IconButton(onClick = { vm.media.next() }) { Icon(Icons.Rounded.SkipNext, "Next", tint = Color.White, modifier = Modifier.size(32.dp)) }
            }
        } else if (mode == Mode.RECOGNIZED) {
            PlayButton(playing = !clockPaused, playLabel = "Resume lyrics", pauseLabel = "Pause lyrics") { vm.toggleClock() }
        }
        // Hidden when nothing was found, since the "Search again" button below the message does the same.
        SideSlot(visible = mode == Mode.RECOGNIZED || !notFound) {
            IconButton(onClick = if (mode == Mode.RECOGNIZED) onListen else vm::retryLyrics) {
                Icon(
                    if (mode == Mode.RECOGNIZED) Icons.Rounded.GraphicEq else Icons.Rounded.Refresh,
                    if (mode == Mode.RECOGNIZED) "Listen again to fix timing" else "Reload lyrics",
                    tint = Color.White.copy(alpha = 0.8f),
                )
            }
        }
    }
}

/** Keeps its 48dp slot when hidden, so the play button stays centred. */
@Composable
private fun SideSlot(visible: Boolean, content: @Composable () -> Unit) {
    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) { content() }
    }
}

@Composable
private fun PlayButton(playing: Boolean, playLabel: String, pauseLabel: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier.pressScale(interaction).size(58.dp).clip(CircleShape).background(Color.White)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = if (playing) pauseLabel else playLabel },
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = playing,
            transitionSpec = {
                (scaleIn(spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium), initialScale = 0.4f) + fadeIn(tween(150))) togetherWith
                    (scaleOut(tween(150), targetScale = 0.4f) + fadeOut(tween(120)))
            },
            label = "play",
        ) { p ->
            Icon(if (p) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = Color.Black, modifier = Modifier.size(32.dp))
        }
    }
}

@Composable
private fun SourceLabel(state: LyricsState) {
    val text = when (state) {
        is LyricsState.Loaded -> when (state.lyrics.sync) {
            SyncType.WORD -> "Word timing · ${state.lyrics.source}"
            SyncType.LINE -> "Line timing · ${state.lyrics.source}"
            SyncType.NONE -> "Plain lyrics · ${state.lyrics.source}"
        }
        LyricsState.Loading -> "Searching lyrics…"
        else -> "" // the message in the middle says it
    }
    val pending = state is LyricsState.Loaded && !state.final
    Row(verticalAlignment = Alignment.CenterVertically) {
        Crossfade(text, label = "source", modifier = Modifier.weight(1f, fill = false)) { s ->
            if (s.isNotEmpty()) Text(s, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        AnimatedVisibility(pending, enter = fadeIn(), exit = fadeOut()) {
            CircularProgressIndicator(
                Modifier.padding(start = 6.dp).size(10.dp).semantics { contentDescription = "Checking more sources" },
                color = Color.White.copy(alpha = 0.7f), strokeWidth = 1.5.dp,
            )
        }
    }
}

@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg by androidx.compose.animation.animateColorAsState(
        if (selected) Color.White else Color.White.copy(alpha = 0.14f), tween(250), label = "chipBg",
    )
    val fg by androidx.compose.animation.animateColorAsState(if (selected) Color.Black else Color.White, tween(250), label = "chipFg")
    val interaction = remember { MutableInteractionSource() }
    Text(
        label,
        color = fg,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.pressScale(interaction).clip(RoundedCornerShape(50))
            .background(bg)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

@Composable
private fun SyncBar(nudge: Long, compact: Boolean, onNudge: (Long) -> Unit) {
    val haptic = LocalHapticFeedback.current
    val buttonMin = if (compact) 88.dp else 104.dp
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = if (compact) 0.dp else 20.dp),
        horizontalArrangement = Arrangement.Center,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        OutlineButton(
            onClick = { haptic.performHapticFeedback(HapticFeedbackType.SegmentTick); onNudge(-250) },
            modifier = Modifier.widthIn(min = buttonMin), contentPadding = PaddingValues(horizontal = 14.dp),
        ) { Text("Later", maxLines = 1, softWrap = false) }
        AnimatedContent(
            targetState = nudge,
            transitionSpec = {
                val up = targetState > initialState
                (slideInVertically { if (up) it else -it } + fadeIn()) togetherWith (slideOutVertically { if (up) -it else it } + fadeOut())
            },
            contentAlignment = Alignment.Center,
            modifier = Modifier.widthIn(min = if (compact) 112.dp else 132.dp).clearAndSetSemantics {
                contentDescription = if (nudge == 0L) "No timing change" else "Timing " + formatOffset(nudge, unit = " seconds")
                liveRegion = LiveRegionMode.Polite
            },
            label = "nudge",
        ) { n ->
            Text(
                formatOffset(n), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
        OutlineButton(
            onClick = { haptic.performHapticFeedback(HapticFeedbackType.SegmentTick); onNudge(250) },
            modifier = Modifier.widthIn(min = buttonMin), contentPadding = PaddingValues(horizontal = 14.dp),
        ) { Text("Earlier", maxLines = 1, softWrap = false) }
    }
}

@Composable
private fun Searching(horizontalPadding: Dp) {
    val t = rememberInfiniteTransition(label = "search")
    val shimmer by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1400), RepeatMode.Restart), label = "a")
    Column(Modifier.fillMaxSize().padding(horizontal = horizontalPadding, vertical = 24.dp), verticalArrangement = Arrangement.Center) {
        repeat(4) { i ->
            Box(
                Modifier.padding(vertical = 8.dp).fillMaxWidth(listOf(0.8f, 0.6f, 0.9f, 0.5f)[i]).height(28.dp)
                    .graphicsLayer {
                        // A soft pulse that travels down the placeholder lines.
                        val phase = ((shimmer - i * 0.15f) % 1f + 1f) % 1f
                        alpha = 0.12f + 0.22f * kotlin.math.sin(phase * Math.PI.toFloat())
                    }
                    .clip(RoundedCornerShape(8.dp)).background(Color.White),
            )
        }
    }
}

@Composable
private fun NotFound(
    spotifyOn: Boolean,
    signedIn: Boolean,
    onRetry: () -> Unit,
    onListen: () -> Unit,
    onSpotifyLogin: () -> Unit,
    onUseSpotify: () -> Unit,
) {
    val (body, extra) = when {
        !spotifyOn -> "Spotify lyrics are turned off. Turning them on might find this song." to ("Use Spotify lyrics" to onUseSpotify)
        !signedIn -> "Spotify has lyrics for many songs the other sources miss." to ("Sign in to Spotify" to onSpotifyLogin)
        else -> "None of the sources have lyrics for this song yet." to null
    }
    CenteredScroll {
        Text("No lyrics found", color = Color.White, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(body, color = Color.White.copy(alpha = 0.7f), textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        PrimaryButton("Identify with the mic", onListen, Modifier.fillMaxWidth(), icon = Icons.Rounded.GraphicEq)
        Spacer(Modifier.height(12.dp))
        SecondaryButton("Search again", onRetry, Modifier.fillMaxWidth())
        if (extra != null) {
            TextButton(onClick = extra.second, modifier = Modifier.padding(top = 8.dp)) {
                Text(extra.first, color = Accent, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun Offline(onRetry: () -> Unit) {
    CenteredScroll {
        Text("Can't load lyrics", color = Color.White, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "Check your connection. Lyrics load again once you're back online.",
            color = Color.White.copy(alpha = 0.7f), textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        SecondaryButton("Try again", onRetry, Modifier.fillMaxWidth())
    }
}

/** Centred, width-limited and scrollable, so it also fits a short landscape window. */
@Composable
private fun CenteredScroll(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 480.dp).verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) { content() }
    }
}

@Composable
private fun EmptyState(hasAccess: Boolean, onGrant: () -> Unit, onListen: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    var asked by rememberSaveable { mutableStateOf(false) }
    val fromFile = remember { installedFromFile(context) }
    CenteredScroll(modifier) {
        Text(
            if (hasAccess) "Play something" else "Lyrics for whatever's playing",
            color = Color.White, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            if (hasAccess) "Start a song in Spotify, YouTube Music, or any player and the lyrics show up here, word by word."
            else "Allow notification access so Syllable can see the song in your media notification and follow along word by word.",
            color = Color.White.copy(alpha = 0.7f), textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        if (!hasAccess) {
            PrimaryButton("Allow notification access", { asked = true; onGrant() }, Modifier.fillMaxWidth())
            Text(
                "Android will warn that Syllable can read all your notifications. Syllable only checks which song is playing.",
                style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f),
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp),
            )
            // Apps installed from an APK file need one more step on Android 13 and later.
            AnimatedVisibility(asked && fromFile) { RestrictedSettingsHint(Modifier.padding(top = 10.dp)) }
            Spacer(Modifier.height(16.dp))
        }
        SecondaryButton("Identify a song with the mic", onListen, Modifier.fillMaxWidth(), icon = Icons.Rounded.GraphicEq)
        Text(
            "Works with music playing around you, like Shazam.",
            style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f), modifier = Modifier.padding(top = 8.dp),
        )
    }
}
