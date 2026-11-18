package com.rozin.compass.ui.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.rozin.compass.R

@Immutable
data class CompassColors(
    val background: Color,
    val glow: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val hairline: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val icon: Color,
    val accent: Color,
    val accentLight: Color,
    val accentDeep: Color,
    val dot: Color,
    val north: Color,
    val east: Color,
    val south: Color,
    val west: Color,
    val good: Color,
    val warn: Color,
    val bad: Color,
    val track: Color,
)

val LightCompassColors = CompassColors(
    background = Color(0xFFF5F6F9),
    glow = Color(0x100A84FF),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFECEEF3),
    hairline = Color(0x1A1C2433),
    textPrimary = Color(0xFF1B1E26),
    textSecondary = Color(0xFF5F6472),
    textMuted = Color(0xFF9DA2AE),
    icon = Color(0xFF2A2E38),
    accent = Color(0xFF0A84FF),
    accentLight = Color(0xFF4BA8FF),
    accentDeep = Color(0xFF0060E6),
    dot = Color(0xFFC9CCD6),
    north = Color(0xFFF0443A),
    east = Color(0xFF22B864),
    south = Color(0xFF2F7BFF),
    west = Color(0xFF13C6DD),
    good = Color(0xFF26B860),
    warn = Color(0xFFF29E1F),
    bad = Color(0xFFF0443A),
    track = Color(0x1F1C2433),
)

val DarkCompassColors = CompassColors(
    background = Color(0xFF0B0D12),
    glow = Color(0x1C3D9BFF),
    surface = Color(0xFF15181F),
    surfaceVariant = Color(0xFF1E222B),
    hairline = Color(0x1FFFFFFF),
    textPrimary = Color(0xFFEDEFF4),
    textSecondary = Color(0xFF9EA4B2),
    textMuted = Color(0xFF5F6575),
    icon = Color(0xFFD9DCE4),
    accent = Color(0xFF3D9BFF),
    accentLight = Color(0xFF6DB6FF),
    accentDeep = Color(0xFF1570E6),
    dot = Color(0xFF353A47),
    north = Color(0xFFFF5B50),
    east = Color(0xFF34D27F),
    south = Color(0xFF5193FF),
    west = Color(0xFF34DCEC),
    good = Color(0xFF3DD47A),
    warn = Color(0xFFFFB13B),
    bad = Color(0xFFFF5B50),
    track = Color(0x24FFFFFF),
)

private fun lerp(a: CompassColors, b: CompassColors, t: Float) = CompassColors(
    background = lerp(a.background, b.background, t),
    glow = lerp(a.glow, b.glow, t),
    surface = lerp(a.surface, b.surface, t),
    surfaceVariant = lerp(a.surfaceVariant, b.surfaceVariant, t),
    hairline = lerp(a.hairline, b.hairline, t),
    textPrimary = lerp(a.textPrimary, b.textPrimary, t),
    textSecondary = lerp(a.textSecondary, b.textSecondary, t),
    textMuted = lerp(a.textMuted, b.textMuted, t),
    icon = lerp(a.icon, b.icon, t),
    accent = lerp(a.accent, b.accent, t),
    accentLight = lerp(a.accentLight, b.accentLight, t),
    accentDeep = lerp(a.accentDeep, b.accentDeep, t),
    dot = lerp(a.dot, b.dot, t),
    north = lerp(a.north, b.north, t),
    east = lerp(a.east, b.east, t),
    south = lerp(a.south, b.south, t),
    west = lerp(a.west, b.west, t),
    good = lerp(a.good, b.good, t),
    warn = lerp(a.warn, b.warn, t),
    bad = lerp(a.bad, b.bad, t),
    track = lerp(a.track, b.track, t),
)

val LocalCompassColors = staticCompositionLocalOf { LightCompassColors }

@OptIn(ExperimentalTextApi::class)
private fun jakarta(weight: Int) = Font(
    R.font.plus_jakarta_sans,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/** Plus Jakarta Sans (variable, 200–800). */
val AppFont = FontFamily(
    jakarta(200), jakarta(300), jakarta(400), jakarta(500), jakarta(600), jakarta(700),
)

/** Tabular figures so changing numbers never jitter sideways. */
val TabularStyle = TextStyle(fontFeatureSettings = "tnum")

private val AppTypography = Typography().run {
    fun TextStyle.app() = copy(fontFamily = AppFont)
    Typography(
        displayLarge = displayLarge.app(), displayMedium = displayMedium.app(), displaySmall = displaySmall.app(),
        headlineLarge = headlineLarge.app(), headlineMedium = headlineMedium.app(), headlineSmall = headlineSmall.app(),
        titleLarge = titleLarge.app(), titleMedium = titleMedium.app(), titleSmall = titleSmall.app(),
        bodyLarge = bodyLarge.app(), bodyMedium = bodyMedium.app(), bodySmall = bodySmall.app(),
        labelLarge = labelLarge.app(), labelMedium = labelMedium.app(), labelSmall = labelSmall.app(),
    )
}

@Composable
fun CompassTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    // A single animated fraction cross-fades the whole palette when the theme changes.
    val t by animateFloatAsState(if (darkTheme) 1f else 0f, tween(450), label = "theme")
    val colors = when (t) {
        0f -> LightCompassColors
        1f -> DarkCompassColors
        else -> lerp(LightCompassColors, DarkCompassColors, t)
    }
    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = colors.accent, onPrimary = Color.White, background = colors.background,
            surface = colors.surface, surfaceContainerLow = colors.surface,
            onSurface = colors.textPrimary, onSurfaceVariant = colors.textSecondary,
        )
    } else {
        lightColorScheme(
            primary = colors.accent, onPrimary = Color.White, background = colors.background,
            surface = colors.surface, surfaceContainerLow = colors.surface,
            onSurface = colors.textPrimary, onSurfaceVariant = colors.textSecondary,
        )
    }
    CompositionLocalProvider(LocalCompassColors provides colors) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
    }
}
