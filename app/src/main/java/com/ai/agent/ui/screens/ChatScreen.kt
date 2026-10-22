package com.ai.agent.ui.screens

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.speech.RecognizerIntent
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pending
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
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
import androidx.compose.ui.text.style.TextOverflow
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
    onOpenBrowser: () -> Unit = {},
    onOpenBackgroundTasks: () -> Unit = {},
    /** v2.2.0 Issue 113e — session id passed from Hub → History's "Continue" tap. */
    pendingSessionId: Long? = null,
    /** Called after the pending session id has been consumed by the ViewModel. */
    onSessionIdConsumed: () -> Unit = {},
    viewModel: ChatViewModel = viewModel(factory = ChatViewModelFactory(LocalContext.current)),
) {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme
    val context = LocalContext.current

    val state by viewModel.state.collectAsState()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // v2.2.0 Issue 113e — Load the session id passed from navigation.
    LaunchedEffect(pendingSessionId) {
        if (pendingSessionId != null && pendingSessionId != 0L) {
            viewModel.loadSession(pendingSessionId)
            onSessionIdConsumed()
        }
    }

    // ===== Voice input launcher (Google Voice Intent) =====
    // Modern Compose-native API — no onActivityResult, no MainActivity changes.
    val voiceLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val results = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val spoken = results?.firstOrNull()
            if (!spoken.isNullOrBlank()) {
                // Append (with separating space) so the user can keep editing afterwards.
                input = if (input.isBlank()) spoken else "$input $spoken".trim()
            }
        } else if (result.resultCode != Activity.RESULT_CANCELED) {
            Toast.makeText(context, "Voice input failed", Toast.LENGTH_SHORT).show()
        }
    }

    fun launchVoice() {
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak now…")
            }
            voiceLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(
                context,
                "Voice input unavailable: ${e.message ?: "no recognizer"}",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    // ===== File attachment launcher (any file type) =====
    val attachLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.addAttachment(uri)
        }
    }

    // Auto-scroll to the latest message when the list grows.
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }

    // Task 40 — show a toast when the VM signals one (e.g. skill saved).
    LaunchedEffect(state.toast) {
        state.toast?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearToast()
        }
    }

    // Task 40 — Save-as-skill dialog state.
    var showSaveSkillDialog by remember { mutableStateOf(false) }
    var skillNameField by remember { mutableStateOf("") }
    var skillDescriptionField by remember { mutableStateOf("") }
    LaunchedEffect(state.showSaveSkillPrompt) {
        // The prompt boolean lives in VM state — but the editable dialog fields
        // are pure UI, so they live in local state. When the user opens the
        // dialog, prefill name from the VM's auto-derived suggestion.
        if (state.showSaveSkillPrompt && !showSaveSkillDialog) {
            skillNameField = state.lastWorkflowName
            skillDescriptionField = ""
            showSaveSkillDialog = true
        }
    }

    // v3.1.0: Add imePadding so keyboard doesn't cover the chat input
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = custom.canvas,
        topBar = {
            // v3.2.7: pass isConfigured so the top bar shows "not configured"
            // instead of "ready" when no provider is set up.
            val isConfigured = remember { com.ai.agent.llm.AIProvider.isConfigured(context) }
            ChatTopBar(
                providerName = state.providerName,
                modelName = state.modelName,
                isThinking = state.isThinking,
                isConfigured = isConfigured,
                ttsEnabled = state.ttsEnabled,
                onToggleTts = { viewModel.setTtsEnabled(!state.ttsEnabled) },
                onOpenBrowser = onOpenBrowser,
                onOpenBackgroundTasks = onOpenBackgroundTasks,
                onClear = { viewModel.clearChat() },
                onNewChat = { viewModel.createNewChatSession() },  // v2.2.0 Issue 113c
            )
        },
        bottomBar = {
            Column {
                // Attachment chips row — visible only when attachments are staged.
                if (state.attachments.isNotEmpty()) {
                    AttachmentsBar(
                        attachments = state.attachments,
                        onRemove = { viewModel.removeAttachment(it) },
                    )
                }
                ChatInputBar(
                    text = input,
                    onTextChange = { input = it },
                    onSend = {
                        val text = input
                        input = ""
                        viewModel.sendMessage(text)
                    },
                    onVoice = { launchVoice() },
                    onAttach = { attachLauncher.launch("*/*") },
                    attachmentCount = state.attachments.size,
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
                // Empty state — input bar below is enough to start.
            } else {
                // v2.2.0 Issue 111 — TaskBanner now lives INSIDE the LazyColumn
                // as the first item so it scrolls with messages instead of
                // floating on top of (and overlapping) the first chat bubble.
                val showBanner = state.isThinking || state.isExecuting || state.currentIteration > 0
                
                // v2.3.2: Group consecutive step messages into one collapsed card.
                // Final AI reply (no [N/M] prefix) is always expanded separately.
                var stepsExpanded by remember { mutableStateOf(false) }
                
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    // v2.2.0 Issue 111 — Banner as the first scrollable item.
                    if (showBanner) {
                        item(key = "task-banner") {
                            TaskBanner(
                                currentIteration = state.currentIteration,
                                maxIterations = state.maxIterations,
                                apiCalls = state.apiCallCount,
                                isExecuting = state.isExecuting,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 4.dp),
                                onStop = { viewModel.stopAgent() },
                            )
                        }
                    }

                    // v2.3.2: Render messages — step messages grouped into one collapsed card.
                    // Iterate through messages, grouping consecutive step messages.
                    var i = 0
                    while (i < state.messages.size) {
                        val msg = state.messages[i]
                        
                        if (!msg.isUser && msg.text.startsWith("[")) {
                            // This is a step message — find all consecutive step messages
                            val stepStart = i
                            while (i < state.messages.size && 
                                   !state.messages[i].isUser && 
                                   state.messages[i].text.startsWith("[")) {
                                i++
                            }
                            val stepEnd = i  // exclusive
                            val stepCount = stepEnd - stepStart
                            val totalTools = (stepStart until stepEnd).sumOf { state.messages[it].toolCalls.size }
                            
                            if (stepsExpanded) {
                                // Show all step messages individually + a collapse button
                                item(key = "steps-collapse-btn-$stepStart") {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 4.dp)
                                            .clickable { stepsExpanded = false },
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = "📁 $stepCount steps · $totalTools tool calls · tap to collapse ▼",
                                            color = custom.fg3,
                                            fontFamily = MonoFontFamily,
                                            fontSize = 11.sp,
                                        )
                                    }
                                }
                                for (j in stepStart until stepEnd) {
                                    val stepMsg = state.messages[j]
                                    item(key = "step-${stepMsg.id}") {
                                        ChatMessageItem(
                                            message = stepMsg,
                                            isLastAi = false,
                                            thinking = false,
                                            // v2.3.3: NO animateItem() here — animating items that appear/disappear
                                            // on toggle causes crash on some devices (IllegalStateException in LazyColumn).
                                            modifier = Modifier,
                                        )
                                    }
                                }
                            } else {
                                // Show ONE collapsed card for all steps
                                item(key = "steps-group-$stepStart") {
                                    StepGroupCard(
                                        stepCount = stepCount,
                                        toolCount = totalTools,
                                        firstLine = state.messages[stepStart].text.substringBefore("\n").take(100),
                                        onExpand = { stepsExpanded = true },
                                    )
                                }
                            }
                        } else {
                            // Non-step message (user message or final AI reply) — render normally
                            val isLastAi = !msg.isUser && i == state.messages.lastIndex
                            item(key = msg.id) {
                                ChatMessageItem(
                                    message = msg,
                                    isLastAi = isLastAi,
                                    thinking = state.isThinking,
                                    modifier = Modifier.animateItem(),
                                    // v2.4.0: Removed onShowBrowser — browser is now a separate screen.
                                    // User navigates via header globe icon or bottom nav.
                                )
                            }
                            i++
                        }
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

            // Task 40 — Save-skill prompt card (bottom-anchored, dismissable).
            if (state.showSaveSkillPrompt && !showSaveSkillDialog) {
                SaveSkillPromptCard(
                    skillName = state.lastWorkflowName,
                    onSave = { showSaveSkillDialog = true },
                    onDismiss = { viewModel.dismissSaveSkillPrompt() },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 12.dp, vertical = 80.dp),
                )
            }

            // v2.4.0: BrowserScreen is now a separate Compose destination (Routes.BROWSER).
            // No overlay needed here. AI's browserOpen() triggers nav.navigate(Routes.BROWSER)
            // via BrowserController.setNavigationCallback (registered in AppNavigation).
        }
    }

    // Task 40 — Save-skill dialog (editable name + description, read-only steps).
    if (showSaveSkillDialog) {
        SaveSkillDialog(
            initialName = skillNameField,
            initialDescription = skillDescriptionField,
            stepsPreview = state.lastWorkflowSteps,
            onDismiss = {
                showSaveSkillDialog = false
                viewModel.dismissSaveSkillPrompt()
            },
            onConfirm = { name, desc ->
                viewModel.saveWorkflowAsSkill(name, desc)
                showSaveSkillDialog = false
            },
        )
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
// Attachments bar — horizontal scroll of staged attachment chips above input
// ---------------------------------------------------------------------------

@Composable
private fun AttachmentsBar(
    attachments: List<Attachment>,
    onRemove: (Attachment) -> Unit,
) {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .background(custom.canvas)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(attachments, key = { it.path }) { att ->
            Row(
                modifier = Modifier
                    .background(custom.surface2, RoundedCornerShape(14.dp))
                    .border(1.dp, custom.border, RoundedCornerShape(14.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = iconForAttachment(att.name, att.mimeType),
                    contentDescription = null,
                    tint = custom.accent,
                    modifier = Modifier.size(12.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = att.name.take(20) + if (att.name.length > 20) "…" else "",
                    color = m3.onSurface,
                    fontFamily = MonoFontFamily,
                    fontSize = 11.sp,
                    maxLines = 1,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = formatSize(att.sizeBytes),
                    color = custom.fg3,
                    fontFamily = MonoFontFamily,
                    fontSize = 9.sp,
                )
                Spacer(Modifier.width(4.dp))
                IconButton(
                    onClick = { onRemove(att) },
                    modifier = Modifier.size(16.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Remove",
                        tint = custom.fg3,
                        modifier = Modifier.size(10.dp),
                    )
                }
            }
        }
    }
}

private fun iconForAttachment(name: String, mime: String): androidx.compose.ui.graphics.vector.ImageVector {
    val n = name.lowercase()
    val m = mime.lowercase()
    return when {
        m.startsWith("image/") || n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg") ||
            n.endsWith(".gif") || n.endsWith(".webp") -> Icons.Outlined.Image
        m.startsWith("audio/") || n.endsWith(".mp3") || n.endsWith(".wav") || n.endsWith(".m4a") ->
            Icons.Outlined.MusicNote
        n.endsWith(".pdf") -> Icons.Outlined.PictureAsPdf
        n.endsWith(".txt") || n.endsWith(".md") || n.endsWith(".json") || n.endsWith(".csv") ->
            Icons.Outlined.Description
        else -> Icons.Outlined.InsertDriveFile
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "${bytes}B"
    bytes < 1024 * 1024 -> "${bytes / 1024}KB"
    else -> "${bytes / (1024 * 1024)}MB"
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
    isConfigured: Boolean,
    ttsEnabled: Boolean,
    onToggleTts: () -> Unit,
    onOpenBrowser: () -> Unit,
    onOpenBackgroundTasks: () -> Unit,
    onClear: () -> Unit,
    onNewChat: () -> Unit,
) {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography

    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // v2.0.7: Removed logo from header (per user request).
                // Just "Aira" + tagline subtitle now.
                Text(
                    text = "Aira",
                    style = typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = m3.onSurface,
                )

                Spacer(Modifier.width(6.dp))

                // v3.2.7: Compact status — just the dot (no text)
                PulsingDot(active = isThinking, color = if (isConfigured) custom.accent else custom.fg3)
            }
        },
        actions = {
            // v2.2.0 Issue 113c — New Chat button (creates a fresh session).
            IconButton(onClick = onNewChat) {
                Icon(
                    imageVector = Icons.Outlined.AddComment,
                    contentDescription = "New chat",
                    tint = custom.fg2,
                )
            }
            // v2.0.4: TTS toggle (default OFF). Emerald when enabled, gray when off.
            IconButton(onClick = onToggleTts) {
                Icon(
                    imageVector = if (ttsEnabled) Icons.Filled.VolumeUp else Icons.Outlined.VolumeOff,
                    contentDescription = if (ttsEnabled) "Mute AI voice" else "Enable AI voice",
                    tint = if (ttsEnabled) custom.accent else custom.fg2,
                )
            }
            // v2.0.7: Browser icon in header (replaced Hub icon).
            // Hub is now reachable only via bottom nav "Aira Lab" tab.
            IconButton(onClick = onOpenBrowser) {
                Icon(
                    imageVector = Icons.Outlined.Public,
                    contentDescription = "Open browser",
                    tint = custom.fg2,
                )
            }
            // v2.2.2: Background Tasks icon — shows running task + recent activity
            IconButton(onClick = onOpenBackgroundTasks) {
                Icon(
                    imageVector = if (isThinking) Icons.Filled.Pending else Icons.Outlined.History,
                    contentDescription = "Background tasks",
                    tint = if (isThinking) custom.accent else custom.fg2,
                )
            }
            IconButton(onClick = onClear) {
                Icon(
                    imageVector = Icons.Outlined.DeleteOutline,
                    contentDescription = "Clear chat",
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
    // Task 37 — status dot pulse: scale 1.0 → 1.2 → 1.0 + alpha 1.0 → 0.6 → 1.0
    // over 1.5s when thinking; steady when ready.
    val transition = rememberInfiniteTransition(label = "status-pulse")
    val alpha by transition.animateFloat(
        initialValue = if (active) 0.6f else 1f,
        targetValue = 1f,
        animationSpec = if (active) {
            infiniteRepeatable(animation = tween(1500), repeatMode = RepeatMode.Reverse)
        } else {
            infiniteRepeatable(animation = tween(1200), repeatMode = RepeatMode.Restart)
        },
        label = "pulse-alpha",
    )
    val scale by transition.animateFloat(
        initialValue = if (active) 1f else 1f,
        targetValue = if (active) 1.2f else 1f,
        animationSpec = if (active) {
            infiniteRepeatable(animation = tween(1500), repeatMode = RepeatMode.Reverse)
        } else {
            infiniteRepeatable(animation = tween(1200), repeatMode = RepeatMode.Restart)
        },
        label = "pulse-scale",
    )
    Box(
        modifier = Modifier
            .size(6.dp)
            .scale(if (active) scale else 1f)
            .alpha(if (active) alpha else 1f)
            .background(color, CircleShape),
    )
}

// ---------------------------------------------------------------------------
// v2.3.2: StepGroupCard — ONE collapsed card for ALL step messages.
// Shows: step count + tool count + first line preview + "tap to expand ▶"
// ---------------------------------------------------------------------------

@Composable
private fun StepGroupCard(
    stepCount: Int,
    toolCount: Int,
    firstLine: String,
    onExpand: () -> Unit,
) {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .background(custom.surface2, RoundedCornerShape(12.dp))
            .border(1.dp, custom.inkBorder, RoundedCornerShape(12.dp))
            .clickable { onExpand() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "📁",
                fontSize = 16.sp,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "$stepCount API step${if (stepCount > 1) "s" else ""} · $toolCount tool call${if (toolCount > 1) "s" else ""}",
                color = custom.accent,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "tap to expand ▶",
                color = custom.fg3,
                fontFamily = MonoFontFamily,
                fontSize = 11.sp,
            )
        }
        if (firstLine.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = firstLine,
                color = custom.fg2,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
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

// ---------------------------------------------------------------------------
// Bottom input bar
// ---------------------------------------------------------------------------

@Composable
private fun ChatInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onVoice: () -> Unit,
    onAttach: () -> Unit,
    attachmentCount: Int = 0,
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

        // Attach (paperclip) — wires GetContent launcher.
        IconButton(
            onClick = onAttach,
            modifier = Modifier.size(40.dp),
        ) {
            if (attachmentCount > 0) {
                BadgedBox(
                    badge = {
                        Badge {
                            Text(
                                text = attachmentCount.toString(),
                                color = Color.White,
                                fontFamily = MonoFontFamily,
                                fontSize = 9.sp,
                            )
                        }
                    },
                ) {
                    Icon(
                        imageVector = Icons.Outlined.AttachFile,
                        contentDescription = "Attach",
                        tint = custom.accent,
                    )
                }
            } else {
                Icon(
                    imageVector = Icons.Outlined.AttachFile,
                    contentDescription = "Attach",
                    tint = custom.fg3,
                )
            }
        }

        Spacer(Modifier.width(4.dp))

        // Send button (round, emerald, with shadow) — Task 37: press-scale feedback.
        val sendInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
        val sendPressed by sendInteraction.collectIsPressedAsState()
        val sendScale by animateFloatAsState(
            targetValue = if (sendPressed) 0.92f else 1f,
            animationSpec = tween(durationMillis = 120, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            label = "send-press-scale",
        )
        IconButton(
            onClick = onSend,
            enabled = text.isNotBlank(),
            interactionSource = sendInteraction,
            modifier = Modifier
                .size(40.dp)
                .scale(sendScale)
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
// Status footer — "v2.0.5 · 47/14400 calls · 847ms avg"
// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------
// Task 40 — Save-skill prompt card + dialog
// ---------------------------------------------------------------------------

@Composable
private fun SaveSkillPromptCard(
    skillName: String,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(m3.surface, RoundedCornerShape(12.dp))
            .border(1.dp, custom.accent.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .shadow(elevation = 6.dp, shape = RoundedCornerShape(12.dp), clip = false)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "💾",
            color = custom.accent,
            fontSize = 18.sp,
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Save as skill?",
                color = m3.onSurface,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "Capture this workflow as \"$skillName\"",
                color = custom.fg2,
                fontFamily = MonoFontFamily,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Row(
            modifier = Modifier
                .background(custom.accent, RoundedCornerShape(8.dp))
                .clickable(onClick = onSave)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Save",
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.width(6.dp))
        IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Dismiss",
                tint = custom.fg3,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@Composable
private fun SaveSkillDialog(
    initialName: String,
    initialDescription: String,
    stepsPreview: String,
    onDismiss: () -> Unit,
    onConfirm: (name: String, description: String) -> Unit,
) {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme
    var name by remember { mutableStateOf(initialName) }
    var description by remember { mutableStateOf(initialDescription) }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = m3.surface,
        title = {
            Text(
                text = "Save as Skill",
                color = m3.onSurface,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("NAME", color = custom.fg3, fontFamily = MonoFontFamily, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(custom.surface2, RoundedCornerShape(8.dp))
                        .border(1.dp, custom.border, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        if (name.isEmpty()) {
                            Text(text = "skill_name", color = custom.fg3, fontFamily = MonoFontFamily, fontSize = 12.sp)
                        }
                        BasicTextField(
                            value = name,
                            onValueChange = { name = it },
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(
                                color = m3.onSurface,
                                fontSize = 12.sp,
                                fontFamily = MonoFontFamily,
                            ),
                            cursorBrush = SolidColor(custom.accent),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text("DESCRIPTION", color = custom.fg3, fontFamily = MonoFontFamily, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(custom.surface2, RoundedCornerShape(8.dp))
                        .border(1.dp, custom.border, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        if (description.isEmpty()) {
                            Text(text = "What does this skill do?", color = custom.fg3, fontSize = 12.sp)
                        }
                        BasicTextField(
                            value = description,
                            onValueChange = { description = it },
                            singleLine = false,
                            minLines = 2,
                            textStyle = androidx.compose.ui.text.TextStyle(
                                color = m3.onSurface,
                                fontSize = 12.sp,
                            ),
                            cursorBrush = SolidColor(custom.accent),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text("STEPS PREVIEW (read-only)", color = custom.fg3, fontFamily = MonoFontFamily, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 180.dp)
                        .verticalScroll(rememberScrollState())
                        .background(custom.ink2, RoundedCornerShape(8.dp))
                        .border(1.dp, custom.inkBorder, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = stepsPreview.take(800) + if (stepsPreview.length > 800) "\n…" else "",
                        color = custom.fg2,
                        fontFamily = MonoFontFamily,
                        fontSize = 10.sp,
                        lineHeight = 13.sp,
                    )
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = { onConfirm(name, description) },
                enabled = name.isNotBlank(),
            ) {
                Text(
                    text = "Confirm save",
                    color = if (name.isNotBlank()) custom.accent else custom.fg3,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("Cancel", color = custom.fg2)
            }
        },
    )
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
                Text("Aira", style = typography.titleMedium, color = m3.onSurface)
                Text(" · Personal Assistant", color = custom.fg3, fontFamily = MonoFontFamily, fontSize = 11.sp)
            }
            Box(modifier = Modifier.weight(1f))
        }
    }
}
