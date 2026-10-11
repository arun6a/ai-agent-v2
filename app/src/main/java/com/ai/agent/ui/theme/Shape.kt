package com.ai.agent.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

// ============================================================================
// AI Agent v2 — Shape system
// xs: 4 · sm: 6 · md: 8 · lg: 12 · xl: 16 · 2xl: 20
// ============================================================================

val ShapeXs = RoundedCornerShape(4.dp)
val ShapeSm = RoundedCornerShape(6.dp)
val ShapeMd = RoundedCornerShape(8.dp)
val ShapeLg = RoundedCornerShape(12.dp)
val ShapeXl = RoundedCornerShape(16.dp)
val Shape2Xl = RoundedCornerShape(20.dp)

// Pill (fully rounded) for input bars + chat bubbles tails.
val ShapePill = RoundedCornerShape(50)

val AIAgentShapes: Shapes = Shapes(
    extraSmall = ShapeXs,
    small = ShapeSm,
    medium = ShapeMd,
    large = ShapeLg,
    extraLarge = ShapeXl,
)
