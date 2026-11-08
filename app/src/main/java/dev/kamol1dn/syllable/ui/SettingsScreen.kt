package dev.kamol1dn.syllable.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.text.format.Formatter
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kamol1dn.syllable.BuildConfig
import dev.kamol1dn.syllable.MainViewModel
import dev.kamol1dn.syllable.SettingsState
import dev.kamol1dn.syllable.spotify.SpotifyAuth
import kotlin.math.roundToInt

private val SpotifyGreen = Color(0xFF1ED760)
private const val REPO = "https://github.com/kamol1dn/syllable-lyrics"

@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit, onSpotifyLogin: () -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val s by vm.settings.state.collectAsStateWithLifecycle()
    val spotify by SpotifyAuth.state.collectAsStateWithLifecycle()
    val hasAccess by vm.media.hasAccess.collectAsStateWithLifecycle()
    val cache by vm.cacheStats.collectAsStateWithLifecycle()
    val fromFile = remember { installedFromFile(context) }

    fun micAllowed() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    fun micRationale() = activity != null && ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO)
    var micGranted by remember { mutableStateOf(micAllowed()) }
    var rationaleBefore by remember { mutableStateOf(false) }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micGranted = granted
        // Android stops asking after two refusals; then the switch is in app settings.
        if (!granted && !rationaleBefore && !micRationale()) openAppInfo(context)
    }
    LifecycleResumeEffect(Unit) {
        micGranted = micAllowed()
        vm.media.checkAccess()
        vm.refreshCacheStats()
        onPauseOrDispose { }
    }
    var confirmSignOut by remember { mutableStateOf(false) }
    var showLicenses by remember { mutableStateOf(false) }

    val scroll = rememberScrollState()
    val scrolled by remember { derivedStateOf { scroll.value > 0 } }
    val barColor by animateColorAsState(
        if (scrolled) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.background, label = "settingsBar",
    )

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // The header stays put and darkens a little once the page scrolls under it.
        Box(
            Modifier.fillMaxWidth().background(barColor)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
            contentAlignment = Alignment.TopCenter,
        ) {
            Row(Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White) }
                Text(
                    "Settings", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() },
                )
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 640.dp).fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                    .imePadding().verticalScroll(scroll),
            ) {
                Section("Display")
                Card {
                    SliderHeader("Text size", "${(s.textScale * 100).roundToInt()}%", s.textScale != 1f) {
                        vm.settings.update { it.copy(textScale = 1f) }
                    }
                    Slider(
                        value = s.textScale,
                        onValueChange = { v -> vm.settings.update { it.copy(textScale = (v * 20).roundToInt() / 20f) } },
                        valueRange = 0.7f..1.4f,
                        modifier = Modifier.semantics {
                            contentDescription = "Text size"
                            stateDescription = "${(s.textScale * 100).roundToInt()}%"
                        },
                    )
                    Text(
                        "Lyrics will look like this", color = Color.White, fontWeight = FontWeight.ExtraBold,
                        fontSize = (30 * s.textScale).sp, lineHeight = (38 * s.textScale).sp, letterSpacing = (-0.3).sp,
                        modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                    )
                    val canBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    Toggle(
                        "Blur lines that aren't being sung", s.blurInactive && canBlur,
                        subtitle = if (canBlur) null else "Needs Android 12 or newer", enabled = canBlur,
                    ) { v -> vm.settings.update { it.copy(blurInactive = v) } }
                    Toggle("Keep screen on while lyrics play", s.keepScreenOn) { v -> vm.settings.update { it.copy(keepScreenOn = v) } }
                }

                Section("Timing")
                Card {
                    val output by vm.output.collectAsStateWithLifecycle()
                    Text("Playing through: ${output.name}", color = Color.White)
                    Helper(
                        "Syllable remembers the timing for each speaker and pair of headphones, and uses it again when you play " +
                            "through them. Change it with the timing button under the lyrics.",
                        Modifier.padding(top = 2.dp),
                    )
                    Spacer(Modifier.height(14.dp))
                    SliderHeader(
                        "New Bluetooth devices", formatOffset(s.bluetoothDefaultMs),
                        s.bluetoothDefaultMs != SettingsState.DEFAULT_BLUETOOTH_MS,
                    ) { vm.settings.update { it.copy(bluetoothDefaultMs = SettingsState.DEFAULT_BLUETOOTH_MS) } }
                    Helper("Bluetooth audio reaches you a moment late, so lyrics wait a little. Slide left to wait longer.")
                    Slider(
                        value = s.bluetoothDefaultMs.toFloat(),
                        onValueChange = { v -> vm.settings.update { it.copy(bluetoothDefaultMs = ((v / 50).roundToInt() * 50).toLong()) } },
                        valueRange = -1000f..500f,
                        modifier = Modifier.semantics {
                            contentDescription = "Starting timing for new Bluetooth devices"
                            stateDescription = formatOffset(s.bluetoothDefaultMs, unit = " seconds")
                        },
                    )
                    if (s.deviceTimings.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text("Saved", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        s.deviceTimings.entries.sortedBy { it.value.name.lowercase() }.forEach { (key, d) ->
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(d.name, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Helper(formatOffset(d.offsetMs))
                                }
                                TextButton(onClick = { vm.settings.forgetTiming(key) }) { Text("Forget", color = Accent) }
                            }
                        }
                    }
                }

                Section("Spicy Lyrics")
                Card { SpicyCard(vm, s) }

                Section("Spotify")
                Card {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(if (spotify.signedIn) SpotifyGreen else Color.Gray))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            when {
                                !spotify.signedIn -> "Not signed in to Spotify"
                                spotify.displayName != null -> "Signed in as ${spotify.displayName}"
                                else -> "Signed in to Spotify"
                            },
                            color = Color.White, fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Helper(
                        when {
                            !spotify.signedIn -> "Sign in to get lyrics from Spotify. Some songs have word-by-word timing."
                            !s.useSpotify -> "Spotify lyrics are off. Turn them on below."
                            else -> "Syllable checks Spotify for lyrics with this account."
                        },
                        Modifier.padding(top = 2.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    if (spotify.signedIn) {
                        Toggle("Use Spotify lyrics", s.useSpotify, subtitle = "Also helps Spicy Lyrics find the right song") { v ->
                            vm.settings.update { it.copy(useSpotify = v) }
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlineButton(onClick = { confirmSignOut = true }, modifier = Modifier.fillMaxWidth()) { Text("Sign out") }
                    } else {
                        Button(
                            onClick = onSpotifyLogin,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen, contentColor = Color.Black),
                        ) { Text("Sign in with Spotify", fontWeight = FontWeight.Bold) }
                    }
                }

                Section("More sources")
                Card {
                    Toggle("Musixmatch", s.useMusixmatch) { v -> vm.settings.update { it.copy(useMusixmatch = v) } }
                    Toggle("NetEase", s.useNetEase) { v -> vm.settings.update { it.copy(useNetEase = v) } }
                    Toggle("LRCLIB", checked = true, subtitle = "Always on", enabled = false) {}
                    Helper(
                        "Syllable asks every source at once. Word timing beats line timing, and if two tie, the one higher on " +
                            "this page wins. When a song only has line timing, Syllable guesses when each word starts.",
                        Modifier.padding(top = 6.dp),
                    )
                }

                Section("Storage")
                Card {
                    val n = cache?.first ?: 0
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Saved lyrics", color = Color.White)
                            cache?.let { (songs, bytes) ->
                                Helper(
                                    if (songs == 0) "Nothing saved yet. Lyrics you play are kept for 30 days."
                                    else "${if (songs == 1) "1 song" else "$songs songs"}, ${Formatter.formatShortFileSize(context, bytes)}. " +
                                        "Kept for 30 days so they load right away.",
                                )
                            }
                        }
                        TextButton(onClick = { vm.clearCache() }, enabled = n > 0) {
                            Text("Clear", color = if (n > 0) Accent else Color.White.copy(alpha = 0.3f))
                        }
                    }
                }

                Section("Permissions")
                Card {
                    PermissionRow("Notification access", "See what's playing in other apps", hasAccess, "Open system settings") {
                        openNotificationAccess(context)
                    }
                    // Apps installed from an APK file need one more step on Android 13 and later.
                    if (!hasAccess && fromFile) {
                        RestrictedSettingsHint(Modifier.fillMaxWidth().padding(bottom = 4.dp), textAlign = TextAlign.Start)
                    }
                    PermissionRow(
                        "Microphone", "Identify songs playing around you", micGranted,
                        if (micGranted) "Open system settings" else "Allow",
                    ) {
                        if (micGranted) {
                            openAppInfo(context)
                        } else {
                            rationaleBefore = micRationale()
                            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                }

                Section("About")
                Card {
                    Text("Syllable ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Helper("Shows the lyrics of what you're playing, word by word.")
                    Text("Privacy", color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 14.dp))
                    Helper(
                        "Syllable uses notification access to see which song is playing and nothing else. To identify a song, it " +
                            "turns a few seconds of sound into a fingerprint on your phone and sends that to Shazam with your time " +
                            "zone and language. The recording itself isn't saved or sent. Song titles, artists and albums go to " +
                            "LRCLIB and the lyrics sources you turn on. Your Spotify sign-in stays on this phone and is only sent to Spotify.",
                        Modifier.padding(top = 2.dp),
                    )
                    FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        AboutLink("Source code") { openUrl(context, REPO) }
                        AboutLink("Report a problem") {
                            val info = "Syllable ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}), Android ${Build.VERSION.RELEASE}, " +
                                "${Build.MANUFACTURER} ${Build.MODEL}\n\n"
                            openUrl(context, "$REPO/issues/new?body=" + Uri.encode(info))
                        }
                        AboutLink("Open-source licenses") { showLicenses = true }
                    }
                }
                Spacer(Modifier.height(32.dp))
                Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
            }
        }
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            title = { Text("Sign out of Spotify?", color = Color.White) },
            text = { Text("You'll need to sign in again to get lyrics from Spotify.", color = Color.White.copy(alpha = 0.7f)) },
            confirmButton = { TextButton(onClick = { confirmSignOut = false; SpotifyAuth.signOut() }) { Text("Sign out", color = Accent) } },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel", color = Color.White) } },
        )
    }
    if (showLicenses) {
        AlertDialog(
            onDismissRequest = { showLicenses = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            title = { Text("Open-source licenses", color = Color.White) },
            text = {
                Text(
                    "Syllable is built with Jetpack Compose and AndroidX, Kotlin coroutines, OkHttp and Okio, Coil and AndroidX " +
                        "Palette. Each is under the Apache License 2.0.",
                    color = Color.White.copy(alpha = 0.7f),
                )
            },
            confirmButton = { TextButton(onClick = { showLicenses = false }) { Text("Close", color = Accent) } },
            dismissButton = {
                TextButton(onClick = { openUrl(context, "https://www.apache.org/licenses/LICENSE-2.0") }) { Text("Read the license", color = Color.White) }
            },
        )
    }
}

@Composable
private fun SpicyCard(vm: MainViewModel, s: SettingsState) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val builtIn = BuildConfig.SPICY_LYRICS_KEY.isNotEmpty()
    val hasKey = s.spicyKey.isNotBlank() || builtIn
    // With a key built in, most people never need the field, so it waits behind a link.
    var showKeyField by rememberSaveable { mutableStateOf(!builtIn || s.spicyKey.isNotBlank()) }
    var keyVisible by rememberSaveable { mutableStateOf(false) }

    Toggle("Use Spicy Lyrics", s.useSpicy) { v -> vm.settings.update { it.copy(useSpicy = v) } }
    Helper("Word-by-word lyrics made by the Spicy Lyrics community, with background vocals and duets. Syllable checks here first.")
    Spacer(Modifier.height(6.dp))
    if (!showKeyField) {
        TextButton(onClick = { showKeyField = true }, enabled = s.useSpicy, contentPadding = PaddingValues(0.dp)) {
            Text("Use your own key", color = if (s.useSpicy) Accent else Color.White.copy(alpha = 0.3f))
        }
        return
    }
    Spacer(Modifier.height(4.dp))
    OutlinedTextField(
        value = s.spicyKey,
        onValueChange = { v -> vm.settings.update { it.copy(spicyKey = v.trim()) } },
        label = { Text(if (builtIn) "Your own key (optional)" else "API key") },
        enabled = s.useSpicy,
        singleLine = true,
        visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
        trailingIcon = {
            IconButton(onClick = { keyVisible = !keyVisible }) {
                Icon(if (keyVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (keyVisible) "Hide key" else "Show key")
            }
        },
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White, unfocusedTextColor = Color.White, disabledTextColor = Color.White.copy(alpha = 0.35f),
            focusedBorderColor = Accent, unfocusedBorderColor = Color.White.copy(alpha = 0.25f), disabledBorderColor = Color.White.copy(alpha = 0.12f),
            focusedLabelColor = Accent, unfocusedLabelColor = Color.White.copy(alpha = 0.5f), disabledLabelColor = Color.White.copy(alpha = 0.3f),
            focusedTrailingIconColor = Color.White.copy(alpha = 0.7f), unfocusedTrailingIconColor = Color.White.copy(alpha = 0.5f),
            disabledTrailingIconColor = Color.White.copy(alpha = 0.25f),
        ),
    )
    if (hasKey) {
        Helper(if (s.spicyKey.isNotBlank()) "Using your key." else "Using Syllable's built-in key.", Modifier.padding(top = 6.dp))
    } else {
        Text(
            buildAnnotatedString {
                append("Spicy Lyrics needs a free key. Get one at ")
                withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) { append("developers.spicylyrics.org") }
            },
            color = Accent, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 6.dp).clickable { openUrl(context, "https://developers.spicylyrics.org") },
        )
    }
}

/** Small grey text under a setting. */
@Composable
private fun Helper(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f), modifier = modifier)
}

@Composable
private fun Section(title: String) {
    Text(
        title.uppercase(), color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp, fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        // Lines up with the text inside the cards.
        modifier = Modifier.padding(start = 32.dp, top = 20.dp, bottom = 8.dp).semantics { heading() },
    )
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainer).padding(16.dp),
    ) { content() }
}

/** The whole row is one switch for TalkBack and for taps. */
@Composable
private fun Toggle(label: String, checked: Boolean, subtitle: String? = null, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = Color.White.copy(alpha = if (enabled) 1f else 0.4f))
            if (subtitle != null) Helper(subtitle)
        }
        Switch(
            checked = checked, onCheckedChange = null, enabled = enabled,
            modifier = Modifier.minimumInteractiveComponentSize(),
            colors = SwitchDefaults.colors(checkedTrackColor = Accent, disabledCheckedTrackColor = Accent.copy(alpha = 0.4f)),
        )
    }
}

/** A slider's name and value on one line, with a Reset once it's changed. */
@Composable
private fun SliderHeader(label: String, value: String, showReset: Boolean, onReset: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, modifier = Modifier.weight(1f))
        AnimatedVisibility(showReset, enter = fadeIn(), exit = fadeOut()) {
            Text(
                "Reset", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(end = 4.dp).clip(RoundedCornerShape(50)).clickable(onClick = onReset)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
        Text(value, color = Color.White.copy(alpha = 0.7f), style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum"))
    }
}

@Composable
private fun PermissionRow(title: String, subtitle: String, granted: Boolean, clickLabel: String, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = clickLabel, onClick = onOpen).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White)
            Helper(subtitle)
        }
        if (granted) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.CheckCircle, null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Allowed", color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp)
            }
        } else {
            Text(
                "Allow", color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.White).padding(horizontal = 14.dp, vertical = 7.dp),
            )
        }
    }
}

@Composable
private fun AboutLink(label: String, onClick: () -> Unit) {
    Text(
        label, color = Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clickable(onClick = onClick).padding(vertical = 10.dp),
    )
}
