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
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Button
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
import dev.kamol1dn.syllable.TrackInfo
import dev.kamol1dn.syllable.lyrics.SyncType

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
fun MainScreen(vm: MainViewModel, onListen: () -> Unit, onSettings: () -> Unit) {
    val context = LocalContext.current
    val track by vm.track.collectAsStateWithLifecycle()
    val lyricsState by vm.lyrics.collectAsStateWithLifecycle()
    val hasAccess by vm.media.hasAccess.collectAsStateWithLifecycle()

    // Artwork: from the media session, or downloaded for a recognised song.
    val remoteArt = rememberCoverBitmap(track?.coverUrl)
    val artwork = track?.artwork ?: remoteArt
    var showSync by rememberSaveable { mutableStateOf(false) }
    val t = track

    val np by vm.nowPlaying.collectAsStateWithLifecycle()
    val mode by vm.mode.collectAsStateWithLifecycle()
    val clockPaused by vm.clockPaused.collectAsStateWithLifecycle()
    val playing = if (mode == Mode.RECOGNIZED) !clockPaused else np?.isPlaying ?: true

    ArtBackground(artwork, playing = playing) {
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            val wide = isWideLayout(maxWidth, maxHeight)
            val boxW = maxWidth
            val boxH = maxHeight
            when {
                t == null -> Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Syllable", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp, modifier = Modifier.weight(1f))
                        ActionIcons(onListen, onSettings)
                    }
                    EmptyState(
                        hasAccess = hasAccess,
                        onGrant = {
                            context.startActivity(
                                Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        },
                        onListen = onListen,
                        modifier = Modifier.weight(1f),
                    )
                }
                wide -> Row(Modifier.fillMaxSize()) {
                    val recognized by vm.recognized.collectAsStateWithLifecycle()
                    val np by vm.nowPlaying.collectAsStateWithLifecycle()
                    val chipsVisible = recognized != null && np != null
                    Column(
                        Modifier.width((boxW * 0.38f).coerceIn(280.dp, 420.dp)).fillMaxHeight()
                            .padding(start = 24.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
                    ) {
                        val artSize by animateDpAsState(
                            (boxH - 196.dp - (if (showSync) 52.dp else 0.dp) - (if (chipsVisible) 44.dp else 0.dp)).coerceIn(56.dp, 240.dp),
                            spring(stiffness = Spring.StiffnessLow),
                            label = "art",
                        )
                        Row(verticalAlignment = Alignment.Top) {
                            Artwork(artwork, artSize, 16.dp) { vm.media.openPlayerApp() }
                            Spacer(Modifier.weight(1f))
                            Column { ActionIcons(onListen, onSettings) }
                        }
                        Spacer(Modifier.height(14.dp))
                        TrackText(t, lyricsState, titleSize = 20.sp)
                        ModeChips(vm, Modifier.padding(top = 10.dp))
                        Spacer(Modifier.weight(1f))
                        SyncSection(vm, showSync, compact = true)
                        Controls(vm, showSync, onToggleSync = { showSync = !showSync }, onListen = onListen)
                    }
                    LyricsArea(
                        vm, lyricsState, onListen,
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
                        Artwork(artwork, 52.dp, 10.dp) { vm.media.openPlayerApp() }
                        Spacer(Modifier.width(12.dp))
                        TrackText(t, lyricsState, titleSize = 17.sp, modifier = Modifier.weight(1f))
                        ActionIcons(onListen, onSettings)
                    }
                    ModeChips(vm, Modifier.padding(start = 20.dp, top = 8.dp))
                    LyricsArea(vm, lyricsState, onListen, modifier = Modifier.weight(1f).fillMaxWidth())
                    SyncSection(vm, showSync, compact = false)
                    Controls(vm, showSync, onToggleSync = { showSync = !showSync }, onListen = onListen)
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
private fun Artwork(artwork: Bitmap?, size: Dp, corner: Dp, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(corner)).background(Color.White.copy(alpha = 0.12f))
            .clickable(onClick = onClick),
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
                Text(tr.artist, color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    AnimatedVisibility(recognized != null && np != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ModeChip("Now playing", mode == Mode.NOW_PLAYING) { vm.setMode(Mode.NOW_PLAYING) }
            ModeChip("Identified", mode == Mode.RECOGNIZED) { vm.setMode(Mode.RECOGNIZED) }
        }
    }
}

@Composable
private fun LyricsArea(
    vm: MainViewModel,
    lyricsState: LyricsState,
    onListen: () -> Unit,
    modifier: Modifier,
    horizontalPadding: Dp = 24.dp,
    anchorFraction: Float = 0.3f,
) {
    val np by vm.nowPlaying.collectAsStateWithLifecycle()
    val mode by vm.mode.collectAsStateWithLifecycle()
    val settings by vm.settings.state.collectAsStateWithLifecycle()
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
            is LyricsState.Loaded -> LyricsView(
                lyrics = s.lyrics,
                position = vm::positionMs,
                onSeek = if (mode == Mode.RECOGNIZED || np?.canSeek == true) vm::seekToLyric else null,
                textScale = settings.textScale,
                blurInactive = settings.blurInactive,
                modifier = Modifier.fillMaxSize(),
                horizontalPadding = horizontalPadding,
                anchorFraction = anchorFraction,
            )
            LyricsState.Loading, LyricsState.Idle -> Searching(horizontalPadding)
            LyricsState.NotFound -> NotFound(onRetry = vm::retryLyrics, onListen = onListen)
        }
    }
}

@Composable
private fun SyncSection(vm: MainViewModel, show: Boolean, compact: Boolean) {
    val nudge by vm.nudge.collectAsStateWithLifecycle()
    AnimatedVisibility(
        show,
        enter = expandVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
        exit = shrinkVertically(tween(250)) + fadeOut(tween(200)),
    ) {
        SyncBar(nudge, compact, onNudge = vm::nudge)
    }
}

@Composable
private fun Controls(vm: MainViewModel, showSync: Boolean, onToggleSync: () -> Unit, onListen: () -> Unit) {
    val np by vm.nowPlaying.collectAsStateWithLifecycle()
    val mode by vm.mode.collectAsStateWithLifecycle()
    val clockPaused by vm.clockPaused.collectAsStateWithLifecycle()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        IconButton(onClick = onToggleSync) {
            Icon(Icons.Rounded.Timer, "Adjust sync", tint = if (showSync) Accent else Color.White.copy(alpha = 0.8f))
        }
        val player = np
        if (mode == Mode.NOW_PLAYING && player != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.media.previous() }) { Icon(Icons.Rounded.SkipPrevious, "Previous", tint = Color.White, modifier = Modifier.size(32.dp)) }
                PlayButton(playing = player.isPlaying, label = "Play/pause") { vm.media.playPause() }
                IconButton(onClick = { vm.media.next() }) { Icon(Icons.Rounded.SkipNext, "Next", tint = Color.White, modifier = Modifier.size(32.dp)) }
            }
        } else if (mode == Mode.RECOGNIZED) {
            PlayButton(playing = !clockPaused, label = "Pause lyrics") { vm.toggleClock() }
        }
        IconButton(onClick = if (mode == Mode.RECOGNIZED) onListen else vm::retryLyrics) {
            Icon(
                if (mode == Mode.RECOGNIZED) Icons.Rounded.GraphicEq else Icons.Rounded.Refresh,
                if (mode == Mode.RECOGNIZED) "Listen again to fix timing" else "Reload lyrics",
                tint = Color.White.copy(alpha = 0.8f),
            )
        }
    }
}

@Composable
private fun PlayButton(playing: Boolean, label: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier.pressScale(interaction).size(58.dp).clip(CircleShape).background(Color.White)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
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
            Icon(if (p) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, label, tint = Color.Black, modifier = Modifier.size(32.dp))
        }
    }
}

@Composable
private fun SourceLabel(state: LyricsState) {
    val text = when (state) {
        is LyricsState.Loaded -> when (state.lyrics.sync) {
            SyncType.WORD -> "Word sync · ${state.lyrics.source}"
            SyncType.LINE -> "Line sync, words guessed · ${state.lyrics.source}"
            SyncType.NONE -> "Not synced · ${state.lyrics.source}"
        } + if (!state.final) " · checking more sources…" else ""
        LyricsState.Loading -> "Searching lyrics…"
        LyricsState.NotFound -> "No lyrics found"
        LyricsState.Idle -> ""
    }
    Crossfade(text, label = "source") { s ->
        if (s.isNotEmpty()) Text(s, color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    Row(
        Modifier.fillMaxWidth().padding(horizontal = if (compact) 0.dp else 20.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(onClick = { onNudge(-250) }, contentPadding = PaddingValues(horizontal = 14.dp)) {
            Text(if (compact) "−0.25s" else "Later −0.25s", color = Color.White)
        }
        AnimatedContent(
            targetState = nudge,
            transitionSpec = {
                val up = targetState > initialState
                (slideInVertically { if (up) it else -it } + fadeIn()) togetherWith (slideOutVertically { if (up) -it else it } + fadeOut())
            },
            label = "nudge",
        ) { n ->
            Text(
                "%+.2fs".format(n / 1000f),
                color = Color.White, modifier = Modifier.padding(horizontal = 12.dp), fontWeight = FontWeight.Bold,
            )
        }
        OutlinedButton(onClick = { onNudge(250) }, contentPadding = PaddingValues(horizontal = 14.dp)) {
            Text(if (compact) "+0.25s" else "Earlier +0.25s", color = Color.White)
        }
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
private fun NotFound(onRetry: () -> Unit, onListen: () -> Unit) {
    CenteredScroll {
        Text("No lyrics found", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Try again, or sign in to Spotify in Settings for more lyrics.",
            color = Color.White.copy(alpha = 0.7f), textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onRetry) { Text("Retry", color = Color.White) }
            Button(onClick = onListen) { Text("Identify with mic") }
        }
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
    CenteredScroll(modifier) {
        Text(
            if (hasAccess) "Play something" else "Lyrics for whatever's playing",
            color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            if (hasAccess) "Start a song in Spotify, YouTube Music, or any player and the lyrics show up here, word by word."
            else "Allow notification access so Syllable can see the song in your media notification and follow along word by word.",
            color = Color.White.copy(alpha = 0.72f), textAlign = TextAlign.Center, fontSize = 15.sp,
        )
        Spacer(Modifier.height(28.dp))
        if (!hasAccess) {
            Button(onClick = onGrant, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp)) {
                Text("Allow notification access", fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(12.dp))
        }
        OutlinedButton(onClick = onListen, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp)) {
            Icon(Icons.Rounded.GraphicEq, null, tint = Color.White)
            Spacer(Modifier.width(8.dp))
            Text("Identify a song with the mic", color = Color.White)
        }
        Text(
            "Works with music playing around you, like Shazam.",
            color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp).alpha(0.9f),
        )
    }
}
