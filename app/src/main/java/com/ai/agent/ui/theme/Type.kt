package com.ai.agent.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ============================================================================
// AI Agent v2 — Typography
// Inter (sans) + JetBrains Mono (mono) with system fallback.
// Scale: 11 / 12 / 13 / 14 / 18 / 22 / 28 / 36 px
// ============================================================================

// Fallback chain: Inter -> Default Sans (system); JetBrains Mono -> Default Monospace.
val SansFontFamily: FontFamily = FontFamily.Default
val MonoFontFamily: FontFamily = FontFamily.Monospace

// ---- Custom text styles (used for non-M3 components) ----

val DisplayTextStyle = TextStyle(
    fontFamily = SansFontFamily,
    fontWeight = FontWeight.Bold,
    fontSize = 36.sp,
    lineHeight = 40.sp,
    letterSpacing = (-0.5).sp,
)

val HeadingTextStyle = TextStyle(
    fontFamily = SansFontFamily,
    fontWeight = FontWeight.SemiBold,
    fontSize = 28.sp,
    lineHeight = 32.sp,
    letterSpacing = (-0.3).sp,
)

val TitleTextStyle = TextStyle(
    fontFamily = SansFontFamily,
    fontWeight = FontWeight.SemiBold,
    fontSize = 22.sp,
    lineHeight = 26.sp,
    letterSpacing = (-0.2).sp,
)

val SubtitleTextStyle = TextStyle(
    fontFamily = SansFontFamily,
    fontWeight = FontWeight.Medium,
    fontSize = 18.sp,
    lineHeight = 22.sp,
)

val BodyTextStyle = TextStyle(
    fontFamily = SansFontFamily,
    fontWeight = FontWeight.Normal,
    fontSize = 14.sp,
    lineHeight = 20.sp,
)

val SmallTextStyle = TextStyle(
    fontFamily = SansFontFamily,
    fontWeight = FontWeight.Normal,
    fontSize = 13.sp,
    lineHeight = 18.sp,
)

val XsTextStyle = TextStyle(
    fontFamily = SansFontFamily,
    fontWeight = FontWeight.Normal,
    fontSize = 12.sp,
    lineHeight = 16.sp,
)

val TwoXsTextStyle = TextStyle(
    fontFamily = MonoFontFamily,
    fontWeight = FontWeight.Normal,
    fontSize = 11.sp,
    lineHeight = 14.sp,
)

// ---- Material 3 Typography mapping ----
// Maps our scale onto the M3 type roles so components like TopAppBar / TextField
// automatically use the correct sizes/weights.

val AIAgentTypography: Typography = Typography(
    displayLarge = DisplayTextStyle,
    displayMedium = HeadingTextStyle,
    displaySmall = TitleTextStyle,
    headlineLarge = HeadingTextStyle,
    headlineMedium = TitleTextStyle,
    headlineSmall = SubtitleTextStyle,
    titleLarge = TitleTextStyle,
    titleMedium = SubtitleTextStyle,
    titleSmall = BodyTextStyle.copy(fontWeight = FontWeight.Medium),
    bodyLarge = BodyTextStyle,
    bodyMedium = SmallTextStyle,
    bodySmall = XsTextStyle,
    labelLarge = BodyTextStyle.copy(fontWeight = FontWeight.Medium),
    labelMedium = SmallTextStyle.copy(fontWeight = FontWeight.Medium),
    labelSmall = XsTextStyle,
)
