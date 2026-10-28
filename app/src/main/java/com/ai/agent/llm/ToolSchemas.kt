package com.ai.agent.llm

import org.json.JSONArray
import org.json.JSONObject

/**
 * v3.7.3: ToolSchemas — real parameter schemas for ALL 130 tools.
 *
 * Why: Previously, buildToolsArray() sent empty parameter schemas to the LLM:
 *   {"name": "tap", "description": "Execute the tap tool", "parameters": {}}
 * The brain had to GUESS what parameters each tool needed — and often guessed
 * wrong (e.g., tap(target=Search) → "missing x").
 *
 * Now each tool has a real schema with parameter names, types, and descriptions.
 * The brain can SEE what arguments are valid instead of guessing.
 *
 * This file is PURE DATA — no logic. One place to maintain all tool schemas.
 * When a new tool is added to ToolExecutor, add its schema here too.
 *
 * Usage:
 *   val schema = ToolSchemas.get("tap")    // returns JSONObject or null
 *   val all = ToolSchemas.allNames()       // returns List<String>
 */
object ToolSchemas {

    // -----------------------------------------------------------------------
    // Data class for a single parameter definition.
    // -----------------------------------------------------------------------
    data class Param(
        val type: String,           // "string", "number", "boolean", "array", "object"
        val description: String,
        val required: Boolean = false,
    )

    data class Schema(
        val name: String,
        val description: String,
        val params: Map<String, Param> = emptyMap(),  // empty = no params needed
    )

    // -----------------------------------------------------------------------
    // The registry — ALL 130 tools. Order matches ToolExecutor.getAvailableToolNames().
    // -----------------------------------------------------------------------
    private val SCHEMAS: List<Schema> = listOf(
        // ==================== SCREEN (14) ====================
        Schema("readScreen", "Read current screen text (3-tier: accessibility → ML Kit OCR → VLM). No API call needed for text-heavy screens."),
        Schema("readScreenStructured", "Read screen as a JSON tree of clickable elements with their bounds. Use to find tappable elements (icons, buttons) when clickByText fails."),
        Schema("tap", "Tap at screen coordinates. Pass {x, y} for a coordinate tap, OR {target: \"text\"} to click by text (redirects to clickByText). IMPORTANT: Call readScreenStructured first to get element bounds, then tap the center: x=(left+right)/2, y=(top+bottom)/2. Do NOT guess coordinates. Screen resolution varies by device.",
            mapOf(
                "x" to Param("number", "X coordinate. Get from readScreenStructured bounds: (left+right)/2.", required = false),
                "y" to Param("number", "Y coordinate. Get from readScreenStructured bounds: (top+bottom)/2.", required = false),
                "target" to Param("string", "Text of element to click (alternative to x/y). Use when you don't know exact coords.", required = false),
            )),
        Schema("clickByText", "Click a UI element by its visible text. Supports partial matching. SILENTLY FAILS on icon-only UIs (no text) — use readScreenStructured instead.",
            mapOf("text" to Param("string", "Text of the element to click (supports partial match).", required = true))),
        // v3.9.9: clickElement — PREFERRED way to click. No coordinate guessing.
        // Brain reads readScreenStructured (elements have "index" field), picks the
        // element it wants (e.g., first video result), calls clickElement(index=N).
        // The app taps that element's bounds center automatically.
        Schema("clickElement", "Click an element by its 1-based index from readScreenStructured(). PREFERRED over tap(x,y) — no coordinate guessing. Call readScreenStructured() first to see element indices, then clickElement(index=N) to click the Nth element.",
            mapOf("index" to Param("number", "1-based index of the element from readScreenStructured(). Example: if the first video result has index 3 in the JSON, call clickElement(index=3).", required = true))),
        // v4.0.2: longClick — long press for context menus, drag, etc.
        Schema("longClick", "Long press at screen coordinates (500ms hold). Use for context menus, drag-and-drop, or elements that respond to long press. Get coordinates from readScreenStructured bounds.",
            mapOf(
                "x" to Param("number", "X coordinate.", required = true),
                "y" to Param("number", "Y coordinate.", required = true),
            )),
        Schema("longClickElement", "Long press an element by index from readScreenStructured(). Tries ACTION_LONG_CLICK first, falls back to gesture.",
            mapOf("index" to Param("number", "1-based index from readScreenStructured().", required = true))),
        // v4.0.2: focusElement — focus before typing
        Schema("focusElement", "Focus an element by index. Some apps need focus before type() works. Call this if type() returns success but text doesn't appear.",
            mapOf("index" to Param("number", "1-based index from readScreenStructured().", required = true))),
        // v4.0.2: paste — paste clipboard into field
        Schema("paste", "Paste clipboard content into an element by index. Focuses the element + performs ACTION_PASTE. Use after setClipboard to paste text into a field.",
            mapOf("index" to Param("number", "1-based index of the target input field from readScreenStructured().", required = true))),
        // v4.0.2: edit — type + optional submit in ONE call (saves API calls)
        Schema("edit", "Edit text in an element by index. Clears existing text + types new text in ONE call. If autoSubmit=true, also submits the form/search. Saves 1 API call vs type()+submitInput(). Use for search boxes.",
            mapOf(
                "index" to Param("number", "1-based index of the input field from readScreenStructured().", required = true),
                "text" to Param("string", "Text to type into the field (replaces existing text).", required = true),
                "autoSubmit" to Param("boolean", "If true, also calls submitInput after typing (submits the search/form). Default false.", required = false),
            )),
        Schema("type", "Type text into the currently focused input field. Use after tapping a search box or text field.",
            mapOf("text" to Param("string", "Text to type into the focused field.", required = true))),
        Schema("swipe", "Swipe from one point to another.",
            mapOf(
                "x1" to Param("number", "Start X.", required = true),
                "y1" to Param("number", "Start Y.", required = true),
                "x2" to Param("number", "End X.", required = true),
                "y2" to Param("number", "End Y.", required = true),
            )),
        Schema("scrollDown", "Scroll the current screen down. No parameters."),
        Schema("scrollUp", "Scroll the current screen up. No parameters."),
        Schema("pressBack", "Press the Android Back button. Use to exit a video, dismiss a screen, or return to the previous page. No parameters."),
        Schema("pressHome", "Press the Android Home button. Returns to the launcher. No parameters."),
        Schema("pressEnter", "Press the Enter key. No parameters."),
        Schema("submitInput", "Submit the currently focused form/search field (presses Enter on the IME). Use after type() in a search box. No parameters."),
        Schema("lockScreen", "Lock the device screen. No parameters."),
        Schema("takeScreenshotToGallery", "Take a screenshot and save it to the Pictures folder. No parameters."),

        // ==================== APPS (10) ====================
        Schema("launchApp", "Launch an app by package name (e.g., com.google.android.youtube). This is the ONLY tool for opening apps — there is no open_youtube_app or open_spotify_app tool. Returns screen content after 2.5s. ALWAYS read the returned screen — apps may resume to unexpected states (videos, Shorts).",
            mapOf("package" to Param("string", "App package name (e.g., com.google.android.youtube, com.whatsapp, com.spotify.music).", required = true))),
        Schema("listInstalledApps", "List all installed apps with their package names. No parameters."),
        Schema("getAppInfo", "Get detailed info about an installed app (version, size, permissions).",
            mapOf("package" to Param("string", "App package name.", required = true))),
        Schema("forceStopApp", "Force-stop an app (kills it).",
            mapOf("package" to Param("string", "App package name.", required = true))),
        Schema("uninstallApp", "Uninstall an app (shows the uninstall confirmation screen).",
            mapOf("package" to Param("string", "App package name.", required = true))),
        Schema("openDialer", "Open the phone dialer. Pass a number to pre-fill it, or omit to open empty.",
            mapOf("number" to Param("string", "Phone number to pre-fill (optional).", required = false))),
        Schema("openContact", "Open a contact by phone number (opens the contact card).",
            mapOf("number" to Param("string", "Phone number of the contact.", required = true))),
        Schema("openSettings", "Deep-link to a specific Android Settings page.",
            mapOf("page" to Param("string", "Settings page: wifi, bluetooth, location, accessibility, notification, apps, display, sound, battery, storage, datetime, language, developer, about, or main.", required = true))),
        Schema("shareText", "Open the Android share sheet with text.",
            mapOf("text" to Param("string", "Text to share.", required = true))),
        Schema("shareFile", "Open the Android share sheet with a file.",
            map_of_file_path_caption()),
        Schema("speak", "v3.0.0: Speak text aloud using TTS. Useful for rules + hands-free.",
            mapOf("text" to Param("string", "Text to speak.", required = true))),

        // ==================== VISION (3) ====================
        Schema("analyzeScreen", "VLM: analyze the current screen with a natural-language prompt. Uses 1 API call. Use for screenshots with no text.",
            mapOf("prompt" to Param("string", "What to look for (e.g., 'find the login button').", required = true))),
        Schema("findElement", "VLM: find a UI element by description. Returns coordinates. Slow — use readScreenStructured first.",
            mapOf("description" to Param("string", "Description of the element (e.g., 'red subscribe button').", required = true))),
        Schema("translateText", "Translate text to a target language using VLM.",
            mapOf(
                "text" to Param("string", "Text to translate.", required = true),
                "targetLang" to Param("string", "Target language (e.g., 'English', 'Hindi').", required = true),
            )),

        // ==================== BROWSER (21) ====================
        Schema("browserOpen", "Open a URL in the in-app browser (VISIBLE — user sees it). Call ONCE per site.",
            mapOf("url" to Param("string", "URL to open.", required = true))),
        Schema("browserReadStructured", "Read the current browser page as a JSON tree of clickable elements + forms. Use after browserOpen."),
        Schema("browserReadPage", "Read the current browser page as plain text."),
        Schema("browserGetLinks", "Get all links on the current browser page."),
        Schema("browserGetForms", "Get all forms on the current browser page."),
        Schema("browserFillForm", "Fill a form field in the browser by field name/selector.",
            map_of_browser_fill_form()),
        Schema("browserClickElement", "Click an element in the browser by CSS selector.",
            mapOf("selector" to Param("string", "CSS selector of the element.", required = true))),
        Schema("browserClickText", "Click an element in the browser by visible text.",
            mapOf("text" to Param("string", "Visible text of the element to click.", required = true))),
        Schema("browserGetText", "Get text content of the current browser page."),
        Schema("browserWaitForElement", "Wait until an element appears in the browser.",
            map_of_browser_wait()),
        Schema("browserScreenshot", "Take a screenshot of the browser. Optional prompt analyzes it with VLM.",
            mapOf("prompt" to Param("string", "Optional: VLM prompt to analyze the screenshot.", required = false))),
        Schema("browserScrollDown", "Scroll the browser page down. No parameters."),
        Schema("browserEval", "Execute JavaScript in the browser (returns the result).",
            mapOf("js" to Param("string", "JavaScript code to execute.", required = true))),
        Schema("browserBack", "Navigate the browser back (history). No parameters."),
        Schema("browserGetUrl", "Get the current browser URL. No parameters."),
        Schema("browserSearch", "Search a query in the browser (uses the default search engine).",
            mapOf("query" to Param("string", "Search query.", required = true))),
        Schema("browserFill", "Fill a single input field by selector with value.",
            map_of_browser_fill()),
        Schema("browserClick", "Click an element by selector (generic).",
            mapOf("selector" to Param("string", "CSS selector.", required = true))),
        Schema("browserListClickable", "List all clickable elements on the page. No parameters."),
        Schema("openInChrome", "Open a URL in Chrome (uses Chrome, not in-app browser). Needs accessibility for interaction.",
            mapOf("url" to Param("string", "URL to open.", required = true))),
        Schema("searchInChrome", "Search a query in Chrome.",
            mapOf("query" to Param("string", "Search query.", required = true))),

        // ==================== WEB (5) ====================
        Schema("webSearch", "Search the web (background — no browser opens). Returns results with title, url, snippet. Use fetchTop=N to auto-fetch top N results inline (1 call instead of N+1).",
            map_of_web_search()),
        Schema("makeHttpRequest", "Make a raw HTTP request to any URL.",
            map_of_make_http_request()),
        Schema("downloadFile", "Download a file from a URL to the local filesystem.",
            map_of_download_file()),
        Schema("openUrl", "Open a URL using the system default handler (browser, app, etc.).",
            mapOf("url" to Param("string", "URL to open.", required = true))),
        Schema("fetchPageText", "Fetch a URL and return its text content (background, no browser).",
            mapOf("url" to Param("string", "URL to fetch.", required = true))),

        // ==================== DEVICE (12) ====================
        Schema("getBatteryLevel", "Get current battery level (0-100). No parameters."),
        Schema("getCurrentTime", "Get the current date and time. No parameters."),
        Schema("getBluetoothState", "Get Bluetooth state (on/off, connected devices). No parameters."),
        Schema("getNetworkInfo", "Get network info (wifi/cellular, connected, type). No parameters."),
        Schema("getDeviceInfo", "Get device info (model, Android version, manufacturer). No parameters."),
        Schema("pingHost", "Ping a host to check connectivity.",
            mapOf("host" to Param("string", "Hostname or IP to ping.", required = true))),
        Schema("getVolume", "Get current volume level. No parameters."),
        Schema("setVolume", "Set the volume level.",
            mapOf("level" to Param("number", "Volume level (0-15).", required = true))),
        Schema("setBrightness", "Set screen brightness.",
            mapOf("level" to Param("number", "Brightness level (0-255).", required = true))),
        Schema("toggleFlashlight", "Toggle the flashlight on/off. No parameters."),
        Schema("toggleWifi", "Toggle WiFi on/off. No parameters."),

        // ==================== MEMORY & KNOWLEDGE (8) ====================
        Schema("remember", "Save a key-value pair to memory. Use for workflows, lessons, user preferences.",
            map_of_remember()),
        Schema("recall", "Recall a value by exact key.",
            mapOf("key" to Param("string", "Key to recall.", required = true))),
        Schema("recallAll", "List all saved memory keys. No parameters."),
        Schema("recallSimilar", "Semantic search of memory (BM25-ranked). Use at task START to find prior workflows.",
            mapOf("query" to Param("string", "What you're trying to do (e.g., 'send whatsapp message').", required = true))),
        Schema("searchMemory", "Full-text search of memory.",
            mapOf("query" to Param("string", "Search query.", required = true))),
        Schema("saveToKnowledge", "Save a document to the knowledge base (longer-term than memory).",
            map_of_save_knowledge()),
        Schema("recallKnowledge", "Semantic search of the knowledge base.",
            mapOf("query" to Param("string", "Search query.", required = true))),
        Schema("listKnowledge", "List all saved knowledge documents. No parameters."),

        // ==================== CLIPBOARD (2) ====================
        Schema("getClipboard", "Get the current clipboard text. No parameters."),
        Schema("setClipboard", "Set the clipboard text.",
            mapOf("text" to Param("string", "Text to set on the clipboard.", required = true))),

        // ==================== LOCATION (3) ====================
        Schema("getCurrentLocation", "Get current GPS location (lat, lon, address). Needs location permission. No parameters."),
        Schema("openMaps", "Open Google Maps app.",
            mapOf("query" to Param("string", "Location to search (optional).", required = false))),
        Schema("getWeather", "Get current weather for the device location (uses GPS + Open-Meteo). NEVER webSearch for weather. No parameters."),

        // ==================== RULES (4) ====================
        Schema("createRule", "Create an automation rule (trigger → action). 36 trigger types.",
            map_of_create_rule()),
        Schema("listRules", "List all saved rules. No parameters."),
        Schema("deleteRule", "Delete a rule by ID.",
            mapOf("id" to Param("string", "Rule ID to delete.", required = true))),
        Schema("modifyRule", "Modify an existing rule.",
            map_of_modify_rule()),

        // ==================== CONTACTS & PHONE (8) ====================
        Schema("readContacts", "Read the contact list. Needs READ_CONTACTS permission. No parameters."),
        Schema("searchContacts", "Search contacts by name.",
            mapOf("name" to Param("string", "Name to search.", required = true))),
        Schema("getCalendarEvents", "Get upcoming calendar events. No parameters."),
        Schema("createCalendarEvent", "Create a calendar event.",
            map_of_calendar_event()),
        Schema("setAlarm", "Set an alarm.",
            map_of_set_alarm()),
        Schema("setTimer", "Set a timer.",
            map_of_set_timer()),
        Schema("sendSMS", "Send an SMS message. Marked safe:false in action chains.",
            map_of_send_sms()),
        Schema("callContact", "Make a phone call to a number. Marked safe:false in action chains.",
            mapOf("number" to Param("string", "Phone number to call.", required = true))),
        Schema("getCallLog", "Get the call log. No parameters."),
        Schema("sendEmail", "Send an email (opens email app).",
            map_of_send_email()),
        Schema("makePhoneCall", "Make a phone call (alias of callContact). Marked safe:false.",
            mapOf("number" to Param("string", "Phone number.", required = true))),
        Schema("composeEmail", "Compose an email (opens the email composer).",
            map_of_compose_email()),

        // ==================== FILES (10) ====================
        Schema("listFiles", "List files in a directory.",
            mapOf("path" to Param("string", "Directory path (default: Documents/ai-workspace).", required = false))),
        Schema("readFile", "Read a text file.",
            mapOf("path" to Param("string", "File path.", required = true))),
        Schema("writeFile", "Write text to a file. Marked safe:false in action chains.",
            map_of_write_file()),
        Schema("copyFile", "Copy a file.",
            map_of_copy_file()),
        Schema("moveFile", "Move a file.",
            map_of_move_file()),
        Schema("deleteFile", "Delete a file. Marked safe:false in action chains.",
            mapOf("path" to Param("string", "File path to delete.", required = true))),
        Schema("createDirectory", "Create a directory.",
            mapOf("path" to Param("string", "Directory path to create.", required = true))),
        Schema("listAttachments", "List files in the attachments folder. No parameters."),
        Schema("readAttachment", "Read an attached file.",
            mapOf("filename" to Param("string", "Attachment filename.", required = true))),
        Schema("sendAttachmentTo", "Send an attachment to another app.",
            map_of_send_attachment()),

        // ==================== ACTIVITY SHORTCUTS (12) ====================
        Schema("openActivity", "Open a specific activity (screen) inside an app.",
            map_of_open_activity()),
        Schema("openDeepLink", "Open a deep link (URL that opens in an app).",
            map_of_open_deep_link()),
        Schema("openAppWithData", "Open an app with data (intent extras).",
            map_of_open_app_with_data()),
        Schema("searchInApp", "Search inside an app (YouTube, Spotify, Instagram, Chrome). 1 call — opens the app + runs the search + shows results IN THE APP (not browser). Does NOT tap a result — follow up with readScreenStructured + tap to play/open a result.",
            map_of_search_in_app()),
        Schema("listAppActivities", "List all activities (screens) in an app.",
            mapOf("package" to Param("string", "App package name.", required = true))),
        Schema("openWhatsAppChat", "Open a WhatsApp chat with a phone number. 1 call vs 5. Optional message pre-fills the chat (user taps send). Marked safe:false if message is set.",
            map_of_whatsapp()),
        Schema("openYouTubeVideo", "DISABLED — do NOT use. Always returns an error. Instead: use searchInApp to search, then readScreenStructured to find the video result's bounds, then tap(center of bounds) to play it.",
            mapOf("videoId" to Param("string", "DISABLED — do not use this tool.", required = false))),
        Schema("openMapsLocation", "Open Google Maps at a specific location.",
            mapOf("query" to Param("string", "Location to show.", required = true))),
        Schema("playSpotify", "Open Spotify + search + auto-play first result. 1 call.",
            mapOf("query" to Param("string", "What to play (song/artist/playlist).", required = true))),
        Schema("openInstagramProfile", "Open an Instagram profile by username. 1 call.",
            mapOf("username" to Param("string", "Instagram username.", required = true))),
        Schema("openTelegramChat", "Open a Telegram chat by username. 1 call.",
            mapOf("username" to Param("string", "Telegram username.", required = true))),
        Schema("openGoogleSearch", "Open Google Search with a query.",
            mapOf("query" to Param("string", "Search query.", required = true))),
        Schema("shareToApp", "Share text to a specific app.",
            map_of_share_to_app()),

        // ==================== NOTIFICATION (1) ====================
        Schema("replyToNotification", "Reply to a notification from an app (e.g., WhatsApp message notification).",
            map_of_reply_notification()),

        // ==================== MEDIA (4) ====================
        Schema("mediaPlayPause", "Toggle media play/pause. No parameters."),
        Schema("mediaNext", "Skip to next media track. No parameters."),
        Schema("mediaPrevious", "Go to previous media track. No parameters."),
        Schema("takePhoto", "Take a photo with the camera. Needs CAMERA permission. No parameters."),

        // ==================== SKILLS (5) ====================
        Schema("executeSkill", "Load a skill's step-by-step instructions into context. Use this when the user's request is complex/multi-step AND a relevant skill is listed at the end of the system prompt. Returns the skill's instructions — read them, then call the tools they specify in order.",
            mapOf("name" to Param("string", "Skill name (e.g., play-youtube). See 'Relevant Skills' section at end of prompt.", required = true))),
        Schema("listSkills", "List all available skills. No parameters."),
        Schema("createSkill", "Create a new skill (composite workflow).",
            map_of_create_skill()),
        Schema("deleteSkill", "Delete a skill by name.",
            mapOf("name" to Param("string", "Skill name to delete.", required = true))),
        Schema("getSkill", "Get a skill's details by name.",
            mapOf("name" to Param("string", "Skill name.", required = true))),

        // ==================== ASYNC (2) ====================
        Schema("wait", "Wait for N seconds, then continue. Use for short waits (5-60s).",
            mapOf("seconds" to Param("number", "Seconds to wait (1-60).", required = true))),
        Schema("waitAndContinue", "Wait N seconds with a reason (for long waits, use createRule instead).",
            map_of_wait_continue()),

        // ==================== ACTION CHAINS (1) ====================
        Schema("actionChain", "v3.7.0: Execute multiple actions sequentially in 1 API call. Saves 50-70% calls. Stops on first failure. Actions with safe:false PAUSE the chain. Screen state is auto-appended after.",
            map_of_action_chain()),
        // v3.9.6: Completion tool — forces brain to verify success before stopping.
        Schema("complete", "Call this when the task is COMPLETE. You MUST verify success via readScreen/VLM BEFORE calling this. Do NOT call this unless you have confirmed the task succeeded (or failed).",
            mapOf(
                "success" to Param("boolean", "true if task succeeded, false if it failed or is impossible.", required = true),
                "reason" to Param("string", "Why the task is complete. Include what you see on screen as proof (e.g., 'Video playing at 9:07, 212M views').", required = true),
            )),
        // v4.0.26: Custom tool management — lets the AI create new tools at runtime.
        Schema("createCustomTool", "Create a NEW custom tool at runtime. Use when you need a tool that doesn't exist (e.g., call a custom API, run a specific shell command). The tool persists across app restarts. Two handler types: 'shell' (runs a shell command with {param} substitution) or 'http' (makes an HTTP GET/POST with {param} substitution in URL+body). Example: createCustomTool(name='check_server', description='Check server status', handlerType='http', handler='{\"method\":\"GET\",\"url\":\"https://myserver.com/health\"}', parameters=[]). After creating, call the tool by its name directly.",
            mapOf(
                "name" to Param("string", "Tool name (lowercase, underscore). Example: 'check_server_status'. Must not conflict with built-in tools.", required = true),
                "description" to Param("string", "What this tool does. Be specific — this is what the AI sees in the tool list.", required = true),
                "handlerType" to Param("string", "Type of handler: 'shell' (runs shell command) or 'http' (makes HTTP request).", required = true),
                "handler" to Param("string", "For 'shell': the command with {param} placeholders (e.g., 'curl -s https://api.com?q={query}'). For 'http': JSON config like '{\"method\":\"GET\",\"url\":\"https://...\",\"headers\":{\"Authorization\":\"Bearer xxx\"}}'. Use {param} placeholders in URL/body.", required = true),
                "parameters" to Param("string", "Comma-separated parameter names (e.g., 'query,limit'). These are substituted into the handler's {param} placeholders.", required = false),
            )),
        Schema("listCustomTools", "List all custom tools you've created. Shows name, description, handler type, and handler for each."),
        Schema("deleteCustomTool", "Delete a custom tool by name.",
            mapOf(
                "name" to Param("string", "Name of the custom tool to delete.", required = true),
            )),
    )

    // -----------------------------------------------------------------------
    // Helpers to build the parameter map for complex tools.
    // (Kept as functions so the Schema list above stays readable.)
    // -----------------------------------------------------------------------
    private fun map_of_file_path_caption() = mapOf(
        "path" to Param("string", "File path to share.", required = true),
        "caption" to Param("string", "Optional caption.", required = false),
    )
    private fun map_of_browser_fill_form() = mapOf(
        "field" to Param("string", "Field name or selector.", required = true),
        "value" to Param("string", "Value to fill.", required = true),
    )
    private fun map_of_browser_wait() = mapOf(
        "selector" to Param("string", "CSS selector to wait for.", required = true),
        "timeout" to Param("number", "Max seconds to wait (default 10).", required = false),
    )
    private fun map_of_browser_fill() = mapOf(
        "selector" to Param("string", "CSS selector of the input field.", required = true),
        "value" to Param("string", "Value to fill.", required = true),
    )
    private fun map_of_web_search() = mapOf(
        "query" to Param("string", "Search query.", required = true),
        "fetchTop" to Param("number", "Auto-fetch top N results inline (0 = just snippets, 1 = fetch first result). Default 0.", required = false),
    )
    private fun map_of_make_http_request() = mapOf(
        "url" to Param("string", "URL to request.", required = true),
        "method" to Param("string", "HTTP method (GET/POST/PUT/DELETE). Default GET.", required = false),
        "headers" to Param("object", "Request headers as JSON object.", required = false),
        "body" to Param("string", "Request body (for POST/PUT).", required = false),
    )
    private fun map_of_download_file() = mapOf(
        "url" to Param("string", "URL to download.", required = true),
        "path" to Param("string", "Local path to save (default: Downloads).", required = false),
    )
    private fun map_of_remember() = mapOf(
        "key" to Param("string", "Memory key (e.g., 'workflow_send_whatsapp').", required = true),
        "value" to Param("string", "Value to remember.", required = true),
    )
    private fun map_of_save_knowledge() = mapOf(
        "name" to Param("string", "Document name.", required = true),
        "content" to Param("string", "Document content.", required = true),
    )
    private fun map_of_create_rule() = mapOf(
        "name" to Param("string", "Rule name.", required = true),
        "triggerType" to Param("string", "Trigger type (time, notification, battery_low, charging, incoming_call, sms_received, headset_connected, screen_on/off, wifi_connected, etc.).", required = true),
        "triggerValue" to Param("string", "Trigger value (e.g., '09:00' for time, 'com.whatsapp' for notification).", required = true),
        "action" to Param("string", "Action to run when triggered.", required = true),
        "days" to Param("string", "Days to run (e.g., 'Mon,Tue,Wed' or 'everyday'). Optional.", required = false),
    )
    private fun map_of_modify_rule() = mapOf(
        "id" to Param("string", "Rule ID to modify.", required = true),
        "name" to Param("string", "New name (optional).", required = false),
        "action" to Param("string", "New action (optional).", required = false),
    )
    private fun map_of_calendar_event() = mapOf(
        "title" to Param("string", "Event title.", required = true),
        "start" to Param("string", "Start time (ISO 8601 or 'yyyy-MM-dd HH:mm').", required = true),
        "duration" to Param("number", "Duration in minutes.", required = false),
    )
    private fun map_of_set_alarm() = mapOf(
        "hour" to Param("number", "Hour (0-23).", required = true),
        "minute" to Param("number", "Minute (0-59).", required = true),
    )
    private fun map_of_set_timer() = mapOf(
        "seconds" to Param("number", "Timer duration in seconds.", required = true),
        "message" to Param("string", "Timer label (optional).", required = false),
    )
    private fun map_of_send_sms() = mapOf(
        "number" to Param("string", "Phone number.", required = true),
        "message" to Param("string", "SMS message.", required = true),
    )
    private fun map_of_send_email() = mapOf(
        "to" to Param("string", "Recipient email.", required = true),
        "subject" to Param("string", "Email subject.", required = true),
        "body" to Param("string", "Email body.", required = true),
    )
    private fun map_of_compose_email() = mapOf(
        "to" to Param("string", "Recipient email.", required = true),
        "subject" to Param("string", "Email subject.", required = true),
        "body" to Param("string", "Email body.", required = true),
    )
    private fun map_of_write_file() = mapOf(
        "path" to Param("string", "File path.", required = true),
        "content" to Param("string", "Content to write.", required = true),
    )
    private fun map_of_copy_file() = mapOf(
        "source" to Param("string", "Source file path.", required = true),
        "destination" to Param("string", "Destination file path.", required = true),
    )
    private fun map_of_move_file() = mapOf(
        "source" to Param("string", "Source file path.", required = true),
        "destination" to Param("string", "Destination file path.", required = true),
    )
    private fun map_of_send_attachment() = mapOf(
        "package" to Param("string", "App package to send to.", required = true),
        "filename" to Param("string", "Attachment filename.", required = true),
        "caption" to Param("string", "Optional caption.", required = false),
    )
    private fun map_of_open_activity() = mapOf(
        "package" to Param("string", "App package name.", required = true),
        "activity" to Param("string", "Activity class name.", required = true),
    )
    private fun map_of_open_deep_link() = mapOf(
        "uri" to Param("string", "Deep link URI.", required = true),
        "package" to Param("string", "Optional: app package to handle the link.", required = false),
    )
    private fun map_of_open_app_with_data() = mapOf(
        "package" to Param("string", "App package name.", required = true),
        "data" to Param("string", "Data to pass to the app.", required = true),
    )
    private fun map_of_search_in_app() = mapOf(
        "package" to Param("string", "App package (e.g., com.google.android.youtube).", required = true),
        "query" to Param("string", "Search query.", required = true),
    )
    private fun map_of_whatsapp() = mapOf(
        "phone" to Param("string", "Phone number with country code (e.g., +91...).", required = true),
        "message" to Param("string", "Optional: message to pre-fill (user taps send).", required = false),
    )
    private fun map_of_share_to_app() = mapOf(
        "package" to Param("string", "App package to share to.", required = true),
        "text" to Param("string", "Text to share.", required = true),
    )
    private fun map_of_reply_notification() = mapOf(
        "package" to Param("string", "App package that posted the notification.", required = true),
        "message" to Param("string", "Reply message.", required = true),
    )
    private fun map_of_create_skill() = mapOf(
        "name" to Param("string", "Skill name.", required = true),
        "description" to Param("string", "Skill description.", required = true),
        "trigger" to Param("string", "Trigger phrase/pattern.", required = true),
        "instructions" to Param("string", "Step-by-step instructions.", required = true),
    )
    private fun map_of_wait_continue() = mapOf(
        "seconds" to Param("number", "Seconds to wait.", required = true),
        "reason" to Param("string", "Why waiting (for the user).", required = false),
    )
    private fun map_of_action_chain() = mapOf(
        "actions" to Param("array", "List of actions. Each: {name, args, safe?}. safe:false pauses the chain before that action.", required = true),
    )

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /** Get the schema for a single tool by name. Returns null if not found. */
    fun get(toolName: String): Schema? = SCHEMAS.find { it.name == toolName }

    /** Get all tool names. */
    fun allNames(): List<String> = SCHEMAS.map { it.name }

    /**
     * Build a JSONArray of OpenAI-compatible tool schemas (the `tools` array
     * sent to the LLM). Each entry: {"type":"function","function":{...}}.
     *
     * Pass a filter to include only specific tools (used by contextual prompts).
     * Null filter = all tools. Empty filter = no tools (Tier 1 "hi" messages).
     */
    fun buildToolsArray(filter: List<String>? = null): JSONArray {
        val tools = JSONArray()
        for (schema in SCHEMAS) {
            if (filter != null && schema.name !in filter) continue
            val function = JSONObject()
                .put("name", schema.name)
                .put("description", schema.description)

            val properties = JSONObject()
            val required = JSONArray()
            for ((pname, param) in schema.params) {
                val propObj = JSONObject()
                    .put("type", param.type)
                    .put("description", param.description)
                properties.put(pname, propObj)
                if (param.required) required.put(pname)
            }

            val parameters = JSONObject()
                .put("type", "object")
                .put("properties", properties)
                .put("additionalProperties", false)
            if (required.length() > 0) {
                parameters.put("required", required)
            }

            function.put("parameters", parameters)

            val tool = JSONObject()
                .put("type", "function")
                .put("function", function)
            tools.put(tool)
        }
        // v4.0.26: Append custom tools (created via createCustomTool) to the array.
        // These are loaded from CustomToolStore and added dynamically.
        try {
            val customTools = com.ai.agent.tools.custom.CustomToolStore.load(android.app.Application())
            for (ct in customTools) {
                if (filter != null && ct.name !in filter) continue
                val properties = JSONObject()
                val required = JSONArray()
                for (paramName in ct.parameters) {
                    properties.put(paramName, JSONObject()
                        .put("type", "string")
                        .put("description", "Parameter: $paramName"))
                    required.put(paramName)
                }
                val parameters = JSONObject()
                    .put("type", "object")
                    .put("properties", properties)
                    .put("additionalProperties", false)
                if (required.length() > 0) {
                    parameters.put("required", required)
                }
                val function = JSONObject()
                    .put("name", ct.name)
                    .put("description", "(Custom Tool) ${ct.description}")
                    .put("parameters", parameters)
                val tool = JSONObject()
                    .put("type", "function")
                    .put("function", function)
                tools.put(tool)
            }
        } catch (e: Exception) {
            // CustomToolStore.load needs a Context — but buildToolsArray is called from
            // LLMClient which has a context. The static call above won't work, so custom
            // tools are added via the context-aware overload below.
        }
        return tools
    }

    /**
     * v4.0.26: Context-aware buildToolsArray — includes custom tools from storage.
     * This is the preferred call path — pass the app context to include custom tools.
     */
    fun buildToolsArray(filter: List<String>? = null, context: android.content.Context? = null): JSONArray {
        val tools = JSONArray()
        for (schema in SCHEMAS) {
            if (filter != null && schema.name !in filter) continue
            val function = JSONObject()
                .put("name", schema.name)
                .put("description", schema.description)

            val properties = JSONObject()
            val required = JSONArray()
            for ((pname, param) in schema.params) {
                val propObj = JSONObject()
                    .put("type", param.type)
                    .put("description", param.description)
                properties.put(pname, propObj)
                if (param.required) required.put(pname)
            }

            val parameters = JSONObject()
                .put("type", "object")
                .put("properties", properties)
                .put("additionalProperties", false)
            if (required.length() > 0) {
                parameters.put("required", required)
            }

            function.put("parameters", parameters)

            val tool = JSONObject()
                .put("type", "function")
                .put("function", function)
            tools.put(tool)
        }
        // v4.0.26: Append custom tools if context is provided.
        if (context != null) {
            try {
                val customTools = com.ai.agent.tools.custom.CustomToolStore.load(context)
                for (ct in customTools) {
                    if (filter != null && ct.name !in filter) continue
                    val properties = JSONObject()
                    val required = JSONArray()
                    for (paramName in ct.parameters) {
                        properties.put(paramName, JSONObject()
                            .put("type", "string")
                            .put("description", "Parameter: $paramName"))
                        required.put(paramName)
                    }
                    val parameters = JSONObject()
                        .put("type", "object")
                        .put("properties", properties)
                        .put("additionalProperties", false)
                    if (required.length() > 0) {
                        parameters.put("required", required)
                    }
                    val function = JSONObject()
                        .put("name", ct.name)
                        .put("description", "(Custom Tool) ${ct.description}")
                        .put("parameters", parameters)
                    val tool = JSONObject()
                        .put("type", "function")
                        .put("function", function)
                    tools.put(tool)
                }
            } catch (e: Exception) {
                // Ignore — custom tools are optional
            }
        }
        return tools
    }
}
