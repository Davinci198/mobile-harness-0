package com.jarves.mh.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Agent redesign palette (mobile_x5f_harness_redesign_agent.html).
 *
 * The saturated orange accent is replaced by a desaturated violet; orange now only
 * appears rarely, as a warning state. Surfaces follow the mock: #0f0f10 screen,
 * #17171c cards, #262631 hairline borders.
 */
val PocketAccent = Color(0xFF7C5CFC)
val PocketAccentTeal = Color(0xFF3AD7D0)
val PocketBlue = Color(0xFF8EA8FF)
val PocketGreen = Color(0xFF69D69E)
val PocketBackground = Color(0xFF0F0F10)
val PocketSurface = Color(0xFF17171C)
val PocketSurfaceVariant = Color(0xFF1F1F28)
val PocketOutline = Color(0xFF2A2A32)
val PocketCardBorder = Color(0xFF262631)
val PocketMuted = Color(0xFF9A9AA3)

private val DarkColors = darkColorScheme(
    primary = PocketAccent,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF2B2150),
    onPrimaryContainer = Color(0xFFDCD4FF),
    secondary = PocketBlue,
    onSecondary = Color(0xFF001F58),
    tertiary = PocketGreen,
    onTertiary = Color(0xFF00391E),
    background = PocketBackground,
    onBackground = Color(0xFFE6EDF3),
    surface = PocketSurface,
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = PocketSurfaceVariant,
    onSurfaceVariant = PocketMuted,
    outline = PocketOutline,
    outlineVariant = PocketCardBorder,
)

private val LightColors = lightColorScheme(
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
)

enum class AppThemeMode { SYSTEM, DARK, LIGHT }

@Composable
fun PocketTheme(themeMode: AppThemeMode = AppThemeMode.SYSTEM, content: @Composable () -> Unit) {
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
        colorScheme = if (isDark) DarkColors else LightColors,
        content = content,
    )
}
