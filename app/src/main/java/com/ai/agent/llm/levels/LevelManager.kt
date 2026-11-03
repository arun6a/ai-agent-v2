package com.ai.agent.llm.levels

import android.content.Context
import android.util.Log

object LevelManager {
    private const val TAG = "LevelManager"
    const val USE_CAPABILITY_LEVELS = true

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
