package com.ai.agent.llm.levels

import android.content.Context
import android.util.Log
import com.ai.agent.llm.ContextualPromptBuilder

object LevelManager {
    private const val TAG = "LevelManager"
    const val USE_CAPABILITY_LEVELS = true

    // v4.0.73: Combined approach — merges capability levels (budget) with
    // ContextualPromptBuilder (relevance). When true, classifyLevelWithContext()
    // is used instead of classifyLevel(), and ChatViewModel uses contextual
    // groups to pick WHICH tools to send (capped by the level's budget).
    // When false, falls back to pure LevelManager (v4.0.72 behavior).
    const val USE_COMBINED_APPROACH = true

    // v4.0.73: Tool budgets per level — caps the number of tools sent.
    // Level 1: 15 tools (basic), Level 2: 100 tools (standard), Level 3: all (null = no cap)
    fun getToolBudget(level: Int): Int? = when (level) {
        1 -> 15
        2 -> 100
        else -> null  // Level 3 = all tools, no cap
    }

    @Volatile
    var currentLevel: Int = 2

    val LEVEL_1_TOOLS = listOf(
        "speak", "getCurrentTime", "getBatteryLevel", "setBrightness",
        "getVolume", "setVolume", "getNetworkInfo",
        "createRule", "listRules", "deleteRule", "modifyRule",
        "remember", "recall", "recallSimilar", "recallAll", "searchMemory",
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

    fun getPromptForLevel(level: Int, context: Context): String {
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
