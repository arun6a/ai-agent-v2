package com.ai.agent.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.agent.ui.screens.ChatMessage
import com.ai.agent.ui.screens.MessageStatus
import com.ai.agent.ui.theme.MonoFontFamily
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ============================================================================
// ChatMessageItem — renders a single message (user OR AI) with optional tools.
// ----------------------------------------------------------------------------
// Visual parity with the approved mockup (v2-design/02-chat-screen.html):
//   - User: right-aligned, emerald bubble, rounded-br-sm
//   - AI:   left-aligned, surface2 bubble, rounded-bl-sm, emerald orb avatar
//   - Tool calls: dense ToolCallCard stack below the AI bubble
//   - Error: red-tinted bubble + ERROR tag
// ============================================================================

@Composable
fun ChatMessageItem(
    message: ChatMessage,
    isLastAi: Boolean = false,
    thinking: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme

    if (message.isUser) {
        // ----- User message: right-aligned emerald bubble -----
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.End,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.82f)
                    .background(custom.accent, RoundedCornerShape(16.dp).copy(bottomEnd = 4.dp)),
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(
                    text = message.text,
                    color = Color.White,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
            }
            Row(
                modifier = Modifier.padding(top = 2.dp, end = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = formatTime(message.timestamp),
                    color = custom.fg3,
                    fontFamily = MonoFontFamily,
                    fontSize = 11.sp,
                )
            }
        }
    } else {
        // ----- AI message: left-aligned with emerald orb avatar -----
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.Top,
            ) {
                // Emerald orb avatar
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .shadow(elevation = 4.dp, shape = CircleShape, clip = false)
                        .background(custom.accent, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "⬡",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.width(6.dp))
                Column(modifier = Modifier.fillMaxWidth(0.88f)) {
                    val isError = message.status == MessageStatus.ERROR
                    val isExecuting = message.status == MessageStatus.EXECUTING

                    if (message.text.isNotBlank()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (isError) custom.error.copy(alpha = 0.12f) else custom.surface2,
                                    RoundedCornerShape(16.dp).copy(bottomStart = 4.dp),
                                )
                                .border(
                                    1.dp,
                                    if (isError) custom.error.copy(alpha = 0.4f) else custom.inkBorder,
                                    RoundedCornerShape(16.dp).copy(bottomStart = 4.dp),
                                )
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Column {
                                if (isError) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .size(6.dp)
                                                .background(custom.error, CircleShape),
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            text = "ERROR",
                                            color = custom.error,
                                            fontFamily = MonoFontFamily,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                    Spacer(Modifier.height(4.dp))
                                }
                                Text(
                                    text = message.text,
                                    color = if (isError) custom.error else m3.onSurface,
                                    fontSize = 13.sp,
                                    lineHeight = 18.sp,
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.padding(top = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = formatTime(message.timestamp),
                                color = custom.fg3,
                                fontFamily = MonoFontFamily,
                                fontSize = 11.sp,
                            )
                            if (isExecuting) {
                                Spacer(Modifier.width(6.dp))
                                Box(
                                    modifier = Modifier
                                        .size(4.dp)
                                        .background(custom.fg3, CircleShape),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = "running",
                                    color = custom.accent,
                                    fontFamily = MonoFontFamily,
                                    fontSize = 11.sp,
                                )
                            }
                        }
                    }

                    // Tool call cards stack
                    if (message.toolCalls.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            message.toolCalls.forEachIndexed { i, call ->
                                ToolCallCard(call = call, index = i + 1)
                            }
                        }
                    }

                    // Inline thinking indicator (when AI is "running" but no tools yet)
                    if (isLastAi && thinking && message.toolCalls.isEmpty() && message.text.isBlank()) {
                        Spacer(Modifier.height(4.dp))
                        ThinkingIndicator()
                    }
                }
            }
        }
    }
}

private fun formatTime(timestamp: Long): String {
    return SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(timestamp))
}
