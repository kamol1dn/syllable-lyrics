package dev.kamol1dn.syllable.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.layout.Column
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
