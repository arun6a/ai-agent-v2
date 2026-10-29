package com.ai.agent.ui.screens

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ai.agent.VoiceManager
import com.ai.agent.llm.AIProvider
import com.ai.agent.llm.ApiUsageTracker
import com.ai.agent.llm.ContextualPromptBuilder
import com.ai.agent.llm.LLMClient
import com.ai.agent.storage.AgentDatabase
import com.ai.agent.tools.ToolExecutor
import com.ai.agent.util.LogCapture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID

// ============================================================================
// ChatViewModel — owns chat state and runs the v2 agent loop.
// ----------------------------------------------------------------------------
// Ports v1's MainActivity.runAgentLoop() into a Compose-friendly ViewModel:
//   - State exposed as StateFlow (collectAsState in Compose)
//   - Iteration / API count / provider info surfaced in the same state
//   - Stop button cancels the current loop without losing partial progress
//   - Per-step messages (each iteration appends a new ChatMessage with tools)
//   - Strategy switching after 2 failures, hard stop after 3 (infinite-loop guard)
//   - Always learns (saves successful workflow + failure lessons to database)
//   - Dynamic prompt fallback: if AI calls unknown tools, expand to full prompt
//   - Conversation trimmed to 8000 chars (keeps last 6000 + original user msg)
//   - Voice: speak(finalReply.take(500), "en") at the end
// ============================================================================

// ---- UI state models ----

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val toolCalls: List<ToolCallDisplay> = emptyList(),
    val status: MessageStatus = MessageStatus.IDLE,
)

data class ToolCallDisplay(
    val name: String,
    val args: Map<String, Any>,
    val success: Boolean = false,
    val output: String = "",
    val durationMs: Long = 0,
)

enum class MessageStatus { IDLE, THINKING, EXECUTING, SUCCESS, ERROR }

/** A file the user has staged to send with the next message. */
data class Attachment(
    val name: String,
    val path: String,
    val mimeType: String,
    val sizeBytes: Long,
)

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val isThinking: Boolean = false,
    val isExecuting: Boolean = false,
    val currentIteration: Int = 0,
    val maxIterations: Int = 80,  // v4.0.29: was 50 → 80 (more room for complex tasks)
    val apiCallCount: Int = 0,
    val providerName: String = "",
    val modelName: String = "",
    val error: String? = null,
    val stopRequested: Boolean = false,
    /** Files staged to send with the next message. */
    val attachments: List<Attachment> = emptyList(),
    /** Task 40 — prompt the user to save the just-finished workflow as a skill. */
    val showSaveSkillPrompt: Boolean = false,
    /** Auto-derived skill name (lowercase, spaces → underscores, truncated to 30 chars). */
    val lastWorkflowName: String = "",
    /** The accumulated workLog string from the just-finished run. */
    val lastWorkflowSteps: String = "",
    /** Toast message for the UI to show (e.g. on skill save). */
    val toast: String? = null,
    /** v2.0.4: TTS auto-speak toggle. Default OFF (was ON, annoyed user). */
    val ttsEnabled: Boolean = false,
    /** v2.2.0 Issue 113b — id of the currently active chat session. */
    val currentSessionId: Long = 0L,
    /** v2.2.0 Issue 113b — list of all chat sessions (most recently active first). */
    val sessions: List<AgentDatabase.ChatSession> = emptyList(),
    // v2.4.0: Removed webViewVisible/webViewUrl/webViewUserMode/webViewAlive —
    // browser is now a separate Compose destination (Routes.BROWSER), not an overlay.
)

class ChatViewModel(
    private val context: Context,
    private val llmClient: LLMClient,
    private val toolExecutor: ToolExecutor,
    private val database: AgentDatabase,
    private val voiceManager: VoiceManager,
) : ViewModel() {

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    /** The currently running agent-loop coroutine (so Stop can cancel it). */
    private var loopJob: Job? = null

    // v2.2.2: Browser method lock — once AI picks in_app or chrome, stick with it
    private var lockedBrowserMethod: String? = null  // "in_app" | "chrome" | null

    init {
        loadProviderInfo()
        loadTtsPref()
        checkAccessibilityStatus()
        loadOrCreateSession()
        loadChatHistory()
        refreshSessions()
        // v2.4.0: Removed EmbeddedBrowserBridge registration — browser is now a Compose destination.
        // BrowserController.setNavigationCallback (registered in AppNavigation) handles nav.
    }

    override fun onCleared() {
        super.onCleared()
        // v2.4.0: Removed EmbeddedBrowserBridge.unregister() — no longer needed.
    }

    // ---- public API ----

    fun loadProviderInfo() {
        val provider = AIProvider.getCurrentProvider(context)
        val model = AIProvider.getModel(context)
        _state.update { it.copy(providerName = provider.name, modelName = model) }
    }

    /**
     * v2.2.0 Issue 113b — Check accessibility status from the Android system
     * setting (source of truth) instead of the in-memory `instance`.
     *
     * The instance is null until the system binds the service — a race
     * condition that made the AI wrongly claim "accessibility is off" when
     * the app was reopened. The system setting is checked here for logging,
     * and again per agent-loop run inside the system prompt injection.
     */
    private fun checkAccessibilityStatus() {
        try {
            val enabled = com.ai.agent.accessibility.AgentAccessibilityService.isEnabled(context)
            Log.i(TAG, "Accessibility service enabled (system setting)? $enabled")
        } catch (e: Exception) {
            Log.w(TAG, "Could not check accessibility status: ${e.message}")
        }
    }

    // ---- v2.2.0 Issue 113b: Chat session management ----

    /** Load the most-recently-active session id (creating one if none exists). */
    fun loadOrCreateSession() {
        val sessionId = try {
            database.getCurrentSessionId()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load current session id", e)
            0L
        }
        _state.update { it.copy(currentSessionId = sessionId) }
    }

    /** Populate the sessions list from the database. */
    fun refreshSessions() {
        val sessions = try {
            database.getChatSessions()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load sessions", e)
            emptyList()
        }
        _state.update { it.copy(sessions = sessions) }
    }

    /** Load all messages for the current session from the database into UI state. */
    fun loadChatHistory() {
        val sessionId = _state.value.currentSessionId
        if (sessionId == 0L) return
        val conversations = try {
            database.getConversationsForSession(sessionId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load chat history", e)
            emptyList()
        }
        val messages = conversations.map { (role, text, timestamp) ->
            ChatMessage(
                text = text,
                isUser = role == "user",  // v2.2.1: "ai_step" and "ai" are both AI
                timestamp = timestamp,
            )
        }
        _state.update { it.copy(messages = messages) }
        Log.i(TAG, "Loaded ${messages.size} messages from session $sessionId")
    }

    /** Start a brand-new chat session and clear the message list. */
    fun createNewChatSession() {
        loopJob?.cancel()
        val newId = try {
            database.createChatSession()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create new session", e)
            0L
        }
        _state.update {
            it.copy(
                currentSessionId = newId,
                messages = emptyList(),
                currentIteration = 0,
                apiCallCount = 0,
                isThinking = false,
                isExecuting = false,
                error = null,
                stopRequested = false,
                attachments = emptyList(),
                showSaveSkillPrompt = false,
                toast = "New chat started",
            )
        }
        refreshSessions()
    }

    /** Switch to an existing session and load its messages. */
    fun loadSession(sessionId: Long) {
        loopJob?.cancel()
        val conversations = try {
            database.getConversationsForSession(sessionId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load session $sessionId", e)
            emptyList()
        }
        val messages = conversations.map { (role, text, timestamp) ->
            ChatMessage(
                text = text,
                isUser = role == "user",  // v2.2.1: "ai_step" and "ai" are both AI
                timestamp = timestamp,
            )
        }
        _state.update {
            it.copy(
                currentSessionId = sessionId,
                messages = messages,
                currentIteration = 0,
                apiCallCount = 0,
                isThinking = false,
                isExecuting = false,
                error = null,
                stopRequested = false,
                attachments = emptyList(),
            )
        }
        Log.i(TAG, "Loaded session $sessionId — ${messages.size} messages")
    }

    /** Delete a session (and all of its messages). If it's the current one, switch to another. */
    fun deleteSession(sessionId: Long) {
        try {
            database.deleteChatSession(sessionId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete session $sessionId", e)
        }
        if (_state.value.currentSessionId == sessionId) {
            loadOrCreateSession()
            loadChatHistory()
        }
        refreshSessions()
    }

    /** v2.0.4: TTS toggle — persists across launches. Default OFF. */
    fun loadTtsPref() {
        val prefs = context.getSharedPreferences("ai_agent_prefs", android.content.Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean("tts_enabled", false)
        _state.update { it.copy(ttsEnabled = enabled) }
    }

    fun setTtsEnabled(enabled: Boolean) {
        context.getSharedPreferences("ai_agent_prefs", android.content.Context.MODE_PRIVATE)
            .edit().putBoolean("tts_enabled", enabled).apply()
        _state.update { it.copy(ttsEnabled = enabled) }
    }

    fun sendMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        // v3.2.6: Gate chat on AI configuration. If the user hasn't set up a
        // provider yet (or skipped onboarding), do NOT send anything to the
        // network. Show an actionable error instead.
        if (!AIProvider.isConfigured(context)) {
            _state.update {
                it.copy(
                    error = "⚠️ No AI provider configured yet.\n\n" +
                            "Open AI Settings → API Keys to add your API key, " +
                            "then pick a model in Model Catalog → save it, " +
                            "then open LLM Configuration → set it as Default Agent Model.",
                    isThinking = false,
                    isExecuting = false,
                )
            }
            return
        }

        // Cancel any in-flight loop (re-entrant send = stop previous task).
        loopJob?.cancel()

        // v2.2.0 Issue 113b — make sure we have a valid current session before saving.
        if (_state.value.currentSessionId == 0L) {
            loadOrCreateSession()
        }
        val sessionId = _state.value.currentSessionId

        // Build the effective message text — append attachment metadata so the LLM
        // knows what files the user has shared.
        val attachments = _state.value.attachments
        val effectiveMessage = if (attachments.isEmpty()) {
            trimmed
        } else {
            val attachmentList = attachments.joinToString(", ") { it.name }
            "$trimmed\n\n[Attached files: $attachmentList — use readFile/listFiles tools to access them from /storage/emulated/0/Documents/ai-workspace/attachments/]"
        }

        // Persist + display the user message (display the trimmed text, not the augmented one).
        // v2.2.0 Issue 113b — persist under the current session.
        try {
            database.addConversationToSession(sessionId, "user", trimmed)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist user message to session $sessionId: ${e.message}")
        }
        val userMsg = ChatMessage(text = trimmed, isUser = true, status = MessageStatus.IDLE)
        _state.update { s ->
            s.copy(
                messages = s.messages + userMsg,
                error = null,
                stopRequested = false,
                currentIteration = 0,
                apiCallCount = 0,
                isThinking = true,
                isExecuting = false,
                // Clear staged attachments — they're now consumed.
                attachments = emptyList(),
            )
        }

        loopJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { runAgentLoop(effectiveMessage) }
    }

    fun stopAgent() {
        _state.update { it.copy(stopRequested = true, isThinking = false, isExecuting = false) }
        loopJob?.cancel()
    }

    /**
     * v2.2.0 Issue 113b — Clear chat now creates a new chat session instead
     * of deleting all DB rows. Previous sessions remain accessible from
     * Hub → History.
     */
    fun clearChat() {
        createNewChatSession()
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    // ---- Attachments ----

    /**
     * Copy a content:// Uri into `/storage/emulated/0/Documents/ai-workspace/attachments/`
     * and stage it as an Attachment for the next message.
     *
     * Runs on Dispatchers.IO so we don't block the UI thread on large files.
     */
    fun addAttachment(uri: Uri) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val resolver = context.contentResolver
                var name: String? = null
                var size: Long = 0L
                resolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (cursor.moveToFirst()) {
                        if (nameIdx >= 0) name = cursor.getString(nameIdx)
                        if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) size = cursor.getLong(sizeIdx)
                    }
                }
                val mime = resolver.getType(uri) ?: "application/octet-stream"
                val finalName = name?.takeIf { it.isNotBlank() }
                    ?: "attachment-${System.currentTimeMillis()}"
                val attachmentsDir = File(ATTACHMENTS_DIR)
                if (!attachmentsDir.exists()) attachmentsDir.mkdirs()
                val destFile = File(attachmentsDir, finalName)
                resolver.openInputStream(uri)?.use { input ->
                    destFile.outputStream().use { output -> input.copyTo(output) }
                } ?: run {
                    _state.update { it.copy(error = "Could not open file stream") }
                    return@launch
                }
                if (size == 0L) size = destFile.length()
                val attachment = Attachment(
                    name = finalName,
                    path = destFile.absolutePath,
                    mimeType = mime,
                    sizeBytes = size,
                )
                _state.update { s ->
                    s.copy(attachments = s.attachments + attachment)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stage attachment", e)
                _state.update { it.copy(error = "Attachment failed: ${e.message ?: e::class.simpleName}") }
            }
        }
    }

    fun removeAttachment(attachment: Attachment) {
        _state.update { s ->
            s.copy(attachments = s.attachments.filterNot { it.path == attachment.path })
        }
    }

    fun clearAttachments() {
        _state.update { it.copy(attachments = emptyList()) }
    }

    // ---- Task 40: Skill creator from chat ----

    /**
     * After a successful agent loop with `iteration >= 3` and a non-empty workLog,
     * prompt the user to save the workflow as a reusable skill.
     *
     * Skill name is auto-derived from the first user message: lowercase, spaces →
     * underscores, only [a-z0-9_] kept, truncated to 30 chars.
     */
    private fun maybePromptSaveSkill(userMessage: String, workLog: String, iteration: Int) {
        if (iteration < 3) return
        if (workLog.isBlank()) return
        val rawName = userMessage.lowercase()
            .replace(" ", "_")
            .replace(Regex("[^a-z0-9_]"), "")
            .take(30)
            .trim('_')
        val skillName = if (rawName.isNotBlank()) rawName else "workflow_${System.currentTimeMillis()}"
        _state.update {
            it.copy(
                showSaveSkillPrompt = true,
                lastWorkflowName = skillName,
                lastWorkflowSteps = workLog.take(2000),
            )
        }
        Log.i(TAG, "Prompting user to save skill: $skillName (${workLog.length} chars, $iteration iterations)")
    }

    /** Dismiss the save-skill prompt without saving. */
    fun dismissSaveSkillPrompt() {
        _state.update { it.copy(showSaveSkillPrompt = false) }
    }

    // v2.4.0: Removed embedded WebView methods (showEmbeddedWebView, hideEmbeddedWebView,
    // closeEmbeddedWebView, resumeEmbeddedWebView, setUserMode, resumeAiMode).
    // Browser is now a separate Compose destination (Routes.BROWSER).
    // AI's browserOpen() navigates via BrowserController.setNavigationCallback.
    // User pauses AI via the "⏸ Pause AI" button in BrowserScreen's top bar.

    /**
     * Persist a workflow as a Markdown skill file in
     * `/storage/emulated/0/Documents/ai-workspace/skills/{name}.md` so that
     * SkillManager.loadSkills() picks it up next time.
     *
     * SkillManager itself is frozen and exposes no save method, so we write
     * the .md file directly using its documented on-disk layout (YAML
     * frontmatter + Markdown body).
     */
    fun saveWorkflowAsSkill(name: String, description: String) {
        val steps = _state.value.lastWorkflowSteps
        if (steps.isBlank()) {
            _state.update { it.copy(toast = "Nothing to save — workflow log is empty.") }
            return
        }
        val safeName = name.lowercase()
            .replace(" ", "_")
            .replace(Regex("[^a-z0-9_]"), "")
            .take(40)
            .trim('_')
            .ifBlank { "workflow_${System.currentTimeMillis()}" }
        // v3.7.8: Extract keywords from the name + description so the skill
        // auto-injects when the user sends a similar message. findRelevantSkills()
        // filters by keywords — without this, saved skills would never trigger.
        val keywordsSource = "$name $description"
        val keywords = keywordsSource.lowercase()
            .split(Regex("[\\s,.;:!?]+"))
            .filter { it.length > 2 && it !in setOf("the", "and", "for", "with", "from", "this", "that") }
            .distinct()
            .take(8)
            .joinToString(", ")
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val skillsDir = java.io.File(USER_SKILLS_DIR)
                if (!skillsDir.exists()) skillsDir.mkdirs()
                val file = java.io.File(skillsDir, "$safeName.md")
                val desc = description.ifBlank { "Workflow captured from chat: $safeName" }
                val frontmatter = buildString {
                    append("---\n")
                    append("name: $safeName\n")
                    append("description: ${desc.replace("\"", "'").replace("\n", " ").take(160)}\n")
                    append("trigger: ${safeName.replace("_", " ")}\n")
                    append("keywords: $keywords\n")
                    append("tools_used: []\n")
                    append("version: 1.0\n")
                    append("---\n\n")
                }
                val body = "# ${description.ifBlank { safeName.replace("_", " ").replaceFirstChar { it.uppercase() } }}\n\n" +
                    "Follow these steps IN ORDER (captured from a previous successful run):\n\n" +
                    "$steps\n\n" +
                    "## CRITICAL RULES\n" +
                    "- Follow these steps IN ORDER. Do not skip steps.\n" +
                    "- Do not re-do steps that already succeeded (if the app is already open, don't launchApp again).\n" +
                    "- If a step fails, try a different approach (don't repeat the same action).\n"
                file.writeText(frontmatter + body)
                LogCapture.i(TAG, "Saved skill file: ${file.absolutePath} (keywords: $keywords)")
                _state.update {
                    it.copy(
                        showSaveSkillPrompt = false,
                        toast = "Skill saved: $safeName",
                    )
                }
            } catch (e: Exception) {
                LogCapture.e(TAG, "Failed to save skill: ${e.message}")
                _state.update {
                    it.copy(
                        showSaveSkillPrompt = false,
                        toast = "Save failed: ${e.message ?: e::class.simpleName}",
                    )
                }
            }
        }
    }

    /** Clear the toast after the UI has shown it. */
    fun clearToast() {
        _state.update { it.copy(toast = null) }
    }

    // ---- the agent loop (ported from v1 MainActivity.runAgentLoop) ----

    private suspend fun runAgentLoop(userMessage: String) {
        LogCapture.i(TAG, "===== Agent loop started: ${userMessage.take(80)} =====")
        val workLog = StringBuilder()
        try {
            // v3.0.8: Contextual Prompts — select relevant tool groups based on user message
            // 3-tier: "Hi" → ultra-tiny, keyword match → contextual, unknown → full prompt
            val contextualEnabled = AIProvider.isContextualPromptsEnabled(context)
            // v3.2.9: also compute the contextual tool filter (null = send all tools)
            // so we don't ship 95 tool definitions (~5000 tokens) for a "hi" message.
            // Mutable — auto-expand may add more tool groups during the loop.
            var contextualToolFilter: List<String>? = if (contextualEnabled) {
                com.ai.agent.llm.ContextualPromptBuilder.getRelevantToolNames(userMessage)
            } else null
            val isTier1 = if (contextualEnabled) {
                com.ai.agent.llm.ContextualPromptBuilder.isTier1NoTools(userMessage)
            } else false
            val basePrompt = com.ai.agent.llm.ContextualPromptBuilder.buildPrompt(context, userMessage)
            LogCapture.i(TAG, "Contextual prompt: ${com.ai.agent.llm.ContextualPromptBuilder.estimateTokens(basePrompt)} tokens, tools=${contextualToolFilter?.size ?: "all"}")
            // v3.6.2: Use FTS5 recallSimilar to find the 10 most RELEVANT memories
            // for the current task, instead of taking the first 10 alphabetically.
            val allMemory = database.getAllMemory()
            val memoryStr = if (allMemory.isEmpty()) {
                "No memories stored yet."
            } else {
                // v3.4.1: Separate regular memories from avoid_* failure lessons.
                val regularMemories = allMemory.entries
                    .filter { !it.key.startsWith("avoid_") && !it.key.startsWith("workflow_") }
                if (regularMemories.isEmpty()) {
                    "(only failure lessons stored — see below)"
                } else if (userMessage.length > 10) {
                    // v3.6.2: use FTS5 to find relevant memories for this task.
                    try {
                        val similar = database.recallSimilar(userMessage, limit = 10)
                        if (similar.isNotEmpty()) {
                            // Filter out avoid_ and workflow_ keys (those go in separate sections).
                            similar.filter { !it.first.startsWith("avoid_") && !it.first.startsWith("workflow_") }
                                .joinToString("\n") { "- ${it.first}: ${it.second}" }
                        } else {
                            // FTS5 returned nothing — fall back to first 10.
                            regularMemories.take(10).joinToString("\n") { "- ${it.key}: ${it.value}" }
                        }
                    } catch (_: Exception) {
                        // FTS5 failed — fall back to first 10.
                        regularMemories.take(10).joinToString("\n") { "- ${it.key}: ${it.value}" }
                    }
                } else {
                    // Short message — just use first 10 (no point in FTS5 search).
                    regularMemories.take(10).joinToString("\n") { "- ${it.key}: ${it.value}" }
                }
            }
            var systemPrompt = "$basePrompt\n\n## What I Remember About the User\n$memoryStr"

            // v3.4.1: Phase 2 — inject past failure lessons for THIS task type.
            // The avoid_* keys are saved when a tool gets stuck (line ~883).
            // Load them directly (not via FTS5) since there are usually <20 total.
            try {
                val allAvoid = allMemory.entries
                    .filter { it.key.startsWith("avoid_") }
                    .take(5)
                if (allAvoid.isNotEmpty()) {
                    // Filter by word overlap with the current task.
                    val taskWords = userMessage.lowercase()
                        .replace(Regex("[^a-z0-9\\s]"), " ")
                        .split(Regex("\\s+"))
                        .filter { it.length > 2 }
                        .toSet()
                    val relevantFailures = allAvoid.filter { (_, value) ->
                        val failWords = value.lowercase()
                            .replace(Regex("[^a-z0-9\\s]"), " ")
                            .split(Regex("\\s+"))
                            .filter { it.length > 2 }
                            .toSet()
                        taskWords.intersect(failWords).isNotEmpty()
                    }.take(3)
                    if (relevantFailures.isNotEmpty()) {
                        systemPrompt += "\n\n## Lessons From Past Tasks (avoid these)\n"
                        relevantFailures.forEach { (_, value) ->
                            systemPrompt += "- $value\n"
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failure recall failed: ${e.message}")
            }

            // v3.4.2: Phase 3 — auto-recall relevant knowledge docs.
            // v3.5.0: now respects the Knowledge Auto-Recall toggle.
            // Only for messages > 20 chars (skip "hi", "thanks", etc.).
            if (userMessage.length > 20 && com.ai.agent.llm.AgentThinkingSettings.isKnowledgeAutoRecallEnabled(context)) {
                try {
                    val knowledgeHits = database.recallKnowledge(userMessage, limit = 3)
                    val relevantHits = knowledgeHits.filter { it.third > 0.10f }.toMutableList()

                    // v3.4.1: Name-based check — if the user message contains a
                    // knowledge doc's name (or first word of it), inject it even
                    // if FTS5 score is low. Catches "what's in the Q4 report?"
                    // when the doc is named "Q4 Report".
                    val allKnowledge = database.getAllKnowledge()
                    val msgLower = userMessage.lowercase()
                    for ((name, content, _) in allKnowledge) {
                        // Check if any significant word from the doc name appears in the message.
                        val nameWords = name.lowercase()
                            .split(Regex("\\W+"))
                            .filter { it.length > 2 }
                        val matched = nameWords.any { word -> msgLower.contains(word) }
                        if (matched && relevantHits.none { it.first == name }) {
                            relevantHits.add(Triple(name, content, 0.5f))  // synthetic score
                        }
                    }

                    if (relevantHits.isNotEmpty()) {
                        systemPrompt += "\n\n## Relevant Knowledge (auto-recalled)\n"
                        relevantHits.take(3).forEach { (name, content, _) ->
                            systemPrompt += "### $name\n${content.take(500)}...\n\n"
                        }
                        systemPrompt += "Use recallKnowledge(query) for deeper search if needed."
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Knowledge auto-recall failed: ${e.message}")
                }
            }

            // v2.3.3: Inject enabled app profiles into the system prompt so the AI
            // knows which apps are installed + how to interact with each (shortcut tools, tips).
            // v3.0.8: Only inject if NOT using contextual prompt (contextual already has app shortcuts)
            // v3.6.4: Also skip on Tier 3 — system_prompt_smart.txt already has extensive app
            // packages + known activities + app-specific UI tips. App profiles are redundant.
            if (!contextualEnabled && !isTier1) {
                // v3.6.4: Only inject for Tier 2 (contextual disabled + not greeting).
                // Tier 3 (full prompt) already has all app info in smart.txt.
                val tier = com.ai.agent.llm.ContextualPromptBuilder.getTier(userMessage)
                if (tier == 2) {
                    try {
                        val appsPrompt = com.ai.agent.llm.AppProfileManager.getActiveAppsPromptSection(context)
                        if (appsPrompt.isNotBlank()) {
                            systemPrompt += "\n\n$appsPrompt"
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "App profile injection failed: ${e.message}")
                    }
                }
            }

            // v2.2.0 Issue 112 — Inject accessibility status into the system prompt.
            //
            // The AI was wrongly telling users "accessibility is off" on app
            // reopen because the in-memory `instance` is null until the system
            // binds the service. The system setting is the source of truth —
            // use it here so the AI knows the real state.
            //
            // v3.2.9: Skip these heavy guides for Tier 1 (greetings) — the user
            // just said "hi", no need to inject 1000+ tokens of a11y/browser/
            // vision/skills guidance. The ultra-tiny prompt is enough.
            if (!isTier1) {
                try {
                    val a11yEnabled = com.ai.agent.accessibility.AgentAccessibilityService.isEnabled(context)
                    val a11yStatus = if (a11yEnabled) {
                        "Accessibility service is ENABLED — you can control the phone " +
                            "(tap, type, read screen, swipe, scroll, take screenshots)."
                    } else {
                        "Accessibility service is DISABLED — phone control tools " +
                            "(tap, type, readScreen, etc.) will NOT work. " +
                            "Ask the user to enable it in Hub → Permissions → Accessibility."
                    }
                    systemPrompt += "\n\n## Accessibility Status\n$a11yStatus"
                    Log.i(TAG, "Injected a11y status into system prompt: enabled=$a11yEnabled")
                } catch (e: Exception) {
                    Log.w(TAG, "A11y status injection failed: ${e.message}")
                }

                // v2.2.2: Browser mode guidance — tell AI which browser method to use + lock it
                val browserMode = AIProvider.getBrowserMode(context)  // "auto" | "in_app" | "chrome"
                val browserGuidance = if (lockedBrowserMethod != null) {
                    "IMPORTANT: You already chose '$lockedBrowserMethod' browser mode for this task. " +
                        "Do NOT switch to a different browser method. Continue using '$lockedBrowserMethod'."
                } else {
                    when (browserMode) {
                        "in_app" -> "Browser mode: IN-APP. Use browserOpen + browserClickText + browserFill for ALL browser operations. Do NOT use openInChrome."
                        "chrome" -> "Browser mode: CHROME. Use openInChrome / searchInChrome for ALL browser operations. Do NOT use browserOpen."
                        else -> "Browser mode: AUTO. For logged-in sites (Gmail, YouTube, social media), use Chrome via openInChrome. For web scraping/research, use in-app browser (browserOpen). Pick ONE method and STICK with it for the entire task. Do NOT mix."
                    }
                }
                val webGuide = """
## Web Tools Guide
- **webSearch(query)**: Get search results WITHOUT opening a browser. Returns text snippets. Fast, no visual feedback. Best for: "what is X", "latest news".
- **browserOpen(url)**: Opens IN-APP browser (user can see what's happening). Has AI Mode + User Mode. If user touches browser, AI pauses. Best for: forms, scraping, logged-out sites.
- **openInChrome(url)**: Opens Chrome app (uses Chrome login state). Best for: Gmail, YouTube, Google services, social media.
- **searchInApp(query)**: Opens search within a specific app (e.g., YouTube search).

RULE: Pick ONE web method per task and STICK with it. Do NOT mix browserOpen with openInChrome in the same task.
""".trimIndent()
                systemPrompt += "\n\n$browserGuidance\n$webGuide"

                // v2.2.3: Vision mode + screen loading guidance
                val visionMode = AIProvider.getVisionMode(context)  // "auto" | "mlkit" | "vlm"
                val visionGuide = """
## Screen Reading & Vision
Vision mode: $visionMode

CRITICAL RULES FOR SCREEN READING:
1. After launchApp() — the tool waits 2.5s + returns screen text. But some apps need MORE time to load (Gmail, YouTube, Chrome). If the returned text shows "Loading" or is very short, call wait(2000) then readScreen() again. Do NOT retry the action — the screen hasn't loaded yet.
2. After browserOpen() — the browser takes 3-5 seconds to load a page. ALWAYS call wait(3000) after browserOpen, then readScreen() or browserGetText() to verify the page loaded. Do NOT click anything until you've verified the page content.
3. After tap() on a button that navigates to a new screen — call wait(1000) then readScreen() to verify the new screen loaded. Do NOT assume the tap worked — verify with readScreen().
4. After type() + submitInput() — call wait(2000) then readScreen() to verify the action completed.
5. If readScreen() returns text with "Loading" or very little text — call wait(2000) then readScreen() again. The screen is still loading.

WAIT STRATEGY:
- After opening ANY app: wait(2500) is automatic in launchApp, but if the screen shows loading, call wait(2000) + readScreen() again.
- After opening browser: ALWAYS wait(3000) before any browser tool. The page needs time to render.
- After clicking a link/button: wait(1000) + readScreen() to verify.
- NEVER retry an action (tap, type, click) if the screen hasn't loaded. Instead: wait() + readScreen() to check again.

readScreen() reads the screen using: accessibility text → ML Kit OCR → VLM (3-tier fallback, 0 API calls for 95% of screens).
analyzeScreen(prompt) uses VLM directly (1 API call, more capable but slower). Use readScreen() first, only use analyzeScreen() if you need to find visual elements (icons, images, positions).
""".trimIndent()
                systemPrompt += "\n\n$visionGuide"

                // v3.9.11: VLM-only mode gets DETAILED clicking rule.
                // In VLM mode, readScreen returns natural language descriptions →
                // brain can match element text to user request → detailed rule works.
                // In auto/mlkit mode, readScreen returns cryptic a11y text →
                // detailed rule would confuse the brain → skip it (uses base CLICKING RULE).
                if (visionMode == "vlm") {
                    val detailedClickingRule = """
## DETAILED CLICKING RULE (VLM mode — follow EXACTLY)

BEFORE calling clickElement, you MUST identify WHICH element and WHY:

STEP 1: Call readScreenStructured() to get the element list with indices.
STEP 2: READ each element's "text" field. Do NOT skip this.
STEP 3: Identify which element matches your target:
  - For "play X": find the element whose text contains the song/movie title
  - For "open Y channel": find the element with the channel name
  - For "click Z button": find the element with button text Z
  - Match the USER'S REQUEST to the element's text — don't pick "index 1" just because it's first
STEP 4: State in your reply: "Element index N has text 'X' — matches my target because [reason]"
STEP 5: THEN call clickElement(index=N)

NEVER call clickElement without first identifying WHICH element and WHY.
If you can't find a matching element → scroll, re-search, or call complete(success=false).
Do NOT guess an index and hope it's right — you will click the wrong thing.

EXAMPLE (good):
"User wants GTA 6 trailer from official channel.
 readScreenStructured shows: index 4 has text 'Grand Theft Auto VI: An Extended Look', channel 'Rockstar Games'.
 This matches — Rockstar is the official GTA channel.
 → clicking index 4."
clickElement(index=4)

EXAMPLE (bad — NEVER do this):
"Done."
clickElement(index=4)  ← no reasoning, no text match, just guessed

ALWAYS state index + text + reason before clicking. No exceptions.
""".trimIndent()
                    systemPrompt += "\n\n$detailedClickingRule"
                    LogCapture.i(TAG, "VLM mode detected — injecting detailed clicking rule")
                }
            }  // end if (!isTier1)

            // v3.7.6: Auto-inject the FULL skill body for relevant skills.
            // v3.7.5 only listed skill names — the brain ignored them and used tools directly.
            // Now the complete step-by-step instructions are in context → brain follows them naturally.
            // Keyword filtering ensures only 1-3 relevant skills get injected (scales to 500+ skills).
            if (!isTier1) {
                try {
                    val skillManager = com.ai.agent.skills.SkillManager(context)
                    skillManager.loadSkills()
                    val skillInjection = skillManager.generateRelevantSkillsInjection(userMessage)
                    if (skillInjection.isNotBlank()) {
                        systemPrompt += "\n\n$skillInjection"
                        LogCapture.i(TAG, "Skills auto-injected: ${skillManager.findRelevantSkills(userMessage).map { it.name }}")
                    }
                } catch (e: Exception) {
                    LogCapture.w(TAG, "Skill injection failed: ${e.message}")
                }
            }

            // v3.9.6: Plan-then-Act REMOVED — replaced by complete tool + action history.
            // The brain naturally plans in its reply. Action history keeps it on track.
            // The complete tool forces verification before stopping.

            // v4.0.37: REMOVED screen resolution from the system prompt.
            // Was: "Screen resolution: 1080x2400 pixels. When tapping, get coordinates from readScreenStructured..."
            // Problem: telling the AI the resolution ENCOURAGED coordinate guessing ("the screen is
            // 1080 wide, so the button must be at x=540"). This caused mistaps on devices with
            // different resolutions + let the AI be lazy (guess instead of reading the screen).
            // Now: the AI must ALWAYS use readScreenStructured to get real element bounds.
            // This is more reliable — works on ANY device without hardcoded assumptions.
            // val displayMetrics = context.resources.displayMetrics
            // val screenWidth = displayMetrics.widthPixels
            // val screenHeight = displayMetrics.heightPixels
            // systemPrompt += "\n\n## Device Info\nScreen resolution: ${screenWidth}x${screenHeight}..."

            // v3.6.2: Per-iteration instruction moved here (was repeated every step).
            // This saves ~50 tokens per iteration × every step.
            systemPrompt += "\n\n## Agent Loop Instructions\n" +
                "After receiving tool results, decide the next step. " +
                "If the task is complete, reply with just the final message and no tool_calls. " +
                "If a step failed, try a DIFFERENT approach (don't repeat the same action). " +
                "If you need to verify, call readScreen()."

            // === 2. Build the conversation: recent history + user message ===
            // v2.2.0 Issue 113b — scope history to the CURRENT session only,
            // so the AI doesn't bleed context across unrelated chat threads.
            // v4.0.29: 5 msgs × 150 chars default. AI calls getConversationHistory()
            // on demand when it needs more context (keeps default cheap).
            val conversation = StringBuilder()
            val currentSession = _state.value.currentSessionId
            val history = if (currentSession != 0L) {
                try {
                    database.getConversationsForSession(currentSession)
                        .takeLast(5)
                        .map { Triple(it.first, it.second, it.third) }
                } catch (e: Exception) {
                    emptyList<Triple<String, String, Long>>()
                }
            } else {
                emptyList<Triple<String, String, Long>>()
            }
            if (history.isNotEmpty()) {
                conversation.append("[Recent conversation history:\n")
                for ((role, text, _) in history) {
                    conversation.append(if (role == "user") "User: " else "AI: ")
                    conversation.append(text.take(150))
                    conversation.append("\n")
                }
                conversation.append("]\n\n")
            }

            // v4.0.29: Smart "try again" — if the user says "try again" / "retry",
            // inject the last REAL user task so the AI knows WHAT to retry.
            val msgLower = userMessage.lowercase().trim()
            val retryPhrases = listOf("try again", "retry", "try it again", "do it again",
                "try once more", "once more", "one more time", "redo", "re-run", "rerun",
                "run again", "do again", "keep going", "go on", "continue")
            val isRetry = retryPhrases.any { msgLower == it || msgLower.startsWith(it + " ") || msgLower.startsWith(it + "!") || msgLower.startsWith(it + ".") || msgLower.startsWith(it + "?") }
            if (isRetry && history.isNotEmpty()) {
                // Find the last USER message that ISN'T a retry phrase (the real task)
                val lastRealTask = history.reversed().find { (role, text, _) ->
                    role == "user" && !retryPhrases.any { p ->
                        text.lowercase().trim().let { it == p || it.startsWith(p + " ") }
                    }
                }
                if (lastRealTask != null) {
                    conversation.append("[RETRY CONTEXT]: The user said '$userMessage'. They want you to RETRY this previous task:\n")
                    conversation.append("  → ${lastRealTask.second}\n")
                    conversation.append("Approach it fresh — try a different strategy if the previous attempt failed.\n")
                    conversation.append("If you need MORE context about what was tried, call getConversationHistory(limit=20).\n\n")
                    LogCapture.i(TAG, "Retry detected — injected last task: ${lastRealTask.second.take(80)}")
                }
            }

            conversation.append(userMessage)

            // === 3. Loop state ===
            val toolCallHistory = mutableListOf<String>()
            // v3.8.1: Track unique tools that FAILED — if 3 different approaches fail, STOP.
            val failedApproachTools = mutableSetOf<String>()
            // v3.8.2: Track which tool signatures have FAILED in previous iterations.
            // Strategy-switch warning only fires AFTER a tool has been tried and failed —
            // not on first use (was confusing the brain before it even ran the tool).
            val previouslyFailedSignatures = mutableSetOf<String>()
            var finalReply = ""
            var iteration = 0

            // v3.9.6: Memory buffer — extracted from <memory> tags, injected next iteration.
            var memoryBuffer = ""

            // v3.8.8: Enforced Plan Tracking + Re-planning
            // The brain states a plan on iteration 1 (Plan-then-Act). We PARSE it,
            // STORE it, and INJECT "[PLAN]: ... [STEP 2 of 3]: ..." every iteration.
            // When a step fails, brain gets: retry, skip, or re-plan.
            // Max 2 re-plans, then STOP.
            // v3.9.12: Removed plan-tracking vars (currentPlan, currentStep, stepFailures,
            // replanCount, MAX_REPLANS) — Phase 1 was removed in v3.9.6, plan parsing
            // was inert. Brain's natural reasoning + action history handle multi-step now.

            // v3.8.9: "Screen didn't change" detection — catches "fake success" loops.
            // When a tool returns ✓ but the screen is identical to before → the brain is stuck.
            // This catches what failure-based detection misses (tools that "succeed" without accomplishing).
            var lastScreenText = ""                 // screen text after last action
            var sameScreenCount = 0                 // how many times screen didn't change in a row
            val MAX_SAME_SCREEN = 4                 // v4.0.29: was 2 → 4 (more tolerance for slow-loading screens)

            // v2.2.2: Notify AgentService that a task is running (updates notification)
            com.ai.agent.service.AgentService.notifyTaskStarted()
            // v2.2.2: Reset stop flag from notification
            com.ai.agent.service.TaskStopReceiver.reset()

            // v4.0.10: Show floating Stop overlay over all apps.
            // This lets the user stop the task even when they're in YouTube/WhatsApp/etc.
            com.ai.agent.service.TaskStopOverlay.resetStopRequest()
            com.ai.agent.service.TaskStopOverlay.show(context)

            // v2.2.2: Reset browser method lock for this new task
            lockedBrowserMethod = null

            // v3.6.3: Check if the active key supports prompt caching.
            // If so, mid-loop modifications go into conversation (not systemPrompt)
            // so the system prompt stays stable for provider-side caching.
            val activeKeyId = com.ai.agent.llm.SavedModelsStore.getDefaultModel(context)
                ?.activeBindings()?.firstOrNull()?.apiKeyId
            val cachingEnabled = activeKeyId?.let {
                com.ai.agent.llm.ApiKeyStore.getCachingSupport(it) == true
            } ?: false
            if (cachingEnabled) {
                LogCapture.i(TAG, "Prompt caching ENABLED — system prompt will stay stable mid-loop")
            }

            // v2.0.9 Task 93: Per-task + per-step timeout guards.
            //   • TASK_TIMEOUT_MS (3 min) — overall wall-clock budget for a
            //     single user message. Prevents pathological loops from
            //     draining battery / API quota when the model keeps cycling
            //     tools without converging.
            //   • STEP_TIMEOUT_MS (30 s) — per-iteration budget covering the
            //     LLM round-trip + tool execution. If a step blows the budget
            //     (slow provider, blocking tool call), we surface a timeout
            //     step message and let the next iteration try again.
            val taskStartTime = System.currentTimeMillis()
            val TASK_TIMEOUT_MS = 600_000L   // v4.0.29: was 180s → 600s (10 min, for 80 iterations)
            val STEP_TIMEOUT_MS = 60_000L    // v4.0.29: was 30s → 60s (some API calls are slow)

            // v3.3.6: Smart Routing — pick the right model for this message
            // based on tier (1=Fast/Cheap, 2=Default, 3=Advanced) + apply it
            // to AIProvider so chatWithFailover uses the right bindings.
            val pickedModel = com.ai.agent.llm.SmartRouting.pickModelForMessage(context, userMessage)
            if (pickedModel != null) {
                com.ai.agent.llm.SavedModelsStore.applyModelForRouting(context, pickedModel.id)
                LogCapture.i(TAG, "SmartRouting: picked '${pickedModel.modelName}' for this message")
            }

            // v3.5.0: P0-2 — Planning step for complex tasks.
            // For Tier 3 tasks with long user messages (>50 chars), make one extra
            // LLM call to break the task into steps. The plan is advisory — the
            // brain can adapt if reality differs.
            if (com.ai.agent.llm.AgentThinkingSettings.isPlanningEnabled(context)) {
                val tier = com.ai.agent.llm.ContextualPromptBuilder.getTier(userMessage)
                if (tier == 3 && userMessage.length > 50) {
                    try {
                        LogCapture.i(TAG, "Planning: generating plan for complex task (tier=$tier, len=${userMessage.length})")
                        val planPrompt = "Break this task into 3-7 ordered steps. Reply with ONLY the steps, one per line, numbered. Task: $userMessage"
                        val planResponse = llmClient.chat(planPrompt, "You are a task planning assistant. Be concise.")
                        if (planResponse.reply.isNotBlank()) {
                            conversation.append("[PLAN]:\n${planResponse.reply.take(500)}\n\n")
                            LogCapture.i(TAG, "Plan generated: ${planResponse.reply.take(100)}")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Planning failed (non-fatal): ${e.message}")
                    }
                }
            }

            ApiUsageTracker.startNewTask()

            while (iteration < MAX_ITERATIONS && !_state.value.stopRequested && !com.ai.agent.service.TaskStopReceiver.stopRequested && !com.ai.agent.service.TaskStopOverlay.isStopRequested()) {
                // ---- Task-level timeout (3 minutes total) ----
                if (System.currentTimeMillis() - taskStartTime > TASK_TIMEOUT_MS) {
                    finalReply = "⏰ Task timed out after 3 minutes. Here's what I accomplished:\n$workLog"
                    _state.update { it.copy(error = "Task timeout") }
                    LogCapture.w(TAG, "Task timed out after ${TASK_TIMEOUT_MS}ms — breaking loop")
                    break
                }

                iteration++
                LogCapture.i(TAG, "----- Iteration $iteration started (API calls: ${ApiUsageTracker.getTaskCalls()}) -----")
                _state.update {
                    it.copy(
                        currentIteration = iteration,
                        isThinking = true,
                        isExecuting = false,
                        apiCallCount = ApiUsageTracker.getTaskCalls(),
                    )
                }

                // v3.5.0: P0-1 — Periodic reflection every 5 iterations.
                // Injects a short "are you making progress?" check into the conversation.
                // The brain reads this as part of the conversation history + can switch
                // approach if it's stuck.
                if (com.ai.agent.llm.AgentThinkingSettings.isReflectionEnabled(context)
                    && iteration > 1 && iteration % 5 == 0) {
                    conversation.append(
                        "\n\n[REFLECTION CHECK — step $iteration]: " +
                        "Are you making progress toward the goal? " +
                        "If stuck, switch approach NOW. Don't repeat the same action."
                    )
                    LogCapture.i(TAG, "Reflection check injected at iteration $iteration")
                }

                // ---- Per-step timeout (30 seconds) ----
                // Wraps the LLM call + dynamic-prompt fallback + tool execution.
                // Returns:
                //   null  → step timed out (caller logs + continues)
                //   true  → step completed normally, continue loop
                //   false → task complete (no tools) or stuck, break loop
                val stepResult = withTimeoutOrNull(STEP_TIMEOUT_MS) {
                    // v3.0.8: Contextual prompts are used for ALL iterations (not just iteration 1)
                    // The same contextual prompt is reused — AI always sees relevant tools
                    val activePrompt = systemPrompt  // already contextual from above
                    // v3.2.9: pass the contextual tool filter so we don't ship all 95 tools
                    // when the user just said "hi" or only needs a small tool group.
                    // v3.9.6: REMOVED two-phase (was wasting API calls — Phase 1 said "Done.")
                    // Now: single-phase with action history + dumb executor prompt + complete tool.
                    // The complete tool forces the brain to verify success before stopping.

                    // v3.9.6: Inject action history (last 5 actions) so brain sees what it did.
                    if (workLog.isNotEmpty() && iteration > 1) {
                        val recentActions = workLog.toString().lines()
                            .filter { it.isNotBlank() }
                            .takeLast(5)
                            .joinToString("\n")
                            .take(1000)  // truncate to avoid context bloat
                        conversation.append("\n\n[ACTION HISTORY — last 5 steps]:\n$recentActions\n")
                    }

                    // v3.9.11: Dumb executor prompt — softened to allow reasoning before clicks.
                    // v3.9.10 was too aggressive ("DUMB ROBOT. Don't think.") → brain stopped reasoning
                    // and just guessed indices. Now: "execute, but REASON about each click first."
                    // Forces brain to state index + text + why before every clickElement.
                    val execPrompt = if (iteration > 1) {
                        activePrompt + "\n\n## EXECUTION MODE\n" +
                            "Execute the NEXT step from your plan. Do NOT re-plan from scratch.\n" +
                            "Do NOT re-search unless the previous search failed.\n\n" +
                            "BUT — before EVERY clickElement call, you MUST:\n" +
                            "1. State the index you're clicking\n" +
                            "2. State the TEXT of that element (from readScreenStructured)\n" +
                            "3. State WHY it matches your target\n" +
                            "Example: 'Element index 4 has text \"GTA VI Trailer\" — matches user request. Clicking.'\n\n" +
                            "If you can't state all 3 → call readScreenStructured() first. Do NOT guess.\n\n" +
                            "After each step, read the ╔══ SCREEN STATE ══╗ block.\n" +
                            "If it shows the task is ALREADY COMPLETE → call complete(success=true). Don't proceed.\n\n" +
                            "Do NOT repeat readScreenStructured() if you called it in the previous step.\n" +
                            "Use the data from [ACTION HISTORY — last 5 steps].\n\n" +
                            "If you can't execute (element not found, wrong screen), call complete(success=false, reason=\"...\").\n" +
                            "If the task is complete, call complete(success=true, reason=\"...\")."
                    } else {
                        activePrompt
                    }

                    // v3.9.6: Inject <memory> from previous iteration if available.
                    if (memoryBuffer.isNotEmpty()) {
                        conversation.append("\n\n[MEMORY]: $memoryBuffer")
                    }

                    val llmResponse = llmClient.chatWithFailover(conversation.toString(), execPrompt, contextualToolFilter)

                    // v3.8.6: Log the brain's reply text + tool calls so we can SEE what it said.
                    // Without this, we only see tool calls in logs — can't tell if brain planned or not.
                    val toolsCalled = if (llmResponse.toolCalls.isEmpty()) "(none)" else llmResponse.toolCalls.joinToString(", ") { "${it.name}(${it.args.entries.joinToString(", ") { "${it.key}=${it.value}" }})" }
                    // v3.9.1: Log FULL brain reply — no truncation. Need to see complete reasoning.
                    LogCapture.i(TAG, "brain reply: ${llmResponse.reply}")
                    LogCapture.i(TAG, "tool calls: $toolsCalled")

                    // v3.9.6: Extract <memory> tag from brain's reply for next iteration.
                    val memoryTagPattern = Regex("(?i)<memory>(.*?)</memory>", RegexOption.DOT_MATCHES_ALL)
                    val memoryMatch = memoryTagPattern.find(llmResponse.reply)
                    if (memoryMatch != null) {
                        memoryBuffer = memoryMatch.groupValues[1].trim().take(300)
                        LogCapture.i(TAG, "Memory extracted: $memoryBuffer")
                    }

                    // v3.9.6: Check if brain called the complete tool — if so, stop the loop.
                    val calledComplete = llmResponse.toolCalls.any { it.name == "complete" }
                    if (calledComplete) {
                        val completeCall = llmResponse.toolCalls.find { it.name == "complete" }
                        val success = completeCall?.args?.get("success") as? Boolean ?: false
                        val reason = completeCall?.args?.get("reason") as? String ?: "No reason"
                        finalReply = if (success) "✅ Done! $reason" else "❌ Task couldn't be completed. $reason"
                        LogCapture.i(TAG, "✓ complete tool called (success=$success) — stopping loop")

                        // v4.0.37: Pattern Learning — save successful tool sequences.
                        // When a task succeeds, save the tool sequence so the AI can reuse it
                        // next time a similar request comes in (saves exploration calls).
                        if (success && toolCallHistory.isNotEmpty()) {
                            try {
                                com.ai.agent.learning.PatternLearner.saveSuccessfulPattern(
                                    context = context,
                                    userMessage = userMessage,
                                    toolSequence = toolCallHistory.distinct().take(10),  // unique tools, max 10
                                    reason = reason
                                )
                            } catch (_: Exception) {}
                        }

                        // Execute the complete tool (so it gets logged)
                        val completeResults = toolExecutor.executeTools(listOf(completeCall!!))
                        val completeDisplay = completeResults.mapIndexed { i, r ->
                            val c = if (i < llmResponse.toolCalls.size) llmResponse.toolCalls[i] else completeCall!!
                            ToolCallDisplay(c.name, c.args, r.success, r.output, 0)
                        }
                        addAiStepMessage(finalReply, llmResponse.toolCalls, completeDisplay, iteration, ApiUsageTracker.getTaskCalls())
                        return@withTimeoutOrNull false // stop the loop
                    }

                    // v3.9.12: Removed plan parsing + plan state injection (was dead code
                    // since v3.9.6 removed Phase 1). Brain plans naturally in its reply text.

                    _state.update {
                        it.copy(apiCallCount = ApiUsageTracker.getTaskCalls())
                    }

                    // v4.0.3: Dynamic-prompt fallback removed (DynamicPromptBuilder deleted).
                    // ContextualPromptBuilder now handles all prompt building.

                    // === If no tool calls → task complete ===
                    if (llmResponse.toolCalls.isEmpty()) {
                        finalReply = llmResponse.reply.ifBlank { "Done." }

                        // v3.5.0: Always learn — save successful workflow for future use.
                        // Now respects the alwaysLearn toggle (was a dead switch before).
                        if (iteration > 1 && workLog.isNotEmpty()
                            && com.ai.agent.llm.AgentThinkingSettings.isAlwaysLearnEnabled(context)) {
                            try {
                                val workflowKey = "workflow_${
                                    userMessage.take(40)
                                        .replace(" ", "_")
                                        .replace(Regex("[^a-zA-Z0-9_]"), "")
                                }"
                                database.remember(workflowKey, workLog.toString().take(500))
                                LogCapture.i(TAG, "Saved workflow: $workflowKey (${workLog.length} chars)")
                            } catch (_: Exception) {
                            }
                        }
                        LogCapture.i(TAG, "✓ No tool calls → task complete after $iteration iterations (${ApiUsageTracker.getTaskCalls()} API calls)")
                        return@withTimeoutOrNull false // task complete, break loop
                    }

                    // === Infinite-loop detection + strategy switching ===
                    // v3.9.7: Exclude verification tools (readScreen, wait) AND navigation tools
                    // (scrollDown, scrollUp, pressBack, pressHome) from the repeat cap.
                    // v3.9.8: Distinguish FAILED repeats from SUCCESSFUL repeats.
                    //   - FAILED repeat (signature in previouslyFailedSignatures) → stop at MAX_REPEATED_TOOLS (2)
                    //     This is a TRUE stuck loop — brain is retrying a failed action.
                    //   - SUCCESSFUL repeat (not in failed set) → inject GUIDANCE, don't stop until SOFT_CAP (4)
                    //     Brain is confused about the next step, not stuck. Give it direction.
                    //   This fixes the issue where brain calls searchInApp(youtube, "X") twice (both succeed)
                    //   and gets stopped with "it's not working" — even though it DID work.
                    val actionTools = llmResponse.toolCalls.filter {
                        it.name != "readScreen" && it.name != "wait" && it.name != "readScreenStructured" &&
                        it.name != "scrollDown" && it.name != "scrollUp" &&
                        it.name != "pressBack" && it.name != "pressHome"
                    }
                    var stuckSignature: String? = null
                    for (call in actionTools) {
                        val signature = "${call.name}(${argsStr(call.args)})"
                        toolCallHistory.add(signature)
                        val count = toolCallHistory.count { it == signature }
                        val isFailedRepeat = signature in previouslyFailedSignatures

                        // v3.8.2: Strategy switch ONLY fires if this signature FAILED in a previous iteration.
                        if (isFailedRepeat) {
                            val alternatives = getStrategyAlternatives(call.name)
                            conversation.append(
                                "\n\n⚠️ STRATEGY SWITCH REQUIRED: \"$signature\" failed before. " +
                                    "Do NOT repeat it with the same args. You have 2 options:\n" +
                                    "1. Use a DIFFERENT TOOL:\n$alternatives\n" +
                                    "2. RE-PLAN: call readScreenStructured() to see the actual screen state, " +
                                    "then choose a different approach based on what you see.\n\n" +
                                    "After 3 different approaches fail, I will STOP the task."
                            )
                            LogCapture.w(TAG, "Strategy switch injected for $signature (previously failed — must switch now)")
                        }

                        // v3.9.8: FAILED repeat → stop at MAX_REPEATED_TOOLS (2).
                        // Brain is retrying a tool that already failed — true stuck loop.
                        if (isFailedRepeat && count >= MAX_REPEATED_TOOLS) {
                            finalReply = "I stopped because I tried \"$signature\" $count times — it's not working. " +
                                "I may be stuck on this step.\n\n$workLog\n\n" +
                                "The task might need a different approach. Try breaking it into smaller steps, " +
                                "or ask me to read the screen and describe what I see."
                            voiceManager.speak(
                                "I'm stuck on a step. I tried the same action $count times and it's not working.",
                                "en"
                            )
                            // Note: stuck-state speak is intentional (not auto-reply), so it ignores ttsEnabled.

                            // v3.5.0: Always learn — save failure lesson. Respects toggle.
                            if (com.ai.agent.llm.AgentThinkingSettings.isAlwaysLearnEnabled(context)) {
                                try {
                                    val failKey = "avoid_${call.name}_${userMessage.take(30).replace(" ", "_")}"
                                    database.remember(
                                        failKey,
                                        "Tried $signature $count times — FAILED. Use alternatives: ${getStrategyAlternatives(call.name)}"
                                    )
                                    Log.i(TAG, "Saved failure lesson: $failKey")
                                } catch (_: Exception) {
                                }
                            }
                            stuckSignature = signature
                            break
                        }

                        // v3.9.8: SUCCESSFUL repeat → inject guidance, don't stop until SOFT_CAP (4).
                        // Brain called a tool that SUCCEEDED before, with the same args.
                        // It's not stuck — it's confused about what to do NEXT.
                        // Give it direction instead of killing the task.
                        if (!isFailedRepeat && count >= 2) {
                            val SOFT_CAP_SUCCESSFUL = 4  // safety valve — if brain ignores guidance 4x, stop
                            if (count >= SOFT_CAP_SUCCESSFUL) {
                                finalReply = "I stopped because I repeated \"$signature\" $count times even though it succeeded. " +
                                    "The task isn't progressing. I may be confused about the next step.\n\n$workLog"
                                stuckSignature = signature
                                break
                            }
                            // Inject guidance — tell the brain what to do INSTEAD of repeating
                            conversation.append(
                                "\n\n⚠️ \"$signature\" ALREADY SUCCEEDED ${count}x. Do NOT call it again.\n" +
                                "The results are already on screen. Your next step should be DIFFERENT:\n" +
                                "- If you searched → call readScreenStructured() to get element bounds, then tap the first result.\n" +
                                "- If you tapped → call readScreen() to verify the result.\n" +
                                "- If you typed → call submitInput() to submit the search.\n" +
                                "Do NOT repeat the same action. Move to the NEXT step."
                            )
                            LogCapture.w(TAG, "Successful repeat detected: $signature (count=$count) — injecting guidance instead of stopping")
                        }
                    }
                    if (stuckSignature != null) {
                        addAiStepMessage(finalReply, emptyList(), emptyList(), iteration, ApiUsageTracker.getTaskCalls(), MessageStatus.ERROR)
                        return@withTimeoutOrNull false // stuck, break loop
                    }

                    // === LLM wants to call tools — execute them (with per-call timing) ===
                    _state.update { it.copy(isThinking = false, isExecuting = true) }
                    var results = executeTimed(llmResponse.toolCalls)

                    // v3.0.9: Auto-expand contextual prompt if AI called a tool not in groups
                    if (contextualEnabled && userMessage.isNotEmpty()) {
                        val missingTools = llmResponse.toolCalls.filter { call ->
                            !com.ai.agent.llm.ContextualPromptBuilder.isToolAvailable(userMessage, call.name)
                        }
                        if (missingTools.isNotEmpty()) {
                            Log.w(TAG, "Contextual auto-expand: AI needs tools not in prompt: ${missingTools.map { it.name }}")
                            for (call in missingTools) {
                                val group = com.ai.agent.llm.ContextualPromptBuilder.findGroupForTool(call.name)
                                if (group != null) {
                                    Log.i(TAG, "Adding group '${group.name}' for tool '${call.name}'")
                                    // v3.6.3: if caching enabled, don't modify systemPrompt —
                                    // inject into conversation instead.
                                    if (cachingEnabled) {
                                        conversation.append("\n\n[EXPANDED]: Added ${group.name} tools. You can now use ${call.name}.")
                                    } else {
                                        systemPrompt = com.ai.agent.llm.ContextualPromptBuilder.buildExpandedPrompt(userMessage, group) + "\n\n## What I Remember About the User\n$memoryStr"
                                    }
                                    // v3.2.9: also expand the tool filter so the next LLM call
                                    // actually exposes the new tool via function-calling.
                                    val currentFilter = contextualToolFilter
                                    if (currentFilter != null) {
                                        contextualToolFilter = (currentFilter + group.tools).distinct()
                                    }
                                    // Add note for AI
                                    conversation.append("\n\n⚠️ EXPANDED: Added ${group.name} tools. You can now use ${call.name}.")
                                }
                            }
                        }
                    }

                    val toolSummary = StringBuilder()
                    toolSummary.append(if (iteration == 1) llmResponse.reply else "Step $iteration: ${llmResponse.reply}")
                    val resultsForLLM = StringBuilder("\n\nI executed these tools:\n")
                    results.forEachIndexed { i, dr ->
                        val call = llmResponse.toolCalls[i]
                        val argsStr = argsStr(call.args)
                        toolSummary.append("\n• ${call.name}($argsStr) → ${if (dr.success) "✓" else "✗"}")
                        // v3.8.2: Track the signature so strategy-switch fires on the NEXT retry.
                        val sig = "${call.name}($argsStr)"
                        if (!dr.success) {
                            toolSummary.append(" ${dr.output.take(200)}")
                            previouslyFailedSignatures.add(sig)
                        }
                        // v3.8.3: Track unique ACTION tools that FAILED for the approach cap.
                        // v4.0.23: CRITICAL FIX — was adding ALL action tools regardless of success.
                        // This caused the "check the screen manually" message to fire on perfectly
                        // valid workflows that used 3+ different action tools (e.g., tap →
                        // clickByText → clickElement, all succeeding). Now ONLY counts FAILED tools.
                        // Exclude SETUP tools (launchApp, searchInApp, openUrl, openInChrome, etc.)
                        // — these are sequential workflow steps, not "approaches."
                        // Only count tools that attempt to accomplish the GOAL (click/play/tap a result):
                        //   tap, clickByText, findElement, submitInput, type, swipe, scrollDown, etc.
                        val isVerificationTool = call.name in setOf("readScreen", "readScreenStructured", "wait", "waitAndContinue")
                        val isSetupTool = call.name in setOf(
                            "launchApp", "searchInApp", "openUrl", "openInChrome", "openActivity",
                            "openDeepLink", "openAppWithData", "listInstalledApps", "listAppActivities",
                            "getAppInfo", "forceStopApp", "openSettings", "listFiles", "readFile",
                            "readContacts", "searchContacts", "getCalendarEvents", "getBatteryLevel",
                            "getCurrentTime", "getNetworkInfo", "getDeviceInfo", "getCurrentLocation",
                            "getWeather", "getVolume", "getBluetoothState", "pingHost", "recall",
                            "recallAll", "recallSimilar", "searchMemory", "listSkills", "getSkill",
                            "executeSkill", "remember",
                            // v3.8.8: Sequential workflow steps — NOT "approaches."
                            // These are steps in a flow (type → submit), not different attempts to click something.
                            "type", "pressEnter", "submitInput", "swipe", "scrollDown", "scrollUp",
                            "pressBack", "pressHome",
                            // v4.0.22: Browser navigation/read tools are SETUP steps, not "approaches."
                            // browserOpen → browserReadStructured → browserGetLinks is a SEQUENTIAL FLOW
                            // (open page → read structure → get links), not 3 different attempts.
                            // Only browserClick/browserFill (actual goal actions) count as approaches.
                            "browserOpen", "browserReadStructured", "browserGetLinks", "browserGetText",
                            "browserListClickable", "browserGetForms", "browserScreenshot", "browserGetUrl",
                            "browserBack", "browserScrollDown", "browserWaitForElement", "browserReadPage",
                            "browserSearch",
                        )
                        // v4.0.23: Only count FAILED action tools toward the approach cap.
                        // A successful workflow with 3+ different action tools is NOT "3 approaches."
                        if (!dr.success && !isVerificationTool && !isSetupTool) {
                            failedApproachTools.add(call.name)
                        }
                        resultsForLLM.append("Tool: ${call.name}($argsStr)\n")
                        // v3.6.2: truncate tool results to 500 chars to slow conversation growth.
                        resultsForLLM.append("Result: ${dr.output.take(500)}\n\n")
                    }

                    // v3.9.12: Removed plan step tracking (was dead code — no plan to track).

                    // v3.8.9: "Screen didn't change" detection — catches "fake success" loops.
                    // v3.9.10: Updated to match new SCREEN STATE block format (was "[Screen after action]: ")
                    // Extract the screen text from the tool results (auto-verify appends SCREEN STATE block)
                    // If the screen is identical to the previous iteration → the brain is stuck.
                    val currentScreenText = results.joinToString("") { it.output }
                        .substringAfter("╔══ SCREEN STATE — CHECK IF TASK IS COMPLETE ══╗\n", "")
                        .substringBefore("╚══════════════════════════════════════════════╝")
                        .take(500)
                    if (currentScreenText.isNotBlank()) {
                        if (currentScreenText == lastScreenText) {
                            sameScreenCount++
                            LogCapture.w(TAG, "Screen didn't change (count=$sameScreenCount) — possible stuck loop")
                            if (sameScreenCount >= MAX_SAME_SCREEN) {
                                // Force re-plan — the brain is stuck on the same screen
                                conversation.append(
                                    "\n\n⚠️ STUCK LOOP DETECTED: You've taken $sameScreenCount actions but the screen hasn't changed. " +
                                        "Your approach isn't working. RE-PLAN NOW: state 'NEW PLAN: 1)... 2)...' with a completely different approach. " +
                                        "The current screen is: ${currentScreenText.take(200)}"
                                )
                                LogCapture.w(TAG, "Stuck loop detected (same screen ${sameScreenCount}x) — forcing re-plan")
                                sameScreenCount = 0 // reset after injecting warning
                            }
                        } else {
                            sameScreenCount = 0 // screen changed — reset
                        }
                        lastScreenText = currentScreenText
                    }

                    // v4.0.23: 3-FAILED-approach cap (only counts FAILED action tools).
                    // v4.0.29: Increased to 10 — user requested more approaches before giving up.
                    // If 10 unique action tools FAILED, STOP — the task isn't converging.
                    // (v3.8.2 bug: was counting ALL tools tried, killing valid workflows.)
                    if (failedApproachTools.size >= 10) {
                        finalReply = "I tried ${failedApproachTools.size} different actions and none succeeded:\n" +
                            failedApproachTools.mapIndexed { i, t -> "  ${i+1}. $t" }.joinToString("\n") + "\n\n" +
                            "The screen might be in an unexpected state, or this task needs a different strategy.\n\n" +
                            "What I tried:\n$workLog\n\n" +
                            "Suggestions: check the screen manually, or describe what you see to me so I can help differently."
                        LogCapture.w(TAG, "${failedApproachTools.size}-FAILED-approach cap reached: $failedApproachTools — stopping task")
                        addAiStepMessage(finalReply, emptyList(), emptyList(), iteration, ApiUsageTracker.getTaskCalls(), MessageStatus.ERROR)
                        return@withTimeoutOrNull false // stop the loop
                    }

                    if (iteration == 1) {
                        workLog.clear()
                        workLog.append(toolSummary)
                    } else {
                        workLog.append("\n\n").append(toolSummary)
                    }

                    // Show iteration progress as a NEW message per step (mirrors v1 v5.0.8 fix).
                    val callCount = ApiUsageTracker.getTaskCalls()
                    addAiStepMessage(toolSummary.toString(), llmResponse.toolCalls, results, iteration, callCount)

                    // Append results to conversation for the next LLM call.
                    conversation.append("\n\n[Tool results from step $iteration]:")
                    conversation.append(resultsForLLM)
                    // v3.6.2: removed per-iteration instruction (was ~50 tokens repeated
                    // every step). This instruction is now in the system prompt once.
                    conversation.append("\n")  // minimal separator

                    // Trim old history to prevent conversation from growing too long.
                    if (conversation.length > MAX_CONVERSATION_CHARS) {
                        val originalMsg = userMessage
                        val recentHistory = conversation.substring(conversation.length - 6000)
                        conversation.clear()
                        conversation.append(originalMsg)
                        conversation.append("\n\n[Previous steps omitted. Most recent steps:]")
                        conversation.append(recentHistory)
                    }

                    _state.update { it.copy(apiCallCount = callCount) }
                    delay(300)
                    return@withTimeoutOrNull true // step completed, continue loop
                }

                when (stepResult) {
                    null -> {
                        // Step timed out — surface a message and let the next
                        // iteration try a (hopefully) faster path.
                        LogCapture.w(TAG, "Step $iteration timed out after ${STEP_TIMEOUT_MS}ms")
                        addAiStepMessage(
                            "⏱️ Step $iteration timed out after 30s — retrying with a simpler approach.",
                            emptyList(),
                            emptyList(),
                            iteration,
                            ApiUsageTracker.getTaskCalls(),
                            MessageStatus.ERROR,
                        )
                        conversation.append(
                            "\n\n[Step $iteration timed out after 30 seconds. " +
                                "Try a simpler approach or fewer tool calls.]"
                        )
                    }
                    false -> {
                        // Task complete or stuck — break out of the loop.
                        break
                    }
                    true -> {
                        // Step completed normally — fall through to the next iteration.
                    }
                }
            }

            if (finalReply.isEmpty()) {
                finalReply = if (iteration >= MAX_ITERATIONS && !_state.value.stopRequested) {
                    "I reached the maximum of $MAX_ITERATIONS steps. Here's what I accomplished:\n" +
                        "$workLog\n\nThe task might need more steps — try asking me to continue, " +
                        "or break it into smaller parts."
                } else {
                    workLog.toString().ifBlank { "Done." }
                }
            }

            // Final reply as a separate message (does NOT overwrite step messages).
            addAiFinalMessage(finalReply)
            // v2.2.0 Issue 113b — save under current session.
            try {
                database.addConversationToSession(_state.value.currentSessionId, "ai", finalReply)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to persist AI reply: ${e.message}")
            }
            database.logAction("Processed: $userMessage → $finalReply")
            // v2.0.4: Only speak if user explicitly enabled TTS (default OFF).
            if (_state.value.ttsEnabled) {
                voiceManager.speak(finalReply.take(500), "en")
            }

            // Task 40 — prompt the user to save this workflow as a skill if it was
            // a sufficiently complex run (3+ iterations with a non-empty workLog).
            if (iteration >= 3 && workLog.isNotBlank() && finalReply.isNotEmpty()) {
                maybePromptSaveSkill(userMessage, workLog.toString(), iteration)
            }

        } catch (e: CancellationException) {
            // User pressed Stop or sent a new message — not a real error.
            if (!_state.value.stopRequested) {
                val msg = "Task interrupted (a new message was sent or the task was stopped). " +
                    "Last progress:\n$workLog"
                addAiFinalMessage(msg)
                try {
                    database.addConversationToSession(_state.value.currentSessionId, "ai", msg)
                } catch (_: Exception) {}
            }
            throw e
        } catch (e: Exception) {
            LogCapture.e(TAG, "Agent loop failed: ${e.message ?: e::class.simpleName}")
            val msg = "Error: ${e.message ?: e::class.simpleName}"
            _state.update { it.copy(error = msg, isThinking = false, isExecuting = false) }
            addAiFinalMessage(msg, MessageStatus.ERROR)
            try {
                database.addConversationToSession(_state.value.currentSessionId, "ai", msg)
            } catch (_: Exception) {}
        } finally {
            // v2.2.2: Notify AgentService that task completed (updates notification)
            com.ai.agent.service.AgentService.notifyTaskCompleted()
            // v4.0.10: Hide the floating Stop overlay (task is done)
            com.ai.agent.service.TaskStopOverlay.hide()
            _state.update {
                it.copy(
                    isThinking = false,
                    isExecuting = false,
                    currentIteration = 0,
                    stopRequested = false,
                )
            }
        }
    }

    /**
     * Execute each tool call individually so we can time each one.
     * Returns ToolCallDisplay list (with success / output / durationMs).
     *
     * We use `toolExecutor.executeTools(listOf(call))` per call so we don't have
     * to modify the (frozen) backend — executeTool is private.
     */
    private suspend fun executeTimed(toolCalls: List<LLMClient.ToolCall>): List<ToolCallDisplay> {
        // v3.7.0: Extended auto-verification — not just high-stakes, but ALL action tools.
        // After tap/type/swipe/launchApp etc., automatically readScreen + append to result.
        // This eliminates separate readScreen() calls (saves 3-5 API calls per task).
        // actionChain already has its own auto-verify built in — skip it here.
        val ACTION_TOOLS = setOf(
            "openWhatsAppChat", "sendSMS", "sendEmail", "deleteFile", "callContact",
            "launchApp", "tap", "clickByText", "clickElement", "submitInput", "pressBack", "pressHome",
            "scrollDown", "scrollUp", "swipe", "type",
            "browserOpen", "browserClick", "browserClickText", "browserFill", "browserSearch",
            "openActivity", "openDeepLink", "searchInApp", "openYouTubeVideo",
        )
        val VERIFY_DELAY_MS = 1500L  // v3.7.0: reduced from 2000 to 1500 (faster)

        val out = mutableListOf<ToolCallDisplay>()
        for (call in toolCalls) {
            val start = System.currentTimeMillis()
            val results = toolExecutor.executeTools(listOf(call))
            val elapsed = System.currentTimeMillis() - start
            var r = results.firstOrNull()

            // v3.7.0: Auto-verify after ALL action tools (was only high-stakes).
            // Skip actionChain — it has its own auto-verify built in.
            // v3.8.6: Use toolExecutor.readScreen (respects vision mode) instead of a11y.readScreen.
            //         This fixes the "57 chars" bug — when VLM mode is ON, the brain now SEES the screen.
            // v3.9.10: Format auto-verify as VISUALLY DISTINCT block so brain can't ignore it.
            //          Was buried in tool result string — brain skipped reading it.
            //          Now uses ╔══ borders + "CHECK IF TASK IS COMPLETE" prompt.
            if (call.name in ACTION_TOOLS && call.name != "actionChain" && r?.success == true
                && com.ai.agent.llm.AgentThinkingSettings.isVerificationEnabled(context)) {
                try {
                    kotlinx.coroutines.delay(VERIFY_DELAY_MS)
                    // v3.8.6: Call readScreen through toolExecutor so it respects vision mode.
                    // Was: a11y.readScreen() — only accessibility text, ignoring VLM mode.
                    val readCall = LLMClient.ToolCall("readScreen", emptyMap())
                    val readResults = toolExecutor.executeTools(listOf(readCall))
                    val screenText = readResults.firstOrNull()?.output ?: ""
                    if (screenText.isNotEmpty()) {
                        val baseOutput = r.output.take(200)
                        // v3.9.10: Visually distinct block — impossible to miss
                        val combinedOutput = "$baseOutput\n\n" +
                            "╔══ SCREEN STATE — CHECK IF TASK IS COMPLETE ══╗\n" +
                            "${screenText.take(500)}\n" +
                            "╚══════════════════════════════════════════════╝\n" +
                            "If the task is ALREADY COMPLETE (you see proof above — e.g., video playing, message sent), " +
                            "call complete(success=true, reason=\"...\") NOW. " +
                            "Do NOT proceed to the next step if the task is already done."
                        r = ToolExecutor.ToolResult(
                            success = r.success,
                            output = combinedOutput,
                        )
                        LogCapture.i(TAG, "Auto-verify: readScreen after ${call.name} (${screenText.length} chars): $screenText")
                    }
                } catch (e: Exception) {
                    LogCapture.w(TAG, "Auto-verify failed (non-fatal): ${e.message}")
                }
            }

            out.add(
                ToolCallDisplay(
                    name = call.name,
                    args = call.args,
                    success = r?.success == true,
                    output = r?.output ?: "(no result)",
                    durationMs = elapsed,
                )
            )
        }
        return out
    }

    /** Pretty-print tool args: "key=value, key=value". */
    private fun argsStr(args: Map<String, Any>): String {
        if (args.isEmpty()) return ""
        return args.entries.joinToString(", ") { "${it.key}=${it.value}" }
    }

    /** Append a per-step AI message (with tool calls) to the chat. */
    private fun addAiStepMessage(
        text: String,
        toolCalls: List<LLMClient.ToolCall>,
        results: List<ToolCallDisplay>,
        iteration: Int,
        apiCalls: Int,
        status: MessageStatus = MessageStatus.EXECUTING,
    ) {
        // v3.9.1: Show the brain's FULL reasoning text, not just a short header.
        // The brain's reply is its "thinking" — the user should see it in the chat.
        val header = "[$iteration/$MAX_ITERATIONS] [API: $apiCalls calls]"
        val msg = ChatMessage(
            text = "$header\n$text",  // Show full reply text below the header
            isUser = false,
            toolCalls = results,
            status = status,
        )
        _state.update { s -> s.copy(messages = s.messages + msg) }
        // v2.2.1: Save step messages to DB so they persist across app restarts.
        // v4.0.17: Fixed — was saving only header, now saves header + text (brain's reply).
        try {
            database.addConversationToSession(
                _state.value.currentSessionId,
                "ai_step",
                "$header\n$text".take(2000),  // save full step text, not just header
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save step message: ${e.message}")
        }
    }

    /** Append the final AI message (no tools, just the reply). */
    private fun addAiFinalMessage(text: String, status: MessageStatus = MessageStatus.SUCCESS) {
        val msg = ChatMessage(text = text, isUser = false, status = status)
        _state.update { s -> s.copy(messages = s.messages + msg) }
    }

    /**
     * v6.1.1: Strategy switching — returns alternative tools to try when a tool fails.
     * Injected into the conversation after 2 failures of the same tool.
     */
    private fun getStrategyAlternatives(toolName: String): String {
        return when (toolName) {
            "browserClickText" -> "Try: tap(x, y) with coordinates from analyzeScreen, " +
                "or use browserClickElement with index, or readScreen to see what's actually there."
            "browserFill" -> "Try: tap on the field first, then type(text). " +
                "Or use browserEval with document.querySelector(...).value = '...'"
            "tap" -> "Try: analyzeScreen first to find correct coordinates. " +
                "Or use browserClickText if in browser. Or use accessibility tap."
            "type" -> "Try: tap the field first to focus it, then type. Or use browserFillForm."
            "launchApp" -> "Try: searchInApp instead, or open via browser."
            else -> "Try a different approach, or call readScreen to see what's on screen."
        }
    }

    companion object {
        private const val TAG = "ChatViewModel"
        private const val MAX_ITERATIONS = 80           // v4.0.29: was 50 → 80 (more room for complex tasks)
        private const val MAX_REPEATED_TOOLS = 3        // v4.0.29: was 2 → 3 (allow 3 retries before stopping)
        private const val MAX_CONVERSATION_CHARS = 8000
        const val ATTACHMENTS_DIR = "/storage/emulated/0/Documents/ai-workspace/attachments"
        /** Task 40 — user-created skills live here so SkillManager picks them up. */
        const val USER_SKILLS_DIR = "/storage/emulated/0/Documents/ai-workspace/skills"
    }
}

// ============================================================================
// Factory — instantiates the VM with all of its carried-over backend deps.
// ============================================================================

class ChatViewModelFactory(
    private val context: Context,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val appContext = context.applicationContext
        val llmClient = LLMClient(appContext)
        val toolExecutor = ToolExecutor(appContext)
        val database = AgentDatabase(appContext)
        val voiceManager = VoiceManager(appContext)
        return ChatViewModel(appContext, llmClient, toolExecutor, database, voiceManager) as T
    }
}
