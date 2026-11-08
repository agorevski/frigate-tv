package com.frigatetv.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Ink = Color(0xFF0B0E12)
val Panel = Color(0xFF161B22)
val PanelHi = Color(0xFF222A35)
val TextPrimary = Color(0xFFEDEFF2)
val TextMuted = Color(0xFF9AA4B2)
val Accent = Color(0xFF4FC3F7)
val LiveGreen = Color(0xFF3DDC84)
val Warn = Color(0xFFFFB300)
val Danger = Color(0xFFFF5252)

private val AppColors = darkColorScheme(
    primary = Accent,
    onPrimary = Ink,
    background = Ink,
    onBackground = TextPrimary,
    surface = Panel,
    onSurface = TextPrimary,
    surfaceVariant = PanelHi,
    onSurfaceVariant = TextMuted,
    error = Danger,
)

@Composable
fun FrigateTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = AppColors, content = content)
}
