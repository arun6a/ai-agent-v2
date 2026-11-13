package com.ai.agent.learning

import android.content.Context
import android.util.Log
import com.ai.agent.storage.AgentDatabase
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v4.0.37: Pattern Learning + Device Calibration Memory.
 *
 * TWO learning mechanisms:
 *
 * 1. PATTERN LEARNING:
 *    When a task succeeds (complete(success=true)), extract the tool sequence
 *    + save it as a pattern. Next time a similar request comes in, the pattern
 *    is recalled via recallSimilar() — the AI can skip exploration + go straight
 *    to the known-good tool sequence.
 *
 *    Example:
 *      User: "play BLACKPINK on YouTube"
 *      AI succeeds: searchInApp(youtube, BLACKPINK) → clickElement(0) → complete
 *      Saved as: pattern_play_on_youtube = "searchInApp → clickElement → complete"
 *      Next time: "play Dua Lipa on YouTube" → recallSimilar finds the pattern
 *      → AI uses searchInApp directly (saves 2-3 API calls)
 *
 * 2. DEVICE CALIBRATION:
 *    When a click SUCCEEDS on an element, save the coordinates + app + element
 *    description. Next time the AI needs to click something similar in the same
 *    app, it can use the known-good coordinates directly (saves readScreenStructured).
 *
 *    Example:
 *      AI: clickElement(index=5) on YouTube → success
 *      Saved: calib_youtube_search_icon = "index=5, text=Search, works"
 *      Next time: recallSimilar("youtube search") → finds the calibration
 *      → AI uses clickElement(index=5) directly
 */
object PatternLearner {
    private const val TAG = "PatternLearner"

    /**
     * Save a successful tool sequence as a pattern.
     * Called when complete(success=true) is called.
     *
     * @param userMessage The original user request
     * @param toolSequence List of tool calls that led to success (e.g., ["searchInApp(package=youtube, query=X)", "clickElement(index=0)"])
     * @param reason The completion reason (what was achieved)
     */
    fun saveSuccessfulPattern(
        context: Context,
        userMessage: String,
        toolSequence: List<String>,
        reason: String
    ) {
        if (toolSequence.isEmpty() || userMessage.isBlank()) return
        try {
            val db = AgentDatabase(context)
            // Create a pattern key from the user message
            val patternKey = "pattern_" + userMessage.take(40)
                .replace(" ", "_")
                .replace(Regex("[^a-zA-Z0-9_]"), "")
                .lowercase()

            // Build a concise pattern summary
            val timestamp = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date())
            val patternValue = buildString {
                append("[$timestamp] SUCCESSFUL PATTERN for: \"$userMessage\"\n")
                append("Result: $reason\n")
                append("Tool sequence (${toolSequence.size} steps):\n")
                toolSequence.forEachIndexed { i, step ->
                    append("  ${i+1}. $step\n")
                }
                append("\nNext time a similar request comes in, use this sequence directly.")
            }

            db.remember(patternKey, patternValue.take(800))
            Log.i(TAG, "✅ Pattern saved: $patternKey (${toolSequence.size} steps) for: ${userMessage.take(50)}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save pattern", e)
        }
    }

    /**
     * Save a successful click as a device calibration.
     * Called when clickElement/clickByText/tap succeeds.
     *
     * @param appPackage The current app (e.g., "com.google.android.youtube")
     * @param toolName Which click tool was used (clickElement, clickByText, tap)
     * @param elementDesc What was clicked (text or index)
     * @param success Whether the click succeeded
     */
    fun saveClickCalibration(
        context: Context,
        appPackage: String,
        toolName: String,
        elementDesc: String,
        success: Boolean
    ) {
        if (appPackage.isBlank() || elementDesc.isBlank()) return
        try {
            val db = AgentDatabase(context)
            // Create a calibration key
            val appName = appPackage.substringAfterLast(".").take(15)
            val descSlug = elementDesc.take(30)
                .replace(" ", "_")
                .replace(Regex("[^a-zA-Z0-9_]"), "")
                .lowercase()
            val calibKey = if (success) {
                "calib_${appName}_${descSlug}"
            } else {
                "avoid_calib_${appName}_${descSlug}"
            }

            val timestamp = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date())
            val value = if (success) {
                "[$timestamp] CLICK WORKS: app=$appPackage, tool=$toolName, target=$elementDesc. " +
                    "This click succeeded on this device. Use the same approach next time."
            } else {
                "[$timestamp] CLICK FAILED: app=$appPackage, tool=$toolName, target=$elementDesc. " +
                    "This click failed on this device. Try a different approach (readScreenStructured first, or clickByText)."
            }

            db.remember(calibKey, value.take(300))
            Log.d(TAG, "Calibration saved: $calibKey (success=$success)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save calibration", e)
        }
    }
}
