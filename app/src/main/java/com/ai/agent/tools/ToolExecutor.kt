package com.ai.agent.tools

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.ai.agent.accessibility.AgentAccessibilityService
import com.ai.agent.browser.BrowserController
import com.ai.agent.llm.AIProvider
import com.ai.agent.llm.LLMClient
import com.ai.agent.llm.SavedModelsStore
import com.ai.agent.llm.ToolSchemas
import com.ai.agent.llm.ApiKeyStore
import com.ai.agent.llm.ProviderEndpoints
import com.ai.agent.llm.BindingHealthStore
import com.ai.agent.storage.AgentDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import com.ai.agent.tools.categories.AppTools
import com.ai.agent.tools.categories.AsyncTools
import com.ai.agent.tools.categories.BleAdvancedTools  // v4.0.77
import com.ai.agent.tools.categories.BrowserTools
import com.ai.agent.tools.categories.ContactTools
import com.ai.agent.tools.categories.DeviceTools
import com.ai.agent.tools.categories.AdvancedHardwareTools  // v4.0.76
import com.ai.agent.tools.categories.HardwareTools  // v4.0.76
import com.ai.agent.tools.categories.IrTools  // v4.0.77
import com.ai.agent.tools.categories.NfcTools  // v4.0.77
import com.ai.agent.mcp.McpTools  // v4.0.79
import com.ai.agent.tools.categories.FileTools
import com.ai.agent.tools.categories.LocationTools
import com.ai.agent.tools.categories.MediaTools
import com.ai.agent.tools.categories.MemoryTools
import com.ai.agent.tools.categories.NotificationTools
import com.ai.agent.tools.categories.RuleTools
import com.ai.agent.tools.categories.ScreenTools
import com.ai.agent.tools.categories.SkillTools
import com.ai.agent.tools.categories.VisionTools
import com.ai.agent.tools.categories.WebTools
import com.ai.agent.util.LogCapture

/**
 * Executes tool calls from the LLM.
 * Each tool returns a result string that can be fed back to the LLM.
 */
class ToolExecutor(private val context: Context) {

    companion object {
        private const val TAG = "ToolExecutor"

        /**
         * The complete list of tool names the agent supports.
         * Used by LLMClient to build the "tools" array for native function calling
         * (Groq, OpenRouter, Together — they get structured tool_calls back, no JSON parsing).
         */
        fun getAvailableToolNames(): List<String> = listOf(
            // Screen
            "readScreen", "readScreenStructured", "tap", "clickByText", "clickElement",
            "longClick", "longClickElement", "focusElement", "paste", "edit",
            "type", "swipe", "scrollDown", "scrollUp", "pressBack", "pressHome", "pressEnter", "submitInput",
            "lockScreen", "takeScreenshotToGallery",
            // Apps
            "launchApp", "listInstalledApps", "getAppInfo", "forceStopApp", "uninstallApp",
            "openDialer", "openContact", "openSettings", "shareText", "shareFile",
            // Vision
            "analyzeScreen", "findElement", "translateText",
            // Browser
            "browserOpen", "browserReadStructured", "browserReadPage", "browserGetLinks",
            "browserGetForms", "browserFillForm", "browserClickElement", "browserClickText",
            "browserGetText", "browserWaitForElement", "browserScreenshot", "browserScrollDown",
            "browserEval", "browserBack", "browserGetUrl", "browserSearch", "browserFill",
            "browserClick", "browserListClickable", "openInChrome", "searchInChrome",
            // Web
            "webSearch", "makeHttpRequest", "downloadFile", "openUrl", "fetchPageText",
            // Device
            "getBatteryLevel", "getCurrentTime", "getBluetoothState", "getNetworkInfo",
            "getDeviceInfo", "pingHost", "getVolume", "setVolume", "setBrightness",
            "toggleFlashlight", "toggleWifi",
            // v4.0.76: Hardware Tools (basic — WiFi/BLE/NFC)
            "scanWifiNetworks", "scanBleDevices", "getBluetoothState", "getWifiState",
            "toggleBluetooth",
            // v4.0.76: Hardware Tools (advanced — USB)
            "listUsbDevices", "getUsbDeviceInfo",
            // v4.0.77: Hardware Tools (NFC + BLE advanced + IR)
            "readNfcTag", "writeNfcTag", "listNfcHistory",
            "findAirTag", "scanBleBeacons", "logBleDevice",
            "hasIrBlaster", "irSend", "irSendPreset",
            // v3.0.0: speak tool for rules
            "speak",
            // Memory
            "remember", "recall", "recallAll", "recallSimilar", "searchMemory", "recallMemory",
            "recallConversationStep", "summarizeProgress",
            // v4.0.79: MCP (Model Context Protocol) tools
            "searchMcpServers", "getMcpServerInfo", "connectMcpServer",
            "disconnectMcpServer", "listMcpServers", "listMcpTools", "callMcpTool",
            "saveToKnowledge", "recallKnowledge", "listKnowledge",
            "getClipboard", "setClipboard",
            // Location
            "getCurrentLocation", "openMaps", "getWeather",
            // Rules
            "createRule", "listRules", "deleteRule", "modifyRule",
            // Contacts & Phone
            "readContacts", "searchContacts",
            "getCalendarEvents", "createCalendarEvent", "setAlarm", "setTimer",
            "sendSMS", "callContact", "getCallLog", "sendEmail",
            "makePhoneCall", "composeEmail",
            // Files
            "listFiles", "readFile", "writeFile", "copyFile", "moveFile", "deleteFile", "createDirectory",
            "listAttachments", "readAttachment", "sendAttachmentTo",
            // Activity shortcuts
            "openActivity", "openDeepLink", "openAppWithData", "searchInApp",
            "listAppActivities", "openWhatsAppChat", "openYouTubeVideo", "openMapsLocation",
            "playSpotify", "openInstagramProfile", "openTelegramChat",
            "openGoogleSearch", "shareToApp",
            // Notification
            "replyToNotification",
            "listNotifications",  // v4.0.71: list active notifications
            // Media
            "mediaPlayPause", "mediaNext", "mediaPrevious", "takePhoto",
            // Skills
            "executeSkill", "listSkills", "createSkill", "deleteSkill", "getSkill",
            // Async
            "wait", "waitAndContinue",
            // v3.7.0: Action chains
            "actionChain",
            // v3.9.6: Completion tool
            "complete",
            // v4.0.62: TodoWrite — planning + progress tracking
            "todoWrite",
            // v4.0.69: delegateTask — sub-agent delegation
            "delegateTask",
            "escalateLevel",  // v4.0.72: capability level escalation
            // v4.0.26: Custom tool management
            "createCustomTool", "listCustomTools", "deleteCustomTool",
            // v4.0.61: Tool search — search ALL tools by keyword
            "searchTools",
            // v4.0.63: Terminal management tools
            "connectTerminal", "listTerminals", "runOnTerminal", "disconnectTerminal",
            // v4.0.28: Wallpaper
            "setWallpaper",
            // v4.0.29: On-demand context tools
            "getConversationHistory", "getFullMemory",
            "getConversationSummary",  // v4.0.41: WhatsApp/messaging conversation history
            // v4.0.30: Permission-based tools
            "readSms", "vibrate", "getPhoneState", "wakeLock",
            // v4.0.30: Device info tools
            "getBrightness", "getFlashlightState", "getDeviceUptime",
            "getMemoryInfo", "getStorageInfo", "getBatteryHealth",
            "getScreenOrientation", "isCharging",
            "getRingtoneMode", "setRingtoneMode",
            // v4.0.32: Headless search + deep link navigation
            "headlessGoogleSearch", "headlessSearch", "braveSearch",
            "googleSearchApi", "smartSearch",
            "navigateToAppScreen", "listAppScreens",
        )
    }

    /**
     * v4.0.26: Get all available tool names INCLUDING custom tools.
     * Custom tools are loaded from CustomToolStore and appended to the built-in list.
     * Called by ToolSchemas to build the tools array for native function calling.
     */
    fun getAllToolNames(context: Context): List<String> {
        return getAvailableToolNames() + com.ai.agent.tools.custom.CustomToolStore.getToolNames(context)
    }

    // v3.6.0: Tool → required permission mapping for auto-detection.
    // When a tool fails because its permission is missing, the user gets a toast.
    val TOOL_PERMISSIONS: Map<String, List<String>> = mapOf(
        "takePhoto" to listOf(android.Manifest.permission.CAMERA),
        "sendSMS" to listOf(android.Manifest.permission.SEND_SMS),
        "readContacts" to listOf(android.Manifest.permission.READ_CONTACTS),
        "searchContacts" to listOf(android.Manifest.permission.READ_CONTACTS),
        "callContact" to listOf(android.Manifest.permission.CALL_PHONE),
        "makePhoneCall" to listOf(android.Manifest.permission.CALL_PHONE),
        "getCurrentLocation" to listOf(android.Manifest.permission.ACCESS_FINE_LOCATION),
        "getWeather" to listOf(android.Manifest.permission.ACCESS_FINE_LOCATION),
        "readFile" to listOf(android.Manifest.permission.READ_EXTERNAL_STORAGE),
        "listFiles" to listOf(android.Manifest.permission.READ_EXTERNAL_STORAGE),
        "writeFile" to listOf(android.Manifest.permission.MANAGE_EXTERNAL_STORAGE),
        // v4.0.5: Added missing permission checks for file operations
        "copyFile" to listOf(android.Manifest.permission.MANAGE_EXTERNAL_STORAGE),
        "moveFile" to listOf(android.Manifest.permission.MANAGE_EXTERNAL_STORAGE),
        "deleteFile" to listOf(android.Manifest.permission.MANAGE_EXTERNAL_STORAGE),
        "createDirectory" to listOf(android.Manifest.permission.MANAGE_EXTERNAL_STORAGE),
        "sendEmail" to listOf(android.Manifest.permission.READ_CONTACTS),
        "replyToNotification" to listOf("__notification_listener__"),
        // readScreen, tap, clickByText, etc. need accessibility — but they already
        // check service != null + return early. No extra check needed here.
    )

    private val database = AgentDatabase(context)

    /**
     * Execute a list of tool calls sequentially.
     * Returns a list of results (one per tool call).
     *
     * v4.0.25: Deduplicate identical tool calls. The model sometimes returns the
     * same tool call 40+ times in one reply (a generation loop). Executing it
     * once is enough — duplicates are skipped with a warning.
     */
    suspend fun executeTools(toolCalls: List<LLMClient.ToolCall>): List<ToolResult> {
        val results = mutableListOf<ToolResult>()
        val seen = mutableSetOf<String>()  // dedup signature: "name|args"
        var skipped = 0
        for (call in toolCalls) {
            // v4.0.25: Dedup — skip if exact same tool+args already executed in this batch.
            val sig = "${call.name}|${call.args.toSortedMap()}"
            if (sig in seen) {
                skipped++
                continue
            }
            seen.add(sig)
            val result = executeTool(call)
            results.add(result)
            val status = if (result.success) "✓" else "✗"
            LogCapture.i(TAG, "tool: ${call.name}(${call.args.entries.joinToString(", ") { "${it.key}=${it.value}" }}) → $status ${result.output.take(80)}")
        }
        if (skipped > 0) {
            LogCapture.w(TAG, "Skipped $skipped duplicate tool call(s) in this batch (model returned same call $skipped extra times)")
        }
        return results
    }

    private suspend fun executeTool(rawCall: LLMClient.ToolCall): ToolResult {
        // v4.0.18: Case-insensitive tool name matching.
        // Brain sometimes sends "createrule" instead of "createRule".
        // v4.0.16 broke this by lowercasing+capitalizing first letter → "Createrule" ≠ "createRule".
        // Now: lookup canonical name from available tools by lowercase match.
        val toolNameLower = rawCall.name.trim().lowercase()
        val canonicalName = getAvailableToolNames().find { it.lowercase() == toolNameLower } ?: rawCall.name.trim()
        var call = LLMClient.ToolCall(canonicalName, rawCall.args)
        // Normalize positional args (0, 1, 2...) to named args for tools that the LLM
        // wrote as plain text (e.g., "tap(970, 180)" → args={"0":970, "1":180}).
        // This converts them to the expected named format ({"x":970, "y":180}).
        call = normalizePositionalArgs(call)

        // Tools that DON'T need accessibility — browser, web, device, memory, rules, etc.
        // These work even without accessibility enabled.
        val noAccessibilityNeeded = call.name in setOf(
            "browserOpen", "browserReadStructured", "browserReadPage", "browserGetLinks",
            "browserGetForms", "browserFillForm", "browserClickElement", "browserClickText",
            "browserGetText", "browserWaitForElement", "browserScreenshot", "browserScrollDown",
            "browserEval", "browserBack", "browserGetUrl", "browserSearch", "browserFill",
            "browserClick", "browserListClickable",
            "openInChrome", "searchInChrome",
            "webSearch", "makeHttpRequest", "downloadFile", "openUrl", "fetchPageText",
            "getBatteryLevel", "getCurrentTime", "getBluetoothState", "getNetworkInfo",
            "getDeviceInfo", "pingHost", "getVolume", "setVolume", "setBrightness",
            "toggleFlashlight", "toggleWifi",
            // v4.0.76: Hardware Tools (basic — WiFi/BLE/NFC)
            "scanWifiNetworks", "scanBleDevices", "getBluetoothState", "getWifiState",
            "toggleBluetooth",
            // v4.0.76: Hardware Tools (advanced — USB)
            "listUsbDevices", "getUsbDeviceInfo",
            // v4.0.77: Hardware Tools (NFC + BLE advanced + IR)
            "readNfcTag", "writeNfcTag", "listNfcHistory",
            "findAirTag", "scanBleBeacons", "logBleDevice",
            "hasIrBlaster", "irSend", "irSendPreset",
            // v3.0.0: speak tool for rules
            "speak",
            "remember", "recall", "recallAll", "recallSimilar", "searchMemory", "recallMemory",
            "recallConversationStep", "summarizeProgress",
            // v4.0.79: MCP (Model Context Protocol) tools
            "searchMcpServers", "getMcpServerInfo", "connectMcpServer",
            "disconnectMcpServer", "listMcpServers", "listMcpTools", "callMcpTool",
            "getClipboard", "setClipboard",
            "getCurrentLocation", "openMaps", "getWeather",
            "createRule", "listRules", "deleteRule", "modifyRule",
            "readContacts", "searchContacts",
            "getCalendarEvents", "createCalendarEvent", "setAlarm", "setTimer",
            "sendSMS", "callContact", "getCallLog", "sendEmail", "shareText", "shareFile",
            "openDialer", "makePhoneCall", "composeEmail",
            "listFiles", "readFile", "writeFile", "copyFile", "moveFile", "deleteFile", "createDirectory",
            "listInstalledApps", "getAppInfo",
            "openSettings", "openAppSettings",
            "wait", "waitAndContinue", "localLLM",
            "executeSkill", "listSkills",
            "createSkill", "deleteSkill", "getSkill",  // v4.0.17: These touch files, not accessibility
            "recallSimilar", "searchMemory",
            "saveToKnowledge", "recallKnowledge", "listKnowledge",
            "listAttachments", "readAttachment", "sendAttachmentTo",
            "replyToNotification",
            "listNotifications",  // v4.0.71: reads via NotificationListener, no accessibility needed
            "openActivity", "openDeepLink", "openAppWithData", "searchInApp",
            "listAppActivities", "openWhatsAppChat", "openYouTubeVideo", "openMapsLocation",
            "playSpotify", "openInstagramProfile", "openTelegramChat",
            "openGoogleSearch", "shareToApp", "openContact",
            "mediaPlayPause", "mediaNext", "mediaPrevious", "takePhoto",
            "forceStopApp", "uninstallApp",  // v4.0.17: Use ActivityManager, not accessibility
            "complete",  // v4.0.19: Just sets a flag, doesn't need accessibility
            // v4.0.62: TodoWrite — just stores the todo list, no accessibility
            "todoWrite",
            // v4.0.69: delegateTask — spawns sub-agent, no accessibility needed
            "delegateTask",
            "escalateLevel",  // v4.0.72: capability level escalation
            // v4.0.26: Custom tool management + custom tools don't need accessibility
            "createCustomTool", "listCustomTools", "deleteCustomTool", "runShellCommand",
            // v4.0.61: searchTools — just searches schemas, no accessibility
            "searchTools",
            // v4.0.63: Terminal management — HTTP calls, no accessibility
            "connectTerminal", "listTerminals", "runOnTerminal", "disconnectTerminal",
            // v4.0.28: Wallpaper
            "setWallpaper",
            // v4.0.29: On-demand context tools (no accessibility needed)
            "getConversationHistory", "getFullMemory",
            "getConversationSummary",  // v4.0.41: conversation history
            // v4.0.30: Permission-based + device info tools (no accessibility needed)
            "readSms", "vibrate", "getPhoneState", "wakeLock",
            "getBrightness", "getFlashlightState", "getDeviceUptime",
            "getMemoryInfo", "getStorageInfo", "getBatteryHealth",
            "getScreenOrientation", "isCharging",
            "getRingtoneMode", "setRingtoneMode",
            // v4.0.32: Headless search + deep link navigation (no accessibility needed)
            "headlessGoogleSearch", "headlessSearch", "braveSearch",
            "googleSearchApi", "smartSearch",
            "navigateToAppScreen", "listAppScreens",
        )

        // v4.0.26: Custom tools (created via createCustomTool) don't need accessibility.
        // Check if this is a custom tool — if so, skip accessibility.
        val isCustomTool = com.ai.agent.tools.custom.CustomToolStore.get(context, call.name) != null
        val noAccessibility = noAccessibilityNeeded || isCustomTool

        // For non-accessibility tools, skip the service check entirely.
        // These tools don't reference `service` in the when block below.
        val service: AgentAccessibilityService? = if (noAccessibility) {
            null  // not needed — these tools don't use it
        } else {
            waitForAccessibilityService()
                ?: return ToolResult(false, buildAccessibilityErrorMessage(context))
        }

        // v3.6.0: Auto-detect missing permissions for specific tools.
        // If a tool needs a permission that isn't granted, return an error
        // with a helpful message instead of failing silently.
        // v4.0.29: Fix storage permission false-negative. MANAGE_EXTERNAL_STORAGE
        // must be checked via Environment.isExternalStorageManager() (Android 11+),
        // NOT checkSelfPermission (which always returns DENIED for it). This was
        // causing "Permission missing: Storage" even when the user had granted it.
        val requiredPerms = TOOL_PERMISSIONS[call.name]
        if (requiredPerms != null) {
            for (perm in requiredPerms) {
                val granted = when (perm) {
                    "__notification_listener__" -> {
                        val expected = "${context.packageName}/com.ai.agent.rules.NotificationListener"
                        val enabled = android.provider.Settings.Secure.getString(
                            context.contentResolver, "enabled_notification_listeners"
                        ) ?: ""
                        enabled.contains(expected)
                    }
                    // v4.0.29: MANAGE_EXTERNAL_STORAGE must use isExternalStorageManager()
                    android.Manifest.permission.MANAGE_EXTERNAL_STORAGE -> {
                        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R &&
                        android.os.Environment.isExternalStorageManager()
                    }
                    // v4.0.29: READ/WRITE_EXTERNAL_STORAGE are deprecated on Android 13+
                    // On API 33+, check the granular media permissions instead.
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE -> {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                            // Android 13+: granular permissions — check if ANY media perm is granted
                            val mediaPerms = listOf(
                                android.Manifest.permission.READ_MEDIA_IMAGES,
                                android.Manifest.permission.READ_MEDIA_VIDEO,
                                android.Manifest.permission.READ_MEDIA_AUDIO,
                            )
                            mediaPerms.any {
                                androidx.core.content.ContextCompat.checkSelfPermission(context, it) ==
                                    android.content.pm.PackageManager.PERMISSION_GRANTED
                            } || android.os.Environment.isExternalStorageManager()
                        } else {
                            androidx.core.content.ContextCompat.checkSelfPermission(context, perm) ==
                                android.content.pm.PackageManager.PERMISSION_GRANTED
                        }
                    }
                    else -> androidx.core.content.ContextCompat.checkSelfPermission(context, perm) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                }
                if (!granted) {
                    val friendlyName = when {
                        perm.contains("CAMERA") -> "Camera"
                        perm.contains("SMS") -> "Send SMS"
                        perm.contains("CONTACTS") -> "Read Contacts"
                        perm.contains("CALL_PHONE") -> "Call Phone"
                        perm.contains("LOCATION") -> "Location"
                        perm.contains("STORAGE") -> "Storage"
                        perm == "__notification_listener__" -> "Notification Listener"
                        else -> perm
                    }
                    return ToolResult(false,
                        "Permission missing: $friendlyName. Open Hub → Permissions to grant it.")
                }
            }
        }

        // v4.0.2: CONSENT CHECK — ask user before sensitive actions.
        // If consent is enabled in settings AND the tool is in the sensitive list,
        // suspend + show a dialog. User must approve before execution proceeds.
        if (com.ai.agent.llm.AgentThinkingSettings.isConsentEnabled(context) &&
            call.name in CONSENT_TOOLS
        ) {
            val (description, details) = buildConsentMessage(call)
            val approved = com.ai.agent.consent.ConsentManager.requestConsent(
                toolName = call.name,
                description = description,
                details = details,
            )
            if (!approved) {
                return ToolResult(false, "User declined consent for ${call.name}. The action was NOT executed.")
            }
        }

        // service is guaranteed non-null for accessibility tools (we returned early above if null)
        // For non-accessibility tools, service is null but they don't use it
        return when (call.name) {
            // === SCREEN TOOLS (require accessibility — service is non-null here) ===
            "readScreen" -> {
                val visionMode = AIProvider.getVisionMode(context)  // "auto" | "mlkit" | "vlm"

                // v2.5.2: OPTIMIZED Screen Reading (Option C)
                // v3.3.11: "mlkit" mode now NEVER calls VLM (cost saving).
                //          VLM calls now use the VISION model from Vision Settings,
                //          not the main chat model.

                Log.i(TAG, "readScreen: visionMode=$visionMode (v3.3.11)")

                when (visionMode) {
                    "vlm" -> {
                        // v3.8.10: Always try VLM — even without a separate vision model.
                        // VisionTools.analyzeScreenWithVLM falls back to the MAIN chat model
                        // (GLM-5.3-Flash) which supports vision through the proxy.
                        // The proxy now detects image requests + routes to createVision().
                        Log.i(TAG, "readScreen: VLM mode — calling VLM (vision model or chat model fallback)")
                        val vlmResult = VisionTools.analyzeScreenWithVLM(context, service!!, "Read ALL text visible on the screen. Include app name, all visible text, button labels, menu items, and any text inside images. Be thorough.")
                        // v3.8.14: Log the VLM result so we can see WHY it failed.
                        LogCapture.i(TAG, "VLM result: ${vlmResult.take(200)}")
                        // v3.8.7: Fallback chain when VLM fails — give the brain something to work with.
                        if (vlmResult.isBlank() || vlmResult.contains("Failed to capture screenshot") || vlmResult.startsWith("VLM error") || vlmResult.startsWith("Vision error")) {
                            LogCapture.w(TAG, "VLM failed — falling back to readScreenStructured + a11y")
                            // Try readScreenStructured first (gives bounds for tapping)
                            val structured = try { service!!.readScreenStructured() } catch (e: Exception) { "" }
                            val a11yText = try { service!!.readScreen() } catch (e: Exception) { "" }
                            if (structured.isNotBlank() && structured != "{\"error\":\"screen is null\"}") {
                                LogCapture.i(TAG, "VLM fallback: readScreenStructured (${structured.length} chars)")
                                ToolResult(true, "Screen structure (VLM failed, a11y fallback): $structured")
                            } else if (a11yText.isNotBlank()) {
                                LogCapture.i(TAG, "VLM fallback: a11y text (${a11yText.length} chars)")
                                ToolResult(true, "Screen text (a11y fallback, VLM failed): $a11yText")
                            } else {
                                ToolResult(true, "Screen is empty or unreadable. The app may be loading or the screen is blank.")
                            }
                        } else {
                            ToolResult(true, "Screen text (VLM): $vlmResult")
                        }
                    }
                    "mlkit" -> {
                        // v3.3.11: ML Kit ONLY — a11y + ML Kit, NO VLM fallback (cost saving).
                        // If both return < 50 chars, return what we got (even if empty).
                        // This is the "free" mode — 0 API calls, ever.
                        Log.i(TAG, "readScreen: ML Kit only mode — 0 API calls guaranteed")
                        val screenshotBase64 = captureScreenshotBase64(service!!)
                        val a11yText: String
                        val mlText: String?
                        kotlinx.coroutines.coroutineScope {
                            val a11yDeferred = async(kotlinx.coroutines.Dispatchers.IO) {
                                try { service!!.readScreen() } catch (e: Exception) { "" }
                            }
                            val mlKitDeferred = async(kotlinx.coroutines.Dispatchers.IO) {
                                if (screenshotBase64 != null) {
                                    try {
                                        val bytes = android.util.Base64.decode(screenshotBase64, android.util.Base64.DEFAULT)
                                        val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                        if (bitmap != null) com.ai.agent.ml.MLKitHelper.tryRecognize(bitmap) else null
                                    } catch (e: Exception) {
                                        Log.e(TAG, "ML Kit OCR failed", e)
                                        null
                                    }
                                } else null
                            }
                            a11yText = a11yDeferred.await()
                            mlText = mlKitDeferred.await()
                        }
                        val combinedText = combineAndDedupe(a11yText, mlText)
                        Log.i(TAG, "readScreen: ML Kit only — a11y=${a11yText.length}, mlKit=${mlText?.length ?: 0}, combined=${combinedText.length} (0 API calls)")
                        ToolResult(true, combinedText.ifEmpty { "(no text on screen — try VLM mode for image-based screens)" })
                    }
                    else -> {
                        // "auto" mode — a11y + ML Kit first, VLM fallback only if both fail.
                        // v3.3.11: VLM fallback uses the vision model from Vision Settings.

                        // Step 1: Take ONE screenshot (shared)
                        val screenshotBase64 = captureScreenshotBase64(service!!)

                        // Step 2: Run a11y text + ML Kit OCR in PARALLEL (both fast, both free)
                        val a11yText: String
                        val mlText: String?
                        kotlinx.coroutines.coroutineScope {
                            val a11yDeferred = async(kotlinx.coroutines.Dispatchers.IO) {
                                try { service!!.readScreen() } catch (e: Exception) { "" }
                            }
                            val mlKitDeferred = async(kotlinx.coroutines.Dispatchers.IO) {
                                if (screenshotBase64 != null) {
                                    try {
                                        val bytes = android.util.Base64.decode(screenshotBase64, android.util.Base64.DEFAULT)
                                        val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                        if (bitmap != null) com.ai.agent.ml.MLKitHelper.tryRecognize(bitmap) else null
                                    } catch (e: Exception) {
                                        Log.e(TAG, "ML Kit OCR failed", e)
                                        null
                                    }
                                } else null
                            }
                            a11yText = a11yDeferred.await()
                            mlText = mlKitDeferred.await()
                        }

                        Log.i(TAG, "readScreen: a11y=${a11yText.length} chars, mlKit=${mlText?.length ?: 0} chars")

                        // Step 3: Combine + dedupe
                        val combinedText = combineAndDedupe(a11yText, mlText)

                        if (combinedText.length >= 50) {
                            // Got enough text from a11y + ML Kit — return combined (0 API calls!)
                            val source = when {
                                a11yText.length >= 50 && mlText != null && mlText.length >= 10 -> "a11y+ML Kit"
                                a11yText.length >= 50 -> "accessibility"
                                mlText != null && mlText.length >= 50 -> "ML Kit"
                                else -> "combined"
                            }
                            Log.i(TAG, "readScreen: using $source (${combinedText.length} chars) — 0 API calls")
                            ToolResult(true, combinedText)
                        } else if (screenshotBase64 != null) {
                            // v3.3.11: VLM fallback — check if a vision model is configured.
                            val visionModel = SavedModelsStore.getVisionModel(context)
                            if (visionModel == null) {
                                // No vision model configured — return what we got (don't waste an API call on the main chat model).
                                Log.i(TAG, "readScreen: a11y+ML Kit insufficient (${combinedText.length} chars) but no vision model configured — returning partial (0 API calls)")
                                ToolResult(true, combinedText.ifEmpty { "(no text detected + no vision model configured. Set one in AI Settings → Vision for image-based screen reading.)" })
                            } else {
                                // VLM fallback on SAME screenshot using the vision model.
                                Log.i(TAG, "readScreen: a11y+ML Kit insufficient (${combinedText.length} chars) — VLM fallback (${visionModel.modelName}, same screenshot)")
                                val vlmResult = VisionTools.analyzeScreenWithVLMBase64(
                                    context,
                                    screenshotBase64,
                                    "Read ALL text visible on the screen. Include app name, all visible text, button labels, menu items, and any text inside images. Be thorough."
                                )
                                ToolResult(true, "Screen text (VLM): $vlmResult")
                            }
                        } else {
                            // Screenshot failed + a11y/ML Kit empty — last resort
                            ToolResult(true, if (a11yText.isNotEmpty()) a11yText else "(no text on screen)")
                        }
                    }
                }
            }
            "tap" -> ScreenTools.execute(call, context, service, database)
            "clickByText" -> ScreenTools.execute(call, context, service, database)
            "clickElement" -> ScreenTools.execute(call, context, service, database)
            "longClick" -> ScreenTools.execute(call, context, service, database)
            "longClickElement" -> ScreenTools.execute(call, context, service, database)
            "focusElement" -> ScreenTools.execute(call, context, service, database)
            "paste" -> ScreenTools.execute(call, context, service, database)
            "edit" -> ScreenTools.execute(call, context, service, database)
            "type" -> ScreenTools.execute(call, context, service, database)
            "swipe" -> ScreenTools.execute(call, context, service, database)
            "scrollDown" -> ScreenTools.execute(call, context, service, database)
            "scrollUp" -> ScreenTools.execute(call, context, service, database)
            "pressBack" -> ScreenTools.execute(call, context, service, database)
            "pressHome" -> ScreenTools.execute(call, context, service, database)
            "pressEnter" -> ScreenTools.execute(call, context, service, database)
            "submitInput" -> ScreenTools.execute(call, context, service, database)
            "launchApp" -> AppTools.execute(call, context, service, database)
            "listInstalledApps" -> AppTools.execute(call, context, service, database)
            "wait" -> AsyncTools.execute(call, context, service, database)
            "waitAndContinue" -> AsyncTools.execute(call, context, service, database)
            // Memory tools
            "remember" -> MemoryTools.execute(call, context, service, database)
            "recall" -> MemoryTools.execute(call, context, service, database)
            "recallAll" -> MemoryTools.execute(call, context, service, database)
            // Vision tools
            "analyzeScreen" -> VisionTools.execute(call, context, service, database)
            "findElement" -> VisionTools.execute(call, context, service, database)
            // System tools
            "getBatteryLevel" -> DeviceTools.execute(call, context, service, database)
            "openUrl" -> {
                val url = call.args["url"] as? String ?: return ToolResult(false, "missing url")
                try {
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).apply {
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    ToolResult(true, "Opened URL: $url")
                } catch (e: Exception) {
                    ToolResult(false, "Failed to open URL: ${e.message}")
                }
            }
            "getCurrentTime" -> DeviceTools.execute(call, context, service, database)
            // v3.0.0: speak tool — for rules to use TTS (Text-to-Speech)
            "speak" -> DeviceTools.execute(call, context, service, database)
            // === DEVICE CONTROL TOOLS ===
            "getClipboard" -> MemoryTools.execute(call, context, service, database)
            "setClipboard" -> MemoryTools.execute(call, context, service, database)
            "setVolume" -> DeviceTools.execute(call, context, service, database)
            "getVolume" -> DeviceTools.execute(call, context, service, database)
            "setBrightness" -> DeviceTools.execute(call, context, service, database)
            "toggleFlashlight" -> DeviceTools.execute(call, context, service, database)
            // v4.0.76: Hardware Tools
            "scanWifiNetworks" -> HardwareTools.execute(call, context)
            "scanBleDevices" -> HardwareTools.execute(call, context)
            "getWifiState" -> HardwareTools.execute(call, context)
            "toggleBluetooth" -> HardwareTools.execute(call, context)
            "listUsbDevices" -> AdvancedHardwareTools.execute(call, context)
            "getUsbDeviceInfo" -> AdvancedHardwareTools.execute(call, context)
            // v4.0.77: NFC Tools
            "readNfcTag" -> NfcTools.execute(call, context)
            "writeNfcTag" -> NfcTools.execute(call, context)
            "listNfcHistory" -> NfcTools.execute(call, context)
            // v4.0.77: BLE Advanced Tools
            "findAirTag" -> BleAdvancedTools.execute(call, context)
            "scanBleBeacons" -> BleAdvancedTools.execute(call, context)
            "logBleDevice" -> BleAdvancedTools.execute(call, context)
            // v4.0.77: IR Tools (Xiaomi/Redmi/Poco only)
            "hasIrBlaster" -> IrTools.execute(call, context)
            "irSend" -> IrTools.execute(call, context)
            "irSendPreset" -> IrTools.execute(call, context)
            "mediaPlayPause" -> MediaTools.execute(call, context, service, database)
            "mediaNext" -> MediaTools.execute(call, context, service, database)
            "mediaPrevious" -> MediaTools.execute(call, context, service, database)
            "takePhoto" -> MediaTools.execute(call, context, service, database)
            "listFiles" -> FileTools.execute(call, context, service, database)
            "readFile" -> FileTools.execute(call, context, service, database)
            "writeFile" -> FileTools.execute(call, context, service, database)
            "toggleWifi" -> DeviceTools.execute(call, context, service, database)
            "getNetworkInfo" -> DeviceTools.execute(call, context, service, database)
            "lockScreen" -> ScreenTools.execute(call, context, service, database)
            "takeScreenshotToGallery" -> ScreenTools.execute(call, context, service, database)
            // === TERMINAL ===
            "runShellCommand" -> {
                val cmd = call.args["command"] as? String ?: return ToolResult(false, "missing command")
                try {
                    val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
                    val output = process.inputStream.bufferedReader().readText()
                    val errors = process.errorStream.bufferedReader().readText()
                    val exitCode = process.waitFor()
                    val result = buildString {
                        if (output.isNotEmpty()) append(output)
                        if (errors.isNotEmpty()) append("\n[STDERR]: $errors")
                        append("\n[Exit: $exitCode]")
                    }
                    ToolResult(true, result)
                } catch (e: Exception) {
                    ToolResult(false, "Shell error: ${e.message}")
                }
            }
            // === CONTACTS ===
            "readContacts" -> ContactTools.execute(call, context, service, database)
            "searchContacts" -> ContactTools.execute(call, context, service, database)
            "callContact" -> ContactTools.execute(call, context, service, database)
            "sendSMS" -> ContactTools.execute(call, context, service, database)
            // === CALENDAR ===
            "getCalendarEvents" -> ContactTools.execute(call, context, service, database)
            "createCalendarEvent" -> ContactTools.execute(call, context, service, database)
            // === HTTP REQUESTS (Web Research) ===
            "makeHttpRequest" -> WebTools.execute(call, context, service, database)
            // v5.0.8: Background page reader — fetches URL, strips HTML, returns clean text.
            // NO browser opens. Use for RESEARCH tasks (reading content) — user stays on chat.
            // For INTERACTION tasks (clicking, forms, ordering) use browserOpen + browserClickText.
            "fetchPageText" -> WebTools.execute(call, context, service, database)
            "downloadFile" -> WebTools.execute(call, context, service, database)
            "webSearch" -> WebTools.execute(call, context, service, database)
            // === CALL LOG ===
            "getCallLog" -> ContactTools.execute(call, context, service, database)
            // === LOCATION ===
            "getCurrentLocation" -> LocationTools.execute(call, context, service, database)
            "openMaps" -> LocationTools.execute(call, context, service, database)
            // v2.3.3: getWeather — uses GPS location + free Open-Meteo API (no key needed, works in India)
            "getWeather" -> LocationTools.execute(call, context, service, database)
            // === DEVICE INFO ===
            "getDeviceInfo" -> DeviceTools.execute(call, context, service, database)
            // === FILE OPERATIONS ===
            "copyFile" -> FileTools.execute(call, context, service, database)
            "moveFile" -> FileTools.execute(call, context, service, database)
            "deleteFile" -> FileTools.execute(call, context, service, database)
            "createDirectory" -> FileTools.execute(call, context, service, database)
            // === ALARM ===
            "setAlarm" -> ContactTools.execute(call, context, service, database)
            "setTimer" -> ContactTools.execute(call, context, service, database)
            // === APP MANAGEMENT ===
            "getAppInfo" -> AppTools.execute(call, context, service, database)
            "forceStopApp" -> AppTools.execute(call, context, service, database)
            "uninstallApp" -> AppTools.execute(call, context, service, database)
            // === EMAIL ===
            "sendEmail" -> ContactTools.execute(call, context, service, database)
            // === BLUETOOTH ===
            "getBluetoothState" -> DeviceTools.execute(call, context, service, database)
            // === SHARE TEXT ===
            "shareText" -> AppTools.execute(call, context, service, database)
            // === SHARE FILE ===
            "shareFile" -> AppTools.execute(call, context, service, database)
            // === OPEN DIALER (don't auto-call, just open) ===
            "openDialer" -> ContactTools.execute(call, context, service, database)
            // === OPEN CONTACT CARD ===
            "openContact" -> {
                val contactId = (call.args["id"] as? Number)?.toLong()
                    ?: return ToolResult(false, "missing id (use searchContacts first)")
                try {
                    val uri = android.content.ContentUris.withAppendedId(
                        android.provider.ContactsContract.Contacts.CONTENT_URI,
                        contactId
                    )
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                        data = uri
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    ToolResult(true, "Opened contact $contactId")
                } catch (e: Exception) { ToolResult(false, "Open contact error: ${e.message}") }
            }
            // === OPEN SETTINGS PAGE (deep-link) ===
            "openSettings" -> AppTools.execute(call, context, service, database)
            // === PING ===
            "pingHost" -> DeviceTools.execute(call, context, service, database)
            // === TRANSLATE (via LLM) ===
            "translateText" -> VisionTools.execute(call, context, service, database)
            // === IN-APP BROWSER ===
            // In Chrome mode, browser tools (except browserOpen) don't work — they operate
            // on the in-app WebView, not Chrome. Auto-route to accessibility tools instead.
            "browserOpen" -> BrowserTools.execute(call, context, service, database)
            "browserReadPage" -> BrowserTools.execute(call, context, service, database)
            "browserEval" -> BrowserTools.execute(call, context, service, database)
            "browserClick" -> BrowserTools.execute(call, context, service, database)
            "browserFill" -> BrowserTools.execute(call, context, service, database)
            "browserScrollDown" -> BrowserTools.execute(call, context, service, database)
            "browserBack" -> BrowserTools.execute(call, context, service, database)
            "browserGetUrl" -> BrowserTools.execute(call, context, service, database)
            "browserSearch" -> BrowserTools.execute(call, context, service, database)
            // ==================== HYBRID BROWSER AGENT (v4.0.0) ====================
            "browserReadStructured" -> BrowserTools.execute(call, context, service, database)
            "browserGetLinks" -> BrowserTools.execute(call, context, service, database)
            "browserGetForms" -> BrowserTools.execute(call, context, service, database)
            "browserFillForm" -> BrowserTools.execute(call, context, service, database)
            "browserClickElement" -> BrowserTools.execute(call, context, service, database)
            "browserClickText" -> BrowserTools.execute(call, context, service, database)
            "browserListClickable" -> BrowserTools.execute(call, context, service, database)
            "browserGetText" -> BrowserTools.execute(call, context, service, database)
            "browserWaitForElement" -> BrowserTools.execute(call, context, service, database)
            "browserScreenshot" -> BrowserTools.execute(call, context, service, database)
            "browserScrollDown" -> BrowserTools.execute(call, context, service, database)
            "openInChrome" -> BrowserTools.execute(call, context, service, database)
            "searchInChrome" -> BrowserTools.execute(call, context, service, database)
            "localLLM" -> {
                ToolResult(false, "Local LLM has been removed. The app now uses cloud AI (GLM-4.6) for everything, which is faster and more capable.")
            }
            // ==================== SCREEN STRUCTURE TREE ====================
            "readScreenStructured" -> ScreenTools.execute(call, context, service, database)
            // ==================== NOTIFICATION REPLY ====================
            "replyToNotification" -> NotificationTools.execute(call, context, service, database)
            // v4.0.71: listNotifications — read active notifications via NotificationListenerService
            "listNotifications" -> {
                val listener = com.ai.agent.rules.NotificationListener.instance
                if (listener == null) {
                    ToolResult(false, "NotificationListener is not connected. Enable notification access in Hub → Permissions.")
                } else {
                    try {
                        val activeNotifs = listener.activeNotifications
                        if (activeNotifs == null || activeNotifs.isEmpty()) {
                            ToolResult(true, "No active notifications.")
                        } else {
                            val sb = StringBuilder("Active notifications (${activeNotifs.size}):\n\n")
                            for (sbn in activeNotifs) {
                                val pkg = sbn.packageName ?: "unknown"
                                val extras = sbn.notification?.extras
                                val title = extras?.getString(android.app.Notification.EXTRA_TITLE)?.toString() ?: ""
                                val text = extras?.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString() ?: ""
                                val bigText = extras?.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT)?.toString() ?: ""
                                val time = sbn.postTime
                                val timeStr = if (time > 0) {
                                    val minsAgo = (System.currentTimeMillis() - time) / 60000
                                    if (minsAgo < 60) "${minsAgo}m ago" else "${minsAgo / 60}h ago"
                                } else ""
                                sb.append("📱 $pkg\n")
                                if (title.isNotEmpty()) sb.append("  Title: $title\n")
                                if (text.isNotEmpty()) sb.append("  Text: $text\n")
                                if (bigText.isNotEmpty() && bigText != text) sb.append("  Full: ${bigText.take(200)}\n")
                                if (timeStr.isNotEmpty()) sb.append("  Time: $timeStr\n")
                                sb.append("\n")
                            }
                            ToolResult(true, sb.toString())
                        }
                    } catch (e: Exception) {
                        ToolResult(false, "Failed to read notifications: ${e.message}")
                    }
                }
            }
            // ==================== VECTOR MEMORY ====================
            "recallSimilar" -> MemoryTools.execute(call, context, service, database)
            "searchMemory" -> MemoryTools.execute(call, context, service, database)
            "recallMemory" -> MemoryTools.execute(call, context, service, database)  // v4.0.74
            "recallConversationStep" -> MemoryTools.execute(call, context, service, database)  // v4.0.76
            "summarizeProgress" -> MemoryTools.execute(call, context, service, database)  // v4.0.76
            // ==================== MCP (v4.0.79) ====================
            "searchMcpServers" -> McpTools.execute(call, context)
            "getMcpServerInfo" -> McpTools.execute(call, context)
            "connectMcpServer" -> McpTools.execute(call, context)
            "disconnectMcpServer" -> McpTools.execute(call, context)
            "listMcpServers" -> McpTools.execute(call, context)
            "listMcpTools" -> McpTools.execute(call, context)
            "callMcpTool" -> McpTools.execute(call, context)
            // ==================== KNOWLEDGE (v6.0.0) ====================
            "saveToKnowledge" -> MemoryTools.execute(call, context, service, database)
            "recallKnowledge" -> MemoryTools.execute(call, context, service, database)
            "listKnowledge" -> MemoryTools.execute(call, context, service, database)
            // ==================== FILE ATTACHMENTS (v6.0.0) ====================
            "listAttachments" -> FileTools.execute(call, context, service, database)
            "readAttachment" -> FileTools.execute(call, context, service, database)
            "sendAttachmentTo" -> FileTools.execute(call, context, service, database)
            // ==================== SKILL MANAGEMENT (v6.1.0) ====================
            "createSkill" -> SkillTools.execute(call, context, service, database)
            "deleteSkill" -> SkillTools.execute(call, context, service, database)
            "getSkill" -> SkillTools.execute(call, context, service, database)
            // ==================== SKILLS ====================
            "executeSkill" -> SkillTools.execute(call, context, service, database)
            "listSkills" -> SkillTools.execute(call, context, service, database)
            // v4.0.26: Custom tool management
            "createCustomTool" -> com.ai.agent.tools.categories.CustomTools.execute(call, context, service, database)
            "listCustomTools" -> com.ai.agent.tools.categories.CustomTools.execute(call, context, service, database)
            "deleteCustomTool" -> com.ai.agent.tools.categories.CustomTools.execute(call, context, service, database)
            // v4.0.61: searchTools — search ALL tools by keyword
            "searchTools" -> searchTools(call, context)
            // v4.0.63: Terminal management tools
            "connectTerminal" -> com.ai.agent.terminal.TerminalTools.Handler.connectTerminal(call.args, context)
            "listTerminals" -> com.ai.agent.terminal.TerminalTools.Handler.listTerminals(call.args, context)
            "runOnTerminal" -> com.ai.agent.terminal.TerminalTools.Handler.runOnTerminal(call.args, context)
            "disconnectTerminal" -> com.ai.agent.terminal.TerminalTools.Handler.disconnectTerminal(call.args, context)
            // v4.0.28: Wallpaper
            "setWallpaper" -> com.ai.agent.tools.categories.WallpaperTools.execute(call, context, service, database)
            // v4.0.29: On-demand context tools
            "getConversationHistory" -> MemoryTools.execute(call, context, service, database)
            "getFullMemory" -> MemoryTools.execute(call, context, service, database)
            // v4.0.41: Conversation summary (WhatsApp/messaging history)
            "getConversationSummary" -> MemoryTools.execute(call, context, service, database)
            // v4.0.30: Permission-based tools
            "readSms" -> com.ai.agent.tools.categories.PermissionTools.execute(call, context, service, database)
            "vibrate" -> com.ai.agent.tools.categories.PermissionTools.execute(call, context, service, database)
            "getPhoneState" -> com.ai.agent.tools.categories.PermissionTools.execute(call, context, service, database)
            "wakeLock" -> com.ai.agent.tools.categories.PermissionTools.execute(call, context, service, database)
            // v4.0.30: Device info tools
            "getBrightness" -> com.ai.agent.tools.categories.DeviceInfoTools.execute(call, context, service, database)
            "getFlashlightState" -> com.ai.agent.tools.categories.DeviceInfoTools.execute(call, context, service, database)
            "getDeviceUptime" -> com.ai.agent.tools.categories.DeviceInfoTools.execute(call, context, service, database)
            "getMemoryInfo" -> com.ai.agent.tools.categories.DeviceInfoTools.execute(call, context, service, database)
            "getStorageInfo" -> com.ai.agent.tools.categories.DeviceInfoTools.execute(call, context, service, database)
            "getBatteryHealth" -> com.ai.agent.tools.categories.DeviceInfoTools.execute(call, context, service, database)
            "getScreenOrientation" -> com.ai.agent.tools.categories.DeviceInfoTools.execute(call, context, service, database)
            "isCharging" -> com.ai.agent.tools.categories.DeviceInfoTools.execute(call, context, service, database)
            "getRingtoneMode" -> com.ai.agent.tools.categories.DeviceInfoTools.execute(call, context, service, database)
            "setRingtoneMode" -> com.ai.agent.tools.categories.DeviceInfoTools.execute(call, context, service, database)
            // v4.0.32: Headless search tools (Google via WebView, Brave API, Google API, smartSearch)
            "headlessGoogleSearch" -> com.ai.agent.tools.categories.HeadlessSearchTools.execute(call, context, service, database)
            "headlessSearch" -> com.ai.agent.tools.categories.HeadlessSearchTools.execute(call, context, service, database)
            "braveSearch" -> com.ai.agent.tools.categories.HeadlessSearchTools.execute(call, context, service, database)
            "googleSearchApi" -> com.ai.agent.tools.categories.HeadlessSearchTools.execute(call, context, service, database)
            "smartSearch" -> com.ai.agent.tools.categories.HeadlessSearchTools.execute(call, context, service, database)
            // v4.0.32: Deep link navigation (AI opens app screens)
            "navigateToAppScreen" -> com.ai.agent.tools.categories.DeepLinkTools.execute(call, context, service, database)
            "listAppScreens" -> com.ai.agent.tools.categories.DeepLinkTools.execute(call, context, service, database)
            // ==================== RULE MANAGEMENT (Proactive Assistant) ====================
            "createRule" -> RuleTools.execute(call, context, service, database)
            "listRules" -> RuleTools.execute(call, context, service, database)
            "deleteRule" -> RuleTools.execute(call, context, service, database)
            "modifyRule" -> RuleTools.execute(call, context, service, database)

            // === ACTIVITY TOOLS (Open specific screens inside other apps) ===

            "openActivity" -> AppTools.execute(call, context, service, database)

            "openDeepLink" -> AppTools.execute(call, context, service, database)

            "openAppWithData" -> AppTools.execute(call, context, service, database)

            "searchInApp" -> AppTools.execute(call, context, service, database)

            "openWhatsAppChat" -> AppTools.execute(call, context, service, database)

            "openYouTubeVideo" -> AppTools.execute(call, context, service, database)

            "openMapsLocation" -> AppTools.execute(call, context, service, database)

            "listAppActivities" -> AppTools.execute(call, context, service, database)

            "composeEmail" -> ContactTools.execute(call, context, service, database)

            "playSpotify" -> AppTools.execute(call, context, service, database)

            "openInstagramProfile" -> AppTools.execute(call, context, service, database)

            "openTelegramChat" -> AppTools.execute(call, context, service, database)

            "makePhoneCall" -> ContactTools.execute(call, context, service, database)

            "openGoogleSearch" -> AppTools.execute(call, context, service, database)

            "openAppSettings" -> AppTools.execute(call, context, service, database)

            "shareToApp" -> AppTools.execute(call, context, service, database)

            // ==================== ACTION CHAINS (v3.7.0) ====================
            "actionChain" -> executeActionChain(call, service)

            // ==================== COMPLETION (v3.9.6) ====================
            "complete" -> {
                // v3.9.6: Forces brain to verify success before stopping.
                // The brain MUST call this tool to signal task completion.
                // Returns TASK_COMPLETE signal that ChatViewModel checks to stop the loop.
                val success = call.args["success"] as? Boolean ?: false
                val reason = call.args["reason"] as? String ?: "No reason given"
                LogCapture.i(TAG, "complete(success=$success, reason=$reason)")
                ToolResult(true, "TASK_COMPLETE:$success:$reason")
            }
            // v4.0.62: TodoWrite — plan + track multi-step tasks
            "todoWrite" -> {
                val todosJson = call.args["todos"] as? String
                    ?: return ToolResult(false, "missing todos. Example: todoWrite(todos='[{\"content\":\"step 1\",\"status\":\"pending\"}]')")
                val formatted = com.ai.agent.tools.planning.TodoStore.update(todosJson)
                LogCapture.i(TAG, "todoWrite: ${com.ai.agent.tools.planning.TodoStore.getTodos().size} items")
                ToolResult(true, "Todo list updated. This will be shown to you in subsequent prompts.\n\n$formatted")
            }
            // v4.0.69: delegateTask — spawn a sub-agent
            "escalateLevel" -> {
                val level = (call.args["level"] as? Number)?.toInt() ?: 2
                val msg = com.ai.agent.llm.levels.LevelManager.escalateToLevel(level)
                LogCapture.i(TAG, "escalateLevel: to $level — ${msg.take(100)}")
                // v4.0.73: In combined approach, escalating also signals that the
                // tool filter should expand. ChatViewModel checks LevelManager.currentLevel
                // on next iteration and expands contextualToolFilter to include all
                // Level 3 tools if the level was escalated.
                // The actual filter expansion happens in ChatViewModel's loop —
                // here we just update the level state.
                ToolResult(true, msg + "\n\n⚠️ TOOL EXPANSION: Your tool filter has been expanded to include ALL Level $level tools. On the next iteration you will see all available tools.")
            }
            "delegateTask" -> {
                val description = call.args["description"] as? String
                    ?: return ToolResult(false, "missing description. Example: delegateTask(description=\"Download a car wallpaper\", agentType=\"research\")")
                val agentType = call.args["agentType"] as? String ?: "general"
                LogCapture.i(TAG, "delegateTask: type=$agentType, task=${description.take(80)}")
                kotlinx.coroutines.runBlocking {
                    val result = com.ai.agent.agent.SubAgentRunner.run(context, description, agentType)
                    ToolResult(result.success,
                        "Sub-agent (${agentType}) completed in ${result.iterations} iterations, ${result.toolCalls} tool calls.\n" +
                        "Success: ${result.success}\n" +
                        "Result: ${result.reply}"
                    )
                }
            }

            else -> {
                // v4.0.26: Check if this is a custom tool (created via createCustomTool)
                val customTool = com.ai.agent.tools.custom.CustomToolStore.get(context, call.name)
                if (customTool != null) {
                    com.ai.agent.tools.categories.CustomTools.execute(call, context, service, database)
                } else {
                    ToolResult(false, "unknown tool: ${call.name}")
                }
            }
        }
    }

    // ==================== ACTION CHAINS (v3.7.0) ====================

    /**
     * v3.7.0: Execute a chain of actions sequentially in 1 API call.
     *
     * - Each action is a {name, args, safe?} object.
     * - Chain STOPS on first failure (returns results so far).
     * - Actions marked safe: false PAUSE the chain — returns current state
     *   so the brain can verify before continuing in the next call.
     * - Default unsafe tools (sendSMS, deleteFile, etc.) are automatically safe: false.
     * - After the chain completes (or pauses), screen state is auto-appended.
     *
     * This saves 50-70% API calls for predictable multi-step UI flows.
     */
    private suspend fun executeActionChain(
        call: LLMClient.ToolCall,
        service: com.ai.agent.accessibility.AgentAccessibilityService?,
    ): ToolResult {
        // Parse the actions list from args.
        // v4.0.43: Handle JSON string actions — the model sometimes passes actions as a
        // JSON string instead of a List<Map>. Parse it if needed.
        var actionsRaw: List<Map<String, Any>>? = null
        when (val actionsArg = call.args["actions"]) {
            is List<*> -> {
                @Suppress("UNCHECKED_CAST")
                actionsRaw = actionsArg as? List<Map<String, Any>>
            }
            is String -> {
                // v4.0.43: Parse JSON string to list of maps
                try {
                    val arr = org.json.JSONArray(actionsArg)
                    val parsed = mutableListOf<Map<String, Any>>()
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        val map = mutableMapOf<String, Any>()
                        val keys = item.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            map[k] = item.get(k)
                        }
                        parsed.add(map)
                    }
                    actionsRaw = parsed
                    LogCapture.i(TAG, "actionChain: parsed JSON string actions (${parsed.size} actions)")
                } catch (e: Exception) {
                    LogCapture.w(TAG, "actionChain: failed to parse actions string: ${e.message}")
                }
            }
        }
        if (actionsRaw == null) return ToolResult(false, "actionChain: missing 'actions' list. Pass actions as a list of {name, args, safe} objects.")

        if (actionsRaw.isEmpty()) return ToolResult(false, "actionChain: empty actions list")

        LogCapture.i(TAG, "actionChain started (${actionsRaw.size} actions: ${actionsRaw.joinToString(", ") { (it["name"] as? String) ?: "?" }})")

        // Tools that are always unsafe (never chain without verification).
        val UNSAFE_TOOLS = setOf(
            "sendSMS", "sendEmail", "openWhatsAppChat", "deleteFile", "callContact",
            "makePhoneCall", "writeFile", "runShellCommand", "deleteRule",
        )

        val results = mutableListOf<String>()
        var chainFailed = false
        var pausedForVerification = false
        var pauseIndex = -1

        for ((index, actionRaw) in actionsRaw.withIndex()) {
            val toolName = actionRaw["name"] as? String ?: continue
            @Suppress("UNCHECKED_CAST")
            val toolArgs = (actionRaw["args"] as? Map<String, Any>) ?: emptyMap()
            val safe = when {
                actionRaw.containsKey("safe") -> actionRaw["safe"] as? Boolean ?: false
                toolName in UNSAFE_TOOLS -> false  // default unsafe
                else -> true  // default safe
            }

            // If this action is unsafe, PAUSE the chain before executing it.
            if (!safe) {
                pausedForVerification = true
                pauseIndex = index
                LogCapture.w(TAG, "actionChain PAUSED at action ${index + 1}/$toolName — safe:false (verify before continuing)")
                results.add("⏸️ PAUSED before action ${index + 1} ($toolName) — safe: false. Verify screen state before continuing.")
                break
            }

            // Execute the sub-tool.
            val subCall = LLMClient.ToolCall(toolName, toolArgs)
            val result = executeTool(subCall)

            val status = if (result.success) "✓" else "✗"
            LogCapture.i(TAG, "actionChain ${index + 1}/${actionsRaw.size}: $toolName(${toolArgs.entries.joinToString(", ") { "${it.key}=${it.value}" }}) → $status")
            results.add("${index + 1}. $toolName(${toolArgs.entries.joinToString(", ") { "${it.key}=${it.value}" }}) → $status ${result.output.take(150)}")

            if (!result.success) {
                chainFailed = true
                LogCapture.e(TAG, "actionChain FAILED at action ${index + 1} ($toolName): ${result.output.take(100)}")
                results.add("❌ Chain stopped at action ${index + 1} ($toolName failed).")
                break
            }

            // Small delay between actions for UI to settle.
            delay(300)
        }

        // Auto-append screen state after chain (if accessibility is available).
        val screenState = if (service != null) {
            try {
                val screen = service.readScreen()
                if (screen.isNotEmpty()) "\n\n[Screen after action]: ${screen.take(300)}" else ""
            } catch (_: Exception) { "" }
        } else ""

        val summary = buildString {
            if (chainFailed) {
                append("Chain FAILED at action ${results.size}.\n")
            } else if (pausedForVerification) {
                append("Chain PAUSED at action $pauseIndex (safe: false). Verify and continue.\n")
            } else {
                append("Chain completed: ${results.size} actions.\n")
            }
            results.forEach { append("$it\n") }
            append(screenState)
        }

        return ToolResult(
            success = !chainFailed,  // paused = success (brain decides next step)
            output = summary,
        ).also {
            if (chainFailed) {
                LogCapture.e(TAG, "actionChain ended: FAILED (${results.size} actions done)")
            } else if (pausedForVerification) {
                LogCapture.w(TAG, "actionChain ended: PAUSED at $pauseIndex (${results.size} actions done)")
            } else {
                LogCapture.i(TAG, "actionChain ended: completed (${results.size} actions)")
            }
        }
    }

    // ==================== VISION (VLM) ====================

    /**
     * v2.5.2: Capture ONE screenshot as base64 string (shared across ML Kit + VLM).
     * Returns null if screenshot fails.
     */
    private suspend fun captureScreenshotBase64(service: com.ai.agent.accessibility.AgentAccessibilityService): String? {
        val deferred = kotlinx.coroutines.CompletableDeferred<String?>()
        try {
            service.captureScreen { base64 ->
                deferred.complete(base64)
            }
        } catch (e: Exception) {
            Log.e(TAG, "captureScreenshotBase64 failed", e)
            deferred.complete(null)
        }
        return deferred.await()
    }

    /**
     * v2.5.2: Combine accessibility text + ML Kit OCR text, deduplicating similar lines.
     * Returns the merged text.
     */
    private fun combineAndDedupe(a11yText: String, mlText: String?): String {
        if (mlText.isNullOrBlank()) return a11yText
        if (a11yText.isBlank()) return mlText

        // Simple dedupe: if a11y text is contained in ML Kit text (or vice versa), use the longer one.
        if (mlText.contains(a11yText.take(100))) return mlText
        if (a11yText.contains(mlText.take(100))) return a11yText

        // Otherwise, combine both with section headers (dedupe exact duplicate lines)
        val a11yLines = a11yText.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val mlLines = mlText.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.toSet()

        val combined = StringBuilder()
        // Add a11y text first (more structured — button labels, content descriptions)
        combined.append(a11yText.trim())
        // Add ML Kit lines that aren't already in a11y (catches text in images/canvas)
        val newLines = mlLines - a11yLines
        if (newLines.isNotEmpty()) {
            combined.append("\n\n[OCR]: ")
            combined.append(newLines.joinToString("\n"))
        }
        return combined.toString()
    }

    /**
     * Wait for the accessibility service to be bound by Android before running a tool.
     *
     * The system calls onServiceConnected() asynchronously — even after the user has enabled
     * accessibility in Settings, the in-memory instance is briefly null at app launch.
     * This caused the "Accessibility service not running" error even when the service WAS
     * enabled. Now we check isEnabled(context) (the actual system setting) and if true, poll
     * for the instance to bind for up to 5 seconds.
     */
    private suspend fun waitForAccessibilityService(): AgentAccessibilityService? {
        // Fast path: instance already bound.
        AgentAccessibilityService.getInstance()?.let { return it }
        // Slow path: if enabled in system settings, wait for the bind to complete.
        if (!AgentAccessibilityService.isEnabled(context)) return null
        // v2.2.5: Increased poll from 5s to 10s (20 × 500ms) — after BrowserActivity
        // opens, the system may take longer to rebind the accessibility service.
        Log.i(TAG, "Accessibility is enabled but instance not yet bound — polling for up to 10s")
        repeat(20) { i ->
            delay(500)
            AgentAccessibilityService.getInstance()?.let {
                Log.i(TAG, "Accessibility service bound after ${i + 1} polls")
                return it
            }
        }
        Log.w(TAG, "Accessibility service enabled but never bound after 10s — Android may be slow")
        return AgentAccessibilityService.getInstance()  // last try
    }

    /**
     * Normalize positional args (0, 1, 2...) to named args.
     *
     * When the LLM writes tool calls as plain text (e.g., "tap(970, 180)"), the text
     * parser produces args like {"0": 970, "1": 180}. This converts them to the named
     * format each tool actually expects ({"x": 970, "y": 180}).
     *
     * If the LLM already used named args (proper JSON), this is a no-op.
     */
    private fun normalizePositionalArgs(call: LLMClient.ToolCall): LLMClient.ToolCall {
        // v4.0.20: Alias normalization — if the LLM used a common wrong key name,
        // remap it to the expected key before anything else.
        //
        // CRITICAL FIX (v4.0.20): The old code applied "name" → "package" GLOBALLY,
        // which stole the `name` argument from createRule, createSkill, deleteSkill,
        // getSkill, executeSkill, searchContacts, saveToKnowledge — all of which
        // legitimately use `name` as a parameter. This caused "missing name" errors
        // and broke Aira's rules + skills system entirely.
        //
        // Now: "name" → "package" is ONLY applied to tools that actually take a
        // `package` parameter (launchApp, searchInApp, etc.). The safe aliases
        // ("app", "pkg") remain global since no tool uses those as real params.
        val toolsWithPackageArg = setOf(
            "launchApp", "searchInApp", "forceStopApp", "uninstallApp",
            "getAppInfo", "openActivity", "openAppWithData", "searchInChrome"
        )
        val argAliases = if (call.name in toolsWithPackageArg) {
            mapOf(
                "app" to "package",
                "pkg" to "package",
                "name" to "package",   // only for package-taking tools
            )
        } else {
            mapOf(
                "app" to "package",
                "pkg" to "package",
                // NO "name" alias here — would break createRule/createSkill/etc.
            )
        }
        var remappedArgs = call.args.toMutableMap()
        for ((alias, canonical) in argAliases) {
            if (alias in remappedArgs && canonical !in remappedArgs) {
                remappedArgs[canonical] = remappedArgs.remove(alias)!!
            }
        }
        val call = LLMClient.ToolCall(call.name, remappedArgs)

        // Only normalize if there are positional args (numeric string keys)
        val hasPositional = call.args.keys.any { it.toIntOrNull() != null }
        if (!hasPositional) return call

        // Map: tool name → list of expected arg names in positional order
        val argOrder = when (call.name) {
            "tap" -> listOf("x", "y")
            "swipe" -> listOf("x1", "y1", "x2", "y2")
            "type", "clickByText" -> listOf("text")
            "launchApp" -> listOf("package")
            "searchInApp" -> listOf("package", "query")
            "webSearch", "openGoogleSearch" -> listOf("query", "fetchTop")
            "browserOpen", "openUrl" -> listOf("url")
            "browserClickText", "browserClickElement", "browserGetText" -> listOf("selector")
            "browserFillForm" -> listOf("field", "value")
            "remember" -> listOf("key", "value")
            "recall", "recallSimilar", "searchMemory" -> listOf("query")
            "setVolume" -> listOf("level")
            "setBrightness" -> listOf("level")
            "sendSMS" -> listOf("number", "message")
            "callContact" -> listOf("number")
            "setAlarm" -> listOf("hour", "minute")
            "setTimer" -> listOf("seconds")
            "takeScreenshotToGallery", "scrollDown", "scrollUp",
            "pressBack", "pressHome", "pressEnter", "submitInput",
            "recallAll", "listInstalledApps" -> emptyList()
            else -> emptyList()
        }

        if (argOrder.isEmpty()) return call

        val newArgs = mutableMapOf<String, Any>()
        // Copy named args first
        for ((key, value) in call.args) {
            if (key.toIntOrNull() == null) {
                newArgs[key] = value
            }
        }
        // Map positional args to named args
        for ((pos, name) in argOrder.withIndex()) {
            val posValue = call.args[pos.toString()]
            if (posValue != null && !newArgs.containsKey(name)) {
                newArgs[name] = posValue
            }
        }

        return LLMClient.ToolCall(call.name, newArgs)
    }

    /**
     * v4.0.21: Build a context-aware accessibility error message.
     *
     * Distinguishes between:
     * 1. Accessibility NOT enabled in settings → tell user to enable it
     * 2. Accessibility enabled in settings BUT service instance not bound →
     *    Android killed/restarted the service. Tell user to toggle OFF then ON.
     *    (This is the "why does it keep saying turn on accessibility when it's
     *    already on" bug — the setting is on, but the service process is dead.)
     */
    private fun buildAccessibilityErrorMessage(context: Context): String {
        val isEnabled = AgentAccessibilityService.isEnabled(context)
        return if (isEnabled) {
            // Settings say it's ON, but the service instance is null (dead/unbound).
            // This happens when Android killed the service (OOM, app restart, MIUI
            // battery optimization). The fix is to toggle it OFF then ON in settings,
            // which forces Android to rebind the service.
            "⚠️ Accessibility is enabled but the service is not responding (Android may have killed it). " +
                "Go to Settings → Accessibility → AI Agent → toggle it OFF then back ON to reconnect. " +
                "This happens when Android restarts the service in the background."
        } else {
            "Accessibility service is not enabled. Open Settings → Accessibility → AI Agent → toggle ON. " +
                "(This tool requires accessibility to control the screen.)"
        }
    }

    data class ToolResult(
        val success: Boolean,
        val output: String
    )

    // v4.0.2: Sensitive tools that require user consent before execution.
    // These tools change state, send data, or delete data — user should approve first.
    // v4.0.25: REMOVED replyToNotification from consent list. Rule-triggered auto-replies
    // (WhatsApp, Telegram) should send INSTANTLY without a confirmation popup. The user
    // already consented by creating the rule. Keeping it in consent list made auto-reply
    // rules unusable (popup appeared, notification expired before user could approve).
    private val CONSENT_TOOLS = setOf(
        "sendSMS", "sendEmail", "makePhoneCall", "callContact",
        "deleteFile", "deleteRule", "uninstallApp", "forceStopApp",
        "writeFile", "setClipboard",
        "openWhatsAppChat",  // sends a message
        "shareToApp", "shareText", "shareFile",
        "sendAttachmentTo",  // v4.0.5: sends file to external app (data exfiltration risk)
    )

    /** Build a human-readable consent message for the dialog. */
    private fun buildConsentMessage(call: LLMClient.ToolCall): Pair<String, String> {
        val args = call.args
        val description = when (call.name) {
            "sendSMS" -> "Send SMS"
            "sendEmail" -> "Send Email"
            "makePhoneCall" -> "Make Phone Call"
            "callContact" -> "Call Contact"
            "deleteFile" -> "Delete File"
            "deleteRule" -> "Delete Rule"
            "uninstallApp" -> "Uninstall App"
            "forceStopApp" -> "Force Stop App"
            "writeFile" -> "Write to File"
            "replyToNotification" -> "Reply to Notification"
            "setClipboard" -> "Set Clipboard"
            "openWhatsAppChat" -> "Send WhatsApp Message"
            "shareToApp" -> "Share to App"
            "shareText" -> "Share Text"
            "shareFile" -> "Share File"
            else -> call.name
        }
        val details = when (call.name) {
            "sendSMS" -> {
                val num = args["number"] ?: args["phone"] ?: "?"
                val msg = args["message"] ?: ""
                "To: $num\nMessage: $msg"
            }
            "sendEmail", "composeEmail" -> {
                val to = args["to"] ?: args["email"] ?: "?"
                val subj = args["subject"] ?: ""
                "To: $to\nSubject: $subj"
            }
            "makePhoneCall", "callContact" -> {
                val num = args["number"] ?: args["phone"] ?: args["name"] ?: "?"
                "Call: $num"
            }
            "deleteFile" -> "Path: ${args["path"] ?: "?"}"
            "writeFile" -> "Path: ${args["path"] ?: "?"}\nContent: ${(args["content"] ?: "").toString().take(100)}"
            "deleteRule" -> "Rule ID: ${args["id"] ?: "?"}"
            "uninstallApp" -> "Package: ${args["package"] ?: "?"}"
            "forceStopApp" -> "Package: ${args["package"] ?: "?"}"
            "replyToNotification" -> "Message: ${args["message"] ?: "?"}"
            "setClipboard" -> "Text: ${(args["text"] ?: "").toString().take(100)}"
            "openWhatsAppChat" -> {
                val phone = args["phone"] ?: args["number"] ?: "?"
                val msg = args["message"] ?: ""
                "To: $phone\nMessage: $msg"
            }
            "shareText" -> "Text: ${(args["text"] ?: "").toString().take(100)}"
            "shareFile" -> "Path: ${args["path"] ?: "?"}"
            else -> args.entries.joinToString("\n") { "${it.key}: ${it.value}" }
        }
        return description to details
    }

    /**
     * v4.0.61: searchTools — search ALL tools (built-in + custom) by keyword.
     *
     * Searches tool names + descriptions (case-insensitive) and returns matching
     * tools with their descriptions. Max 15 results to keep output concise.
     *
     * Use cases:
     * - "I need to create a rule" → searchTools("rule") → createRule, listRules, etc.
     * - "I need to reply to WhatsApp" → searchTools("whatsapp reply") → replyToNotification
     * - "Does a download tool exist?" → searchTools("download") → downloadFile, openUrl
     *
     * If no results → tells the AI it can create a custom tool via createCustomTool.
     */
    private fun searchTools(call: LLMClient.ToolCall, context: Context): ToolResult {
        val query = call.args["query"] as? String
            ?: return ToolResult(false, "missing query. Example: searchTools(query=\"download file\")")

        if (query.isBlank()) {
            return ToolResult(false, "query is empty. Example: searchTools(query=\"whatsapp notification\")")
        }

        // Normalize query for matching
        val queryLower = query.lowercase().trim()
        val queryWords = queryLower.split(Regex("\\s+")).filter { it.isNotEmpty() }

        // Collect all tools (name + description) from ToolSchemas
        val allSchemas: List<ToolSchemas.Schema> = ToolSchemas.allSchemas()
        val results = mutableListOf<Pair<String, String>>()  // (name, description)

        for (schema in allSchemas) {
            val nameLower = schema.name.lowercase()
            val descLower = schema.description.lowercase()

            // Match if query appears in name or description, OR any query word matches
            val matches = queryLower in nameLower || queryLower in descLower ||
                queryWords.any { word -> nameLower.contains(word) || descLower.contains(word) }

            if (matches) {
                results.add(Pair(schema.name, schema.description))
            }
        }

        // Also search custom tools
        try {
            val customTools = com.ai.agent.tools.custom.CustomToolStore.load(context)
            for (tool in customTools) {
                val nameLower = tool.name.lowercase()
                val descLower = tool.description.lowercase()
                val matches = queryLower in nameLower || queryLower in descLower ||
                    queryWords.any { word -> nameLower.contains(word) || descLower.contains(word) }
                if (matches) {
                    results.add(Pair(tool.name, "(custom) ${tool.description}"))
                }
            }
        } catch (_: Exception) {
            // Custom tools not available — skip
        }

        return if (results.isEmpty()) {
            ToolResult(true,
                "No tools found for '$query'.\n\n" +
                "No built-in or custom tool matches. You can CREATE one:\n" +
                "  createCustomTool(name=\"my_tool\", description=\"...\", handlerType=\"shell\" or \"http\", handler=\"...\", parameters=\"param1,param2\")\n\n" +
                "Or try a broader search (e.g., 'file' instead of 'download file to sdcard').")
        } else {
            // Sort: name matches first (more relevant), then description matches
            val sorted = results.sortedWith(compareByDescending { (name, _) ->
                queryWords.any { word -> name.contains(word) }
            })

            val sb = StringBuilder("Tools matching '$query' (${sorted.size} found):\n\n")
            for ((name, desc) in sorted.take(15)) {  // max 15 results
                sb.append("• $name\n")
                sb.append("  $desc\n\n")
            }
            if (sorted.size > 15) {
                sb.append("... and ${sorted.size - 15} more. Try a more specific query.\n")
            }
            sb.append("\nCall any tool above by its name. If none fit, use createCustomTool.")
            ToolResult(true, sb.toString())
        }
    }
}
