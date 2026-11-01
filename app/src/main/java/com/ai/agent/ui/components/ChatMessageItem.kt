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
import androidx.compose.ui.draw.clip
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
        // v4.0.69: Redesigned to look like Z.ai Code's chat:
        // 1. Thinking bubble (collapsible) — shows AI's reasoning
        // 2. Tool call cards — always visible, each independently expandable
        // 3. Reasoning text — shown before/after tool calls (what AI is doing + what's next)
        val isStepMessage = message.text.startsWith("[")
        var thinkingExpanded by remember { mutableStateOf(false) }

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

                    // v4.0.69: Thinking bubble — collapsible reasoning
                    // Shows the AI's thought process. Tap to expand/collapse.
                    if (message.text.isNotBlank()) {
                        // v4.0.69: For step messages, show thinking bubble (collapsed by default)
                        // For final reply, show the text directly (no collapse)
                        if (isStepMessage) {
                            // THINKING BUBBLE (for step messages)
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .animateContentSize()
                                    .clickable { thinkingExpanded = !thinkingExpanded }
                                    .background(
                                        if (isError) custom.error.copy(alpha = 0.08f) else custom.surface3,
                                        RoundedCornerShape(12.dp),
                                    )
                                    .border(
                                        1.dp,
                                        if (isError) custom.error.copy(alpha = 0.3f) else custom.inkBorder.copy(alpha = 0.5f),
                                        RoundedCornerShape(12.dp),
                                    )
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                            ) {
                                Column {
                                    // Header: 💭 Thinking + expand/collapse indicator
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = "💭",
                                                fontSize = 11.sp,
                                            )
                                            Spacer(Modifier.width(4.dp))
                                            Text(
                                                text = if (isError) "Error" else "Thinking",
                                                color = if (isError) custom.error else custom.fg3,
                                                fontFamily = MonoFontFamily,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Medium,
                                            )
                                        }
                                        Text(
                                            text = if (thinkingExpanded) "▼" else "▶",
                                            color = custom.fg3,
                                            fontSize = 10.sp,
                                        )
                                    }

                                    // Content: collapsed = first line, expanded = full text
                                    if (thinkingExpanded) {
                                        Spacer(Modifier.height(6.dp))
                                        Text(
                                            text = renderMarkdown(message.text),
                                            color = if (isError) custom.error else m3.onSurface,
                                            fontSize = 13.sp,
                                            lineHeight = 18.sp,
                                        )
                                    } else {
                                        // Show first line as preview
                                        Spacer(Modifier.height(2.dp))
                                        val firstLine = message.text.substringBefore("\n").take(120)
                                        Text(
                                            text = renderMarkdown(firstLine),
                                            color = if (isError) custom.error else custom.fg2,
                                            fontSize = 12.sp,
                                            lineHeight = 16.sp,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        } else {
                            // FINAL REPLY — show full text directly (no thinking bubble)
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
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
                                    Text(
                                        text = renderMarkdown(message.text),
                                        color = if (isError) custom.error else m3.onSurface,
                                        fontSize = 13.sp,
                                        lineHeight = 18.sp,
                                    )
                                }
                            }
                        }
                        // Timestamp + running indicator
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

                    // v4.0.69: Tool call cards — ALWAYS visible (not hidden behind expand)
                    // Each ToolCallCard has its OWN expand/collapse state (independent)
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

                    // v2.4.0: Removed "Open browser" button — browser is now a separate screen.
                    // User navigates via header globe icon or bottom nav.

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
