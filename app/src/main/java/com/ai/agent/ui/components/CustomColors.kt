package com.ai.agent.ui.components

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.ai.agent.ui.theme.Accent
import com.ai.agent.ui.theme.Accent2
import com.ai.agent.ui.theme.Accent3
import com.ai.agent.ui.theme.BorderLight
import com.ai.agent.ui.theme.CanvasLight
import com.ai.agent.ui.theme.Fg2Light
import com.ai.agent.ui.theme.Fg3Light
import com.ai.agent.ui.theme.Ink2Dark
import com.ai.agent.ui.theme.Ink3Dark
import com.ai.agent.ui.theme.InkBorderDark
import com.ai.agent.ui.theme.InkDark
import com.ai.agent.ui.theme.StatusError
import com.ai.agent.ui.theme.StatusInfo
import com.ai.agent.ui.theme.StatusSuccess
import com.ai.agent.ui.theme.StatusWarn
import com.ai.agent.ui.theme.Surface2Light
import com.ai.agent.ui.theme.Surface3Light
import com.ai.agent.ui.theme.Text2Dark
import com.ai.agent.ui.theme.Text3Dark

/**
 * Custom color scheme for AI Agent v2.
 *
 * Material 3's [androidx.compose.material3.ColorScheme] covers the standard
 * roles (primary, surface, background, onXxx, etc.) but our design system has
 * extra tokens that don't map cleanly onto M3 — emerald accents with separate
 * pressed/hover variants, a canvas-vs-surface split, surface2/surface3 tiers,
 * and the dedicated status colors (success / warn / error / info).
 *
 * Those non-standard tokens live here and are exposed to composables through
 * [LocalCustomColors]. Light + dark flavors are produced by the theme and
 * swapped automatically when the system theme changes.
 */
@Immutable
data class CustomColors(
    // Accent (emerald) — same hex in both themes
    val accent: Color = Accent,
    val accent2: Color = Accent2,      // pressed
    val accent3: Color = Accent3,     // hover / lighter

    // Light-mode surfaces
    val canvas: Color = CanvasLight,
    val surface2: Color = Surface2Light,
    val surface3: Color = Surface3Light,
    val border: Color = BorderLight,
    val fg2: Color = Fg2Light,
    val fg3: Color = Fg3Light,

    // Dark-mode surfaces (kept accessible even when theme is light, so the
    // dense right-panel / tool cards can keep their distinctive ink look)
    val inkBg: Color = InkDark,
    val ink2: Color = Ink2Dark,
    val ink3: Color = Ink3Dark,
    val inkBorder: Color = InkBorderDark,

    // Status (same hex in both themes)
    val success: Color = StatusSuccess,
    val warn: Color = StatusWarn,
    val error: Color = StatusError,
    val info: Color = StatusInfo,
)

/** Light-mode custom colors. */
val LightCustomColors = CustomColors()

/** Dark-mode custom colors — re-maps the surface / text tokens to the ink palette. */
val DarkCustomColors = CustomColors(
    canvas = InkDark,
    surface2 = Ink3Dark,
    surface3 = Ink2Dark,
    border = InkBorderDark,
    fg2 = Text2Dark,
    fg3 = Text3Dark,
    inkBg = InkDark,
    ink2 = Ink2Dark,
    ink3 = Ink3Dark,
    inkBorder = InkBorderDark,
)

/**
 * CompositionLocal holding the currently active [CustomColors].
 *
 * Read it inside any composable with `LocalCustomColors.current.accent` etc.
 * Defaults to the light palette so previews / tooling render correctly.
 */
val LocalCustomColors = staticCompositionLocalOf { CustomColors() }
