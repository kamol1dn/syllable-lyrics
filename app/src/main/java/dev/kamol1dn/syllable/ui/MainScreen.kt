package dev.kamol1dn.syllable.ui

import android.content.Intent
import android.graphics.Bitmap
import android.provider.Settings as AndroidSettings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import dev.kamol1dn.syllable.LyricsState
import dev.kamol1dn.syllable.MainViewModel
import dev.kamol1dn.syllable.Mode
import dev.kamol1dn.syllable.lyrics.SyncType

@Composable
fun MainScreen(vm: MainViewModel, onListen: () -> Unit, onSettings: () -> Unit) {
    val context = LocalContext.current
    val track by vm.track.collectAsStateWithLifecycle()
    val lyricsState by vm.lyrics.collectAsStateWithLifecycle()
    val np by vm.nowPlaying.collectAsStateWithLifecycle()
    val mode by vm.mode.collectAsStateWithLifecycle()
    val hasAccess by vm.media.hasAccess.collectAsStateWithLifecycle()
    val settings by vm.settings.state.collectAsStateWithLifecycle()
    val nudge by vm.nudge.collectAsStateWithLifecycle()
    val clockPaused by vm.clockPaused.collectAsStateWithLifecycle()
    val recognized by vm.recognized.collectAsStateWithLifecycle()

    // Artwork: from the media session, or downloaded for a recognised song.
    var remoteArt by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(track?.coverUrl) {
        remoteArt = null
        val url = track?.coverUrl ?: return@LaunchedEffect
        val req = ImageRequest.Builder(context).data(url).allowHardware(false).build()
        remoteArt = (context.imageLoader.execute(req) as? SuccessResult)?.image?.toBitmap()
    }
    val artwork = track?.artwork ?: remoteArt
    var showSync by remember { mutableStateOf(false) }

    ArtBackground(artwork) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            // Header
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (track != null) {
                    Box(
                        Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.12f))
                            .clickable { vm.media.openPlayerApp() },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (artwork != null) {
                            Image(artwork.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        } else {
                            Icon(Icons.Rounded.MusicNote, null, tint = Color.White.copy(alpha = 0.7f))
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(track!!.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(track!!.artist, color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        SourceLabel(lyricsState)
                    }
                } else {
                    Text("Syllable", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp, modifier = Modifier.weight(1f))
                }
                IconButton(onClick = onListen) { Icon(Icons.Rounded.GraphicEq, "Identify song", tint = Color.White) }
                IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, "Settings", tint = Color.White) }
            }

            // Source switch when both a player and a recognised song exist.
            if (recognized != null && np != null) {
                Row(Modifier.padding(start = 20.dp, top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModeChip("Now playing", mode == Mode.NOW_PLAYING) { vm.setMode(Mode.NOW_PLAYING) }
                    ModeChip("Identified", mode == Mode.RECOGNIZED) { vm.setMode(Mode.RECOGNIZED) }
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    track == null -> EmptyState(
                        hasAccess = hasAccess,
                        onGrant = {
                            context.startActivity(
                                Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        },
                        onListen = onListen,
                    )
                    else -> Crossfade(lyricsState::class, label = "lyrics") { _ ->
                        when (val s = lyricsState) {
                            is LyricsState.Loaded -> LyricsView(
                                lyrics = s.lyrics,
                                position = vm::positionMs,
                                onSeek = if (mode == Mode.RECOGNIZED || np?.canSeek == true) vm::seekToLyric else null,
                                textScale = settings.textScale,
                                blurInactive = settings.blurInactive,
                                modifier = Modifier.fillMaxSize(),
                            )
                            LyricsState.Loading, LyricsState.Idle -> Searching()
                            LyricsState.NotFound -> NotFound(onRetry = vm::retryLyrics, onListen = onListen)
                        }
                    }
                }
            }

            // Bottom controls
            if (track != null) {
                AnimatedVisibility(showSync, enter = fadeIn(), exit = fadeOut()) {
                    SyncBar(nudge, onNudge = vm::nudge)
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    IconButton(onClick = { showSync = !showSync }) {
                        Icon(Icons.Rounded.Timer, "Adjust sync", tint = if (showSync) Accent else Color.White.copy(alpha = 0.8f))
                    }
                    if (mode == Mode.NOW_PLAYING && np != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { vm.media.previous() }) { Icon(Icons.Rounded.SkipPrevious, "Previous", tint = Color.White, modifier = Modifier.size(32.dp)) }
                            Box(
                                Modifier.size(58.dp).clip(CircleShape).background(Color.White).clickable { vm.media.playPause() },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(if (np!!.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play/pause", tint = Color.Black, modifier = Modifier.size(32.dp))
                            }
                            IconButton(onClick = { vm.media.next() }) { Icon(Icons.Rounded.SkipNext, "Next", tint = Color.White, modifier = Modifier.size(32.dp)) }
                        }
                    } else if (mode == Mode.RECOGNIZED) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(
                                Modifier.size(58.dp).clip(CircleShape).background(Color.White).clickable { vm.toggleClock() },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(if (clockPaused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, "Pause lyrics", tint = Color.Black, modifier = Modifier.size(32.dp))
                            }
                        }
                    }
                    IconButton(onClick = if (mode == Mode.RECOGNIZED) onListen else vm::retryLyrics) {
                        Icon(
                            if (mode == Mode.RECOGNIZED) Icons.Rounded.GraphicEq else Icons.Rounded.Refresh,
                            if (mode == Mode.RECOGNIZED) "Re-sync by listening" else "Reload lyrics",
                            tint = Color.White.copy(alpha = 0.8f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceLabel(state: LyricsState) {
    val text = when (state) {
        is LyricsState.Loaded -> when (state.lyrics.sync) {
            SyncType.WORD -> "Word sync · ${state.lyrics.source}"
            SyncType.LINE -> "Line sync (words estimated) · ${state.lyrics.source}"
            SyncType.NONE -> "Not synced · ${state.lyrics.source}"
        } + if (!state.final) " · checking more sources…" else ""
        LyricsState.Loading -> "Searching lyrics…"
        LyricsState.NotFound -> "No lyrics found"
        LyricsState.Idle -> ""
    }
    if (text.isNotEmpty()) Text(text, color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (selected) Color.Black else Color.White,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(50))
            .background(if (selected) Color.White else Color.White.copy(alpha = 0.14f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

@Composable
private fun SyncBar(nudge: Long, onNudge: (Long) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(onClick = { onNudge(-250) }) { Text("Later −0.25s", color = Color.White) }
        Text(
            "%+.2fs".format(nudge / 1000f),
            color = Color.White, modifier = Modifier.padding(horizontal = 12.dp), fontWeight = FontWeight.Bold,
        )
        OutlinedButton(onClick = { onNudge(250) }) { Text("Earlier +0.25s", color = Color.White) }
    }
}

@Composable
private fun Searching() {
    val t = rememberInfiniteTransition(label = "search")
    val a by t.animateFloat(0.25f, 0.7f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        repeat(4) { i ->
            Box(
                Modifier.padding(vertical = 8.dp).fillMaxWidth(listOf(0.8f, 0.6f, 0.9f, 0.5f)[i]).height(28.dp)
                    .clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = a * 0.4f)),
            )
        }
    }
}

@Composable
private fun NotFound(onRetry: () -> Unit, onListen: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
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

@Composable
private fun EmptyState(hasAccess: Boolean, onGrant: () -> Unit, onListen: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
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
