package com.ai.agent.tools

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.ai.agent.accessibility.AgentAccessibilityService
import com.ai.agent.browser.BrowserController
import com.ai.agent.llm.AIProvider
import com.ai.agent.llm.LLMClient
import com.ai.agent.llm.SavedModelsStore
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
import com.ai.agent.tools.categories.BrowserTools
import com.ai.agent.tools.categories.ContactTools
import com.ai.agent.tools.categories.DeviceTools
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
            "readScreen", "readScreenStructured", "tap", "clickByText", "type", "swipe",
            "scrollDown", "scrollUp", "pressBack", "pressHome", "pressEnter", "submitInput",
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
            // v3.0.0: speak tool for rules
            "speak",
            // Memory
            "remember", "recall", "recallAll", "recallSimilar", "searchMemory",
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
            // Media
            "mediaPlayPause", "mediaNext", "mediaPrevious", "takePhoto",
            // Skills
            "executeSkill", "listSkills", "createSkill", "deleteSkill", "getSkill",
            // Async
            "wait", "waitAndContinue",
            // v3.7.0: Action chains
            "actionChain"
        )
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
        "sendEmail" to listOf(android.Manifest.permission.READ_CONTACTS),
        "replyToNotification" to listOf("__notification_listener__"),
        // readScreen, tap, clickByText, etc. need accessibility — but they already
        // check service != null + return early. No extra check needed here.
    )

    private val database = AgentDatabase(context)

    /**
     * Execute a list of tool calls sequentially.
     * Returns a list of results (one per tool call).
     */
    suspend fun executeTools(toolCalls: List<LLMClient.ToolCall>): List<ToolResult> {
        val results = mutableListOf<ToolResult>()
        for (call in toolCalls) {
            val result = executeTool(call)
            results.add(result)
            val status = if (result.success) "✓" else "✗"
            LogCapture.i(TAG, "tool: ${call.name}(${call.args.entries.joinToString(", ") { "${it.key}=${it.value}" }}) → $status ${result.output.take(80)}")
        }
        return results
    }

    private suspend fun executeTool(call: LLMClient.ToolCall): ToolResult {
        // Normalize positional args (0, 1, 2...) to named args for tools that the LLM
        // wrote as plain text (e.g., "tap(970, 180)" → args={"0":970, "1":180}).
        // This converts them to the expected named format ({"x":970, "y":180}).
        val call = normalizePositionalArgs(call)

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
            // v3.0.0: speak tool for rules
            "speak",
            "remember", "recall", "recallAll", "recallSimilar", "searchMemory",
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
            "recallSimilar", "searchMemory",
            "saveToKnowledge", "recallKnowledge", "listKnowledge",
            "listAttachments", "readAttachment", "sendAttachmentTo",
            "replyToNotification",
            "openActivity", "openDeepLink", "openAppWithData", "searchInApp",
            "listAppActivities", "openWhatsAppChat", "openYouTubeVideo", "openMapsLocation",
            "playSpotify", "openInstagramProfile", "openTelegramChat",
            "openGoogleSearch", "shareToApp", "openContact",
            "mediaPlayPause", "mediaNext", "mediaPrevious", "takePhoto"
        )

        // For non-accessibility tools, skip the service check entirely.
        // These tools don't reference `service` in the when block below.
        val service: AgentAccessibilityService? = if (noAccessibilityNeeded) {
            null  // not needed — these tools don't use it
        } else {
            waitForAccessibilityService()
                ?: return ToolResult(false, "Accessibility service is not enabled. Open Settings → Accessibility → AI Agent → toggle ON. (This tool requires accessibility to control the screen.)")
        }

        // v3.6.0: Auto-detect missing permissions for specific tools.
        // If a tool needs a permission that isn't granted, return an error
        // with a helpful message instead of failing silently.
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
                        // VLM mode — skip a11y + ML Kit, go straight to VLM (1 screenshot, 1 API call)
                        // v3.3.11: check if a vision model is configured first.
                        val visionModel = SavedModelsStore.getVisionModel(context)
                        if (visionModel == null) {
                            ToolResult(false, "VLM mode selected but no vision model configured. Set one in AI Settings → Vision, or switch to Auto/ML Kit mode.")
                        } else {
                            Log.i(TAG, "readScreen: VLM mode — calling VLM directly (${visionModel.modelName})")
                            val vlmResult = VisionTools.analyzeScreenWithVLM(context, service!!, "Read ALL text visible on the screen. Include app name, all visible text, button labels, menu items, and any text inside images. Be thorough.")
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
            // ==================== VECTOR MEMORY ====================
            "recallSimilar" -> MemoryTools.execute(call, context, service, database)
            "searchMemory" -> MemoryTools.execute(call, context, service, database)
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

            else -> ToolResult(false, "unknown tool: ${call.name}")
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
        @Suppress("UNCHECKED_CAST")
        val actionsRaw = call.args["actions"] as? List<Map<String, Any>>
            ?: return ToolResult(false, "actionChain: missing 'actions' list")

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

    data class ToolResult(
        val success: Boolean,
        val output: String
    )
}
