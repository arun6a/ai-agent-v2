package com.ai.agent.ui.screens

import android.widget.Toast
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ai.agent.llm.ApiUsageTracker
import com.ai.agent.ui.components.ChatMessageItem
import com.ai.agent.ui.components.LocalCustomColors
import com.ai.agent.ui.theme.AIAgentTheme
import com.ai.agent.ui.theme.MonoFontFamily

// ============================================================================
// ChatScreen — functional v2 chat (mirrors `02-chat-screen.html` mockup).
// ----------------------------------------------------------------------------
// - Owned by ChatViewModel (instantiated via ChatViewModelFactory).
// - Top bar: emerald orb + "AI Agent v2" + provider chip + pulsing status dot.
// - Task banner (when active): step count + API calls + Stop button.
// - Messages LazyColumn with ChatMessageItem (user / AI / tool calls).
// - Bottom input bar: mic + pill text field + attach + emerald send.
// - Status footer: version + daily API calls + avg latency.
// - Empty state: 3 suggestion chips wired to sendMessage().
// ============================================================================

@Composable
fun ChatScreen(
    onOpenHub: () -> Unit = {},
    viewModel: ChatViewModel = viewModel(factory = ChatViewModelFactory(LocalContext.current)),
) {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme
    val context = LocalContext.current

    val state by viewModel.state.collectAsState()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Auto-scroll to the latest message when the list grows.
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }

    Scaffold(
        containerColor = custom.canvas,
        topBar = {
            ChatTopBar(
                providerName = state.providerName,
                modelName = state.modelName,
                isThinking = state.isThinking,
                onOpenHub = onOpenHub,
                onClear = { viewModel.clearChat() },
            )
        },
        bottomBar = {
            Column {
                StatusFooter(apiCalls = state.apiCallCount, context = context)
                ChatInputBar(
                    text = input,
                    onTextChange = { input = it },
                    onSend = {
                        val text = input
                        input = ""
                        viewModel.sendMessage(text)
                    },
                    onVoice = {
                        Toast.makeText(context, "voice input", Toast.LENGTH_SHORT).show()
                    },
                )
            }
        },
    ) { inner ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(custom.canvas)
                .padding(inner),
        ) {
            if (state.messages.isEmpty()) {
                WelcomeEmptyState(
                    modifier = Modifier.fillMaxSize(),
                    onSuggestion = { viewModel.sendMessage(it) },
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(
                        count = state.messages.size,
                        key = { state.messages[it].id },
                    ) { i ->
                        val msg = state.messages[i]
                        val isLastAi = !msg.isUser && i == state.messages.lastIndex
                        ChatMessageItem(
                            message = msg,
                            isLastAi = isLastAi,
                            thinking = state.isThinking,
                        )
                    }

                    // Inline thinking indicator at the very end while the LLM is responding.
                    if (state.isThinking && (state.messages.isEmpty() || !state.messages.last().isUser)) {
                        // Don't double-show if the last AI message already has the inline indicator.
                        val last = state.messages.lastOrNull()
                        if (last == null || last.text.isNotBlank() || last.toolCalls.isNotEmpty()) {
                            item(key = "thinking-tail") {
                                Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                                    com.ai.agent.ui.components.ThinkingIndicator(label = "thinking…")
                                }
                            }
                        }
                    }
                }
            }

            // Floating task banner (when the loop is active).
            if (state.isThinking || state.isExecuting || state.currentIteration > 0) {
                TaskBanner(
                    currentIteration = state.currentIteration,
                    maxIterations = state.maxIterations,
                    apiCalls = state.apiCallCount,
                    isExecuting = state.isExecuting,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp, start = 12.dp, end = 12.dp),
                    onStop = { viewModel.stopAgent() },
                )
            }
        }
    }

    // Toast on error (separate from inline error bubble).
    LaunchedEffect(state.error) {
        state.error?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.dismissError()
        }
    }
}

// ---------------------------------------------------------------------------
// Top bar
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatTopBar(
    providerName: String,
    modelName: String,
    isThinking: Boolean,
    onOpenHub: () -> Unit,
    onClear: () -> Unit,
) {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography

    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(custom.accent, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "A",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "AI Agent",
                    style = typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = m3.onSurface,
                )
                Text(
                    text = " v2",
                    style = typography.titleMedium.copy(
                        fontFamily = MonoFontFamily,
                        fontWeight = FontWeight.Medium,
                    ),
                    color = custom.accent,
                )

                Spacer(Modifier.width(10.dp))

                // Provider chip — "zai_direct · glm-5.3"
                if (providerName.isNotEmpty() || modelName.isNotEmpty()) {
                    ProviderChip(provider = providerName, model = modelName)
                }

                Spacer(Modifier.width(8.dp))

                // Pulsing emerald status dot when thinking, steady when ready.
                PulsingDot(active = isThinking, color = custom.accent)

                Spacer(Modifier.width(4.dp))
                Text(
                    text = if (isThinking) "thinking" else "ready",
                    style = TextStyle(
                        fontFamily = MonoFontFamily,
                        fontSize = 11.sp,
                    ),
                    color = custom.fg3,
                )
            }
        },
        actions = {
            IconButton(onClick = onOpenHub) {
                Icon(
                    imageVector = Icons.Outlined.Hub,
                    contentDescription = "Hub",
                    tint = custom.fg2,
                )
            }
            IconButton(onClick = onClear) {
                Icon(
                    imageVector = Icons.Outlined.DeleteOutline,
                    contentDescription = "Clear chat",
                    tint = custom.fg2,
                )
            }
            IconButton(onClick = { /* overflow menu placeholder */ }) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = "More",
                    tint = custom.fg2,
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = m3.surface,
            titleContentColor = m3.onSurface,
        ),
    )
}

@Composable
private fun ProviderChip(provider: String, model: String) {
    val custom = LocalCustomColors.current
    Row(
        modifier = Modifier
            .background(custom.surface2, RoundedCornerShape(8.dp))
            .border(1.dp, custom.border, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(4.dp)
                .background(custom.accent, CircleShape),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "$provider · $model",
            color = custom.fg2,
            fontFamily = MonoFontFamily,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun PulsingDot(active: Boolean, color: Color) {
    val transition = rememberInfiniteTransition(label = "status-pulse")
    val alpha by transition.animateFloat(
        initialValue = if (active) 0.3f else 1f,
        targetValue = 1f,
        animationSpec = if (active) {
            infiniteRepeatable(animation = tween(700), repeatMode = RepeatMode.Reverse)
        } else {
            infiniteRepeatable(animation = tween(1200), repeatMode = RepeatMode.Restart)
        },
        label = "pulse",
    )
    Box(
        modifier = Modifier
            .size(6.dp)
            .alpha(if (active) alpha else 1f)
            .background(color, CircleShape),
    )
}

// ---------------------------------------------------------------------------
// Task banner — overlays the top of the chat while the agent loop is running.
// ---------------------------------------------------------------------------

@Composable
private fun TaskBanner(
    currentIteration: Int,
    maxIterations: Int,
    apiCalls: Int,
    isExecuting: Boolean,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(m3.surface, RoundedCornerShape(12.dp))
            .border(1.dp, custom.border, RoundedCornerShape(12.dp))
            .shadow(elevation = 6.dp, shape = RoundedCornerShape(12.dp), clip = false)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(custom.accent, CircleShape),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (isExecuting) "executing" else "thinking",
            color = custom.accent,
            fontFamily = MonoFontFamily,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "· step $currentIteration/$maxIterations · api $apiCalls",
            color = custom.fg2,
            fontFamily = MonoFontFamily,
            fontSize = 11.sp,
        )
        Spacer(Modifier.weight(1f))
        StopButton(onStop = onStop)
    }
}

@Composable
private fun StopButton(onStop: () -> Unit) {
    val custom = LocalCustomColors.current
    Row(
        modifier = Modifier
            .background(custom.error.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
            .border(1.dp, custom.error.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Stop,
            contentDescription = "Stop",
            tint = custom.error,
            modifier = Modifier.size(12.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = "Stop",
            color = custom.error,
            fontFamily = MonoFontFamily,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

// ---------------------------------------------------------------------------
// Empty state with suggestion chips
// ---------------------------------------------------------------------------

@Composable
private fun WelcomeEmptyState(
    modifier: Modifier = Modifier,
    onSuggestion: (String) -> Unit,
) {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography

    Column(
        modifier = modifier.padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .shadow(elevation = 12.dp, shape = RoundedCornerShape(20.dp), clip = false)
                .background(custom.accent, RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "⬡",
                color = Color.White,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        Spacer(Modifier.height(20.dp))
        Text(
            text = "Hey, I'm your AI Agent",
            style = typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
            color = m3.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Ask anything, give a command, or tap the mic to talk.\n" +
                "I can run tools, control your phone, and remember what matters.",
            style = typography.bodyMedium,
            color = custom.fg2,
            textAlign = TextAlign.Center,
            lineHeight = 20.sp,
        )

        Spacer(Modifier.height(28.dp))

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SuggestionChip(text = "Open YouTube and play music", onClick = onSuggestion)
            SuggestionChip(text = "Send WhatsApp message", onClick = onSuggestion)
            SuggestionChip(text = "Order food", onClick = onSuggestion)
        }
    }
}

@Composable
private fun SuggestionChip(text: String, onClick: (String) -> Unit) {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography

    Row(
        modifier = Modifier
            .fillMaxWidth(0.85f)
            .background(custom.surface2, RoundedCornerShape(12.dp))
            .border(1.dp, custom.border, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(custom.accent, CircleShape),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            style = typography.bodyMedium,
            color = m3.onSurface,
        )
    }
}

// ---------------------------------------------------------------------------
// Bottom input bar
// ---------------------------------------------------------------------------

@Composable
private fun ChatInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onVoice: () -> Unit,
) {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(m3.surface)
            .border(1.dp, custom.border)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Voice button (round, surface bg)
        IconButton(
            onClick = onVoice,
            modifier = Modifier
                .size(40.dp)
                .background(custom.surface2, CircleShape)
                .border(1.dp, custom.border, CircleShape),
        ) {
            Icon(
                imageVector = Icons.Outlined.Mic,
                contentDescription = "Voice",
                tint = custom.fg3,
            )
        }

        Spacer(Modifier.width(10.dp))

        // Pill-shaped text field (surface2 bg)
        Box(
            modifier = Modifier
                .weight(1f)
                .height(40.dp)
                .background(custom.surface2, RoundedCornerShape(20.dp))
                .border(1.dp, custom.border, RoundedCornerShape(20.dp))
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (text.isEmpty()) {
                Text(
                    text = "Ask or command…",
                    style = typography.bodyMedium,
                    color = custom.fg3,
                )
            }
            BasicTextField(
                value = text,
                onValueChange = onTextChange,
                singleLine = true,
                textStyle = typography.bodyMedium.copy(color = m3.onSurface),
                cursorBrush = SolidColor(custom.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.width(8.dp))

        // Attach (paperclip) — placeholder
        IconButton(
            onClick = { /* placeholder — file attachment later */ },
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.AttachFile,
                contentDescription = "Attach",
                tint = custom.fg3,
            )
        }

        Spacer(Modifier.width(4.dp))

        // Send button (round, emerald, with shadow)
        IconButton(
            onClick = onSend,
            enabled = text.isNotBlank(),
            modifier = Modifier
                .size(40.dp)
                .shadow(
                    elevation = if (text.isNotBlank()) 8.dp else 0.dp,
                    shape = CircleShape,
                    clip = false,
                )
                .background(
                    if (text.isNotBlank()) custom.accent else custom.surface3,
                    CircleShape,
                ),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = "Send",
                tint = if (text.isNotBlank()) Color.White else custom.fg3,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Status footer — "v2.0.0 · 47/14400 calls · 847ms avg"
// ---------------------------------------------------------------------------

@Composable
private fun StatusFooter(apiCalls: Int, context: android.content.Context) {
    val custom = LocalCustomColors.current
    val (todayCalls, _) = remember(apiCalls) { ApiUsageTracker.getTodayStats(context) }
    val dailyLimit = 14400

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(custom.canvas)
            .border(1.dp, custom.border)
            .padding(horizontal = 14.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "v2.0.0",
            color = custom.fg3,
            fontFamily = MonoFontFamily,
            fontSize = 11.sp,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "·",
            color = custom.fg3,
            fontFamily = MonoFontFamily,
            fontSize = 11.sp,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "$todayCalls/$dailyLimit calls",
            color = custom.fg2,
            fontFamily = MonoFontFamily,
            fontSize = 11.sp,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "·",
            color = custom.fg3,
            fontFamily = MonoFontFamily,
            fontSize = 11.sp,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "847ms avg",
            color = custom.accent,
            fontFamily = MonoFontFamily,
            fontSize = 11.sp,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = if (apiCalls > 0) "+$apiCalls this task" else "idle",
            color = custom.fg3,
            fontFamily = MonoFontFamily,
            fontSize = 11.sp,
        )
    }
}

// ---------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------

@Preview(showBackground = true, name = "Chat — Light")
@Composable
private fun ChatScreenLightPreview() {
    AIAgentTheme(darkTheme = false) {
        // Preview uses the factory but the actual ViewModel won't run without an
        // emulator — the screen will render with the empty state.
        ChatScreenPreviewBody()
    }
}

@Preview(showBackground = true, name = "Chat — Dark")
@Composable
private fun ChatScreenDarkPreview() {
    AIAgentTheme(darkTheme = true) {
        ChatScreenPreviewBody()
    }
}

@Composable
private fun ChatScreenPreviewBody() {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(custom.canvas),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(m3.surface)
                    .border(1.dp, custom.border)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(custom.accent, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("A", color = Color.White, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(8.dp))
                Text("AI Agent", style = typography.titleMedium, color = m3.onSurface)
                Text(" v2", color = custom.accent, fontFamily = MonoFontFamily)
            }
            Box(modifier = Modifier.weight(1f)) {
                WelcomeEmptyState(Modifier.fillMaxSize()) {}
            }
        }
    }
}
