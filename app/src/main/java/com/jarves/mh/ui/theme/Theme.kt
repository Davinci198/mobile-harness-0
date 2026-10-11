package com.jarves.mh.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.jarves.mh.R

/**
 * Selectable app-wide color theme. DEV is the default terminal/IDE palette;
 * VIOLET is the original agent redesign palette; GREEN and ROSE sample the
 * Gboard keyboards the user shipped as screenshots.
 */
enum class AppColorTheme { DEV, VIOLET, GREEN, ROSE, CUSTOM }

val JetBrainsMono: FontFamily = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
)

val DevTypography: Typography = Typography().run {
    copy(
        displayLarge = displayLarge.copy(fontFamily = JetBrainsMono),
        displayMedium = displayMedium.copy(fontFamily = JetBrainsMono),
        displaySmall = displaySmall.copy(fontFamily = JetBrainsMono),
        headlineLarge = headlineLarge.copy(fontFamily = JetBrainsMono),
        headlineMedium = headlineMedium.copy(fontFamily = JetBrainsMono),
        headlineSmall = headlineSmall.copy(fontFamily = JetBrainsMono),
        titleLarge = titleLarge.copy(fontFamily = JetBrainsMono),
        titleMedium = titleMedium.copy(fontFamily = JetBrainsMono),
        titleSmall = titleSmall.copy(fontFamily = JetBrainsMono),
        bodyLarge = bodyLarge.copy(fontFamily = JetBrainsMono, fontSize = 14.sp, lineHeight = 21.sp),
        bodyMedium = bodyMedium.copy(fontFamily = JetBrainsMono, fontSize = 13.sp, lineHeight = 19.sp),
        bodySmall = bodySmall.copy(fontFamily = JetBrainsMono),
        labelLarge = labelLarge.copy(fontFamily = JetBrainsMono),
        labelMedium = labelMedium.copy(fontFamily = JetBrainsMono),
        labelSmall = labelSmall.copy(fontFamily = JetBrainsMono),
    )
}

/**
 * Brand colors + Material3 schemes for one [AppColorTheme]. The [darkColors] and
 * [lightColors] schemes feed [MaterialTheme]; the scalar colors back the `Pocket*`
 * constants so every existing call site recolors with the theme.
 */
data class PocketPalette(
    val accent: Color,
    val accentTeal: Color,
    val blue: Color,
    val green: Color,
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val outline: Color,
    val cardBorder: Color,
    val muted: Color,
    val darkColors: ColorScheme,
    val lightColors: ColorScheme,
)

/** Terminal/IDE dark: near-black blue-tinted bg, green run accent, cyan AI accent. */
private val DevPalette = PocketPalette(
    accent = Color(0xFF22C55E),
    accentTeal = Color(0xFF22D3EE),
    blue = Color(0xFF60A5FA),
    green = Color(0xFF4ADE80),
    background = Color(0xFF0B0F14),
    surface = Color(0xFF111823),
    surfaceVariant = Color(0xFF1A2332),
    outline = Color(0xFF2A3A4D),
    cardBorder = Color(0xFF1E2A3A),
    muted = Color(0xFF8B9BB4),
    darkColors = darkColorScheme(
        primary = Color(0xFF22C55E),
        onPrimary = Color(0xFF052E16),
        primaryContainer = Color(0xFF14361F),
        onPrimaryContainer = Color(0xFFB9F6CA),
        secondary = Color(0xFF22D3EE),
        onSecondary = Color(0xFF00313A),
        secondaryContainer = Color(0xFF0C3A44),
        onSecondaryContainer = Color(0xFFA7F3FC),
        tertiary = Color(0xFF60A5FA),
        onTertiary = Color(0xFF002F65),
        background = Color(0xFF0B0F14),
        onBackground = Color(0xFFE2E8F0),
        surface = Color(0xFF111823),
        onSurface = Color(0xFFE2E8F0),
        surfaceVariant = Color(0xFF1A2332),
        onSurfaceVariant = Color(0xFF8B9BB4),
        outline = Color(0xFF2A3A4D),
        outlineVariant = Color(0xFF1E2A3A),
    ),
    lightColors = lightColorScheme(
        primary = Color(0xFF15803D),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFDCFCE7),
        onPrimaryContainer = Color(0xFF14532D),
        secondary = Color(0xFF0E7490),
        onSecondary = Color(0xFFFFFFFF),
        tertiary = Color(0xFF1D4ED8),
        onTertiary = Color(0xFFFFFFFF),
        background = Color(0xFFF8FAFC),
        onBackground = Color(0xFF0F172A),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF0F172A),
        surfaceVariant = Color(0xFFF1F5F9),
        onSurfaceVariant = Color(0xFF475569),
        outline = Color(0xFFCBD5E1),
        outlineVariant = Color(0xFFE2E8F0),
    ),
)

private val VioletPalette = PocketPalette(
    accent = Color(0xFF7C5CFC),
    accentTeal = Color(0xFF3AD7D0),
    blue = Color(0xFF8EA8FF),
    green = Color(0xFF69D69E),
    background = Color(0xFF0F0F10),
    surface = Color(0xFF17171C),
    surfaceVariant = Color(0xFF1F1F28),
    outline = Color(0xFF2A2A32),
    cardBorder = Color(0xFF262631),
    muted = Color(0xFF9A9AA3),
    darkColors = darkColorScheme(
        primary = Color(0xFF7C5CFC),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFF2B2150),
        onPrimaryContainer = Color(0xFFDCD4FF),
        secondary = Color(0xFF8EA8FF),
        onSecondary = Color(0xFF001F58),
        tertiary = Color(0xFF69D69E),
        onTertiary = Color(0xFF00391E),
        background = Color(0xFF0F0F10),
        onBackground = Color(0xFFE6EDF3),
        surface = Color(0xFF17171C),
        onSurface = Color(0xFFE6EDF3),
        surfaceVariant = Color(0xFF1F1F28),
        onSurfaceVariant = Color(0xFF9A9AA3),
        outline = Color(0xFF2A2A32),
        outlineVariant = Color(0xFF262631),
    ),
    lightColors = lightColorScheme(
        primary = Color(0xFF5B3FD9),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFE8E0FF),
        onPrimaryContainer = Color(0xFF2B2150),
        secondary = Color(0xFF3366CC),
        onSecondary = Color(0xFFFFFFFF),
        tertiary = Color(0xFF1B8A5A),
        onTertiary = Color(0xFFFFFFFF),
        background = Color(0xFFF6F8FA),
        onBackground = Color(0xFF1F2328),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF1F2328),
        surfaceVariant = Color(0xFFEAEFF5),
        onSurfaceVariant = Color(0xFF57606A),
        outline = Color(0xFFD0D7DE),
        outlineVariant = Color(0xFFD8DEE4),
    ),
)

/** Screenshot green: #43A047 key field, #64B168 lit keys, #287830 deep green. */
private val GreenPalette = PocketPalette(
    accent = Color(0xFF2E9E5B),
    accentTeal = Color(0xFF34D399),
    blue = Color(0xFF64B168),
    green = Color(0xFF69D69E),
    background = Color(0xFF0A140E),
    surface = Color(0xFF101D15),
    surfaceVariant = Color(0xFF17281D),
    outline = Color(0xFF274032),
    cardBorder = Color(0xFF1F3628),
    muted = Color(0xFF8FA896),
    darkColors = darkColorScheme(
        primary = Color(0xFF43A047),
        onPrimary = Color(0xFF04210F),
        primaryContainer = Color(0xFF123B22),
        onPrimaryContainer = Color(0xFFA9E9C0),
        secondary = Color(0xFF64B168),
        onSecondary = Color(0xFF052E16),
        secondaryContainer = Color(0xFF17351F),
        onSecondaryContainer = Color(0xFFC6F0D2),
        tertiary = Color(0xFF69D69E),
        onTertiary = Color(0xFF00391E),
        background = Color(0xFF0A140E),
        onBackground = Color(0xFFE1EFE5),
        surface = Color(0xFF101D15),
        onSurface = Color(0xFFE1EFE5),
        surfaceVariant = Color(0xFF17281D),
        onSurfaceVariant = Color(0xFF8FA896),
        outline = Color(0xFF274032),
        outlineVariant = Color(0xFF1F3628),
    ),
    lightColors = lightColorScheme(
        primary = Color(0xFF157A46),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFD3F0DE),
        onPrimaryContainer = Color(0xFF0A3B22),
        secondary = Color(0xFF2E7D50),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFD2F0DC),
        onSecondaryContainer = Color(0xFF0A3B22),
        tertiary = Color(0xFF1B8A5A),
        onTertiary = Color(0xFFFFFFFF),
        background = Color(0xFFF2F8F4),
        onBackground = Color(0xFF17241B),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF17241B),
        surfaceVariant = Color(0xFFE7F0EA),
        onSurfaceVariant = Color(0xFF52655A),
        outline = Color(0xFFC9D9CF),
        outlineVariant = Color(0xFFDCE8E0),
    ),
)

/** Screenshot gradient: #140E64 indigo -> #5B2A7F purple -> #B64FA0 magenta, #F951AD enter key. */
private val RosePalette = PocketPalette(
    accent = Color(0xFFDB2777),
    accentTeal = Color(0xFFA855F7),
    blue = Color(0xFF7C3AED),
    green = Color(0xFF69D69E),
    background = Color(0xFF131033),
    surface = Color(0xFF1C1645),
    surfaceVariant = Color(0xFF271E5C),
    outline = Color(0xFF3A2E7A),
    cardBorder = Color(0xFF2E2466),
    muted = Color(0xFFA99CC8),
    darkColors = darkColorScheme(
        primary = Color(0xFFDB2777),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFF43102C),
        onPrimaryContainer = Color(0xFFFFD9E9),
        secondary = Color(0xFF7C3AED),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFF2E1065),
        onSecondaryContainer = Color(0xFFE9DDFF),
        tertiary = Color(0xFF69D69E),
        onTertiary = Color(0xFF00391E),
        background = Color(0xFF131033),
        onBackground = Color(0xFFE9E4F7),
        surface = Color(0xFF1C1645),
        onSurface = Color(0xFFE9E4F7),
        surfaceVariant = Color(0xFF271E5C),
        onSurfaceVariant = Color(0xFFA99CC8),
        outline = Color(0xFF3A2E7A),
        outlineVariant = Color(0xFF2E2466),
    ),
    lightColors = lightColorScheme(
        primary = Color(0xFFDB2777),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFFFDCEB),
        onPrimaryContainer = Color(0xFF4A0327),
        secondary = Color(0xFF7C3AED),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFEDE6FF),
        onSecondaryContainer = Color(0xFF2A0A66),
        tertiary = Color(0xFF1B8A5A),
        onTertiary = Color(0xFFFFFFFF),
        background = Color(0xFFFAF6FB),
        onBackground = Color(0xFF1F1426),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF1F1426),
        surfaceVariant = Color(0xFFF4EAF4),
        onSurfaceVariant = Color(0xFF5C4B66),
        outline = Color(0xFFE3D3E8),
        outlineVariant = Color(0xFFEFE3F1),
    ),
)

private val ActivePalette = mutableStateOf(DevPalette)
val PocketAccent: Color get() = ActivePalette.value.accent
val PocketAccentTeal: Color get() = ActivePalette.value.accentTeal
val PocketBlue: Color get() = ActivePalette.value.blue
val PocketGreen: Color get() = ActivePalette.value.green
val PocketBackground: Color get() = ActivePalette.value.background
val PocketSurface: Color get() = ActivePalette.value.surface
val PocketSurfaceVariant: Color get() = ActivePalette.value.surfaceVariant
val PocketOutline: Color get() = ActivePalette.value.outline
val PocketCardBorder: Color get() = ActivePalette.value.cardBorder
val PocketMuted: Color get() = ActivePalette.value.muted

val DefaultCustomAccent: Int = 0xFF22C55E.toInt()
val DefaultCustomBackground: Int = 0xFF0B0F14.toInt()

private fun paletteFor(colorTheme: AppColorTheme, customAccent: Int, customBackground: Int): PocketPalette = when (colorTheme) {
    AppColorTheme.DEV -> DevPalette
    AppColorTheme.VIOLET -> VioletPalette
    AppColorTheme.GREEN -> GreenPalette
    AppColorTheme.ROSE -> RosePalette
    AppColorTheme.CUSTOM -> customPalette(Color(customAccent), Color(customBackground))
}

enum class AppThemeMode { SYSTEM, DARK, LIGHT }

@Composable
fun PocketTheme(
    themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    colorTheme: AppColorTheme = AppColorTheme.DEV,
    customAccent: Int = DefaultCustomAccent,
    customBackground: Int = DefaultCustomBackground,
    content: @Composable () -> Unit,
) {
    val palette = remember(colorTheme, customAccent, customBackground) {
        paletteFor(colorTheme, customAccent, customBackground)
    }
    if (ActivePalette.value != palette) ActivePalette.value = palette

    val isDark = when (themeMode) {
        AppThemeMode.DARK -> true
        AppThemeMode.LIGHT -> false
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !isDark
            insetsController.isAppearanceLightNavigationBars = !isDark
        }
    }

    MaterialTheme(
        colorScheme = if (isDark) palette.darkColors else palette.lightColors,
        typography = DevTypography,
        content = content,
    )
}
