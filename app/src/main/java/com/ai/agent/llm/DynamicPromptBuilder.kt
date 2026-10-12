package com.ai.agent.llm

import android.content.Context
import android.util.Log
import java.io.File

/**
 * DynamicPromptBuilder (v6.3.0) — builds a system prompt dynamically based on
 * the user's message. Only includes relevant tools + tips → fewer tokens → more API calls.
 *
 * Modes:
 * - "dynamic" (default): Analyzes message, includes relevant tools only + fallback
 * - "smart": Full prompt (all tools + tips + examples) — ~11K tokens
 * - "balanced": Trimmed prompt (core tools + key rules) — ~1.5K tokens
 * - "fast": Minimal prompt (tool names + 8 rules) — ~750 tokens
 * - "custom": User-edited prompt from storage
 */
object DynamicPromptBuilder {

    private const val TAG = "DynamicPromptBuilder"

    // Core prompt — ALWAYS included (all modes)
    private const val CORE_PROMPT = """You are an AI agent controlling an Android phone. Respond in JSON only.

## Response Format
{"reply":"short msg","tool_calls":[{"name":"...","args":{...}}]}
Complete: {"reply":"Done!","tool_calls":[]}

## Core Rules
1. BATCH read-only tools. NEVER batch actions.
2. Verify with readScreen before saying "Done!"
3. If tool fails → try different tool (2 tries before giving up)
4. After type() in search → submitInput() then readScreen()
5. NEVER announce without executing — "I'll tap X" MUST include tap() in tool_calls
6. recallSimilar(request) at start. remember("workflow_X","steps") on success.
7. readScreen is 3-tier: accessibility text → ML Kit OCR (free, offline) → VLM API (last resort)
8. Multi-step tasks: continue until done or user input needed

## When to use WHAT (CRITICAL):
- RESEARCH (weather, news, find info) → webSearch + fetchPageText (BACKGROUND, no browser opens, user stays on chat)
- INTERACTION (order, fill form, click buttons) → browserOpen + browserClickText (VISIBLE browser, user sees)
- APP CONTROL (play YouTube, send WhatsApp) → searchInApp / launchApp (accessibility)
- SHORTCUT first → searchInApp(1 call), playSpotify(1 call), openWhatsAppChat(1 call) before manual steps"""

    // Tool categories with keywords that trigger inclusion
    private val toolCategories = listOf(
        ToolCategory(
            name = "Screen",
            keywords = listOf("screen", "tap", "click", "type", "swipe", "scroll", "press", "read screen"),
            tools = "Screen: readScreen, readScreenStructured, tap(x,y), clickByText, type, swipe, scrollDown, scrollUp, pressBack, pressHome, pressEnter, submitInput, lockScreen"
        ),
        ToolCategory(
            name = "Apps",
            keywords = listOf("open", "launch", "app", "install", "stop", "uninstall", "settings"),
            tools = "Apps: launchApp(pkg), listInstalledApps, getAppInfo, forceStopApp, openSettings(page), shareText, shareFile"
        ),
        ToolCategory(
            name = "Shortcuts",
            keywords = listOf("play", "youtube", "spotify", "whatsapp", "instagram", "telegram", "email", "call", "maps", "search in"),
            tools = """Shortcuts (USE FIRST — save 4-5 calls):
searchInApp(pkg,query) — search YouTube/Spotify (1 call)
playSpotify(query) — play on Spotify (1 call)
openWhatsAppChat(phone,msg?) — open WhatsApp chat (1 call)
openInstagramProfile(user) | openTelegramChat(user) | openMapsLocation(q) | composeEmail(to,subj,body) | makePhoneCall(num)
openYouTubeVideo(id) — NEVER guess ID, use searchInApp+tap instead"""
        ),
        ToolCategory(
            name = "Browser",
            keywords = listOf("browser", "website", "order", "buy", "purchase", "form", "page", "url", "click on", "kutty", "amazon", "flipkart"),
            tools = """Browser (VISIBLE — user sees what you do):
browserOpen(url) | browserReadStructured() | browserClickText(text) | browserClickElement(sel) | browserFillForm(field,val) | browserListClickable() | browserScreenshot(prompt?) | browserScrollDown() | browserBack() | browserSearch(q)
Call browserOpen ONLY ONCE per site. Use browserBack for navigation.
After readScreenStructured returns bounds "x1,y1,x2,y2" → tap center: ((x1+x2)/2, (y1+y2)/2)"""
        ),
        ToolCategory(
            name = "Web",
            keywords = listOf("search", "weather", "news", "research", "find info", "google", "look up", "what is", "who is", "how to"),
            tools = "Web (BACKGROUND — no browser): webSearch(query), fetchPageText(url), makeHttpRequest(url), downloadFile(url,path)"
        ),
        ToolCategory(
            name = "Memory",
            keywords = listOf("remember", "recall", "memory", "forget", "know", "my name", "my city"),
            tools = "Memory: remember(key,val), recall(key), recallAll, recallSimilar(query), searchMemory(query)"
        ),
        ToolCategory(
            name = "Knowledge",
            keywords = listOf("knowledge", "document", "save as", "learn from", "pdf", "doc", "read this"),
            tools = "Knowledge: saveToKnowledge(name,content), recallKnowledge(query), listKnowledge"
        ),
        ToolCategory(
            name = "Files",
            keywords = listOf("file", "photo", "attach", "image", "download", "share", "send file", "picture"),
            tools = "Files: listFiles(path), readFile, writeFile, deleteFile, listAttachments, readAttachment(filename), sendAttachmentTo(pkg,filename,caption?), shareFile"
        ),
        ToolCategory(
            name = "Rules",
            keywords = listOf("remind", "every", "each", "daily", "when", "schedule", "rule", "trigger", "alarm", "timer"),
            tools = """Rules: createRule(name,triggerType,triggerValue,action,days?), listRules, deleteRule, modifyRule
Triggers: time(HH:MM), notification(pkg:name), battery_low(%), charging, incoming_call, sms_received, headset_connected, screen_on/off, user_unlocked, wifi_connected/disconnected, app_installed/uninstalled, bluetooth_connected/disconnected, location_entered/exited, app_opened/closed, battery_full, storage_low, airplane_mode, day_changed, calendar_event_starting, + more"""
        ),
        ToolCategory(
            name = "Contacts",
            keywords = listOf("contact", "call", "phone", "sms", "message", "dial", "email"),
            tools = "Contacts: readContacts, searchContacts(name), callContact(num), sendSMS(num,msg), getCallLog, sendEmail(to,subj,body), openDialer, makePhoneCall"
        ),
        ToolCategory(
            name = "Calendar",
            keywords = listOf("calendar", "meeting", "event", "schedule", "appointment"),
            tools = "Calendar: getCalendarEvents, createCalendarEvent(title,start,dur), setAlarm(h,m), setTimer(sec,msg)"
        ),
        ToolCategory(
            name = "Device",
            keywords = listOf("battery", "wifi", "bluetooth", "volume", "brightness", "flashlight", "device", "time", "location", "network", "storage"),
            tools = "Device: getBatteryLevel, getCurrentTime, getNetworkInfo, getDeviceInfo, getVolume, setVolume, setBrightness, toggleFlashlight, toggleWifi, getBluetoothState, pingHost, getCurrentLocation, openMaps"
        ),
        ToolCategory(
            name = "Media",
            keywords = listOf("music", "song", "play media", "pause", "next track", "photo", "camera", "screenshot"),
            tools = "Media: mediaPlayPause, mediaNext, mediaPrevious, takePhoto, takeScreenshotToGallery"
        ),
        ToolCategory(
            name = "Vision",
            keywords = listOf("see", "look at", "describe screen", "find element", "what's on", "analyze", "translate"),
            tools = "Vision: analyzeScreen(prompt), findElement(desc), translateText(text,targetLang)"
        ),
        ToolCategory(
            name = "Notification",
            keywords = listOf("notification", "reply", "whatsapp reply", "auto reply", "telegram reply"),
            tools = "Notification: replyToNotification(pkg,msg) — reply without opening app"
        ),
        ToolCategory(
            name = "Skills",
            keywords = listOf("skill", "create skill", "delete skill", "list skill"),
            tools = """Skills: executeSkill(name), listSkills, createSkill(name,desc,trigger,instr), deleteSkill, getSkill
Triggers: morning briefing, check price, send whatsapp, play music, order food, book cab, send email, translate, research, backup photos, remind me, call, share file, read news, check traffic, scan QR, pay bill, score, teach me, summarize day"""
        ),
        ToolCategory(
            name = "Async",
            keywords = listOf("wait", "continue", "later", "follow up", "check back"),
            tools = "Async: wait(seconds), waitAndContinue(seconds,reason)"
        ),
        ToolCategory(
            name = "Terminal",
            keywords = listOf("terminal", "shell", "command", "run command", "cmd", "execute command", "root", "su "),
            tools = "Terminal: runShellCommand(command) — runs shell commands (may need root for some commands)"
        )
    )

    // App-specific tips (included only when relevant)
    private val appTips = listOf(
        AppTip(
            keywords = listOf("youtube", "play on youtube", "search youtube"),
            tip = "YouTube: search at tap(970,180). NEVER clickByText(\"Search\"). Use searchInApp(\"com.google.android.youtube\",query)→readScreenStructured→tap(first video)."
        ),
        AppTip(
            keywords = listOf("whatsapp", "message", "send whatsapp"),
            tip = "WhatsApp: search at tap(970,200). NEVER clickByText(\"Search\"). Use openWhatsAppChat(phone,msg) for 1-call send."
        ),
        AppTip(
            keywords = listOf("instagram", "insta"),
            tip = "Instagram: use openInstagramProfile(username) — 1 call."
        ),
        AppTip(
            keywords = listOf("spotify", "play music", "play song"),
            tip = "Spotify: use playSpotify(query) — 1 call, auto-plays."
        )
    )

    /**
     * Build the system prompt based on mode + user message.
     */
    fun buildPrompt(context: Context, userMessage: String): String {
        val mode = AIProvider.getPromptMode(context)
        Log.i(TAG, "Building prompt: mode=$mode, message='${userMessage.take(50)}'")

        return when (mode) {
            "smart" -> loadFromAssets(context, "system_prompt_smart.txt")
            "balanced" -> loadFromAssets(context, "system_prompt_balanced.txt")
            "fast" -> loadFromAssets(context, "system_prompt_fast.txt")
            "custom" -> loadCustomPrompt(context)
            "dynamic" -> buildDynamic(userMessage)
            else -> loadFromAssets(context, "system_prompt_smart.txt")  // default to smart
        }
    }

    /**
     * Dynamic prompt — analyzes user message, includes only relevant tools + tips.
     */
    private fun buildDynamic(userMessage: String): String {
        val msg = userMessage.lowercase()
        val sb = StringBuilder()

        // 1. Core prompt (always included)
        sb.append(CORE_PROMPT)

        // 2. Relevant tools
        sb.append("\n\n## Available Tools\n")
        var anyMatched = false
        for (category in toolCategories) {
            if (category.keywords.any { msg.contains(it) }) {
                sb.append(category.tools).append("\n")
                anyMatched = true
            }
        }

        // If no specific category matched, include all shortcuts (most common tools)
        if (!anyMatched) {
            sb.append("Shortcuts: searchInApp, playSpotify, openWhatsAppChat, launchApp\n")
            sb.append("Device: getBatteryLevel, getCurrentTime, getNetworkInfo\n")
            sb.append("Memory: remember, recall, recallSimilar\n")
            sb.append("Web: webSearch, fetchPageText\n")
        }

        // 3. Always include memory + device (most common)
        if (!msg.contains("battery") && !msg.contains("time") && !msg.contains("remember") && !msg.contains("recall")) {
            sb.append("Quick: getBatteryLevel, getCurrentTime, remember(key,val), recall(key), recallSimilar(query)\n")
        }

        // 4. Relevant app tips
        sb.append("\n## App Tips\n")
        var anyTip = false
        for (tip in appTips) {
            if (tip.keywords.any { msg.contains(it) }) {
                sb.append(tip.tip).append("\n")
                anyTip = true
            }
        }
        if (!anyTip) {
            sb.append("Icon-only UIs: readScreenStructured() first → hardcoded coords → findElement (VLM) last.\n")
        }

        // 5. Skills (always mention triggers — they're short)
        sb.append("\n## Skills: executeSkill(name) for: morning briefing, play music, order food, book cab, send email, research, remind me, call, share file, read news, check traffic, scan QR, pay bill, teach me, summarize day\n")

        val result = sb.toString()
        Log.i(TAG, "Dynamic prompt built: ${result.length} bytes, ~${result.length / 4} tokens")
        return result
    }

    private fun loadFromAssets(context: Context, fileName: String): String {
        return try {
            context.assets.open(fileName).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.w(TAG, "Could not load $fileName, falling back to system_prompt.txt")
            context.assets.open("system_prompt.txt").bufferedReader().use { it.readText() }
        }
    }

    private fun loadCustomPrompt(context: Context): String {
        val customFile = File("/storage/emulated/0/Documents/ai-workspace/system_prompt_custom.txt")
        return if (customFile.exists()) {
            Log.i(TAG, "Loading custom prompt from ${customFile.absolutePath}")
            customFile.readText()
        } else {
            Log.w(TAG, "Custom prompt file not found, falling back to smart")
            loadFromAssets(context, "system_prompt_smart.txt")
        }
    }

    /**
     * Estimate token count for a prompt.
     */
    fun estimateTokens(prompt: String): Int {
        return prompt.length / 4  // rough estimate: 4 chars ≈ 1 token
    }

    /**
     * Get estimated daily calls based on prompt size + provider.
     */
    fun estimateDailyCalls(promptTokens: Int, providerId: String): Int {
        val dailyLimit = when (providerId) {
            "groq" -> 500_000
            "openrouter" -> 500_000  // varies
            "zai" -> 300_000
            "gemini" -> 1_000_000
            else -> 500_000
        }
        val tokensPerCall = promptTokens + 2000  // prompt + conversation + tool results
        return dailyLimit / tokensPerCall
    }

    private data class ToolCategory(
        val name: String,
        val keywords: List<String>,
        val tools: String
    )

    private data class AppTip(
        val keywords: List<String>,
        val tip: String
    )
}
