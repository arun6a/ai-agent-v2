package com.ai.agent.llm

import android.content.Context
import android.util.Log

/**
 * v3.0.8: ContextualPromptBuilder — selects relevant tool groups based on user message.
 *
 * 3-tier system:
 *   Tier 1: "Hi" / "Hello" → ultra-tiny prompt (100 bytes, no tools)
 *   Tier 2: Contextual prompt (only relevant tool groups, 1.5-3K tokens)
 *   Tier 3: Full prompt (all tools, 11K tokens) — fallback for complex tasks
 *
 * If AI calls a tool not in the contextual prompt → auto-expand (add that group + retry).
 *
 * This replaces: Dynamic Prompt (old keyword system) + Prompt Caching (made AI blind).
 */
object ContextualPromptBuilder {
    private const val TAG = "ContextualPrompt"

    data class ToolGroup(
        val name: String,
        val tools: List<String>,
        val toolDescs: List<String>,  // one-line description per tool
        val keywords: List<String>,
    )

    // Always-included tools (cheap, useful in any context)
    private val ALWAYS_TOOLS = listOf(
        "speak(text)" to "speak text aloud via TTS",
        "getCurrentTime()" to "get current date/time",
        "getBatteryLevel()" to "get battery percentage",
        "remember(key, value)" to "save to memory",
        "recall(key)" to "recall from memory",
        // v3.9.6: complete tool — ALWAYS available so brain can signal task completion
        "complete(success, reason)" to "call when task is DONE. Verify via readScreen first. success=true/false + reason why.",
    )

    private val TOOL_GROUPS = listOf(
        ToolGroup(
            name = "Screen",
            tools = listOf("readScreen", "readScreenStructured", "tap", "clickByText", "clickElement",
                "longClick", "longClickElement", "focusElement", "paste", "edit",
                "type", "swipe", "scrollDown", "scrollUp", "pressBack", "pressHome", "pressEnter", "submitInput", "lockScreen"),
            toolDescs = listOf(
                "readScreen() — read all text on screen (3-tier: a11y → ML Kit → VLM)",
                "readScreenStructured() — get JSON tree of screen elements with bounds + index (use with clickElement)",
                "tap(x, y) — tap screen coordinates (PREFER clickElement instead — no guessing)",
                "clickByText(text) — click element by visible text",
                "clickElement(index) — click element by index from readScreenStructured (PREFERRED — no coordinate guessing)",
                "longClick(x, y) / longClickElement(index) — long press (context menus, drag)",
                "focusElement(index) — focus an element before typing (some apps need this)",
                "paste(index) — paste clipboard into an element by index",
                "edit(index, text, autoSubmit?) — type + optional submit in ONE call (saves API calls)",
                "type(text) — type text into focused field",
                "swipe(x1, y1, x2, y2) — swipe gesture",
                "scrollDown() / scrollUp() — scroll screen",
                "pressBack() / pressHome() / pressEnter() — system buttons",
                "submitInput() — submit current form/search",
                "lockScreen() — lock the phone",
            ),
            keywords = listOf("tap", "click", "type", "swipe", "scroll", "screen", "button", "press", "back", "home", "read screen", "what's on", "describe"),
        ),
        ToolGroup(
            name = "Apps",
            tools = listOf("launchApp", "listInstalledApps", "getAppInfo", "forceStopApp", "openSettings", "openActivity", "openDeepLink", "listAppActivities"),
            toolDescs = listOf(
                "launchApp(package) — open an app",
                "listInstalledApps() — list all installed apps",
                "getAppInfo(package) — get app details",
                "forceStopApp(package) — force stop an app",
                "openSettings(page) — open system settings",
                "openActivity(package, activity) — open specific activity",
                "openDeepLink(uri, package?) — open deep link",
                "listAppActivities(package) — list activities in an app",
            ),
            keywords = listOf("open", "launch", "start", "app", "install", "uninstall", "settings", "activity", "deep link"),
        ),
        ToolGroup(
            name = "Shortcuts",
            tools = listOf("searchInApp", "playSpotify", "openWhatsAppChat", "openYouTubeVideo", "openInstagramProfile", "openTelegramChat", "openGoogleSearch", "openMapsLocation", "composeEmail", "makePhoneCall", "openContact", "shareToApp", "openInChrome", "searchInChrome"),
            toolDescs = listOf(
                "searchInApp(package, query) — search inside YouTube/Spotify/etc (1 call)",
                "playSpotify(query) — play on Spotify (1 call)",
                "openWhatsAppChat(phone, msg?) — open WhatsApp chat (1 call)",
                "openYouTubeVideo(id) — open YouTube video",
                "openInstagramProfile(user) — open Instagram profile (1 call)",
                "openTelegramChat(user) — open Telegram chat (1 call)",
                "openMapsLocation(query) — open Google Maps (1 call)",
                "composeEmail(to, subject, body) — open email composer (1 call)",
                "makePhoneCall(number) — make a phone call (1 call)",
            ),
            keywords = listOf("play", "youtube", "spotify", "whatsapp", "instagram", "telegram", "maps", "email", "call", "search in app", "share"),
        ),
        ToolGroup(
            name = "Browser",
            tools = listOf("browserOpen", "browserReadStructured", "browserClickText", "browserClickElement", "browserFillForm", "browserFill", "browserClick", "browserGetText", "browserListClickable", "browserGetLinks", "browserGetForms", "browserScreenshot", "browserScrollDown", "browserEval", "browserBack", "browserGetUrl", "browserSearch", "browserWaitForElement", "browserReadPage"),
            toolDescs = listOf(
                "browserOpen(url) — open website in visible browser",
                "browserReadStructured() — get page structure (links, buttons, forms, text)",
                "browserClickText(text) — click element by visible text",
                "browserClickElement(selector) — click by CSS selector",
                "browserFillForm(field, value) — fill form field by label/name",
                "browserGetText(selector) — get text from element",
                "browserListClickable() — list all clickable elements with bounds",
                "browserScrollDown() — scroll page down",
                "browserBack() — go back",
                "browserGetUrl() — get current URL",
                "browserSearch(query) — open Google search in browser",
                "browserScreenshot(prompt?) — take screenshot + optional VLM analysis",
                "browserEval(script) — run JavaScript on page",
            ),
            keywords = listOf("browse", "website", "url", "page", "order", "buy", "purchase", "form", "click on", "amazon", "flipkart", "kutty", "site", "navigate"),
        ),
        ToolGroup(
            name = "Web",
            tools = listOf("webSearch", "fetchPageText", "makeHttpRequest", "downloadFile", "openUrl"),
            toolDescs = listOf(
                "webSearch(query, fetchTop=0) — search web (fetchTop=N gets full pages inline, 1 call)",
                "fetchPageText(url) — fetch URL, return clean text (no browser opens)",
                "makeHttpRequest(url) — raw HTTP call",
                "downloadFile(url, path) — download file to storage",
                "openUrl(url) — open URL in system browser",
            ),
            keywords = listOf("search", "find", "research", "information", "google", "look up", "what is", "who is", "how to", "news", "price", "download", "http"),
        ),
        ToolGroup(
            name = "Contacts",
            tools = listOf("readContacts", "searchContacts", "callContact", "sendSMS", "getCallLog"),
            toolDescs = listOf(
                "readContacts() — list all contacts",
                "searchContacts(name) — search contacts by name",
                "callContact(number) — make a call",
                "sendSMS(number, message) — send SMS",
                "getCallLog() — recent call history",
            ),
            keywords = listOf("contact", "call", "phone", "sms", "text", "dial", "message", "call log"),
        ),
        ToolGroup(
            name = "Calendar",
            tools = listOf("getCalendarEvents", "createCalendarEvent", "setAlarm", "setTimer"),
            toolDescs = listOf(
                "getCalendarEvents() — today's calendar events",
                "createCalendarEvent(title, start, duration) — create event",
                "setAlarm(hour, minute) — set alarm",
                "setTimer(seconds, message) — set timer",
            ),
            keywords = listOf("calendar", "meeting", "event", "schedule", "appointment", "alarm", "timer", "remind", "wake"),
        ),
        ToolGroup(
            name = "Device",
            tools = listOf("getBluetoothState", "getNetworkInfo", "getDeviceInfo", "getVolume", "setVolume", "setBrightness", "toggleFlashlight", "toggleWifi", "pingHost"),
            toolDescs = listOf(
                "getVolume() / setVolume(level) — media volume (0-15)",
                "setBrightness(level) — screen brightness (0-100)",
                "toggleFlashlight() — toggle flashlight",
                "toggleWifi() — toggle WiFi",
                "getBluetoothState() — check Bluetooth",
                "getNetworkInfo() — WiFi/network status",
                "getDeviceInfo() — phone model, OS version",
                "pingHost(host) — ping network host",
            ),
            keywords = listOf("battery", "wifi", "bluetooth", "volume", "brightness", "flashlight", "torch", "device", "network", "storage", "ping"),
        ),
        ToolGroup(
            name = "Location",
            tools = listOf("getCurrentLocation", "openMaps", "getWeather"),
            toolDescs = listOf(
                "getCurrentLocation() — GPS coordinates",
                "openMaps(query) — open Google Maps",
                "getWeather() — current weather + 3-day forecast (GPS+Open-Meteo, 1 call)",
            ),
            keywords = listOf("where", "location", "maps", "weather", "temperature", "forecast", "rain", "directions", "nearby"),
        ),
        ToolGroup(
            name = "Files",
            tools = listOf("listFiles", "readFile", "writeFile", "copyFile", "moveFile", "deleteFile", "createDirectory", "listAttachments", "readAttachment", "sendAttachmentTo"),
            toolDescs = listOf(
                "listFiles(path) — list files in directory",
                "readFile(path) — read text file",
                "writeFile(path, content) — write text file",
                "copyFile(src, dst) / moveFile(src, dst) — copy/move file",
                "deleteFile(path) — delete file",
                "createDirectory(path) — create folder",
            ),
            keywords = listOf("file", "photo", "image", "attach", "download", "save", "folder", "document", "picture"),
        ),
        ToolGroup(
            name = "Media",
            tools = listOf("mediaPlayPause", "mediaNext", "mediaPrevious", "takePhoto", "takeScreenshotToGallery"),
            toolDescs = listOf(
                "mediaPlayPause() — play/pause media",
                "mediaNext() / mediaPrevious() — skip tracks",
                "takePhoto() — take a photo",
                "takeScreenshotToGallery() — take screenshot + save to gallery",
            ),
            keywords = listOf("music", "song", "play media", "pause", "next track", "photo", "camera", "screenshot", "picture"),
        ),
        ToolGroup(
            name = "Rules",
            tools = listOf("createRule", "listRules", "deleteRule", "modifyRule"),
            toolDescs = listOf(
                "createRule(name, triggerType, triggerValue, action, days?) — create automation rule",
                "listRules() — list all rules",
                "deleteRule(id) — delete rule",
                "modifyRule(id, ...) — modify rule",
            ),
            keywords = listOf("rule", "when", "every", "each", "daily", "schedule", "trigger", "automate", "auto-reply", "reminder"),
        ),
        ToolGroup(
            name = "Notification",
            tools = listOf("replyToNotification"),
            toolDescs = listOf(
                "replyToNotification(package, message) — reply to WhatsApp/Telegram without opening app",
            ),
            keywords = listOf("notification", "reply", "whatsapp reply", "auto reply", "respond"),
        ),
        ToolGroup(
            name = "Terminal",
            tools = listOf("runShellCommand"),
            toolDescs = listOf(
                "runShellCommand(command) — run shell command (may need root)",
            ),
            keywords = listOf("terminal", "shell", "command", "cmd", "execute", "root", "su"),
        ),
        ToolGroup(
            name = "Memory",
            tools = listOf("recallAll", "recallSimilar", "searchMemory", "saveToKnowledge", "recallKnowledge", "listKnowledge"),
            toolDescs = listOf(
                "recallAll() — list all memories",
                "recallSimilar(query) — semantic search memory",
                "searchMemory(query) — keyword search memory",
                "saveToKnowledge(name, content) — save document",
                "recallKnowledge(query) — search knowledge base",
                "listKnowledge() — list all documents",
            ),
            keywords = listOf("remember", "recall", "memory", "forget", "know", "knowledge", "document", "save as", "learn"),
        ),
        ToolGroup(
            name = "Skills",
            tools = listOf("executeSkill", "listSkills", "createSkill", "deleteSkill", "getSkill"),
            toolDescs = listOf(
                "executeSkill(name) — run a skill workflow",
                "listSkills() — list all skills",
                "createSkill(name, desc, trigger, instr) — create skill",
                "deleteSkill(name) — delete skill",
                "getSkill(name) — get skill details",
            ),
            keywords = listOf("skill", "create skill", "delete skill", "list skill", "workflow"),
        ),
        ToolGroup(
            name = "Vision",
            tools = listOf("analyzeScreen", "findElement", "translateText"),
            toolDescs = listOf(
                "analyzeScreen(prompt) — VLM analyzes current screen (1 API call)",
                "findElement(desc) — find UI element by description",
                "translateText(text, targetLang) — translate text",
            ),
            keywords = listOf("see", "look at", "describe screen", "find element", "what's on", "analyze", "translate"),
        ),
        ToolGroup(
            name = "Communication",
            tools = listOf("sendEmail", "shareText", "shareFile"),
            toolDescs = listOf(
                "sendEmail(to, subject, body) — send email",
                "shareText(text) — share text to other apps",
                "shareFile(path) — share file to other apps",
            ),
            keywords = listOf("email", "send email", "share", "send file", "send to"),
        ),
    )

    // Messages that need NO tools
    private val NO_TOOL_PATTERNS = listOf(
        "hi", "hello", "hey", "hai", "hola", "namaste",
        "thanks", "thank you", "thx", "ok", "okay", "k",
        "yes", "no", "nope", "yeah", "yep",
        "how are you", "how's it going", "what's up", "sup",
        "what can you do", "who are you", "what are you",
        "good morning", "good night", "good afternoon", "good evening",
        "bye", "goodbye", "see you",
        "lol", "haha", "nice", "cool", "great", "awesome",
    )

    /**
     * Build a contextual prompt based on user message.
     * Returns the prompt string to use for ALL iterations in the agent loop.
     */
    fun buildPrompt(context: Context, userMessage: String): String {
        val msg = userMessage.lowercase().trim()

        // TIER 1: No tools needed (greetings, simple replies)
        if (isNoToolMessage(msg)) {
            Log.i(TAG, "Tier 1: No tools needed → ultra-tiny prompt")
            return buildUltraTinyPrompt()
        }

        // v4.0.22: Retry/continue messages → use full prompt (Tier 3) so the brain
        // has the complete system prompt + all tool descriptions for the retry.
        if (isRetryMessage(msg)) {
            Log.i(TAG, "Retry/continue message → full prompt (Tier 3)")
            return try {
                context.assets.open("system_prompt_smart.txt").bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                "You are an AI agent controlling an Android phone. Respond in JSON: {\"reply\":\"\",\"tool_calls\":[{\"name\":\"\",\"args\":{}}]}"
            }
        }

        // TIER 2: Contextual (match groups to keywords)
        val relevantGroups = getRelevantGroups(msg)
        if (relevantGroups.isNotEmpty()) {
            val promptSize = relevantGroups.sumOf { it.tools.size }
            Log.i(TAG, "Tier 2: Contextual → ${relevantGroups.size} groups, $promptSize tools")
            return buildGroupPrompt(relevantGroups)
        }

        // TIER 3: Full prompt (complex/unknown tasks)
        Log.i(TAG, "Tier 3: Full prompt (no keyword match or complex task)")
        return try {
            context.assets.open("system_prompt_smart.txt").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            "You are an AI agent controlling an Android phone. Respond in JSON: {\"reply\":\"\",\"tool_calls\":[{\"name\":\"\",\"args\":{}}]}"
        }
    }

    /**
     * v3.2.9: Return the set of tool names that should be sent via OpenAI
     * function-calling for this message.
     *
     *   Tier 1 ("hi")         → empty list (no tools, model just replies)
     *   Tier 2 (matched group)→ always-tools + matched group tools only
     *   Tier 3 (full prompt)  → null = send ALL tools (unchanged behavior)
     *
     * Returning null means "no filtering — send everything" so we preserve
     * the Tier 3 behavior without changing the calling code.
     */
    fun getRelevantToolNames(userMessage: String): List<String>? {
        val msg = userMessage.lowercase().trim()
        // Tier 1: greeting → no tools at all.
        if (isNoToolMessage(msg)) {
            return emptyList()
        }
        // v4.0.22: Retry/continue phrases → send ALL tools (null = no filter).
        // When the user says "try again", "retry", "continue", etc., we don't know
        // what the previous task needed. Sending a minimal toolset (only memory/TTS)
        // makes it impossible to retry the original task. Return null = full toolset
        // so the brain has access to browser/screen/app tools for the retry.
        if (isRetryMessage(msg)) {
            Log.i(TAG, "Retry/continue message → sending ALL tools (full toolset)")
            return null
        }
        // Tier 2: matched groups → only those tool names + ALWAYS_TOOLS.
        val groups = getRelevantGroups(msg)
        if (groups.isNotEmpty()) {
            val names = mutableSetOf<String>()
            // Strip the "(...)" suffix from ALWAYS_TOOLS entries like "speak(text)".
            for ((raw, _) in ALWAYS_TOOLS) {
                val n = raw.substringBefore("(").trim()
                if (n.isNotEmpty()) names.add(n)
            }
            for (g in groups) names.addAll(g.tools)
            return names.toList()
        }
        // Tier 3: no keyword match → caller should send the full toolset.
        return null
    }

    /**
     * v4.0.22: Detect retry/continue messages.
     * These should get the FULL toolset (not a minimal set) because we don't
     * know what the previous task needed. Examples:
     *   "try again", "retry", "continue", "do it again", "try once more",
     *   "try it again", "redo", "re-run", "run again"
     */
    private fun isRetryMessage(msg: String): Boolean {
        val retryPhrases = listOf(
            "try again", "try it again", "try once more", "try once",
            "retry", "redo", "re-run", "rerun", "run again", "do it again",
            "do again", "again", "continue", "keep going", "go on",
            "one more time", "once more",
        )
        return retryPhrases.any { msg == it || msg.startsWith(it + " ") || msg.startsWith(it + "!") || msg.startsWith(it + ".") || msg.startsWith(it + "?") }
    }

    /**
     * v3.2.9: Was this message classified as Tier 1 (no tools)?
     * Used by ChatViewModel.runAgentLoop to skip appending large guide
     * sections (vision/web/a11y) to a tiny greeting prompt.
     */
    fun isTier1NoTools(userMessage: String): Boolean {
        return isNoToolMessage(userMessage.lowercase().trim())
    }

    /**
     * v3.3.6: Classify a user message into a tier for model routing.
     *
     *   Tier 1 → Fast/Cheap model (greetings, simple questions)
     *   Tier 2 → Default model (matched tools — tap, launchApp, etc.)
     *   Tier 3 → Advanced model (complex/unknown tasks — full prompt)
     *
     * The routing layer (SmartRouting) uses this to pick which saved model
     * to use for the message.
     */
    fun getTier(userMessage: String): Int {
        val msg = userMessage.lowercase().trim()
        // Tier 1: greetings + no-tool messages.
        if (isNoToolMessage(msg)) return 1
        // Tier 2: contextual (matched tool groups).
        val groups = getRelevantGroups(msg)
        if (groups.isNotEmpty()) return 2
        // Tier 3: full prompt (no keyword match — complex/unknown task).
        return 3
    }

    /**
     * Check if message needs no tools.
     */
    /**
     * v3.8.1: Check if a message needs no tools (Tier 1 — greetings, simple replies).
     *
     * Uses exact match or word-boundary matching (not startsWith).
     * v3.8.0 bug: 'now play ar rahman...' matched 'no' (startsWith) → Tier 1 → no tools.
     * Fixed: now requires the pattern to be the ENTIRE message OR followed by a word boundary.
     */
    private fun isNoToolMessage(msg: String): Boolean {
        return NO_TOOL_PATTERNS.any { pattern ->
            msg == pattern ||
            msg.startsWith(pattern + " ") ||  // "hi there" matches "hi"
            msg.startsWith(pattern + "!") ||
            msg.startsWith(pattern + ".") ||
            msg.startsWith(pattern + "?")
        }
    }

    /**
     * Match user message to relevant tool groups.
     * v3.0.9: Auto-include Screen group for App + Browser + Shortcut tasks
     * (these tasks often need readScreen/tap to verify state or click elements).
     */
    private fun getRelevantGroups(msg: String): List<ToolGroup> {
        var matched = TOOL_GROUPS.filter { group ->
            group.keywords.any { keyword ->
                msg.contains(keyword)
            }
        }

        // v3.0.9: If Apps, Shortcuts, or Browser matched → also include Screen group
        // (app control + browser tasks often need readScreen/tap to verify state)
        val hasApps = matched.any { it.name == "Apps" }
        val hasShortcuts = matched.any { it.name == "Shortcuts" }
        val hasBrowser = matched.any { it.name == "Browser" }
        val hasScreen = matched.any { it.name == "Screen" }
        if ((hasApps || hasShortcuts || hasBrowser) && !hasScreen) {
            matched = matched + (TOOL_GROUPS.find { it.name == "Screen" }!!)
            Log.i(TAG, "Auto-included Screen group (app/browser/shortcut task detected)")
        }

        // v3.7.4: If Shortcuts matched → also include Apps group
        // (shortcut tasks like "play X on YouTube" often need launchApp to open the app
        // first — without it the brain invents non-existent tools like open_youtube_app)
        if (hasShortcuts && !hasApps) {
            matched = matched + (TOOL_GROUPS.find { it.name == "Apps" }!!)
            Log.i(TAG, "Auto-included Apps group (shortcut task detected — needs launchApp)")
        }

        // v3.0.9: If Browser matched → also include Web group
        // (browser tasks often need webSearch to find URLs first)
        val hasWeb = matched.any { it.name == "Web" }
        if (hasBrowser && !hasWeb) {
            matched = matched + (TOOL_GROUPS.find { it.name == "Web" }!!)
            Log.i(TAG, "Auto-included Web group (browser task detected)")
        }

        // Always include Memory group (for recallSimilar at task start)
        val hasMemory = matched.any { it.name == "Memory" }
        if (!hasMemory) {
            matched = matched + (TOOL_GROUPS.find { it.name == "Memory" }!!)
        }
        return matched
    }

    /**
     * Tier 1: Ultra-tiny prompt — no tools listed.
     */
    private fun buildUltraTinyPrompt(): String {
        return """You are Aira, an AI assistant on the user's phone. Reply briefly and naturally.
If the user asks what you can do, mention: control phone apps, browse web, set rules, send messages, take photos, read screen, and more.
{"reply":"your reply","tool_calls":[]}"""
    }

    /**
     * Tier 2: Contextual prompt — only relevant tool groups.
     */
    private fun buildGroupPrompt(groups: List<ToolGroup>): String {
        val sb = StringBuilder()
        sb.append("You are an AI agent on the user's Android phone. Respond in JSON only.\n")
        sb.append("Format: {\"reply\":\"short msg\",\"tool_calls\":[{\"name\":\"...\",\"args\":{...}}]}\n")
        sb.append("Complete (no tools): {\"reply\":\"Done!\",\"tool_calls\":[]}\n\n")

        sb.append("## Available Tools\n\n")

        // Always-included tools
        sb.append("### Always Available\n")
        for ((tool, desc) in ALWAYS_TOOLS) {
            sb.append("- $tool — $desc\n")
        }
        sb.append("\n")

        // Selected groups
        for (group in groups) {
            sb.append("### ${group.name}\n")
            for (desc in group.toolDescs) {
                sb.append("- $desc\n")
            }
            sb.append("\n")
        }

        sb.append("## Rules\n")
        // v3.7.0: Updated to allow action chains (was "NEVER batch action tools")
        sb.append("1. BATCH read-only tools freely. Use actionChain() for sequential actions when UI is predictable.\n")
        sb.append("2. Keep replies SHORT (1-2 sentences)\n")
        sb.append("3. After tool results, decide next step. Don't repeat successful tools.\n")
        sb.append("4. If a tool fails, try a different approach.\n")
        sb.append("5. recallSimilar(request) at task START. remember(\"workflow_X\",\"steps\") on success.\n")
        sb.append("\n## Action Chains (compact)\n")
        sb.append("actionChain({\"actions\": [{\"name\":\"tap\",\"args\":{...},\"safe\":true}, ...]})\n")
        sb.append("- Executes ALL actions in 1 call. Stops on first failure.\n")
        sb.append("- safe: false → pauses chain, you verify before continuing.\n")
        sb.append("- safe: false for: Send, Delete, Call, Confirm buttons.\n")
        sb.append("- safe: true for: Navigation, Selection, Typing, Scrolling.\n")
        sb.append("- When in doubt: safe: false.\n")
        sb.append("- Don't chain after launchApp (screen might not be loaded).\n")

        return sb.toString()
    }

    /**
     * Check if a tool is available in the current contextual prompt.
     * Used by ChatViewModel/RuleEngine to detect if auto-expand is needed.
     */
    fun isToolAvailable(userMessage: String, toolName: String): Boolean {
        val msg = userMessage.lowercase().trim()
        if (isNoToolMessage(msg)) return false  // no tools in ultra-tiny mode

        val groups = getRelevantGroups(msg)
        val allTools = groups.flatMap { it.tools } + ALWAYS_TOOLS.map { it.first.substringBefore("(") }
        return toolName in allTools
    }

    /**
     * Get the group that contains a specific tool.
     * Used for auto-expand when AI calls a tool not in the contextual prompt.
     */
    fun findGroupForTool(toolName: String): ToolGroup? {
        return TOOL_GROUPS.find { it.tools.contains(toolName) }
    }

    /**
     * Build an expanded prompt by adding a new group to existing groups.
     * Used when auto-expand is triggered (AI needs a tool not in the prompt).
     */
    fun buildExpandedPrompt(userMessage: String, extraGroup: ToolGroup): String {
        val msg = userMessage.lowercase().trim()
        val groups = getRelevantGroups(msg).toMutableList()
        if (extraGroup !in groups) {
            groups.add(extraGroup)
        }
        return buildGroupPrompt(groups)
    }

    /**
     * Estimate token count for a prompt.
     */
    fun estimateTokens(prompt: String): Int {
        return prompt.length / 4  // rough estimate: 4 chars ≈ 1 token
    }
}
