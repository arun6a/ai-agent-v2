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
        Schema("readScreen", "[BATCHABLE] [NO VERIFY] [BATCHABLE] [NO VERIFY] Read current screen text (3-tier: accessibility → ML Kit OCR → VLM). No API call needed for text-heavy screens."),
        Schema("readScreenStructured", "[BATCHABLE] [NO VERIFY] [BATCHABLE] [NO VERIFY] Read screen as a JSON tree of clickable elements with their bounds. Use to find tappable elements (icons, buttons) when clickByText fails."),
        Schema("tap", "[VERIFY ALWAYS] [VERIFY ALWAYS] Tap at screen coordinates. Pass {x, y} for a coordinate tap, OR {target: \"text\"} to click by text (redirects to clickByText). IMPORTANT: Call readScreenStructured first to get element bounds, then tap the center: x=(left+right)/2, y=(top+bottom)/2. Do NOT guess coordinates. Screen resolution varies by device.",
            mapOf(
                "x" to Param("number", "X coordinate. Get from readScreenStructured bounds: (left+right)/2.", required = false),
                "y" to Param("number", "Y coordinate. Get from readScreenStructured bounds: (top+bottom)/2.", required = false),
                "target" to Param("string", "Text of element to click (alternative to x/y). Use when you don't know exact coords.", required = false),
            )),
        Schema("clickByText", "[VERIFY ALWAYS] [VERIFY ALWAYS] Click a UI element by its visible text. Supports partial matching. SILENTLY FAILS on icon-only UIs (no text) — use readScreenStructured instead.",
            mapOf("text" to Param("string", "Text of the element to click (supports partial match).", required = true))),
        // v3.9.9: clickElement — PREFERRED way to click. No coordinate guessing.
        // Brain reads readScreenStructured (elements have "index" field), picks the
        // element it wants (e.g., first video result), calls clickElement(index=N).
        // The app taps that element's bounds center automatically.
        Schema("clickElement", "[VERIFY ALWAYS] [VERIFY ALWAYS] Click an element by its 1-based index from readScreenStructured(). PREFERRED over tap(x,y) — no coordinate guessing. Call readScreenStructured() first to see element indices, then clickElement(index=N) to click the Nth element.",
            mapOf("index" to Param("number", "1-based index of the element from readScreenStructured(). Example: if the first video result has index 3 in the JSON, call clickElement(index=3).", required = true))),
        // v4.0.2: longClick — long press for context menus, drag, etc.
        Schema("longClick", "[VERIFY ALWAYS] [VERIFY ALWAYS] Long press at screen coordinates (500ms hold). Use for context menus, drag-and-drop, or elements that respond to long press. Get coordinates from readScreenStructured bounds.",
            mapOf(
                "x" to Param("number", "X coordinate.", required = true),
                "y" to Param("number", "Y coordinate.", required = true),
            )),
        Schema("longClickElement", "[VERIFY ALWAYS] [VERIFY ALWAYS] Long press an element by index from readScreenStructured(). Tries ACTION_LONG_CLICK first, falls back to gesture.",
            mapOf("index" to Param("number", "1-based index from readScreenStructured().", required = true))),
        // v4.0.2: focusElement — focus before typing
        Schema("focusElement", "[BATCHABLE with type] [BATCHABLE with type] Focus an element by index. Some apps need focus before type() works. Call this if type() returns success but text doesn't appear.",
            mapOf("index" to Param("number", "1-based index from readScreenStructured().", required = true))),
        // v4.0.2: paste — paste clipboard into field
        Schema("paste", "[VERIFY ALWAYS] [VERIFY ALWAYS] Paste clipboard content into an element by index. Focuses the element + performs ACTION_PASTE. Use after setClipboard to paste text into a field.",
            mapOf("index" to Param("number", "1-based index of the target input field from readScreenStructured().", required = true))),
        // v4.0.2: edit — type + optional submit in ONE call (saves API calls)
        Schema("edit", "[BATCHABLE with submitInput] [BATCHABLE with submitInput] Edit text in an element by index. Clears existing text + types new text in ONE call. If autoSubmit=true, also submits the form/search. Saves 1 API call vs type()+submitInput(). Use for search boxes.",
            mapOf(
                "index" to Param("number", "1-based index of the input field from readScreenStructured().", required = true),
                "text" to Param("string", "Text to type into the field (replaces existing text).", required = true),
                "autoSubmit" to Param("boolean", "If true, also calls submitInput after typing (submits the search/form). Default false.", required = false),
            )),
        Schema("type", "[BATCHABLE with submitInput] [BATCHABLE with submitInput] Type text into the currently focused input field. Use after tapping a search box or text field.",
            mapOf("text" to Param("string", "Text to type into the focused field.", required = true))),
        Schema("swipe", "[VERIFY ALWAYS] [VERIFY ALWAYS] Swipe from one point to another.",
            mapOf(
                "x1" to Param("number", "Start X.", required = true),
                "y1" to Param("number", "Start Y.", required = true),
                "x2" to Param("number", "End X.", required = true),
                "y2" to Param("number", "End Y.", required = true),
            )),
        Schema("scrollDown", "[VERIFY ALWAYS] [VERIFY ALWAYS] Scroll the current screen down. No parameters."),
        Schema("scrollUp", "[VERIFY ALWAYS] [VERIFY ALWAYS] Scroll the current screen up. No parameters."),
        Schema("pressBack", "Press the Android Back button. Use to exit a video, dismiss a screen, or return to the previous page. No parameters."),
        Schema("pressHome", "Press the Android Home button. Returns to the launcher. No parameters."),
        Schema("pressEnter", "Press the Enter key. No parameters."),
        Schema("submitInput", "[VERIFY ALWAYS] Submit the currently focused form/search field (presses Enter on the IME). Use after type() in a search box. No parameters."),
        Schema("lockScreen", "[VERIFY ALWAYS] Lock the device screen. No parameters."),
        Schema("takeScreenshotToGallery", "[BATCHABLE] [NO VERIFY] Take a screenshot and save it to the Pictures folder. No parameters."),

        // ==================== APPS (10) ====================
        Schema("launchApp", "[VERIFY AFTER] Launch an app by package name (e.g., com.google.android.youtube). This is the ONLY tool for opening apps — there is no open_youtube_app or open_spotify_app tool. Returns screen content after 2.5s. ALWAYS read the returned screen — apps may resume to unexpected states (videos, Shorts).",
            mapOf("package" to Param("string", "App package name (e.g., com.google.android.youtube, com.whatsapp, com.spotify.music).", required = true))),
        Schema("listInstalledApps", "[BATCHABLE] [NO VERIFY] List all installed apps with their package names. No parameters."),
        Schema("getAppInfo", "[BATCHABLE] [NO VERIFY] Get detailed info about an installed app (version, size, permissions).",
            mapOf("package" to Param("string", "App package name.", required = true))),
        Schema("forceStopApp", "[VERIFY AFTER] Force-stop an app (kills it).",
            mapOf("package" to Param("string", "App package name.", required = true))),
        Schema("uninstallApp", "[SINGLE CALL] Uninstall an app (shows the uninstall confirmation screen).",
            mapOf("package" to Param("string", "App package name.", required = true))),
        Schema("openDialer", "[VERIFY AFTER] Open the phone dialer. Pass a number to pre-fill it, or omit to open empty.",
            mapOf("number" to Param("string", "Phone number to pre-fill (optional).", required = false))),
        Schema("openContact", "[VERIFY AFTER] Open a contact by phone number (opens the contact card).",
            mapOf("number" to Param("string", "Phone number of the contact.", required = true))),
        Schema("openSettings", "[VERIFY AFTER] Deep-link to a specific Android Settings page.",
            mapOf("page" to Param("string", "Settings page: wifi, bluetooth, location, accessibility, notification, apps, display, sound, battery, storage, datetime, language, developer, about, or main.", required = true))),
        Schema("shareText", "[VERIFY AFTER] Open the Android share sheet with text.",
            mapOf("text" to Param("string", "Text to share.", required = true))),
        Schema("shareFile", "[VERIFY AFTER] Open the Android share sheet with a file.",
            map_of_file_path_caption()),
        Schema("speak", "[BATCHABLE] [NO VERIFY] v3.0.0: Speak text aloud using TTS. Useful for rules + hands-free.",
            mapOf("text" to Param("string", "Text to speak.", required = true))),

        // ==================== VISION (3) ====================
        Schema("analyzeScreen", "[BATCHABLE] [NO VERIFY] VLM: analyze the current screen with a natural-language prompt. Uses 1 API call. Use for screenshots with no text.",
            mapOf("prompt" to Param("string", "What to look for (e.g., 'find the login button').", required = true))),
        Schema("findElement", "[BATCHABLE] [NO VERIFY] VLM: find a UI element by description. Returns coordinates. Slow — use readScreenStructured first.",
            mapOf("description" to Param("string", "Description of the element (e.g., 'red subscribe button').", required = true))),
        Schema("translateText", "[BATCHABLE] [NO VERIFY] Translate text to a target language using VLM.",
            mapOf(
                "text" to Param("string", "Text to translate.", required = true),
                "targetLang" to Param("string", "Target language (e.g., 'English', 'Hindi').", required = true),
            )),

        // ==================== BROWSER (21) ====================
        Schema("browserOpen", "[VERIFY AFTER] Open a URL in the in-app browser (VISIBLE — user sees it). Call ONCE per site.",
            mapOf("url" to Param("string", "URL to open.", required = true))),
        Schema("browserReadStructured", "[BATCHABLE] [NO VERIFY] Read the current browser page as a JSON tree of clickable elements + forms. Use after browserOpen."),
        Schema("browserReadPage", "[BATCHABLE] [NO VERIFY] Read the current browser page as plain text."),
        Schema("browserGetLinks", "[BATCHABLE] [NO VERIFY] Get all links on the current browser page."),
        Schema("browserGetForms", "[BATCHABLE] [NO VERIFY] Get all forms on the current browser page."),
        Schema("browserFillForm", "[VERIFY ALWAYS] Fill a form field in the browser by field name/selector.",
            map_of_browser_fill_form()),
        Schema("browserClickElement", "[VERIFY ALWAYS] Click an element in the browser by CSS selector.",
            mapOf("selector" to Param("string", "CSS selector of the element.", required = true))),
        Schema("browserClickText", "[VERIFY ALWAYS] Click an element in the browser by visible text.",
            mapOf("text" to Param("string", "Visible text of the element to click.", required = true))),
        Schema("browserGetText", "[BATCHABLE] [NO VERIFY] Get text content of the current browser page."),
        Schema("browserWaitForElement", "[BATCHABLE] [NO VERIFY] Wait until an element appears in the browser.",
            map_of_browser_wait()),
        Schema("browserScreenshot", "[BATCHABLE] [NO VERIFY] Take a screenshot of the browser. Optional prompt analyzes it with VLM.",
            mapOf("prompt" to Param("string", "Optional: VLM prompt to analyze the screenshot.", required = false))),
        Schema("browserScrollDown", "[VERIFY AFTER] Scroll the browser page down. No parameters."),
        Schema("browserEval", "[VERIFY AFTER] Execute JavaScript in the browser (returns the result).",
            mapOf("js" to Param("string", "JavaScript code to execute.", required = true))),
        Schema("browserBack", "[VERIFY AFTER] Navigate the browser back (history). No parameters."),
        Schema("browserGetUrl", "[BATCHABLE] [NO VERIFY] Get the current browser URL. No parameters."),
        Schema("browserSearch", "[VERIFY AFTER] Search a query in the browser (uses the default search engine).",
            mapOf("query" to Param("string", "Search query.", required = true))),
        Schema("browserFill", "[VERIFY ALWAYS] Fill a single input field by selector with value.",
            map_of_browser_fill()),
        Schema("browserClick", "[VERIFY ALWAYS] Click an element by selector (generic).",
            mapOf("selector" to Param("string", "CSS selector.", required = true))),
        Schema("browserListClickable", "[BATCHABLE] [NO VERIFY] List all clickable elements on the page. No parameters."),
        Schema("openInChrome", "[VERIFY AFTER] Open a URL in Chrome (uses Chrome, not in-app browser). Needs accessibility for interaction.",
            mapOf("url" to Param("string", "URL to open.", required = true))),
        Schema("searchInChrome", "[VERIFY AFTER] Search a query in Chrome.",
            mapOf("query" to Param("string", "Search query.", required = true))),

        // ==================== WEB (5) ====================
        Schema("webSearch", "[BATCHABLE] [NO VERIFY] Search the web (background — no browser opens). Returns results with title, url, snippet. Use fetchTop=N to auto-fetch top N results inline (1 call instead of N+1).",
            map_of_web_search()),
        Schema("makeHttpRequest", "[BATCHABLE] [NO VERIFY] Make a raw HTTP request to any URL.",
            map_of_make_http_request()),
        Schema("downloadFile", "[BATCHABLE] [VERIFY ON FAIL] Download a file from a URL to the local filesystem.",
            map_of_download_file()),
        Schema("openUrl", "[VERIFY AFTER] Open a URL using the system default handler (browser, app, etc.).",
            mapOf("url" to Param("string", "URL to open.", required = true))),
        Schema("fetchPageText", "[BATCHABLE] [NO VERIFY] Fetch a URL and return its text content (background, no browser).",
            mapOf("url" to Param("string", "URL to fetch.", required = true))),

        // ==================== DEVICE (12) ====================
        Schema("getBatteryLevel", "[BATCHABLE] [NO VERIFY] Get current battery level (0-100). No parameters."),
        Schema("getCurrentTime", "[BATCHABLE] [NO VERIFY] Get the current date and time. No parameters."),
        Schema("getBluetoothState", "[BATCHABLE] [NO VERIFY] Get Bluetooth state (on/off, connected devices). No parameters."),
        Schema("getNetworkInfo", "[BATCHABLE] [NO VERIFY] Get network info (wifi/cellular, connected, type). No parameters."),
        Schema("getDeviceInfo", "[BATCHABLE] [NO VERIFY] Get device info (model, Android version, manufacturer). No parameters."),
        Schema("pingHost", "[BATCHABLE] [NO VERIFY] Ping a host to check connectivity.",
            mapOf("host" to Param("string", "Hostname or IP to ping.", required = true))),
        Schema("getVolume", "[BATCHABLE] [NO VERIFY] Get current volume level. No parameters."),
        Schema("setVolume", "[BATCHABLE] [NO VERIFY] Set the volume level.",
            mapOf("level" to Param("number", "Volume level (0-15).", required = true))),
        Schema("setBrightness", "[BATCHABLE] [NO VERIFY] Set screen brightness.",
            mapOf("level" to Param("number", "Brightness level (0-255).", required = true))),
        Schema("toggleFlashlight", "[BATCHABLE] [NO VERIFY] Toggle the flashlight on/off. No parameters."),
        Schema("toggleWifi", "[SINGLE CALL] Toggle WiFi on/off. No parameters."),

        // ==================== MEMORY & KNOWLEDGE (8) ====================
        Schema("remember", "[BATCHABLE] [NO VERIFY] Save a key-value pair to memory. Use for workflows, lessons, user preferences.",
            map_of_remember()),
        Schema("recall", "[BATCHABLE] [NO VERIFY] Recall a value by exact key.",
            mapOf("key" to Param("string", "Key to recall.", required = true))),
        Schema("recallAll", "[BATCHABLE] [NO VERIFY] List all saved memory keys. No parameters."),
        Schema("recallSimilar", "[BATCHABLE] [NO VERIFY] Semantic search of memory (BM25-ranked). Use at task START to find prior workflows.",
            mapOf("query" to Param("string", "What you're trying to do (e.g., 'send whatsapp message').", required = true))),
        Schema("searchMemory", "[BATCHABLE] [NO VERIFY] Full-text search of memory.",
            mapOf("query" to Param("string", "Search query.", required = true))),
        Schema("saveToKnowledge", "[BATCHABLE] [NO VERIFY] Save a document to the knowledge base (longer-term than memory).",
            map_of_save_knowledge()),
        Schema("recallKnowledge", "[BATCHABLE] [NO VERIFY] Semantic search of the knowledge base.",
            mapOf("query" to Param("string", "Search query.", required = true))),
        Schema("listKnowledge", "[BATCHABLE] [NO VERIFY] List all saved knowledge documents. No parameters."),

        // ==================== CLIPBOARD (2) ====================
        Schema("getClipboard", "[BATCHABLE] [NO VERIFY] Get the current clipboard text. No parameters."),
        Schema("setClipboard", "[BATCHABLE] [NO VERIFY] Set the clipboard text.",
            mapOf("text" to Param("string", "Text to set on the clipboard.", required = true))),

        // ==================== LOCATION (3) ====================
        Schema("getCurrentLocation", "[BATCHABLE] [NO VERIFY] Get current GPS location (lat, lon, address). Needs location permission. No parameters."),
        Schema("openMaps", "[VERIFY AFTER] Open Google Maps app.",
            mapOf("query" to Param("string", "Location to search (optional).", required = false))),
        Schema("getWeather", "[BATCHABLE] [NO VERIFY] Get current weather for the device location (uses GPS + Open-Meteo). NEVER webSearch for weather. No parameters."),

        // ==================== RULES (4) ====================
        Schema("createRule", "[SINGLE CALL] Create an automation rule (trigger → action). 36 trigger types.",
            map_of_create_rule()),
        Schema("listRules", "[BATCHABLE] [NO VERIFY] List all saved rules. No parameters."),
        Schema("deleteRule", "[SINGLE CALL] Delete a rule by ID.",
            mapOf("id" to Param("string", "Rule ID to delete.", required = true))),
        Schema("modifyRule", "[SINGLE CALL] Modify an existing rule.",
            map_of_modify_rule()),

        // ==================== CONTACTS & PHONE (8) ====================
        Schema("readContacts", "[BATCHABLE] [NO VERIFY] Read the contact list. Needs READ_CONTACTS permission. No parameters."),
        Schema("searchContacts", "[BATCHABLE] [NO VERIFY] Search contacts by name.",
            mapOf("name" to Param("string", "Name to search.", required = true))),
        Schema("getCalendarEvents", "[BATCHABLE] [NO VERIFY] Get upcoming calendar events. No parameters."),
        Schema("createCalendarEvent", "[SINGLE CALL] Create a calendar event.",
            map_of_calendar_event()),
        Schema("setAlarm", "[BATCHABLE] [NO VERIFY] Set an alarm.",
            map_of_set_alarm()),
        Schema("setTimer", "[BATCHABLE] [NO VERIFY] Set a timer.",
            map_of_set_timer()),
        Schema("sendSMS", "[SINGLE CALL] Send an SMS message. Marked safe:false in action chains.",
            map_of_send_sms()),
        Schema("callContact", "[SINGLE CALL] Make a phone call to a number. Marked safe:false in action chains.",
            mapOf("number" to Param("string", "Phone number to call.", required = true))),
        Schema("getCallLog", "[BATCHABLE] [NO VERIFY] Get the call log. No parameters."),
        Schema("sendEmail", "[SINGLE CALL] Send an email (opens email app).",
            map_of_send_email()),
        Schema("makePhoneCall", "[SINGLE CALL] Make a phone call (alias of callContact). Marked safe:false.",
            mapOf("number" to Param("string", "Phone number.", required = true))),
        Schema("composeEmail", "[SINGLE CALL] Compose an email (opens the email composer).",
            map_of_compose_email()),

        // ==================== FILES (10) ====================
        Schema("listFiles", "[BATCHABLE] [NO VERIFY] List files in a directory.",
            mapOf("path" to Param("string", "Directory path (default: Aira).", required = false))),
        Schema("readFile", "[BATCHABLE] [NO VERIFY] Read a text file.",
            mapOf("path" to Param("string", "File path.", required = true))),
        Schema("writeFile", "[BATCHABLE] [NO VERIFY] Write text to a file. Marked safe:false in action chains.",
            map_of_write_file()),
        Schema("copyFile", "[BATCHABLE] [NO VERIFY] Copy a file.",
            map_of_copy_file()),
        Schema("moveFile", "[BATCHABLE] [NO VERIFY] Move a file.",
            map_of_move_file()),
        Schema("deleteFile", "[SINGLE CALL] Delete a file. Marked safe:false in action chains.",
            mapOf("path" to Param("string", "File path to delete.", required = true))),
        Schema("createDirectory", "[BATCHABLE] [NO VERIFY] Create a directory.",
            mapOf("path" to Param("string", "Directory path to create.", required = true))),
        Schema("listAttachments", "List files in the attachments folder. No parameters."),
        Schema("readAttachment", "Read an attached file.",
            mapOf("filename" to Param("string", "Attachment filename.", required = true))),
        Schema("sendAttachmentTo", "Send an attachment to another app.",
            map_of_send_attachment()),

        // ==================== ACTIVITY SHORTCUTS (12) ====================
        Schema("openActivity", "[VERIFY AFTER] Open a specific activity (screen) inside an app.",
            map_of_open_activity()),
        Schema("openDeepLink", "[VERIFY AFTER] Open a deep link (URL that opens in an app).",
            map_of_open_deep_link()),
        Schema("openAppWithData", "[VERIFY AFTER] Open an app with data (intent extras).",
            map_of_open_app_with_data()),
        Schema("searchInApp", "[VERIFY AFTER] Search inside an app (YouTube, Spotify, Instagram, Chrome). 1 call — opens the app + runs the search + shows results IN THE APP (not browser). Does NOT tap a result — follow up with readScreenStructured + tap to play/open a result.",
            map_of_search_in_app()),
        Schema("listAppActivities", "[BATCHABLE] [NO VERIFY] List all activities (screens) in an app.",
            mapOf("package" to Param("string", "App package name.", required = true))),
        Schema("openWhatsAppChat", "[VERIFY AFTER] Open a WhatsApp chat with a phone number. 1 call vs 5. Optional message pre-fills the chat (user taps send). Marked safe:false if message is set.",
            map_of_whatsapp()),
        Schema("openYouTubeVideo", "[VERIFY AFTER] DISABLED — do NOT use. Always returns an error. Instead: use searchInApp to search, then readScreenStructured to find the video result's bounds, then tap(center of bounds) to play it.",
            mapOf("videoId" to Param("string", "DISABLED — do not use this tool.", required = false))),
        Schema("openMapsLocation", "[VERIFY AFTER] Open Google Maps at a specific location.",
            mapOf("query" to Param("string", "Location to show.", required = true))),
        Schema("playSpotify", "[VERIFY AFTER] Open Spotify + search + auto-play first result. 1 call.",
            mapOf("query" to Param("string", "What to play (song/artist/playlist).", required = true))),
        Schema("openInstagramProfile", "[VERIFY AFTER] Open an Instagram profile by username. 1 call.",
            mapOf("username" to Param("string", "Instagram username.", required = true))),
        Schema("openTelegramChat", "[VERIFY AFTER] Open a Telegram chat by username. 1 call.",
            mapOf("username" to Param("string", "Telegram username.", required = true))),
        Schema("openGoogleSearch", "[VERIFY AFTER] Open Google Search with a query.",
            mapOf("query" to Param("string", "Search query.", required = true))),
        Schema("shareToApp", "[VERIFY AFTER] Share text to a specific app.",
            map_of_share_to_app()),

        // ==================== NOTIFICATION (1) ====================
        Schema("replyToNotification", "[SINGLE CALL] Reply to a notification from an app (e.g., WhatsApp message notification).",
            map_of_reply_notification()),
        // v4.0.71: listNotifications — read ALL active notifications via NotificationListenerService
        Schema("listNotifications", "[BATCHABLE] [NO VERIFY] List all active notifications on the phone. Returns app name, title, text, and time for each notification. Uses NotificationListenerService (requires notification access permission). Example: listNotifications() returns [{app: 'com.whatsapp', title: 'Mom', text: 'Call me'}, {app: 'com.google.android.gm', title: 'Amazon', text: 'Your order shipped'}]. Use to answer 'what notifications do I have?' or 'who texted me?'."),

        // ==================== MEDIA (4) ====================
        Schema("mediaPlayPause", "[VERIFY AFTER] Toggle media play/pause. No parameters."),
        Schema("mediaNext", "[VERIFY AFTER] Skip to next media track. No parameters."),
        Schema("mediaPrevious", "[VERIFY AFTER] Go to previous media track. No parameters."),
        Schema("takePhoto", "[SINGLE CALL] Take a photo with the camera. Needs CAMERA permission. No parameters."),

        // ==================== SKILLS (5) ====================
        Schema("executeSkill", "[SINGLE CALL] Load a skill's step-by-step instructions into context. Use this when the user's request is complex/multi-step AND a relevant skill is listed at the end of the system prompt. Returns the skill's instructions — read them, then call the tools they specify in order.",
            mapOf("name" to Param("string", "Skill name (e.g., play-youtube). See 'Relevant Skills' section at end of prompt.", required = true))),
        Schema("listSkills", "[BATCHABLE] [NO VERIFY] List all available skills. No parameters."),
        Schema("createSkill", "[SINGLE CALL] Create a new skill (composite workflow).",
            map_of_create_skill()),
        Schema("deleteSkill", "[SINGLE CALL] Delete a skill by name.",
            mapOf("name" to Param("string", "Skill name to delete.", required = true))),
        Schema("getSkill", "[BATCHABLE] [NO VERIFY] Get a skill's details by name.",
            mapOf("name" to Param("string", "Skill name.", required = true))),

        // ==================== ASYNC (2) ====================
        Schema("wait", "[BATCHABLE] [NO VERIFY] Wait for N seconds, then continue. Use for short waits (5-60s).",
            mapOf("seconds" to Param("number", "Seconds to wait (1-60).", required = true))),
        Schema("waitAndContinue", "[BATCHABLE] [NO VERIFY] Wait N seconds with a reason (for long waits, use createRule instead).",
            map_of_wait_continue()),

        // ==================== ACTION CHAINS (1) ====================
        Schema("actionChain", "[VERIFY ALWAYS] v3.7.0: Execute multiple actions sequentially in 1 API call. Saves 50-70% calls. Stops on first failure. Actions with safe:false PAUSE the chain. Screen state is auto-appended after.",
            map_of_action_chain()),
        // v3.9.6: Completion tool — forces brain to verify success before stopping.
        Schema("complete", "[LAST CALL] Call this when the task is COMPLETE. You MUST verify success via readScreen/VLM BEFORE calling this. Do NOT call this unless you have confirmed the task succeeded (or failed).",
            mapOf(
                "success" to Param("boolean", "true if task succeeded, false if it failed or is impossible.", required = true),
                "reason" to Param("string", "Why the task is complete. Include what you see on screen as proof (e.g., 'Video playing at 9:07, 212M views').", required = true),
            )),
        // v4.0.62: TodoWrite — structured planning + progress tracking.
        // The AI creates a todo list at task start, then updates it as steps complete.
        // The list is injected into subsequent prompts so the AI stays on track.
        Schema("todoWrite", "[BATCHABLE] [NO VERIFY] Plan + track a multi-step task. Call this BEFORE starting complex tasks (3+ steps). Break the task into ordered steps. Update statuses as you progress. The list persists across iterations + is shown to you in subsequent prompts so you never lose track. Status: 'pending' (not started), 'in_progress' (currently working on it — only ONE at a time), 'completed' (done + verified). Example: todoWrite(todos='[{\"content\":\"Search for wallpaper\",\"status\":\"in_progress\"},{\"content\":\"Download image\",\"status\":\"pending\"},{\"content\":\"Set as wallpaper\",\"status\":\"pending\"}]')",
            mapOf(
                "todos" to Param("string", "JSON array of todo items. Each item: {\"content\":\"step description\",\"status\":\"pending\"|\"in_progress\"|\"completed\"}. Pass the FULL list every time (not just changes). Max 10 items.", required = true),
            )),
        // v4.0.69: delegateTask — spawn a sub-agent for focused tasks (saves tokens)
        Schema("escalateLevel", "[BATCHABLE] [NO VERIFY] Escalate to a higher capability level for more tools. Level 1=basic (current), Level 2=screen+app+browser+file+media, Level 3=terminal+all tools. Call when you need tools not available at your current level. Example: escalateLevel(2) unlocks screen tools, escalateLevel(3) unlocks terminal tools.",
            mapOf(
                "level" to Param("number", "Target level: 2 (standard) or 3 (advanced/terminal).", required = true),
            )),
        Schema("delegateTask", "[SINGLE CALL] Delegate a task to a sub-agent. The sub-agent runs its own mini agent loop with LIMITED tools + a FOCUSED prompt (saves ~60% tokens vs doing it in the main loop). Use for: research (web search), terminal (install packages, run scripts), screen (tap/type/click). The sub-agent returns its result to you. Example: delegateTask(description=\"Find and download a car wallpaper\", agentType=\"research\").",
            mapOf(
                "description" to Param("string", "The task for the sub-agent. Be specific — the sub-agent only sees this description, not the conversation history. E.g., 'Download a car wallpaper image to /sdcard/Pictures/car.jpg'.", required = true),
                "agentType" to Param("string", "Agent type: 'research' (web search + download), 'terminal' (runOnTerminal + connectTerminal), 'screen' (readScreen + tap + type), or 'general' (all tools). Default: 'general'.", required = false),
            )),
        // v4.0.26: Custom tool management — lets the AI create new tools at runtime.
        Schema("createCustomTool", "[SINGLE CALL] Create a NEW custom tool at runtime. Use when you need a tool that doesn't exist (e.g., call a custom API, run a specific shell command). The tool persists across app restarts. Two handler types: 'shell' (runs a shell command with {param} substitution) or 'http' (makes an HTTP GET/POST with {param} substitution in URL+body). Example: createCustomTool(name='check_server', description='Check server status', handlerType='http', handler='{\"method\":\"GET\",\"url\":\"https://myserver.com/health\"}', parameters=[]). After creating, call the tool by its name directly.",
            mapOf(
                "name" to Param("string", "Tool name (lowercase, underscore). Example: 'check_server_status'. Must not conflict with built-in tools.", required = true),
                "description" to Param("string", "What this tool does. Be specific — this is what the AI sees in the tool list.", required = true),
                "handlerType" to Param("string", "Type of handler: 'shell' (runs shell command) or 'http' (makes HTTP request).", required = true),
                "handler" to Param("string", "For 'shell': the command with {param} placeholders (e.g., 'curl -s https://api.com?q={query}'). For 'http': JSON config like '{\"method\":\"GET\",\"url\":\"https://...\",\"headers\":{\"Authorization\":\"Bearer xxx\"}}'. Use {param} placeholders in URL/body.", required = true),
                "parameters" to Param("string", "Comma-separated parameter names (e.g., 'query,limit'). These are substituted into the handler's {param} placeholders.", required = false),
            )),
        Schema("listCustomTools", "[BATCHABLE] [NO VERIFY] List all custom tools you've created. Shows name, description, handler type, and handler for each."),
        Schema("deleteCustomTool", "[SINGLE CALL] Delete a custom tool by name.",
            mapOf(
                "name" to Param("string", "Name of the custom tool to delete.", required = true),
            )),
        // v4.0.88: Connector management — AI-created connectors with OAuth support
        Schema("createConnector", "[SINGLE CALL] Create a connector to an external API. For COMMON APIs (Gmail, GitHub, Slack, Notion, etc.) you already know the endpoints from training — create directly without searching. For CUSTOM/unknown APIs, webSearch the docs first, then create. Each endpoint becomes a tool: {name}__{endpoint}. Three auth types: 'none' (public API), 'apikey' (bearer token), 'oauth_pkce' (OAuth 2.0 + PKCE browser login). For oauth_pkce, you need a clientId — ask the user to provide one (they register an OAuth app with the provider). After creating, call connectConnector(name) to open the browser login.",
            mapOf(
                "name" to Param("string", "Short identifier (lowercase, e.g. 'gmail', 'home_assistant').", required = true),
                "description" to Param("string", "What this connector does (shown to user).", required = true),
                "authType" to Param("string", "Auth: 'none' | 'apikey' | 'oauth_pkce'.", required = true),
                "apiBase" to Param("string", "API base URL, e.g. 'https://gmail.googleapis.com'.", required = true),
                "endpoints" to Param("string", "JSON array: [{\"name\":\"send_email\",\"method\":\"POST\",\"path\":\"/gmail/v1/users/me/messages/send\",\"bodyTemplate\":\"{\\\"raw\\\":\\\"{raw}\\\"}\"}]. Use {param} placeholders in path/body.", required = true),
                "apiKey" to Param("string", "For apikey auth: the API key/token.", required = false),
                "apiKeyHeader" to Param("string", "Header name for API key (default 'Authorization').", required = false),
                "apiKeyPrefix" to Param("string", "Prefix before key (default 'Bearer', use '' for raw).", required = false),
                "authorizeUrl" to Param("string", "For oauth_pkce: provider's authorize URL.", required = false),
                "tokenUrl" to Param("string", "For oauth_pkce: provider's token URL.", required = false),
                "scopes" to Param("string", "For oauth_pkce: space-separated OAuth scopes.", required = false),
                "clientId" to Param("string", "For oauth_pkce: OAuth client_id (register an app with the provider).", required = false),
            )),
        Schema("listConnectors", "[BATCHABLE] [NO VERIFY] List all connectors you've created. Shows name, auth type, connection status, and available tools."),
        Schema("deleteConnector", "[SINGLE CALL] Delete a connector and all its tools.",
            mapOf(
                "name" to Param("string", "Connector name to delete.", required = true),
            )),
        Schema("connectConnector", "[SINGLE CALL] Start the OAuth login flow for a connector (authType='oauth_pkce'). Opens the browser for the user to authorize. After approval, the connector's tools become callable.",
            mapOf(
                "name" to Param("string", "Connector name to connect.", required = true),
            )),
        // v4.0.61: searchTools — search ALL tools (built-in + custom) by name or description.
        // Use when you're not sure which tool to use, or when you think a tool might not exist.
        // Returns matching tool names + descriptions (max 15 results).
        Schema("searchTools", "[BATCHABLE] [NO VERIFY] Search ALL available tools (built-in + custom) by keyword. Use when you're not sure which tool to use, or to check if a tool exists before creating a custom one. Returns tool name + description for each match. Example: searchTools(query='rule') returns createRule, listRules, deleteRule, modifyRule. Example: searchTools(query='whatsapp notification') returns replyToNotification, openWhatsAppChat.",
            mapOf(
                "query" to Param("string", "Search query — keyword or phrase. Matches tool names AND descriptions (case-insensitive). E.g., 'download file', 'whatsapp', 'rule', 'brightness'.", required = true),
            )),
        // v4.0.63: Terminal management tools — connect to any terminal (AndroidIDE, Termux, laptop, cloud)
        Schema("connectTerminal", "[BATCHABLE] [NO VERIFY] Connect to a terminal server (aira-server.js). Tests connection + auto-detects capabilities (pkg, node, java, git, etc.). The terminal must be running aira-server.js. For AndroidIDE-Rv2 on the same phone, use host='127.0.0.1'. For other devices on same WiFi, use their IP (e.g., '192.168.1.100'). Example: connectTerminal(name='phone-androidide', host='127.0.0.1', port=8080, token='aira', type='androidide').",
            mapOf(
                "name" to Param("string", "Unique name for this terminal. E.g., 'phone-androidide', 'laptop-linux', 'tablet-termux'.", required = true),
                "host" to Param("string", "IP address or hostname. '127.0.0.1' for localhost, or WiFi IP like '192.168.1.100'.", required = true),
                "port" to Param("number", "Port number. Default: 8080.", required = false),
                "token" to Param("string", "Auth token. Default: 'aira'. Must match the server's token.", required = false),
                "type" to Param("string", "Terminal type: 'androidide', 'termux', 'laptop', 'cloud', or 'custom'.", required = false),
            )),
        Schema("listTerminals", "[BATCHABLE] [NO VERIFY] List all configured terminal connections + their status (connected/disconnected) + capabilities. No parameters."),
        Schema("runOnTerminal", "[BATCHABLE] [VERIFY ON FAIL] Run a shell command on a specific terminal. The terminal must be connected first (call connectTerminal). Returns stdout. Example: runOnTerminal(name='phone-androidide', command='ls /sdcard/').",
            mapOf(
                "name" to Param("string", "Terminal name (must match a configured terminal).", required = true),
                "command" to Param("string", "Shell command to run.", required = true),
                "timeout" to Param("number", "Timeout in seconds (default: 120, max: 600).", required = false),
            )),
        Schema("disconnectTerminal", "[BATCHABLE] [NO VERIFY] Disconnect from a terminal. The terminal stays configured but won't accept commands until reconnected.",
            mapOf(
                "name" to Param("string", "Terminal name to disconnect.", required = true),
            )),
        // v4.0.28: Wallpaper tool
        Schema("setWallpaper", "[SINGLE CALL] Set an image file as the phone wallpaper. Works with any image path (e.g., /storage/emulated/0/Pictures/car.jpg). Use after downloadFile to set the downloaded image as wallpaper.",
            mapOf(
                "path" to Param("string", "Path to the image file (e.g., /storage/emulated/0/Pictures/car_wallpaper.jpg).", required = true),
                "which" to Param("string", "Which wallpaper to set: 'home', 'lock', or 'both' (default).", required = false),
            )),
        // v4.0.29: On-demand context tools
        Schema("getConversationHistory", "[BATCHABLE] [NO VERIFY] Load MORE conversation history when you need full context. Default returns last 20 messages. Use when 'try again' or complex tasks need more context than the short summary in the prompt. Call this if you're unsure what the user previously asked for.",
            mapOf(
                "limit" to Param("number", "Number of recent messages to load (default 20, max 50).", required = false),
            )),
        Schema("getFullMemory", "[BATCHABLE] [NO VERIFY] Read ALL stored memories (not just the top 10 in the prompt). Use when recallSimilar might have missed something, or when you need to find a specific past fact/workflow.",
            mapOf()),
        // v4.0.41: Conversation summary — WhatsApp/messaging history
        Schema("getConversationSummary", "[BATCHABLE] [NO VERIFY] Read WhatsApp/messaging conversation history. Use when user asks 'what messages did I get?', 'who texted me?', 'summarize WhatsApp replies', 'what did Mom say?'. Pass contact='all' for all conversations, or contact='whatsapp'/'telegram'/'Mom' for specific.",
            mapOf(
                "contact" to Param("string", "Contact/app name: 'all' (default), 'whatsapp', 'telegram', 'Mom', or a phone number. Partial matches work.", required = false),
            )),
        // v4.0.30: Permission-based tools
        Schema("readSms", "[BATCHABLE] [NO VERIFY] Read recent SMS messages from the inbox. Useful for 'who texted me?' or 'read my last messages'.",
            mapOf(
                "limit" to Param("number", "Max messages to return (default 10, max 50).", required = false),
            )),
        Schema("vibrate", "[BATCHABLE] [NO VERIFY] Vibrate the device for haptic feedback. Great for rules: direct:vibrate(pattern=double) as a silent alert.",
            mapOf(
                "duration" to Param("number", "Vibration duration in ms (default 500).", required = false),
                "pattern" to Param("string", "Pattern: 'short', 'long', 'double', 'triple' (overrides duration).", required = false),
            )),
        Schema("getPhoneState", "[BATCHABLE] [NO VERIFY] Get carrier name, SIM status, network type, signal strength. Uses READ_PHONE_STATE permission.",
            mapOf()),
        Schema("wakeLock", "[BATCHABLE] [NO VERIFY] Keep the CPU awake for N seconds (even if screen is off). Useful for long-running rules that need to run while phone is sleeping.",
            mapOf(
                "seconds" to Param("number", "Seconds to hold the wake lock (default 60, max 600).", required = true),
            )),
        // v4.0.30: Device info tools (10 new read-only + action tools)
        Schema("getBrightness", "[BATCHABLE] [NO VERIFY] Get current screen brightness level + mode (auto/manual).",
            mapOf()),
        Schema("getFlashlightState", "[BATCHABLE] [NO VERIFY] Check if the flashlight/torch is available on this device.",
            mapOf()),
        Schema("getDeviceUptime", "[BATCHABLE] [NO VERIFY] How long since the device was last booted (days/hours/minutes).",
            mapOf()),
        Schema("getMemoryInfo", "[BATCHABLE] [NO VERIFY] Get RAM info: total, used, available, low-memory threshold.",
            mapOf()),
        Schema("getStorageInfo", "[BATCHABLE] [NO VERIFY] Get disk space: total, used, available (internal + external SD card).",
            mapOf()),
        Schema("getBatteryHealth", "[BATCHABLE] [NO VERIFY] Get battery health, temperature, voltage, technology, charging status. More detailed than getBatteryLevel.",
            mapOf()),
        Schema("getScreenOrientation", "[BATCHABLE] [NO VERIFY] Get current screen orientation (portrait/landscape).",
            mapOf()),
        Schema("isCharging", "[BATCHABLE] [NO VERIFY] Is the phone currently charging? Returns charging status + power source (AC/USB/Wireless).",
            mapOf()),
        Schema("getRingtoneMode", "[BATCHABLE] [NO VERIFY] Get ringer mode (silent/vibrate/normal) + current volume levels.",
            mapOf()),
        Schema("setRingtoneMode", "[BATCHABLE] [NO VERIFY] Set ringer mode. Useful for rules: 'when in meeting, set silent'.",
            mapOf(
                "mode" to Param("string", "'silent', 'vibrate', or 'normal'.", required = true),
            )),
        // v4.0.32: Headless search tools
        Schema("headlessGoogleSearch", "[BATCHABLE] [NO VERIFY] Search Google WITHOUT opening a browser UI. Uses Android's WebView engine (executes JavaScript, has cookies) so Google serves real results — no CAPTCHA block. Slower than webSearch (5-10s) but reliable. Use when webSearch returns 0 results.",
            mapOf(
                "query" to Param("string", "Search query.", required = true),
            )),
        Schema("headlessSearch", "[BATCHABLE] [NO VERIFY] Search any engine WITHOUT opening a browser UI (headless WebView). Engine: 'google', 'bing', or 'duckduckgo'. Google is recommended (most results).",
            mapOf(
                "query" to Param("string", "Search query.", required = true),
                "engine" to Param("string", "Search engine: 'google', 'bing', 'duckduckgo' (default google).", required = false),
            )),
        Schema("braveSearch", "[BATCHABLE] [NO VERIFY] Official Brave Search API (2000 queries/month free, clean JSON, no scraping). Requires API key in Hub → Settings → Search API Keys. Best quality when key is set.",
            mapOf(
                "query" to Param("string", "Search query.", required = true),
                "limit" to Param("number", "Max results (default 5, max 20).", required = false),
            )),
        Schema("googleSearchApi", "[BATCHABLE] [NO VERIFY] Official Google Custom Search JSON API (100 queries/day free, clean JSON, no scraping, no CAPTCHA). Requires API key + Engine ID in Hub → Settings → Search API Keys. Best for reliable Google results.",
            mapOf(
                "query" to Param("string", "Search query.", required = true),
                "limit" to Param("number", "Max results (default 5, max 10).", required = false),
            )),
        Schema("smartSearch", "[BATCHABLE] [NO VERIFY] Smart search — tries methods in sequence: 1. Google API (if key set) 2. Brave API (if key set) 3. Bing HTTP scrape 4. Headless Google WebView. Returns first that yields results. Use when you don't know which search method works.",
            mapOf(
                "query" to Param("string", "Search query.", required = true),
            )),
        // v4.0.32: Deep link navigation
        Schema("navigateToAppScreen", "[VERIFY AFTER] Navigate to a screen INSIDE Aira (settings, permissions, hub, etc.). Use to guide the user to change settings, add API keys, or check permissions. After navigating, call readScreen() to see the screen.",
            mapOf(
                "screen" to Param("string", "Screen name: 'chat', 'hub', 'browser', 'settings', 'llm_settings', 'api_keys', 'permissions', 'system_prompt', 'background_tasks'.", required = true),
            )),
        Schema("listAppScreens", "[BATCHABLE] [NO VERIFY] List all available app screens you can navigate to. Call this first to see the options.",
            mapOf()),
        // === v4.0.85: MCP tools (7) ===
        Schema("searchMcpServers", "[BATCHABLE] [NO VERIFY] Search 47,000+ MCP servers across registries. Returns names, descriptions, URLs, transport types.",
            mapOf("query" to Param("string", "Search query (e.g. 'gmail', 'notion', 'filesystem')", required = true))),
        Schema("getMcpServerInfo", "[BATCHABLE] [NO VERIFY] Get detailed info about a specific MCP server from the registry.",
            mapOf("name" to Param("string", "Server registry name (e.g. 'com.notion/mcp')", required = true))),
        Schema("connectMcpServer", "Connect to an MCP server. Triggers initialize + tools/list. If OAuth needed, opens browser for authorization.",
            mapOf("name" to Param("string", "Short name for this server", required = true),
                "transport" to Param("string", "http or stdio (default: http)", required = false),
                "url" to Param("string", "Server URL (for HTTP transport)", required = false),
                "auth" to Param("string", "none, bearer, or oauth", required = false),
                "token" to Param("string", "Bearer token (if auth=bearer)", required = false))),
        Schema("disconnectMcpServer", "Disconnect from an MCP server.",
            mapOf("name" to Param("string", "Server name to disconnect", required = true))),
        Schema("listMcpServers", "[BATCHABLE] [NO VERIFY] List all connected MCP servers + their tool counts.",
            mapOf()),
        Schema("listMcpTools", "[BATCHABLE] [NO VERIFY] List tools on connected MCP server(s).",
            mapOf("serverName" to Param("string", "Server name (optional — lists all if omitted)", required = false))),
        Schema("callMcpTool", "Call a specific MCP tool explicitly. Usually you can call mcp__server__tool directly instead.",
            mapOf("serverName" to Param("string", "Server name", required = true),
                "toolName" to Param("string", "Tool name to call", required = true),
                "args" to Param("string", "JSON object of arguments", required = false))),
        // === v4.0.85: Hardware tools (14) ===
        Schema("scanWifiNetworks", "[BATCHABLE] [NO VERIFY] List nearby WiFi networks (SSID, BSSID, signal, security). Requires location permission.",
            mapOf()),
        Schema("scanBleDevices", "[BATCHABLE] [NO VERIFY] Scan for Bluetooth/BLE devices. Returns paired devices + info.",
            mapOf("duration" to Param("integer", "Scan duration in seconds (default: 5)", required = false))),
        Schema("getWifiState", "[BATCHABLE] [NO VERIFY] Check WiFi state (on/off) + connected SSID.",
            mapOf()),
        Schema("toggleBluetooth", "Toggle Bluetooth on/off. NOT runShellCommand — use this tool for Bluetooth control.",
            mapOf("enabled" to Param("boolean", "true to enable, false to disable", required = true))),
        Schema("listUsbDevices", "[BATCHABLE] [NO VERIFY] List connected USB devices (vendor, product, class).",
            mapOf()),
        Schema("getUsbDeviceInfo", "[BATCHABLE] [NO VERIFY] Get detailed info about a USB device (interfaces, endpoints, permissions).",
            mapOf("deviceId" to Param("string", "USB device ID (from listUsbDevices)", required = true))),
        Schema("readNfcTag", "[BATCHABLE] [NO VERIFY] Read NFC tag (UID, type, NDEF data). User must tap a tag.",
            mapOf()),
        Schema("writeNfcTag", "Write text to an NFC tag. Requires consent — user must tap a writable tag.",
            mapOf("text" to Param("string", "Text to write to the NFC tag", required = true))),
        Schema("listNfcHistory", "[BATCHABLE] [NO VERIFY] List all NFC tags previously read by Aira.",
            mapOf()),
        Schema("findAirTag", "[BATCHABLE] [NO VERIFY] Scan for nearby AirTags (Apple manufacturer data). Duration in seconds.",
            mapOf("duration" to Param("integer", "Scan duration in seconds (default: 10)", required = false))),
        Schema("scanBleBeacons", "[BATCHABLE] [NO VERIFY] Scan for BLE beacons (iBeacon, Eddystone). Returns devices with RSSI.",
            mapOf("duration" to Param("integer", "Scan duration in seconds (default: 5)", required = false))),
        Schema("logBleDevice", "Log a BLE device to knowledge for future reference.",
            mapOf("mac" to Param("string", "MAC address", required = true),
                "name" to Param("string", "Device name", required = false))),
        Schema("hasIrBlaster", "[BATCHABLE] [NO VERIFY] Check if this phone has an IR blaster (Xiaomi/Redmi/Poco only).",
            mapOf()),
        Schema("irSend", "Transmit an IR signal. Requires IR blaster (Xiaomi only). Requires consent.",
            mapOf("frequency" to Param("integer", "Carrier frequency in Hz (e.g. 38000)", required = true),
                "pattern" to Param("string", "Comma-separated microsecond timings", required = true))),
        Schema("irSendPreset", "Send a common IR preset code (TV power, AC, etc.). Requires IR blaster. Requires consent.",
            mapOf("device" to Param("string", "Device type (tv, ac)", required = true),
                "action" to Param("string", "Action (power, mute, volume_up, volume_down)", required = true))),
        // === v4.0.85: Memory tools (3) ===
        Schema("recallMemory", "[BATCHABLE] [NO VERIFY] Search past experiences/memories via FTS5. Use when you need context from previous tasks.",
            mapOf("query" to Param("string", "Search query", required = true),
                "limit" to Param("integer", "Max results (default: 5)", required = false))),
        Schema("recallConversationStep", "[BATCHABLE] [NO VERIFY] Get details of a specific past iteration step.",
            mapOf("step" to Param("integer", "Iteration step number to recall", required = true))),
        Schema("summarizeProgress", "[BATCHABLE] [NO VERIFY] Get a summary of progress so far in the current task.",
            mapOf()),
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

    /** v4.0.61: Get all schemas (for searchTools to search through). */
    fun allSchemas(): List<Schema> = SCHEMAS.toList()

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

            // v4.0.88: Auto-inject connector endpoint tools (e.g., gmail__send_email).
            // Each connector's endpoints become callable tools, like MCP tools.
            try {
                val connectors = com.ai.agent.tools.custom.ConnectorStore.load(context)
                for (conn in connectors) {
                    for (ep in conn.endpoints) {
                        val namespacedName = "${conn.name}__${ep.name}"
                        if (filter != null && namespacedName !in filter) continue
                        val properties = JSONObject()
                        val required = JSONArray()
                        for (paramName in ep.params) {
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
                        val statusNote = if (conn.authType == "oauth_pkce" && !conn.connected)
                            " [NOT CONNECTED — call connectConnector(name=\"${conn.name}\") first]"
                        else ""
                        val function = JSONObject()
                            .put("name", namespacedName)
                            .put("description", "(Connector: ${conn.name}) ${ep.description}$statusNote")
                            .put("parameters", parameters)
                        val tool = JSONObject()
                            .put("type", "function")
                            .put("function", function)
                        tools.put(tool)
                    }
                }
            } catch (e: Exception) {
                // Ignore — connectors are optional
            }

            // v4.0.80: GAP 1 FIX — Auto-inject MCP tools from connected servers.
            // This lets the brain call mcp__server__tool directly via native
            // function calling, without needing callMcpTool() explicitly.
            try {
                if (com.ai.agent.llm.AIProvider.isMcpEnabled(context)) {
                    val mcpTools = com.ai.agent.mcp.McpManager.getAllTools()
                    for ((serverName, tool) in mcpTools) {
                        val namespacedName = "mcp__${serverName}__${tool.name}"
                        // v4.0.85: FIX — Always include MCP tools regardless of filter.
                        // The contextual filter only covers built-in tool names, not mcp__* names.
                        // Bypassing the filter here ensures MCP server tools are always visible
                        // to the brain when MCP is enabled + a server is connected.
                        // (Previous code: if (filter != null && namespacedName !in filter) continue —
                        //  this skipped ALL MCP tools because mcp__* names are never in the filter.)
                        val function = JSONObject()
                            .put("name", namespacedName)
                            .put("description", "[MCP/$serverName] ${tool.description}")
                        // Parse the input schema from the MCP tool
                        try {
                            val schema = JSONObject(tool.inputSchema)
                            function.put("parameters", schema)
                        } catch (_: Exception) {
                            // If schema parsing fails, use empty object
                            function.put("parameters", JSONObject()
                                .put("type", "object")
                                .put("properties", JSONObject())
                                .put("additionalProperties", true))
                        }
                        val toolObj = JSONObject()
                            .put("type", "function")
                            .put("function", function)
                        tools.put(toolObj)
                    }
                }
            } catch (e: Exception) {
                // Ignore — MCP tools are optional
            }
        }
        return tools
    }
}
