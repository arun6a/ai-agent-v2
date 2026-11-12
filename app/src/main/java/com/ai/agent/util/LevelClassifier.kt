package com.ai.agent.util

/**
 * v4.0.99-collab5: LevelClassifier — pure classification logic extracted from
 * LevelManager so the level/intent classification can be unit-tested on the
 * JVM without Context/assets. LevelManager should delegate the pure methods
 * here (wiring GAP below).
 *
 * Extracted functions (faithful copies of LevelManager v4.0.73-v4.0.75):
 *  - getToolBudget(level)
 *  - classifyLevel(userMessage)                       [PURE — keyword only]
 *  - classifyLevelWithContext(msg, contextualGroups)  [delegates isTier1NoTools]
 *  - isConceptualQuestion(msg)
 *  - hasActionIntent(msg)
 *  - findLevelForTool(toolName)
 *  - isToolAvailable(toolName, level, customToolNames)
 *  - getToolsForLevel(level)
 *
 * ===== DOCUMENTED BUGS / GAPS (found while extracting — for the other AI) =====
 *
 * GAP-1 (HIGH): classifyLevel() and classifyLevelWithContext() use SUBSTRING
 *     matching (`msg.contains(keyword)`) for level-1 keywords. The keywords
 *     "hi" and "hey" appear as substrings of common English words:
 *       - "thinking" contains "hi"      → Level 1 (greeting). WRONG.
 *       - "this" contains "hi"          → Level 1. WRONG.
 *       - "his" contains "hi"           → Level 1. WRONG.
 *       - "they" contains "hey"        → Level 1. WRONG.
 *       - "hip" / "hippo" contains "hi"→ Level 1. WRONG.
 *     Real impact: a user saying "I was thinking about dinner" gets the
 *     Level-1 greeting prompt (no tools, no thinking) instead of Level 2.
 *     The AI cannot act. Reproducible in collab5 tests
 *     `GAP-1 thinking triggers Level 1 greeting` and
 *     `GAP-1 they triggers Level 1 greeting`.
 *     FIX: use word-boundary regex (`\bhi\b`) or match the whole token.
 *
 * GAP-2 (LOW): classifyLevel() matches "build" (no trailing space) as a
 *     Level-3 keyword. So "building a house" / "I built a thing" → Level 3
 *     (terminal mode). Not harmful (Level 3 just unlocks more tools) but
 *     misclassifies intent. Compare "install " / "pkg " / "pip " which
 *     DO require trailing space — inconsistent.
 *
 * GAP-3 (INFO): level3 keyword list has both "install " (trailing space) and
 *     "build" (no space). If someone fixes GAP-2 by adding a space to
 *     "build ", they'll also need to handle "build!" / "build." / "build?".
 *     Consider word-boundary regex instead of trailing-space hacks.
 *
 * GAP-4 (MEDIUM): hasActionIntent() only matches verbs at the START of the
 *     message. "Please send a message" → false (starts with "please").
 *     "Can you open Chrome?" → false (starts with "can"). This causes many
 *     real action messages to be classified as ambiguous → Layer-3 LLM call
 *     (~100 tokens wasted). A leading-word skip list ("please", "can you",
 *     "could you", "hey") would fix it.
 *
 * GAP-5 (LOW): isConceptualQuestion() matches "can you" (in COMPARISON_PATTERNS)
 *     as a conceptual question. But "can you sendSMS?" is an action request,
 *     not a comparison. Combined with GAP-4, "can you open Chrome?" is BOTH
 *     conceptual (matches "can you") AND not-action (doesn't start with verb)
 *     → classified as conceptual → Level 1. The user can't get their action
 *     executed. Reproducible: `GAP-5 can you open Chrome is conceptual`.
 *
 * GAP-6 (INFO): classifyLevelWithContext() calls ContextualPromptBuilder
 *     .isTier1NoTools() for the greeting check. LevelClassifier.classifyLevel
 *     (the pure version) does NOT do this — it only does keyword matching.
 *     So the two classify functions can disagree on the same input. The
 *     pure version is more conservative (fewer Level-1 results).
 *
 * GAP-7 (INFO): findLevelForTool() returns 1 for any CUSTOM_TOOL_NAMES entry
 *     — even tools the user created that conceptually belong to Level 2 or 3.
 *     This means custom tools are always available at Level 1, which may
 *     overwhelm the Level-1 prompt budget (15 tools). Not a bug per se,
 *     but worth knowing.
 */
object LevelClassifier {

    /** Pure port of LevelManager.getToolBudget. */
    fun getToolBudget(level: Int): Int? = when (level) {
        1 -> 15
        2 -> 100
        else -> null  // Level 3 = all tools, no cap
    }

    /**
     * Pure port of LevelManager.classifyLevel (keyword-only, no Context).
     * GAP-1, GAP-2, GAP-3 apply.
     */
    fun classifyLevel(userMessage: String): Int {
        val msg = userMessage.lowercase().trim()
        val level3Keywords = listOf(
            "terminal", "build ", "install ", "python", "node", "gradle", "compile",  // v4.0.101: "build" → "build " (BUG 2 fix)
            "script", "shell", "pkg ", "pip ", "npm ", "git ", "runonterminal",
            "connectterminal", "androidide", "termux", "apk", "java ",
            "remove background", "rembg", "pillow", "opencv", "run script"
        )
        if (level3Keywords.any { msg.contains(it) }) return 3
        // v4.0.101 BUG 1 FIX: word-boundary matching for short keywords that substring-match
        val level1ShortKeywords = listOf("hi", "hey", "help")
        val level1PhraseKeywords = listOf(
            "hello", "thanks", "thank you", "what can you do",
            "battery", "brightness", "what time", "current time", "set alarm",
            "set timer", "volume", "wifi", "bluetooth", "create rule",
            "list rule", "delete rule", "modify rule", "remember", "recall",
            "what do you remember", "list notification", "check notification"
        )
        val wordBoundary = Regex("\\b(?:${level1ShortKeywords.joinToString("|")})\\b")
        if (wordBoundary.containsMatchIn(msg) || level1PhraseKeywords.any { msg.contains(it) }) return 1
        return 2
    }

    /**
     * Pure port of LevelManager.classifyLevelWithContext MINUS the
     * ContextualPromptBuilder.isTier1NoTools call (GAP-6).
     * Pass isTier1NoToolsResult explicitly.
     */
    fun classifyLevelWithContext(
        userMessage: String,
        contextualGroups: List<String>,
        isTier1NoToolsResult: Boolean = false,
    ): Int {
        val msg = userMessage.lowercase().trim()
        if (isTier1NoToolsResult) return 1
        val level3Keywords = listOf(
            "terminal", "build ", "install ", "python", "node", "gradle",  // v4.0.101: "build" → "build " (BUG 2 fix)
            "compile", "script", "shell", "pkg ", "pip ", "npm ", "git ", "runonterminal",
            "connectterminal", "androidide", "termux", "apk", "java ",
            "remove background", "rembg", "pillow", "opencv", "run script"
        )
        if (level3Keywords.any { msg.contains(it) }) return 3
        if (contextualGroups.isNotEmpty()) return 2
        return 3
    }

    /** Pure port of LevelManager.COMPARISON_PATTERNS check. */
    private val COMPARISON_PATTERNS = listOf(
        "difference between", " vs ", " vs.", " versus ", "compare", "comparison",
        "which is better", "pros and cons", "trade-off", "tradeoff", "tradeoffs",
        "what is", "what's", "how does", "how do", "how is", "how are",
        "why does", "why do", "why is", "why are",
        "explain", "describe", "tell me about", "tell me how",
        "can you", "could you", "are you able", "do you have",
        "was reading", "were reading", "was thinking", "were thinking",
        "was wondering", "were wondering", "i read about", "i heard about",
        "don't ", "do not ", "never ", "stop ", "avoid ",
    )

    /** Pure port of LevelManager.ACTION_VERBS. */
    private val ACTION_VERBS = listOf(
        "send", "open", "play", "call", "create", "delete", "modify", "update",
        "read", "write", "check", "set", "toggle", "search", "find", "look",
        "download", "upload", "share", "reply", "schedule", "remind",
        "post", "tweet", "message", "text", "email", "screenshot",
        "install", "build", "run", "start", "stop", "pause", "resume",
        "take", "get", "show", "list", "add", "remove", "enable", "disable",
    )

    fun isConceptualQuestion(msg: String): Boolean {
        val msgLower = msg.lowercase().trim()
        // v4.0.101 BUG 3 FIX: action requests are not conceptual.
        if (hasActionIntent(msg)) return false
        if (COMPARISON_PATTERNS.any { msgLower.contains(it) }) return true
        return false
    }

    fun hasActionIntent(msg: String): Boolean {
        var msgLower = msg.lowercase().trim()
        // v4.0.101 BUG 3 FIX: Strip leading pleasantries before checking action verbs.
        val pleasantries = listOf("can you ", "could you ", "please ", "hey ", "hi ", "hello ")
        var changed = true
        while (changed) {
            changed = false
            for (p in pleasantries) {
                if (msgLower.startsWith(p)) {
                    msgLower = msgLower.substring(p.length)
                    changed = true
                    break
                }
            }
        }
        return ACTION_VERBS.any { verb ->
            msgLower.startsWith(verb + " ") || msgLower.startsWith(verb + "!") ||
            msgLower.startsWith(verb + "?") || msgLower == verb
        }
    }

    /** Level-1 tool list (faithful copy of LevelManager.LEVEL_1_TOOLS). */
    val LEVEL_1_TOOLS: List<String> = listOf(
        "speak", "getCurrentTime", "getBatteryLevel", "setBrightness",
        "getVolume", "setVolume", "getNetworkInfo",
        "createRule", "listRules", "deleteRule", "modifyRule",
        "remember", "recall", "recallSimilar", "recallAll", "searchMemory", "recallMemory",
        "recallConversationStep", "summarizeProgress",
        "searchMcpServers", "getMcpServerInfo", "connectMcpServer",
        "disconnectMcpServer", "listMcpServers", "listMcpTools", "callMcpTool",
        "writeFile", "readFile", "listFiles",
        "searchTools", "escalateLevel", "getSkill", "listSkills",
        "listNotifications", "listTerminals",
        "todoWrite", "delegateTask",
        "wait", "complete",
    )

    /** Level-2 = Level-1 + the full Level-2 extension (faithful copy). */
    val LEVEL_2_TOOLS: List<String> = LEVEL_1_TOOLS + listOf(
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
        "scanWifiNetworks", "scanBleDevices", "getWifiState", "toggleBluetooth",
        "listUsbDevices", "getUsbDeviceInfo",
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

    fun getToolsForLevel(level: Int): List<String>? = when (level) {
        1 -> LEVEL_1_TOOLS
        2 -> LEVEL_2_TOOLS
        else -> LEVEL_3_TOOLS
    }

    fun isToolAvailable(toolName: String, level: Int, customToolNames: Set<String> = emptySet()): Boolean {
        val tools = getToolsForLevel(level) ?: return true
        return toolName in tools || toolName in customToolNames
    }

    fun findLevelForTool(toolName: String, customToolNames: Set<String> = emptySet()): Int {
        if (LEVEL_1_TOOLS.contains(toolName) || customToolNames.contains(toolName)) return 1
        if (LEVEL_2_TOOLS.contains(toolName)) return 2
        return 3
    }
}
