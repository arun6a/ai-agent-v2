package com.ai.agent.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
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
                    .background(
                        custom.accent,
                        RoundedCornerShape(
                            topStart = 16.dp,
                            topEnd = 16.dp,
                            bottomEnd = 4.dp,
                            bottomStart = 16.dp,
                        ),
                    )
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
        // v2.3.1: Step messages are always collapsed (show summary only).
        // Only the final AI reply (no [N/M] prefix) shows full text.
        // Tap a step message to expand/collapse its full text + tool calls.
        val isStepMessage = message.text.startsWith("[")
        var expanded by remember { mutableStateOf(false) }  // steps always start collapsed

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
                                .animateContentSize()  // v2.2.4: smooth expand/collapse
                                .clickable(enabled = isStepMessage) { expanded = !expanded }  // tap to toggle
                                .background(
                                    if (isError) custom.error.copy(alpha = 0.12f) else custom.surface2,
                                    RoundedCornerShape(
                                        topStart = 16.dp,
                                        topEnd = 16.dp,
                                        bottomEnd = 16.dp,
                                        bottomStart = 4.dp,
                                    ),
                                )
                                .border(
                                    1.dp,
                                    if (isError) custom.error.copy(alpha = 0.4f) else custom.inkBorder,
                                    RoundedCornerShape(
                                        topStart = 16.dp,
                                        topEnd = 16.dp,
                                        bottomEnd = 16.dp,
                                        bottomStart = 4.dp,
                                    ),
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

                                // v2.3.1: Collapsible step messages
                                if (isStepMessage && !expanded) {
                                    // COLLAPSED: show first line + tool count + expand hint
                                    val firstLine = message.text.substringBefore("\n").take(120)
                                    val toolCount = message.toolCalls.size
                                    Text(
                                        text = renderMarkdown(firstLine),
                                        color = if (isError) custom.error else m3.onSurface,
                                        fontSize = 13.sp,
                                        lineHeight = 18.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (toolCount > 0) {
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            text = "$toolCount tool call${if (toolCount > 1) "s" else ""} · tap to expand ▶",
                                            color = custom.fg3,
                                            fontFamily = MonoFontFamily,
                                            fontSize = 11.sp,
                                        )
                                    } else {
                                        Text(
                                            text = "tap to expand ▶",
                                            color = custom.fg3,
                                            fontFamily = MonoFontFamily,
                                            fontSize = 11.sp,
                                        )
                                    }
                                } else {
                                    // EXPANDED step OR final reply (not a step): show full text
                                    Text(
                                        text = renderMarkdown(message.text),
                                        color = if (isError) custom.error else m3.onSurface,
                                        fontSize = 13.sp,
                                        lineHeight = 18.sp,
                                    )
                                    // v2.3.1: Only show "tap to collapse" for step messages (not final reply)
                                    if (isStepMessage && expanded) {
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            text = "tap to collapse ▼",
                                            color = custom.fg3,
                                            fontFamily = MonoFontFamily,
                                            fontSize = 11.sp,
                                        )
                                    }
                                }
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

                    // Tool call cards stack — only show when expanded
                    if (message.toolCalls.isNotEmpty() && (expanded || !isStepMessage)) {
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

// ============================================================================
// renderMarkdown — lightweight inline-markdown → AnnotatedString converter.
// ----------------------------------------------------------------------------
// v2.0.9 Task 94: Renders **bold**, *italic*, and `inline code` spans on top
// of plain text, preserving newlines. No external markdown libraries — uses
// Compose's `buildAnnotatedString` + `withStyle`.
//
// Supported syntax:
//   **bold**        → FontWeight.Bold
//   *italic*        → FontStyle.Italic
//   `inline code`   → FontFamily.Monospace
//
// Only applied to AI message bubbles — user bubbles + tool cards stay plain
// Text (so the existing styling / mono layout isn't disturbed).
//
// Implementation notes:
//   - Line-by-line iteration so that an unclosed marker doesn't bleed across
//     paragraph boundaries (matches the v1 reference implementation).
//   - The `**` check is performed BEFORE the `*` check so that the bold
//     delimiter wins (otherwise the leading `*` would be consumed as the
//     start of an italic span).
//   - Fallback for an unclosed / empty marker: append the literal character
//     and continue scanning one char at a time.
// ============================================================================
private fun renderMarkdown(text: String): AnnotatedString {
    return buildAnnotatedString {
        val lines = text.split("\n")
        for ((lineIdx, line) in lines.withIndex()) {
            var i = 0
            while (i < line.length) {
                // **bold**
                if (line.startsWith("**", i)) {
                    val end = line.indexOf("**", i + 2)
                    if (end > 0) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(line.substring(i + 2, end))
                        }
                        i = end + 2
                        continue
                    }
                }
                // `code`
                if (line.startsWith("`", i)) {
                    val end = line.indexOf("`", i + 1)
                    if (end > 0) {
                        withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) {
                            append(line.substring(i + 1, end))
                        }
                        i = end + 1
                        continue
                    }
                }
                // *italic* (NOT **bold** — that's already handled above)
                if (line.startsWith("*", i) && !line.startsWith("**", i)) {
                    val end = line.indexOf("*", i + 1)
                    if (end > 0) {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            append(line.substring(i + 1, end))
                        }
                        i = end + 1
                        continue
                    }
                }
                // Regular character — fall through.
                append(line[i])
                i++
            }
            if (lineIdx < lines.size - 1) append("\n")
        }
    }
}
