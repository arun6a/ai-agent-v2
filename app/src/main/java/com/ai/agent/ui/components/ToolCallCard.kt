package com.ai.agent.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.agent.ui.screens.ToolCallDisplay
import com.ai.agent.ui.theme.MonoFontFamily

// ============================================================================
// ToolCallCard — dense, mono-spaced card showing a single tool execution.
// ----------------------------------------------------------------------------
// Mirrors the `tool-card` from `02-chat-screen.html`:
//   - Header: status icon · tool name (mono, emerald/info tint) · #index · duration
//   - Body:   mono args (key=value, one per row or inline)
//   - On failure: red-tinted output text below args
// ============================================================================

@Composable
fun ToolCallCard(
    call: ToolCallDisplay,
    index: Int,
    modifier: Modifier = Modifier,
) {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme

    val statusColor = if (call.success) custom.accent else custom.error
    val statusIcon = if (call.success) Icons.Filled.Check else Icons.Filled.Close

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(custom.ink2, RoundedCornerShape(8.dp))
            .border(1.dp, custom.inkBorder, RoundedCornerShape(8.dp)),
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(custom.surface3)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = statusIcon,
                contentDescription = if (call.success) "ok" else "fail",
                tint = statusColor,
                modifier = Modifier.size(10.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = call.name,
                color = statusColor,
                fontFamily = MonoFontFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 11.sp,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "#$index",
                color = custom.fg3,
                fontFamily = MonoFontFamily,
                fontSize = 11.sp,
            )
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(statusColor, CircleShape),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "${call.durationMs}ms",
                    color = if (call.success) custom.accent else custom.error,
                    fontFamily = MonoFontFamily,
                    fontSize = 11.sp,
                )
            }
        }

        // Args body (mono)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (call.args.isEmpty()) {
                Text(
                    text = "(no args)",
                    color = custom.fg3,
                    fontFamily = MonoFontFamily,
                    fontSize = 11.sp,
                )
            } else {
                call.args.entries.forEach { (k, v) ->
                    Row {
                        Text(
                            text = "$k:",
                            color = custom.fg3,
                            fontFamily = MonoFontFamily,
                            fontSize = 11.sp,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = v.toString(),
                            color = m3.onSurface,
                            fontFamily = MonoFontFamily,
                            fontSize = 11.sp,
                        )
                    }
                }
            }

            if (!call.success && call.output.isNotBlank()) {
                Spacer(Modifier.size(2.dp))
                Text(
                    text = call.output.take(220),
                    color = custom.error,
                    fontFamily = MonoFontFamily,
                    fontSize = 11.sp,
                    modifier = Modifier.alpha(0.9f),
                )
            }
        }
    }
}

// ============================================================================
// ThinkingIndicator — pulsing dot + "thinking…" text. Shown while the agent
// loop is in the "thinking" phase (waiting for the LLM response).
// ============================================================================

@Composable
fun ThinkingIndicator(modifier: Modifier = Modifier, label: String = "thinking…") {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme

    val transition = rememberInfiniteTransition(label = "thinking-pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(700),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse-alpha",
    )

    Row(
        modifier = modifier
            .background(custom.surface2, RoundedCornerShape(16.dp))
            .border(1.dp, custom.inkBorder, RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .alpha(alpha)
                .background(custom.accent, CircleShape),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            color = custom.fg2,
            fontSize = 12.sp,
        )
    }
}
