package com.ai.agent.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.ai.agent.ui.components.DarkCustomColors
import com.ai.agent.ui.components.LightCustomColors
import com.ai.agent.ui.components.LocalCustomColors

// ============================================================================
// AI Agent v2 — Theme
// "Friendly AI Assistant + Power-user Dense"
//  - Material 3 base
//  - Light mode default
//  - Custom (non-M3) tokens exposed via LocalCustomColors
//  - Dynamic status-bar color that follows the active theme
// ============================================================================

// ---- Light Material 3 color scheme ----
private val LightColorScheme = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    primaryContainer = Surface3Light,
    onPrimaryContainer = FgLight,
    secondary = Accent2,
    onSecondary = Color.White,
    secondaryContainer = Surface2Light,
    onSecondaryContainer = FgLight,
    tertiary = Accent3,
    onTertiary = FgLight,
    background = CanvasLight,
    onBackground = FgLight,
    surface = SurfaceLight,
    onSurface = FgLight,
    surfaceVariant = Surface2Light,
    onSurfaceVariant = Fg2Light,
    surfaceTint = Accent,
    outline = BorderLight,
    outlineVariant = BorderLight,
    error = StatusError,
    onError = Color.White,
    errorContainer = StatusError.copy(alpha = 0.12f),
    onErrorContainer = StatusError,
)

// ---- Dark Material 3 color scheme ----
private val DarkColorScheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF002117),
    primaryContainer = Ink3Dark,
    onPrimaryContainer = Accent3,
    secondary = Accent2,
    onSecondary = Color.White,
    secondaryContainer = Ink3Dark,
    onSecondaryContainer = Accent3,
    tertiary = Accent3,
    onTertiary = InkDark,
    background = InkDark,
    onBackground = TextDark,
    surface = Ink2Dark,
    onSurface = TextDark,
    surfaceVariant = Ink3Dark,
    onSurfaceVariant = Text2Dark,
    surfaceTint = Accent,
    outline = InkBorderDark,
    outlineVariant = InkBorderDark,
    error = StatusError,
    onError = Color.White,
    errorContainer = StatusError.copy(alpha = 0.20f),
    onErrorContainer = StatusError,
)

/**
 * Root theme composable for AI Agent v2.
 *
 * Wrap the entire app content with this — it sets up Material 3 colors,
 * typography, shapes, our custom color tokens, and the system-bar appearance
 * (status bar / navigation bar) so it matches the active palette.
 *
 * @param darkTheme    true for the dark (ink) palette. Defaults to system setting.
 * @param dynamicColor  reserved for future Material You support. Currently
 *                      disabled — our approved design uses a fixed brand palette.
 * @param content       app content to render inside the theme.
 */
@Composable
fun AIAgentTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    @Suppress("UNUSED_PARAMETER") dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val customColors = if (darkTheme) DarkCustomColors else LightCustomColors

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // Status bar background — match the canvas so it blends with the app.
            window.statusBarColor = customColors.canvas.toArgb()
            // Nav bar background — also follow canvas.
            @Suppress("DEPRECATION")
            window.navigationBarColor = customColors.canvas.toArgb()

            // Icon contrast: light icons on dark canvas, dark icons on light canvas.
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    CompositionLocalProvider(LocalCustomColors provides customColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AIAgentTypography,
            shapes = AIAgentShapes,
            content = content,
        )
    }
}
