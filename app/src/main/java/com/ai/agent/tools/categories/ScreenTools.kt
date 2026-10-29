package com.ai.agent.tools.categories

import android.content.Context
import android.util.Log
import com.ai.agent.accessibility.AgentAccessibilityService
import com.ai.agent.llm.LLMClient
import com.ai.agent.storage.AgentDatabase
import com.ai.agent.tools.ToolExecutor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay

/**
 * Screen tools category — extracted from ToolExecutor in v3.3.13.
 *
 * Tools: readScreenStructured, tap, clickByText, type, swipe, scrollDown,
 * scrollUp, pressBack, pressHome, pressEnter, submitInput, lockScreen,
 * takeScreenshotToGallery.
 *
 * NOTE: `readScreen` is intentionally kept in ToolExecutor — it depends on the
 * shared helpers captureScreenshotBase64(), combineAndDedupe(), and the VLM
 * helpers in VisionTools. It will be extracted in a later phase.
 *
 * All tools in this category require the accessibility service (`service`)
 * to be non-null — guaranteed by ToolExecutor.executeTool before delegation.
 *
 * No behavior changes — code moved verbatim from ToolExecutor.kt.
 */
object ScreenTools {

    private const val TAG = "ScreenTools"

    suspend fun execute(
        call: LLMClient.ToolCall,
        @Suppress("UNUSED_PARAMETER") context: Context,
        service: AgentAccessibilityService?,
        @Suppress("UNUSED_PARAMETER") database: AgentDatabase
    ): ToolExecutor.ToolResult {
        return when (call.name) {
            "tap" -> {
                // v3.7.3: If the brain passes target=, redirect to clickByText.
                val target = call.args["target"] as? String
                if (target != null) {
                    return ToolExecutor.ToolResult(service!!.clickByText(target), "tap→clickByText($target)")
                }
                val x = (call.args["x"] as? Number)?.toFloat() ?: return ToolExecutor.ToolResult(false, "missing x. Pass {x, y} for coordinates OR {target: \"text\"} to click by text.")
                val y = (call.args["y"] as? Number)?.toFloat() ?: return ToolExecutor.ToolResult(false, "missing y. Pass {x, y} for coordinates OR {target: \"text\"} to click by text.")
                // v3.8.13: Dynamic screen resolution — was hardcoded 1080x2400, but tablets have
                // different resolutions (e.g., 2880x1800). Detect actual screen size from the context.
                val displayMetrics = context.resources.displayMetrics
                val screenWidth = displayMetrics.widthPixels
                val screenHeight = displayMetrics.heightPixels
                // v3.7.7: Reject out-of-bounds taps.
                if (x > screenWidth || y > screenHeight || x < 0 || y < 0) {
                    return ToolExecutor.ToolResult(false, "tap($x, $y) is OUT OF BOUNDS — screen is ${screenWidth}x${screenHeight}. Call readScreenStructured() to get real element bounds, then tap the center: x=(left+right)/2, y=(top+bottom)/2.")
                }
                ToolExecutor.ToolResult(service!!.tap(x, y), "tap($x, $y)")
            }
            "clickByText" -> {
                val text = call.args["text"] as? String ?: return ToolExecutor.ToolResult(false, "missing text")
                val result = service!!.clickByText(text)
                // v4.0.37: Device Calibration — save click result for this app
                try {
                    val appPkg = service.getCurrentForegroundPackage() ?: ""
                    if (appPkg.isNotEmpty()) {
                        com.ai.agent.learning.PatternLearner.saveClickCalibration(
                            context = context,
                            appPackage = appPkg,
                            toolName = "clickByText",
                            elementDesc = text,
                            success = result
                        )
                    }
                } catch (_: Exception) {}
                ToolExecutor.ToolResult(result, "clickByText($text)")
            }
            // v3.9.9: clickElement(index) — click by index from readScreenStructured.
            // Preferred over tap(x,y) — no coordinate guessing. The brain reads
            // readScreenStructured (which now includes "index" field), picks the
            // element it wants (e.g., the first video result), and clicks it by index.
            "clickElement" -> {
                val index = (call.args["index"] as? Number)?.toInt()
                    ?: return ToolExecutor.ToolResult(false, "missing index. Call readScreenStructured() first to see element indices, then clickElement(index=N).")
                val clicked = service!!.clickElement(index)
                // v4.0.37: Device Calibration — save click result for this app
                try {
                    val appPkg = service.getCurrentForegroundPackage() ?: ""
                    if (appPkg.isNotEmpty()) {
                        com.ai.agent.learning.PatternLearner.saveClickCalibration(
                            context = context,
                            appPackage = appPkg,
                            toolName = "clickElement",
                            elementDesc = "index=$index",
                            success = clicked
                        )
                    }
                } catch (_: Exception) {}
                ToolExecutor.ToolResult(clicked, if (clicked) "clickElement($index) → tapped" else "clickElement($index) failed (element not found — screen may have changed. Call readScreenStructured() again.)")
            }
            // v4.0.2: longClick — long press (context menus, drag, etc.)
            "longClick" -> {
                val x = (call.args["x"] as? Number)?.toFloat()
                val y = (call.args["y"] as? Number)?.toFloat()
                if (x != null && y != null) {
                    ToolExecutor.ToolResult(service!!.longClick(x, y), "longClick($x, $y)")
                } else {
                    return ToolExecutor.ToolResult(false, "missing x or y. Pass {x, y} for coordinates.")
                }
            }
            "longClickElement" -> {
                val index = (call.args["index"] as? Number)?.toInt()
                    ?: return ToolExecutor.ToolResult(false, "missing index. Call readScreenStructured() first, then longClickElement(index=N).")
                val clicked = service!!.longClickElement(index)
                ToolExecutor.ToolResult(clicked, if (clicked) "longClickElement($index) → done" else "longClickElement($index) failed (element not found)")
            }
            // v4.0.2: focusElement — focus before typing (some apps need this)
            "focusElement" -> {
                val index = (call.args["index"] as? Number)?.toInt()
                    ?: return ToolExecutor.ToolResult(false, "missing index. Call readScreenStructured() first, then focusElement(index=N).")
                val focused = service!!.focusElement(index)
                ToolExecutor.ToolResult(focused, if (focused) "focusElement($index) → focused" else "focusElement($index) failed")
            }
            // v4.0.2: paste — paste clipboard into an element by index
            "paste" -> {
                val index = (call.args["index"] as? Number)?.toInt()
                    ?: return ToolExecutor.ToolResult(false, "missing index. Call readScreenStructured() first, then paste(index=N) to paste clipboard into that element.")
                val pasted = service!!.pasteElement(index)
                ToolExecutor.ToolResult(pasted, if (pasted) "paste($index) → pasted" else "paste($index) failed (element not found or not editable)")
            }
            // v4.0.2: edit — type + optional submit in ONE call (saves API calls)
            // Replaces the type() + submitInput() 2-call pattern for search boxes.
            "edit" -> {
                val index = (call.args["index"] as? Number)?.toInt()
                    ?: return ToolExecutor.ToolResult(false, "missing index. Call readScreenStructured() first, then edit(index=N, text=\"...\", autoSubmit=true).")
                val text = call.args["text"] as? String ?: return ToolExecutor.ToolResult(false, "missing text")
                val autoSubmit = (call.args["autoSubmit"] as? Boolean) ?: false
                val edited = service!!.editElement(index, text, autoSubmit)
                // If autoSubmit, also call submitInput
                var submitted = false
                if (edited && autoSubmit) {
                    delay(400)
                    submitted = service!!.submitInput()
                    delay(800)
                }
                val msg = if (edited && autoSubmit) "edit($index, text) → typed + submitted"
                    else if (edited) "edit($index, text) → typed (autoSubmit=false)"
                    else "edit($index) failed (element not found or not editable)"
                ToolExecutor.ToolResult(edited, msg)
            }
            "type" -> {
                val text = call.args["text"] as? String ?: return ToolExecutor.ToolResult(false, "missing text")
                // First, check what app is currently foreground so we don't accidentally type
                // into the AI Agent's own chat input.
                val am = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                val currentPkg = am.runningAppProcesses?.firstOrNull { it.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND }?.processName ?: "unknown"
                if (currentPkg == "com.ai.agent") {
                    // We're about to type into our own app. Try to dismiss focus by hiding keyboard
                    // and pressing back a couple of times — this is a recovery path.
                    android.util.Log.w(TAG, "type() called while AI Agent is foreground — likely a launchApp didn't complete. Attempting recovery.")
                    return ToolExecutor.ToolResult(false, "type($text) ABORTED: AI Agent app is still foreground. The previous launchApp did not complete. Try calling launchApp again, then readScreen() to verify.")
                }
                val typed = service!!.type(text)
                delay(400)  // let the IME settle
                ToolExecutor.ToolResult(typed, "type($text) on $currentPkg")
            }
            "swipe" -> {
                val x1 = (call.args["x1"] as? Number)?.toFloat() ?: return ToolExecutor.ToolResult(false, "missing x1")
                val y1 = (call.args["y1"] as? Number)?.toFloat() ?: return ToolExecutor.ToolResult(false, "missing y1")
                val x2 = (call.args["x2"] as? Number)?.toFloat() ?: return ToolExecutor.ToolResult(false, "missing x2")
                val y2 = (call.args["y2"] as? Number)?.toFloat() ?: return ToolExecutor.ToolResult(false, "missing y2")
                ToolExecutor.ToolResult(service!!.swipe(x1, y1, x2, y2), "swipe($x1,$y1 → $x2,$y2)")
            }
            "scrollDown" -> ToolExecutor.ToolResult(service!!.scrollDown(), "scrollDown")
            "scrollUp" -> ToolExecutor.ToolResult(service!!.scrollUp(), "scrollUp")
            "pressBack" -> ToolExecutor.ToolResult(service!!.pressBack(), "pressBack")
            "pressHome" -> ToolExecutor.ToolResult(service!!.pressHome(), "pressHome")
            "pressEnter" -> {
                // v3.8.9: pressEnter often doesn't submit on YouTube/Google search boxes
                // because the Enter key transforms into a Search button. Fall back to submitInput.
                val entered = service!!.pressEnter()
                delay(500)
                // Check if the input field is still focused (meaning Enter didn't submit)
                val stillFocused = service!!.rootInActiveWindow?.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT) != null
                if (stillFocused) {
                    android.util.Log.w(TAG, "pressEnter didn't submit (input still focused) — falling back to submitInput")
                    val submitted = service!!.submitInput()
                    delay(800)
                    ToolExecutor.ToolResult(submitted, "pressEnter → submitInput fallback (Enter didn't submit)")
                } else {
                    ToolExecutor.ToolResult(entered, "pressEnter (submitted)")
                }
            }
            "submitInput" -> {
                // Same AI-Agent-foreground guard as type() — don't submit our own chat input.
                val am = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                val currentPkg = am.runningAppProcesses?.firstOrNull { it.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND }?.processName ?: "unknown"
                if (currentPkg == "com.ai.agent") {
                    android.util.Log.w(TAG, "submitInput() called while AI Agent is foreground — aborting to prevent sending our own chat.")
                    return ToolExecutor.ToolResult(false, "submitInput ABORTED: AI Agent is foreground. launchApp didn't complete. Try again with launchApp + readScreen first.")
                }
                val submitted = service!!.submitInput()
                delay(800)  // wait for navigation/results to load
                ToolExecutor.ToolResult(submitted, "submitInput on $currentPkg")
            }
            "lockScreen" -> {
                service!!.lockScreen()
                ToolExecutor.ToolResult(true, "Screen locked")
            }
            "takeScreenshotToGallery" -> {
                val screenshotDeferred = CompletableDeferred<String?>()
                service!!.captureScreen { base64 ->
                    screenshotDeferred.complete(base64)
                }
                val base64 = screenshotDeferred.await()
                if (base64 != null) {
                    try {
                        val bytes = android.util.Base64.decode(base64, android.util.Base64.NO_WRAP)
                        val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        val filename = "ai_agent_screenshot_${System.currentTimeMillis()}.jpg"
                        val path = android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_PICTURES
                        ).toString() + "/$filename"
                        val file = java.io.File(path)
                        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
                        ToolExecutor.ToolResult(true, "Screenshot saved to: $path")
                    } catch (e: Exception) {
                        ToolExecutor.ToolResult(false, "Error saving screenshot: ${e.message}")
                    }
                } else {
                    ToolExecutor.ToolResult(false, "Failed to capture screenshot")
                }
            }
            // ==================== SCREEN STRUCTURE TREE ====================
            "readScreenStructured" -> {
                val svc = service ?: AgentAccessibilityService.getInstance()
                    ?: return ToolExecutor.ToolResult(false, "Accessibility service not running")
                val result = svc.readScreenStructured()
                ToolExecutor.ToolResult(true, "Screen structure:\n$result")
            }
            else -> ToolExecutor.ToolResult(false, "Unknown screen tool: ${call.name}")
        }
    }
}
