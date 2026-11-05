package com.ai.agent.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
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
import com.ai.agent.ui.screens.Attachment
import com.ai.agent.ui.screens.ChatMessage
import com.ai.agent.ui.screens.FileOp
import com.ai.agent.ui.screens.MessageStatus
import com.ai.agent.ui.theme.MonoFontFamily
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ============================================================================
// ChatMessageItem — renders a single message (user OR AI) with optional tools.
// ----------------------------------------------------------------------------
// v4.0.87 redesign:
//   - User: right-aligned emerald bubble + attached-file cards below
//   - AI step: unified "step card" — header (step · status · calls) + collapsible
//     thinking + tool-call stack + file-op cards. Long-press → message actions.
//   - AI final: surface2 bubble (no collapse)
//   - Long-press any message → onLongPress callback (wired to ModalBottomSheet
//     with Copy / Delete / Regenerate in ChatScreen)
// ============================================================================

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatMessageItem(
    message: ChatMessage,
    isLastAi: Boolean = false,
    thinking: Boolean = false,
    onLongPress: () -> Unit = {},  // v4.0.87: long-press → message actions sheet
    modifier: Modifier = Modifier,
) {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .combinedClickable(
                onClick = {},
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongPress()
                },
            ),
    ) {
        if (message.isUser) {
            // ----- User message: right-aligned emerald bubble -----
            Column(
                modifier = Modifier.fillMaxWidth(),
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
                // v4.0.87: Attached files as cards (below the user bubble)
                if (message.attachments.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Column(
                        modifier = Modifier.fillMaxWidth(0.82f),
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        message.attachments.forEach { att ->
                            AttachedFileCard(att)
                        }
                    }
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
            val isStepMessage = message.text.startsWith("[")
            var thinkingExpanded by remember { mutableStateOf(false) }

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
                        if (isStepMessage) {
                            // v4.0.87: Unified STEP CARD — header + collapsible thinking + tools + files
                            StepCard(
                                message = message,
                                thinkingExpanded = thinkingExpanded,
                                onToggleExpand = { thinkingExpanded = !thinkingExpanded },
                                isError = isError,
                                isLastAi = isLastAi,
                                isExecuting = isExecuting,
                            )
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
                                        .background(custom.accent, CircleShape),
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

                    // v4.0.87: File-op cards (created/copied/moved/deleted by tools in this step)
                    if (message.fileOps.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            message.fileOps.forEach { op ->
                                FileOpCard(op)
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

// ============================================================================
// v4.0.87: StepCard — a unified card for AI step messages.
// ----------------------------------------------------------------------------
// Header strip: status dot · "Step N/M" · status label · API calls · expand ▶
// Body (collapsible): the brain's reasoning text (muted, italic).
// Below the card: tool-call cards (each independently expandable) live OUTSIDE
// this card so they keep their own border + expand state.
// ============================================================================

@Composable
private fun StepCard(
    message: ChatMessage,
    thinkingExpanded: Boolean,
    onToggleExpand: () -> Unit,
    isError: Boolean,
    isLastAi: Boolean,
    isExecuting: Boolean,
) {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme

    // Parse step header from text: "[3/80] [API: 5 calls]\n<reasoning>"
    val lines = message.text.split("\n", limit = 2)
    val headerLine = lines.firstOrNull() ?: ""
    val reasoning = lines.getOrNull(1) ?: ""
    val stepMatch = Regex("\\[(\\d+)/(\\d+)\\]").find(headerLine)
    val apiMatch = Regex("API:\\s*(\\d+)\\s*calls").find(headerLine)
    val stepNum = stepMatch?.groupValues?.getOrNull(1) ?: "?"
    val maxSteps = stepMatch?.groupValues?.getOrNull(2) ?: "?"
    val apiCalls = apiMatch?.groupValues?.getOrNull(1) ?: "?"

    // Status: error (red), executing (accent pulse), done (success)
    val statusColor = when {
        isError -> custom.error
        isExecuting -> custom.accent
        else -> custom.success
    }
    val statusLabel = when {
        isError -> "error"
        isExecuting -> "executing"
        else -> "done"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    if (isError) custom.error.copy(alpha = 0.08f) else custom.surface3,
                    RoundedCornerShape(12.dp),
                )
                .border(
                    1.dp,
                    if (isError) custom.error.copy(alpha = 0.3f) else custom.inkBorder.copy(alpha = 0.5f),
                    RoundedCornerShape(12.dp),
                )
                .clickable { onToggleExpand() }
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Column {
                // ---- Header row ----
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Status dot (pulses if executing)
                        StatusDot(color = statusColor, pulse = isExecuting)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "Step $stepNum/$maxSteps",
                            color = if (isError) custom.error else m3.onSurface,
                            fontFamily = MonoFontFamily,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "· $statusLabel · $apiCalls calls",
                            color = custom.fg3,
                            fontFamily = MonoFontFamily,
                            fontSize = 11.sp,
                        )
                    }
                    Text(
                        text = if (thinkingExpanded) "▼" else "▶",
                        color = custom.fg3,
                        fontSize = 10.sp,
                    )
                }

                // ---- Thinking body (collapsible) ----
                if (thinkingExpanded && reasoning.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.Top) {
                        Text("💭", fontSize = 10.sp)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = renderMarkdown(reasoning),
                            color = if (isError) custom.error else custom.fg2,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            fontStyle = FontStyle.Italic,
                        )
                    }
                } else if (reasoning.isNotBlank()) {
                    // Collapsed preview: first line
                    Spacer(Modifier.height(2.dp))
                    val firstLine = reasoning.substringBefore("\n").take(120)
                    Text(
                        text = renderMarkdown(firstLine),
                        color = if (isError) custom.error else custom.fg2,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.alpha(0.85f),
                    )
                }
            }
        }

        // ---- Tool call cards (below the step card, each independently expandable) ----
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
    }
}

// ============================================================================
// v4.0.87: StatusDot — a colored dot that pulses when active (executing).
// ============================================================================

@Composable
private fun StatusDot(color: Color, pulse: Boolean) {
    val transition = rememberInfiniteTransition(label = "status-pulse")
    val alpha by transition.animateFloat(
        initialValue = if (pulse) 0.4f else 1f,
        targetValue = 1f,
        animationSpec = if (pulse) {
            infiniteRepeatable(animation = tween(800), repeatMode = RepeatMode.Reverse)
        } else {
            infiniteRepeatable(animation = tween(2000), repeatMode = RepeatMode.Restart)
        },
        label = "status-dot-alpha",
    )
    Box(
        modifier = Modifier
            .size(7.dp)
            .alpha(alpha)
            .background(color, CircleShape),
    )
}

// ============================================================================
// v4.0.87: AttachedFileCard — shows a user-attached file below the user bubble.
// ============================================================================

@Composable
private fun AttachedFileCard(att: Attachment) {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .background(custom.surface2, RoundedCornerShape(10.dp))
            .border(1.dp, custom.border, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = fileEmoji(att.name, att.mimeType), fontSize = 14.sp)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                text = att.name.take(28) + if (att.name.length > 28) "…" else "",
                color = m3.onSurface,
                fontFamily = MonoFontFamily,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = formatSize(att.sizeBytes),
                color = custom.fg3,
                fontFamily = MonoFontFamily,
                fontSize = 9.sp,
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = "attached",
            color = custom.accent,
            fontFamily = MonoFontFamily,
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

// ============================================================================
// v4.0.87: FileOpCard — shows a file operation performed by a tool.
// ============================================================================

@Composable
private fun FileOpCard(op: FileOp) {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme
    val (emoji, badge, badgeColor) = when (op.kind) {
        FileOp.Kind.CREATED -> Triple("📄", "created", custom.accent)
        FileOp.Kind.COPIED -> Triple("📋", "copied", custom.info)
        FileOp.Kind.MOVED -> Triple("↔️", "moved", custom.info)
        FileOp.Kind.DELETED -> Triple("🗑️", "deleted", custom.error)
        FileOp.Kind.DIR_CREATED -> Triple("📁", "created", custom.accent)
    }
    val fileName = op.path.substringAfterLast("/")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(custom.ink2, RoundedCornerShape(10.dp))
            .border(1.dp, custom.inkBorder, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = emoji, fontSize = 14.sp)
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = fileName.take(32) + if (fileName.length > 32) "…" else "",
                color = m3.onSurface,
                fontFamily = MonoFontFamily,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (op.detail.isNotEmpty()) {
                Text(
                    text = op.detail,
                    color = custom.fg3,
                    fontFamily = MonoFontFamily,
                    fontSize = 9.sp,
                )
            }
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = badge,
            color = badgeColor,
            fontFamily = MonoFontFamily,
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .background(badgeColor.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

// ---- Helpers ----

private fun fileEmoji(name: String, mime: String): String {
    val n = name.lowercase()
    val m = mime.lowercase()
    return when {
        m.startsWith("image/") || n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg") ||
            n.endsWith(".gif") || n.endsWith(".webp") -> "🖼️"
        m.startsWith("audio/") || n.endsWith(".mp3") || n.endsWith(".wav") || n.endsWith(".m4a") -> "🎵"
        n.endsWith(".pdf") -> "📕"
        n.endsWith(".txt") || n.endsWith(".md") -> "📝"
        n.endsWith(".json") || n.endsWith(".csv") -> "📊"
        n.endsWith(".zip") || n.endsWith(".rar") -> "🗜️"
        else -> "📎"
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "${bytes}B"
    bytes < 1024 * 1024 -> "${bytes / 1024}KB"
    else -> "${bytes / (1024 * 1024)}MB"
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
