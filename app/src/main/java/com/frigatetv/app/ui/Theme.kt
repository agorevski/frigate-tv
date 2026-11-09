package com.frigatetv.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Surfaces step up in lightness so depth reads without borders.
val Ink = Color(0xFF0B0E12)
val Panel = Color(0xFF161B22)
val PanelHi = Color(0xFF222A35)

// Text tiers. Secondary text is kept at 7:1+ against Panel so it stays legible from a sofa.
val TextPrimary = Color(0xFFF2F4F7)
val TextSecondary = Color(0xFFBAC3D0)

/** Deprecated alias kept for older call sites. Use [TextSecondary]. */
val TextMuted = TextSecondary

// The single accent. Used for primary actions and focus/selection only.
val Accent = Color(0xFF4FC3F7)

// Status colors. Semantic only; never decorative.
val LiveGreen = Color(0xFF3DDC84)
val Warn = Color(0xFFFFB300)
val Danger = Color(0xFFFF5252)

/** Spacing scale. Use these instead of ad-hoc dp values. */
object Space {
    val xxs = 4.dp
    val xs = 8.dp
    val sm = 12.dp
    val md = 16.dp
    val lg = 24.dp
    val xl = 32.dp
    val xxl = 48.dp
}

/**
 * Corner radii. Nested shapes follow inner = outer - padding: a [lg] surface that pads its
 * content by [Space.xs] gives its children [md] corners.
 */
object Radius {
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
}

/** Motion durations in milliseconds. Feedback stays short; ambient motion may be slower. */
object Motion {
    const val FocusMs = 150
    const val EnterMs = 220
    const val PulseMs = 900

    /** How long the launch artwork stays fully opaque after the first frame. */
    const val SplashHoldMs = 450

    /** Exit fades (splash, overlays). Shorter than enter so dismissal feels snappy. */
    const val ExitMs = 180
}

/** Tabular figures so clocks, counters, and timestamps do not shift width as they change. */
val NumericStyle = TextStyle(fontFeatureSettings = "tnum")

private val AppTypography = Typography(
    headlineLarge = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Bold, lineHeight = 38.sp),
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold, lineHeight = 32.sp),
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, lineHeight = 28.sp),
    titleMedium = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.SemiBold, lineHeight = 24.sp),
    // Material defaults for the small styles are phone-sized; keep them readable at 10 feet.
    titleSmall = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontSize = 18.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
    labelSmall = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp),
)

private val AppColors = darkColorScheme(
    primary = Accent,
    onPrimary = Ink,
    background = Ink,
    onBackground = TextPrimary,
    surface = Panel,
    onSurface = TextPrimary,
    surfaceVariant = PanelHi,
    onSurfaceVariant = TextSecondary,
    outline = TextSecondary,
    outlineVariant = PanelHi,
    surfaceContainer = Panel,
    surfaceContainerHigh = PanelHi,
    error = Danger,
)

@Composable
fun FrigateTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = AppColors, typography = AppTypography, content = content)
}
