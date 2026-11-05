package com.ai.agent.llm.levels

import android.content.Context
import android.util.Log
import com.ai.agent.llm.ContextualPromptBuilder
import com.ai.agent.llm.LLMClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

object LevelManager {
    private const val TAG = "LevelManager"
    const val USE_CAPABILITY_LEVELS = true

    // v4.0.73: Layer 3 timeout — 3 seconds. If the classifier is slow or fails, skip.
    private const val INTENT_CLASSIFIER_TIMEOUT_MS = 3000L

    // v4.0.73: Combined approach — merges capability levels (budget) with
    // ContextualPromptBuilder (relevance). When true, classifyLevelWithContext()
    // is used instead of classifyLevel(), and ChatViewModel uses contextual
    // groups to pick WHICH tools to send (capped by the level's budget).
    // When false, falls back to pure LevelManager (v4.0.72 behavior).
    // v4.0.75: Now reads from AIProvider (user-configurable in Settings).
    val USE_COMBINED_APPROACH: Boolean
        get() = com.ai.agent.llm.AIProvider.DEFAULT_USE_COMBINED_APPROACH  // fallback; real check in ChatViewModel via context

    // v4.0.73: Layer 3 — LLM intent classifier for ambiguous messages.
    // v4.0.75: Now reads from AIProvider (user-configurable in Settings).
    val USE_LLM_INTENT_CLASSIFIER: Boolean
        get() = com.ai.agent.llm.AIProvider.DEFAULT_USE_LLM_INTENT  // fallback; real check via context

    // v4.0.74: On-Demand Architecture — sends only meta-tools + quick-access upfront.
    // v4.0.75: Now reads from AIProvider (user-configurable in Settings).
    val USE_ON_DEMAND_ARCHITECTURE: Boolean
        get() = com.ai.agent.llm.AIProvider.DEFAULT_USE_ON_DEMAND  // fallback; real check via context

    // v4.0.73: Tool budgets per level — caps the number of tools sent.
    // Level 1: 15 tools (basic), Level 2: 100 tools (standard), Level 3: all (null = no cap)
    fun getToolBudget(level: Int): Int? = when (level) {
        1 -> 15
        2 -> 100
        else -> null  // Level 3 = all tools, no cap
    }

    // v4.0.74: On-Demand Architecture — sends only meta-tools + quick-access upfront.
    // Model searches for the rest via searchTools. Saves 60-80% tokens.
    // When false → sends all tools at the level (v4.0.73 behavior).
    // v4.0.75: Now reads from AIProvider (user-configurable in Settings).
    // (USE_ON_DEMAND_ARCHITECTURE getter defined above near USE_COMBINED_APPROACH)

    // v4.0.74: Meta-tools — always available in on-demand mode.
    // These are the "discovery" tools the model uses to find what it needs.
    val META_TOOLS = listOf(
        "searchTools", "recallMemory", "getSkill", "escalateLevel",
        "createCustomTool", "listCustomTools", "deleteCustomTool",
        "todoWrite", "delegateTask", "speak", "complete",
        "listTerminals", "listSkills", "searchMemory",
        "recallConversationStep", "summarizeProgress",  // v4.0.76: conversation memory
        // v4.0.79: MCP (Model Context Protocol) tools
        "searchMcpServers", "getMcpServerInfo", "connectMcpServer",
        "disconnectMcpServer", "listMcpServers", "listMcpTools", "callMcpTool",
    )

    // v4.0.74: Quick-access tools — always available (most common actions).
    // These are the tools needed for almost every task.
    val QUICK_ACCESS_TOOLS = listOf(
        "launchApp", "readScreen", "readScreenStructured", "clickElement",
        "type", "scrollDown", "scrollUp", "pressBack", "pressHome", "pressEnter",
        "getBatteryLevel", "getCurrentTime", "getNetworkInfo",
        "writeFile", "readFile", "listFiles", "runShellCommand",
        "wait", "remember", "recall", "recallSimilar", "recallAll",
    )

    // v4.0.74: Combined upfront tools for on-demand mode.
    // Meta-tools + quick-access = ~35 tools (~1200 tokens) vs 125-158 (~5000-6300 tokens)
    val ON_DEMAND_UPFRONT_TOOLS: List<String> = META_TOOLS + QUICK_ACCESS_TOOLS

    @Volatile
    var currentLevel: Int = 2

    val LEVEL_1_TOOLS = listOf(
        "speak", "getCurrentTime", "getBatteryLevel", "setBrightness",
        "getVolume", "setVolume", "getNetworkInfo",
        "createRule", "listRules", "deleteRule", "modifyRule",
        "remember", "recall", "recallSimilar", "recallAll", "searchMemory", "recallMemory",
        "recallConversationStep", "summarizeProgress",
        // v4.0.79: MCP tools — always available (when MCP is enabled)
        "searchMcpServers", "getMcpServerInfo", "connectMcpServer",
        "disconnectMcpServer", "listMcpServers", "listMcpTools", "callMcpTool",
        "writeFile", "readFile", "listFiles",
        "searchTools", "escalateLevel", "getSkill", "listSkills",
        "listNotifications", "listTerminals",
        "todoWrite", "delegateTask",
        "wait", "complete",
    )

    val LEVEL_2_TOOLS = LEVEL_1_TOOLS + listOf(
        "readScreen", "readScreenStructured", "tap", "clickByText", "clickElement",
        "type", "swipe", "scrollDown", "scrollUp", "pressBack", "pressHome",
        "pressEnter", "submitInput", "actionChain", "longClick", "longClickElement",
        "focusElement", "paste", "edit", "takeScreenshotToGallery",
        "launchApp", "listInstalledApps", "searchInApp", "openUrl", "openInChrome",
        "openWhatsAppChat", "playSpotify", "openInstagramProfile", "openYouTubeVideo",
        "openTelegramChat", "openGoogleSearch", "shareText", "shareFile",
        "browserOpen", "browserSearch", "browserReadStructured", "browserReadPage",
        "browserGetLinks", "browserClickText", "browserClickElement", "browserGetText",
        "browserListClickable", "browserScreenshot", "browserGetUrl", "browserBack",
        "browserScrollDown", "browserFillForm", "browserFill", "browserClick",
        "downloadFile", "copyFile", "moveFile", "deleteFile", "createDirectory",
        "mediaPlayPause", "mediaNext", "mediaPrevious", "takePhoto",
        "readContacts", "searchContacts", "getCalendarEvents", "createCalendarEvent",
        "setAlarm", "setTimer", "sendSMS", "sendEmail", "composeEmail",
        "makePhoneCall", "callContact", "getCallLog",
        "getCurrentLocation", "getWeather", "openMaps",
        "replyToNotification",
        "getDeviceInfo", "getBluetoothState", "pingHost", "setWallpaper",
        "toggleFlashlight", "getClipboard", "setClipboard",
        // v4.0.76: Hardware Tools
        "scanWifiNetworks", "scanBleDevices", "getWifiState", "toggleBluetooth",
        "listUsbDevices", "getUsbDeviceInfo",
        // v4.0.77: Hardware Tools (NFC + BLE advanced + IR)
        "readNfcTag", "writeNfcTag", "listNfcHistory",
        "findAirTag", "scanBleBeacons", "logBleDevice",
        "hasIrBlaster", "irSend", "irSendPreset",
        "saveToKnowledge", "recallKnowledge", "listKnowledge",
        "analyzeScreen", "findElement", "translateText",
        "webSearch", "fetchPageText", "makeHttpRequest",
        "createCustomTool", "listCustomTools", "deleteCustomTool",
        "getConversationHistory", "getFullMemory", "getConversationSummary",
    )

    val LEVEL_3_TOOLS: List<String>? = null

    @Volatile
    var CUSTOM_TOOL_NAMES: Set<String> = emptySet()

    fun classifyLevel(userMessage: String): Int {
        val msg = userMessage.lowercase().trim()
        val level3Keywords = listOf("terminal", "build", "install ", "python", "node", "gradle", "compile", "script", "shell", "pkg ", "pip ", "npm ", "git ", "runonterminal", "connectterminal", "androidide", "termux", "apk", "java ", "remove background", "rembg", "pillow", "opencv", "run script")
        if (level3Keywords.any { msg.contains(it) }) return 3
        val level1Keywords = listOf("hi", "hello", "hey", "thanks", "thank you", "what can you do", "help", "battery", "brightness", "what time", "current time", "set alarm", "set timer", "volume", "wifi", "bluetooth", "create rule", "list rule", "delete rule", "modify rule", "remember", "recall", "what do you remember", "list notification", "check notification")
        if (level1Keywords.any { msg.contains(it) }) return 1
        return 2
    }

    /**
     * v4.0.73: Combined classification — uses BOTH signals:
     *   - ContextualPromptBuilder groups (relevance — which tools does this task need?)
     *   - LevelManager keywords (complexity — how hard is this task?)
     *
     * Returns a level (1/2/3) that controls the PROMPT TEMPLATE size.
     * The TOOL SET is computed separately in ChatViewModel using contextual groups
     * (capped by getToolBudget(level)).
     *
     * Logic:
     *   1. If ContextualPromptBuilder says no-tools (greeting) → Level 1 (tiny prompt)
     *   2. If LevelManager keywords match Level 3 (terminal/build) → Level 3 (full prompt)
     *   3. If contextual groups matched AND task is simple → Level 2 (standard prompt)
     *   4. If no contextual groups matched → Level 3 (complex/unknown, needs full prompt)
     *
     * This replaces the old EITHER/OR approach. Both signals are used together.
     */
    fun classifyLevelWithContext(userMessage: String, contextualGroups: List<String>): Int {
        val msg = userMessage.lowercase().trim()

        // Rule 1: Greeting → Level 1 (ultra-tiny, no tools)
        if (ContextualPromptBuilder.isTier1NoTools(userMessage)) return 1

        // Rule 2: Level 3 keywords (terminal/build/script) → Level 3 (full prompt)
        // These tasks need the full system_prompt_smart.txt with thinking instructions
        val level3Keywords = listOf("terminal", "build", "install ", "python", "node", "gradle",
            "compile", "script", "shell", "pkg ", "pip ", "npm ", "git ", "runonterminal",
            "connectterminal", "androidide", "termux", "apk", "java ",
            "remove background", "rembg", "pillow", "opencv", "run script")
        if (level3Keywords.any { msg.contains(it) }) return 3

        // Rule 3: Contextual groups matched → standard task → Level 2
        // The groups tell us WHICH tools to send; Level 2 gives the thinking prompt
        if (contextualGroups.isNotEmpty()) return 2

        // Rule 4: No groups matched → complex/unknown → Level 3 (send all tools + full prompt)
        return 3
    }

    // ========================================================================
    // v4.0.73: 3-LAYER INTENT DETECTION
    // ========================================================================
    // Layer 0: Greeting check (free, already in classifyLevelWithContext)
    // Layer 1: Level 3 keywords (free, already in classifyLevelWithContext)
    // Layer 1.5: Contextual groups (free, passed in as parameter)
    // Layer 2: Action-verb + comparison-pattern detection (free, new)
    // Layer 3: LLM intent classifier (~100 tokens, only for ambiguous, new)
    //
    // WHY: Keyword matching can't tell if the user wants to USE a tool or just
    // TALK about it. "What's the difference between sendSMS and email?" matches
    // "sms" + "email" keywords, but the user wants a comparison, not to send.
    // Without Layer 2+3, Aira would inject Contacts + Communication tools (~60
    // tools, ~2400 tokens) and the model might call sendSMS — bad outcome.
    //
    // Layer 2 catches obvious cases ("difference between", "how does", "don't").
    // Layer 3 catches ambiguous cases ("I was reading about sendSMS") via a
    // cheap 100-token LLM call.
    // ========================================================================

    /**
     * Layer 2: Patterns that indicate a CONCEPTUAL question, not an action.
     * If any of these appear in the message AND tool keywords matched,
     * treat it as a question → Level 1 (reasoning mode, no tools).
     */
    private val COMPARISON_PATTERNS = listOf(
        // Direct comparison
        "difference between", " vs ", " vs.", " versus ", "compare", "comparison",
        "which is better", "pros and cons", "trade-off", "tradeoff", "tradeoffs",
        // Explanation requests
        "what is", "what's", "how does", "how do", "how is", "how are",
        "why does", "why do", "why is", "why are",
        "explain", "describe", "tell me about", "tell me how",
        // Capability questions
        "can you", "could you", "are you able", "do you have",
        // Meta-discussion (past tense = not action)
        "was reading", "were reading", "was thinking", "were thinking",
        "was wondering", "were wondering", "i read about", "i heard about",
        // Prohibition ("don't sendSMS" = not an action)
        "don't ", "do not ", "never ", "stop ", "avoid ",
    )

    /**
     * Layer 2: Action verbs that indicate the user wants to EXECUTE something.
     * If the message starts with one of these, it's likely a real action.
     */
    private val ACTION_VERBS = listOf(
        "send", "open", "play", "call", "create", "delete", "modify", "update",
        "read", "write", "check", "set", "toggle", "search", "find", "look",
        "download", "upload", "share", "reply", "schedule", "remind",
        "post", "tweet", "message", "text", "email", "screenshot",
        "install", "build", "run", "start", "stop", "pause", "resume",
        "take", "get", "show", "list", "add", "remove", "enable", "disable",
    )

    /**
     * v4.0.73 Layer 2: Check if the message is a conceptual question (not action).
     *
     * Returns true if the message contains comparison/explanation/prohibition
     * patterns. This catches ~85% of conceptual-question misfires for FREE
     * (no extra LLM call needed).
     *
     * Examples caught:
     *   "What's the difference between sendSMS and email?" → true (comparison)
     *   "How does webSearch work?" → true (explanation)
     *   "Don't ever sendSMS without asking" → true (prohibition)
     *   "Explain how createRule differs from a skill" → true (explanation)
     *
     * Examples NOT caught (need Layer 3):
     *   "I was reading about sendSMS" → no pattern match (ambiguous)
     *   "Can you sendSMS?" → "can you" matches but might be action
     *   "sendSMS sometimes fails" → no pattern (ambiguous)
     */
    fun isConceptualQuestion(msg: String): Boolean {
        val msgLower = msg.lowercase().trim()
        // Check for comparison/explanation/prohibition patterns
        if (COMPARISON_PATTERNS.any { msgLower.contains(it) }) return true
        return false
    }

    /**
     * v4.0.73 Layer 2: Check if the message has clear action intent.
     *
     * Returns true if the message starts with an action verb.
     * This confirms the user wants to EXECUTE something, not just talk about it.
     *
     * Examples:
     *   "sendSMS to Mom" → true (starts with "send")
     *   "play BLACKPINK on YouTube" → true (starts with "play")
     *   "What's the difference between sendSMS and email" → false (starts with "what")
     */
    fun hasActionIntent(msg: String): Boolean {
        val msgLower = msg.lowercase().trim()
        return ACTION_VERBS.any { verb ->
            msgLower.startsWith(verb + " ") || msgLower.startsWith(verb + "!")
        }
    }

    /**
     * v4.0.73 Layer 3: LLM intent classifier for ambiguous messages.
     *
     * Makes a ~100-token Tier 1 LLM call to classify intent:
     *   ACTION      → user wants to execute something
     *   QUESTION    → user wants an explanation
     *   COMPARISON  → user wants to compare options
     *   PROHIBITION → user is telling you NOT to do something
     *
     * Cost: ~100 input + 1 output = ~101 tokens per ambiguous message.
     * Only fires for ~5-10% of messages (the ambiguous ones Layer 2 can't classify).
     * Timeout: 3 seconds — if it fails, returns null (caller defaults to ACTION).
     *
     * Savings: prevents ~4000-token misfires (wrong tools + wrong API calls).
     * Net: saves ~3900 tokens per caught misfire, costs ~100 per ambiguous check.
     */
    suspend fun llmClassifyIntent(userMessage: String, context: Context): String? {
        // v4.0.75: Check AIProvider flag (user-configurable)
        if (!com.ai.agent.llm.AIProvider.isLlmIntentEnabled(context)) return null
        // v4.0.73: Layer 3 timeout — 3 seconds. If the classifier is slow or fails, skip.
        return withContext(Dispatchers.IO) {
            try {
                withTimeoutOrNull(INTENT_CLASSIFIER_TIMEOUT_MS) {
                    val llm = LLMClient(context)
                    val prompt = """Classify the intent of this message to a phone AI assistant:

"$userMessage"

Reply with ONE word only:
- ACTION (user wants to execute something — send a message, open an app, play music)
- QUESTION (user wants an explanation — how does X work, what is X)
- COMPARISON (user wants to compare options — X vs Y, which is better)
- PROHIBITION (user is telling you NOT to do something — don't, never)

Reply:"""
                    val response = llm.chat(prompt, "You are an intent classifier. Reply with ONE word only: ACTION, QUESTION, COMPARISON, or PROHIBITION.", emptyList())
                    val intent = response.reply.trim().uppercase().split("\\s+".toRegex()).firstOrNull() ?: ""
                    Log.i(TAG, "Layer 3 intent classifier: '$userMessage' -> $intent")
                    when {
                        intent.startsWith("ACTION") -> "ACTION"
                        intent.startsWith("QUESTION") -> "QUESTION"
                        intent.startsWith("COMPARISON") -> "COMPARISON"
                        intent.startsWith("PROHIBITION") -> "PROHIBITION"
                        else -> null  // couldn't classify
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Layer 3 intent classifier failed: ${e.message}")
                null  // fail safe — caller defaults to ACTION
            }
        }
    }

    /**
     * v4.0.73: Combined classification WITH 3-layer intent detection.
     *
     * This is the SUSPEND version — uses all 3 layers:
     *   Layer 0: Greeting → Level 1 (free)
     *   Layer 1: Level 3 keywords (terminal/build) → Level 3 (free)
     *   Layer 1.5: Contextual groups (passed in) (free)
     *   Layer 2: Action-verb / comparison-pattern detection (free)
     *   Layer 3: LLM intent classifier (~100 tokens, only if Layer 2 ambiguous)
     *
     * When Layer 2 detects a conceptual question (comparison/explanation/prohibition):
     *   → Level 1 (reasoning mode, no tools) — saves ~2400 tokens of wrong tools
     *
     * When Layer 2 is ambiguous (no clear action or comparison):
     *   → Layer 3 LLM call classifies intent (~100 tokens)
     *   → If QUESTION/COMPARISON/PROHIBITION → Level 1
     *   → If ACTION or classifier fails → Level 2 (send tools)
     *
     * Call this from a coroutine (ChatViewModel.runAgentLoop is already suspend).
     */
    suspend fun classifyLevelWithIntent(userMessage: String, contextualGroups: List<String>, context: Context): Int {
        val msg = userMessage.lowercase().trim()

        // Layer 0: Greeting → Level 1 (ultra-tiny, no tools)
        if (ContextualPromptBuilder.isTier1NoTools(userMessage)) return 1

        // Layer 1: Level 3 keywords (terminal/build/script) → Level 3 (full prompt)
        // These are always action — user wants to build/run/compile
        val level3Keywords = listOf("terminal", "build", "install ", "python", "node", "gradle",
            "compile", "script", "shell", "pkg ", "pip ", "npm ", "git ", "runonterminal",
            "connectterminal", "androidide", "termux", "apk", "java ",
            "remove background", "rembg", "pillow", "opencv", "run script")
        if (level3Keywords.any { msg.contains(it) }) return 3

        // Layer 1.5: No contextual groups matched → complex/unknown → Level 3
        if (contextualGroups.isEmpty()) return 3

        // Groups matched. Now check: is this ACTION or CONCEPTUAL?
        // Layer 2: Free pattern detection
        val isConceptual = if (com.ai.agent.llm.AIProvider.isPatternDetectionEnabled(context)) isConceptualQuestion(msg) else false
        val hasAction = hasActionIntent(msg)

        if (isConceptual && !hasAction) {
            // OBVIOUS conceptual question → Level 1 (reasoning, no tools)
            Log.i(TAG, "Layer 2: conceptual question detected → Level 1 (saves ~2400 tokens)")
            return 1
        }

        if (hasAction && !isConceptual) {
            // OBVIOUS action → Level 2 (send tools)
            Log.i(TAG, "Layer 2: clear action intent → Level 2")
            return 2
        }

        // AMBIGUOUS — Layer 2 couldn't decide. Try Layer 3 (LLM classifier).
        if (com.ai.agent.llm.AIProvider.isLlmIntentEnabled(context)) {
            val intent = llmClassifyIntent(userMessage, context)
            if (intent != null) {
                return when (intent) {
                    "ACTION" -> { Log.i(TAG, "Layer 3: ACTION → Level 2"); 2 }
                    "QUESTION", "COMPARISON", "PROHIBITION" -> {
                        Log.i(TAG, "Layer 3: $intent → Level 1 (saves ~2400 tokens)")
                        1
                    }
                    else -> 2  // default to action
                }
            }
            // Layer 3 failed (timeout/error) → default to ACTION (Level 2)
            Log.w(TAG, "Layer 3 failed → defaulting to Level 2 (ACTION)")
            return 2
        }

        // Layer 3 disabled → default to Level 2 (action)
        return 2
    }

    fun getPromptForLevel(level: Int, context: Context): String {
        // v4.0.74: On-Demand mode uses the tiny level0.txt prompt
        // v4.0.75: Now reads from AIProvider (user-configurable)
        if (com.ai.agent.llm.AIProvider.isOnDemandEnabled(context)) {
            return try { context.assets.open("level0.txt").bufferedReader().use { it.readText() } }
            catch (e: Exception) { context.assets.open("system_prompt_smart.txt").bufferedReader().use { it.readText() } }
        }
        return when (level) {
            1 -> try { context.assets.open("level1.txt").bufferedReader().use { it.readText() } } catch (e: Exception) { context.assets.open("system_prompt_smart.txt").bufferedReader().use { it.readText() } }
            2 -> try { context.assets.open("level2.txt").bufferedReader().use { it.readText() } } catch (e: Exception) { context.assets.open("system_prompt_smart.txt").bufferedReader().use { it.readText() } }
            else -> try { context.assets.open("system_prompt_smart.txt").bufferedReader().use { it.readText() } } catch (e: Exception) { "You are Aira. Respond in JSON." }
        }
    }

    fun getToolsForLevel(level: Int): List<String>? {
        return when (level) { 1 -> LEVEL_1_TOOLS; 2 -> LEVEL_2_TOOLS; else -> LEVEL_3_TOOLS }
    }

    fun isToolAvailable(toolName: String, level: Int): Boolean {
        val tools = getToolsForLevel(level) ?: return true
        return toolName in tools || toolName in CUSTOM_TOOL_NAMES
    }

    fun findLevelForTool(toolName: String): Int {
        if (LEVEL_1_TOOLS.contains(toolName) || CUSTOM_TOOL_NAMES.contains(toolName)) return 1
        if (LEVEL_2_TOOLS.contains(toolName)) return 2
        return 3
    }

    fun escalateToLevel(targetLevel: Int): String {
        if (targetLevel <= currentLevel) return "Already at Level $currentLevel."
        val oldLevel = currentLevel
        currentLevel = targetLevel
        Log.i(TAG, "Escalated: Level $oldLevel -> Level $targetLevel")
        return when (targetLevel) {
            2 -> "\n\n[LEVEL 2 UNLOCKED — Screen, app, browser, file, media tools now available. Also: On Failure (STOP, think WHY, try DIFFERENT approach). Shortcuts: searchInApp, playSpotify, openWhatsAppChat. Guardrails: NEVER guess coords, verify before complete.]\n"
            3 -> "\n\n[LEVEL 3 UNLOCKED — Terminal tools now available. runShellCommand = SANDBOX (no pkg/python/node). runOnTerminal = Terminal server (has pkg, python3, node, git). RULE: need pkg/python/node -> use runOnTerminal. Custom tools: createCustomTool, listCustomTools. Full guardrails active.]\n"
            else -> ""
        }
    }

    fun resetForNewTask() { currentLevel = 2 }
}
