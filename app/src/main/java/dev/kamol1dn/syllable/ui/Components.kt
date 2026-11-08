package dev.kamol1dn.syllable.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

val Accent = Color(0xFFFF4E8A)
val AccentViolet = Color(0xFF6A3CFF)
/** Filled buttons use a deeper pink so white text on them stays readable. */
val AccentButton = Color(0xFFD6336C)
/** The app's background, shared with the window and splash screen (res/values/colors.xml app_bg). */
val AppBg = Color(0xFF120B1C)
val ButtonMinHeight = 52.dp

private val Scheme: ColorScheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    secondary = AccentViolet,
    background = AppBg,
    surface = Color(0xFF16101F),
    surfaceVariant = Color(0xFF241B30),
    surfaceContainer = Color(0xFF21172F),
    onSurface = Color.White,
    onBackground = Color.White,
)

private val AppType = Typography().let { t ->
    t.copy(
        // Centred state headlines: nothing playing, no access, not found, offline, listening.
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.ExtraBold, lineHeight = 34.sp),
        // Helper and caption text, the same size as the credit line under the lyrics.
        bodySmall = t.bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp),
    )
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
)

@Composable
fun SyllableTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = AppType, shapes = AppShapes, content = content)
}

private val ButtonPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
// Bright enough to stay visible on light album art too.
private val OutlineBorder = BorderStroke(1.dp, Color.White.copy(alpha = 0.7f))

/** Compact outlined button for inline actions. Stays visible on any album art. */
@Composable
fun OutlineButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) = OutlinedButton(
    onClick = onClick, modifier = modifier, enabled = enabled, border = OutlineBorder,
    colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White.copy(alpha = 0.08f), contentColor = Color.White),
    contentPadding = contentPadding, content = content,
)

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) = Button(
    onClick = onClick, modifier = modifier.heightIn(min = ButtonMinHeight),
    colors = ButtonDefaults.buttonColors(containerColor = AccentButton, contentColor = Color.White),
    contentPadding = ButtonPadding,
) { ButtonLabel(text, icon) }

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) = OutlinedButton(
    onClick = onClick, modifier = modifier.heightIn(min = ButtonMinHeight), border = OutlineBorder,
    colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White.copy(alpha = 0.08f), contentColor = Color.White),
    contentPadding = ButtonPadding,
) { ButtonLabel(text, icon) }

@Composable
private fun ButtonLabel(text: String, icon: ImageVector?) {
    if (icon != null) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
    }
    Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
}

/** "0.25s earlier", "0.25s later" or "No change", always with a dot. Pass unit = " seconds" for TalkBack. */
internal fun formatOffset(ms: Long, unit: String = "s"): String = when {
    ms > 0 -> "%.2f%s earlier".format(Locale.US, ms / 1000f, unit)
    ms < 0 -> "%.2f%s later".format(Locale.US, -ms / 1000f, unit)
    else -> "No change"
}
