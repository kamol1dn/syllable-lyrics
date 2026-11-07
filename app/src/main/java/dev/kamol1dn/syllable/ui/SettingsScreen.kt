package dev.kamol1dn.syllable.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kamol1dn.syllable.BuildConfigInfo
import dev.kamol1dn.syllable.MainViewModel
import dev.kamol1dn.syllable.spotify.SpotifyAuth
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val SpotifyGreen = Color(0xFF1ED760)

@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit, onSpotifyLogin: () -> Unit) {
    val context = LocalContext.current
    val s by vm.settings.state.collectAsStateWithLifecycle()
    val spotify by SpotifyAuth.state.collectAsStateWithLifecycle()
    val hasAccess by vm.media.hasAccess.collectAsStateWithLifecycle()
    var micGranted by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            micGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            vm.media.checkAccess()
            delay(1500)
        }
    }
    var cacheCleared by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(Color(0xFF0B0712)), contentAlignment = Alignment.TopCenter) {
    Column(
        Modifier.widthIn(max = 640.dp).fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White) }
            Text("Settings", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }

        Section("Spotify")
        Card {
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.padding(top = 6.dp).size(12.dp).clip(CircleShape).background(if (spotify.signedIn) SpotifyGreen else Color.Gray))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (spotify.signedIn) "Signed in to Spotify" else "Not signed in",
                        color = Color.White, fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (spotify.signedIn) {
                            if (spotify.hasToken) "Connected" else "Stays signed in"
                        } else {
                            "Sign in to get lyrics from Spotify. Some songs have word-by-word timing."
                        },
                        color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            if (spotify.signedIn) {
                OutlinedButton(onClick = { SpotifyAuth.signOut() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Sign out", color = Color.White)
                }
            } else {
                Button(
                    onClick = onSpotifyLogin,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen, contentColor = Color.Black),
                ) { Text("Log in with Spotify", fontWeight = FontWeight.Bold) }
            }
            Toggle("Use Spotify lyrics", s.useSpotify) { v -> vm.settings.update { it.copy(useSpotify = v) } }
        }

        Section("Lyrics sources")
        Card {
            Toggle("Musixmatch (word-synced)", s.useMusixmatch) { v -> vm.settings.update { it.copy(useMusixmatch = v) } }
            Toggle("NetEase (word-synced)", s.useNetEase) { v -> vm.settings.update { it.copy(useNetEase = v) } }
            Text(
                "LRCLIB is always on. If a song only has line timing, Syllable guesses the timing of each word.",
                color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { vm.clearCache(); cacheCleared = true }, modifier = Modifier.fillMaxWidth()) {
                Text(if (cacheCleared) "Cache cleared" else "Clear lyrics cache", color = Color.White)
            }
        }

        Section("Display")
        Card {
            Text("Text size  ${(s.textScale * 100).roundToInt()}%", color = Color.White)
            Slider(
                value = s.textScale, onValueChange = { v -> vm.settings.update { it.copy(textScale = (v * 20).roundToInt() / 20f) } },
                valueRange = 0.7f..1.4f,
            )
            Toggle("Blur lines that aren't being sung", s.blurInactive) { v -> vm.settings.update { it.copy(blurInactive = v) } }
            Toggle("Keep screen on", s.keepScreenOn) { v -> vm.settings.update { it.copy(keepScreenOn = v) } }
        }

        Section("Sync")
        Card {
            Text("Timing offset  %+.1fs".format(s.syncOffsetMs / 1000f), color = Color.White)
            Text("Slide right to show lyrics earlier. Handy with Bluetooth speakers.", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
            Slider(
                value = s.syncOffsetMs.toFloat(),
                onValueChange = { v -> vm.settings.update { it.copy(syncOffsetMs = ((v / 100).roundToInt() * 100).toLong()) } },
                valueRange = -2000f..2000f,
            )
        }

        Section("Permissions")
        Card {
            PermissionRow("Notification access", "See what's playing in other apps", hasAccess) {
                context.startActivity(Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PermissionRow("Microphone", "Identify songs playing around you", micGranted) {
                context.startActivity(
                    Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }

        Section("About")
        Card {
            Text("Syllable ${BuildConfigInfo.versionName(context)}", color = Color.White, fontWeight = FontWeight.SemiBold)
            Text(
                "Shows the lyrics of what you're playing, word by word. Songs are identified with Shazam. Lyrics come from Spotify, Musixmatch, NetEase and LRCLIB.",
                color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp,
            )
            Text(
                "github.com/kamol1dn/syllable-lyrics",
                color = Accent, fontSize = 13.sp,
                modifier = Modifier.padding(top = 6.dp).clickable {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/kamol1dn/syllable-lyrics")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                },
            )
        }
        Spacer(Modifier.height(32.dp))
    }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title.uppercase(), color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp, fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp, modifier = Modifier.padding(start = 24.dp, top = 20.dp, bottom = 8.dp),
    )
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF1A1325)).padding(16.dp),
    ) { content() }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Color.White, modifier = Modifier.weight(1f))
        Switch(checked, onChange, colors = SwitchDefaults.colors(checkedTrackColor = Accent))
    }
}

@Composable
private fun PermissionRow(title: String, subtitle: String, granted: Boolean, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White)
            Text(subtitle, color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp)
        }
        Text(
            if (granted) "Allowed" else "Allow",
            color = if (granted) SpotifyGreen else Accent, fontWeight = FontWeight.SemiBold,
        )
    }
}
