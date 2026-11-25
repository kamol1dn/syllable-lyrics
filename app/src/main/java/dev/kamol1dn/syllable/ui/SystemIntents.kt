package dev.kamol1dn.syllable.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.kamol1dn.syllable.AppUpdate
import dev.kamol1dn.syllable.BuildConfig
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import dev.kamol1dn.syllable.media.MediaNotificationListener

/** Opens Syllable's own notification access page, or the full list where that page doesn't exist. */
fun openNotificationAccess(ctx: Context) {
    if (Build.VERSION.SDK_INT >= 30) {
        val detail = Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(
                AndroidSettings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                ComponentName(ctx, MediaNotificationListener::class.java).flattenToString(),
            )
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { ctx.startActivity(detail) }.isSuccess) return
    }
    runCatching { ctx.startActivity(Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

fun openAppInfo(ctx: Context) = runCatching {
    ctx.startActivity(
        Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

/** Opens a link, and does nothing if there's no browser. */
fun openUrl(ctx: Context, url: String) = runCatching {
    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/**
 * Says that a newer version is out. Download hands its APK to the browser, which fetches it, and
 * closes the notice for now.
 */
@Composable
fun UpdateDialog(update: AppUpdate, onLater: () -> Unit, onSkip: () -> Unit) {
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onLater,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        title = { Text("Syllable ${update.version} is out", color = Color.White) },
        text = {
            Column {
                Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        update.notes.ifEmpty { "A newer version than this one (${BuildConfig.VERSION_NAME}) is ready to download." },
                        color = Color.White.copy(alpha = 0.7f),
                    )
                }
                // Three buttons don't fit the row below, so the least used one sits here.
                TextButton(onClick = onSkip, contentPadding = PaddingValues(0.dp), modifier = Modifier.padding(top = 4.dp)) {
                    Text("Skip this version", color = Color.White.copy(alpha = 0.6f))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { openUrl(ctx, update.downloadUrl); onLater() }) { Text("Download", color = Accent, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onLater) { Text("Later", color = Color.White) } },
    )
}

/** Android 13+ blocks notification access for apps installed from an APK file until you allow it. */
fun installedFromFile(ctx: Context): Boolean = Build.VERSION.SDK_INT >= 33 && runCatching {
    ctx.packageManager.getInstallSourceInfo(ctx.packageName).packageSource in
        setOf(PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE, PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
}.getOrDefault(false)

const val RESTRICTED_HELP =
    "If the switch is greyed out, tap it once. Then open App info, tap the three-dot menu, choose Allow restricted settings and try again."

@Composable
fun RestrictedSettingsHint(modifier: Modifier = Modifier, textAlign: TextAlign = TextAlign.Center) {
    val ctx = LocalContext.current
    Column(modifier, horizontalAlignment = if (textAlign == TextAlign.Start) Alignment.Start else Alignment.CenterHorizontally) {
        Text(RESTRICTED_HELP, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f), textAlign = textAlign)
        TextButton(onClick = { openAppInfo(ctx) }) { Text("Open App info", color = Accent) }
    }
}
