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
                val x = (call.args["x"] as? Number)?.toFloat() ?: return ToolExecutor.ToolResult(false, "missing x")
                val y = (call.args["y"] as? Number)?.toFloat() ?: return ToolExecutor.ToolResult(false, "missing y")
                ToolExecutor.ToolResult(service!!.tap(x, y), "tap($x, $y)")
            }
            "clickByText" -> {
                val text = call.args["text"] as? String ?: return ToolExecutor.ToolResult(false, "missing text")
                ToolExecutor.ToolResult(service!!.clickByText(text), "clickByText($text)")
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
            "pressEnter" -> ToolExecutor.ToolResult(service!!.pressEnter(), "pressEnter")
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
