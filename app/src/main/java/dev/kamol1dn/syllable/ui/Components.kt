package dev.kamol1dn.syllable.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Accent = Color(0xFFFF4E8A)
val AccentViolet = Color(0xFF6A3CFF)

private val Scheme: ColorScheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    secondary = AccentViolet,
    background = Color(0xFF0B0712),
    surface = Color(0xFF16101F),
    surfaceVariant = Color(0xFF241B30),
    onSurface = Color.White,
    onBackground = Color.White,
)

@Composable
fun SyllableTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
