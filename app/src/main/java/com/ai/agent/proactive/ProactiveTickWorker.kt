package com.ai.agent.proactive

import android.content.Context
import android.util.Log
import com.ai.agent.llm.LLMClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * v4.0.76: ProactiveTickWorker — runs every N minutes to suggest actions.
 *
 * Called by ProactiveScheduler (a periodic WorkManager job).
 * Each tick:
 * 1. Checks if proactive is enabled + not in quiet hours
 * 2. Gathers context (time, battery, location, recent patterns)
 * 3. Makes a small LLM call (~800 tokens at Level 1) to decide:
 *    - SUGGEST: propose an action to the user
 *    - EXECUTE: auto-execute an action (if autoExecute is ON)
 *    - SILENT: do nothing (no relevant suggestion)
 * 4. If SUGGEST: shows a notification with the suggestion
 * 5. If EXECUTE: runs the action via the agent loop
 *
 * Token cost per tick: ~800 tokens (Level 1 prompt + 1 tool call)
 * At 5-min intervals: 288 calls/day × 800 = ~230K tokens/day
 */
object ProactiveTickWorker {
    private const val TAG = "ProactiveTick"
    private const val TIMEOUT_MS = 15000L  // 15 seconds — don't block too long

    /**
     * Run one proactive tick. Called by ProactiveScheduler.
     * Returns the suggestion (if any) for the scheduler to display.
     */
    suspend fun tick(context: Context): ProactiveResult {
        // Check if proactive is enabled
        if (!ProactiveSettings.isEnabled(context)) {
            return ProactiveResult.Silent("Proactive disabled")
        }

        // Check quiet hours
        if (ProactiveSettings.isInQuietHours(context)) {
            Log.i(TAG, "In quiet hours — skipping tick")
            return ProactiveResult.Silent("Quiet hours")
        }

        return withContext(Dispatchers.IO) {
            try {
                withTimeoutOrNull(TIMEOUT_MS) {
                    runTick(context)
                } ?: ProactiveResult.Silent("Tick timed out")
            } catch (e: Exception) {
                Log.w(TAG, "Tick failed: ${e.message}")
                ProactiveResult.Silent("Error: ${e.message}")
            }
        }
    }

    private suspend fun runTick(context: Context): ProactiveResult {
        // Gather context
        val contextStr = buildContext(context)
        val personality = ProactiveSettings.getPersonality(context)
        val establishedPatterns = ProactiveObserver.getEstablishedPatterns(context)

        // Build prompt
        val patternsStr = if (establishedPatterns.isEmpty()) {
            "No established patterns yet."
        } else {
            establishedPatterns.joinToString("\n") { "- ${it.trigger} → ${it.suggestedAction} (${it.occurrences}x)" }
        }

        val prompt = """$personality

## Current Context
$contextStr

## Established User Patterns
$patternsStr

## Your Decision
Based on the context + patterns, decide:
- SUGGEST: propose ONE useful action (e.g. "Play your evening playlist?", "Text Mom good morning?")
- EXECUTE: auto-execute an action the user always does at this time (only if very confident)
- SILENT: do nothing (no relevant suggestion right now)

Reply in JSON only:
{"decision": "SUGGEST" | "EXECUTE" | "SILENT", "action": "what to do", "reason": "why"}"""

        // Make LLM call (Level 1 — cheap, ~800 tokens)
        val llm = LLMClient(context)
        val response = llm.chat(prompt, "You are an intent classifier. Reply in JSON only.", emptyList())

        // Parse response
        val json = parseJson(response.reply)
        val decision = json?.optString("decision", "SILENT")?.uppercase() ?: "SILENT"
        val action = json?.optString("action", "") ?: ""
        val reason = json?.optString("reason", "") ?: ""

        Log.i(TAG, "Decision: $decision, Action: $action, Reason: $reason")

        return when (decision) {
            "SUGGEST" -> ProactiveResult.Suggest(action, reason)
            "EXECUTE" -> {
                if (ProactiveSettings.isAutoExecuteEnabled(context)) {
                    ProactiveResult.Execute(action, reason)
                } else {
                    // Auto-execute disabled — downgrade to suggestion
                    ProactiveResult.Suggest(action, "$reason (auto-execute disabled — suggesting instead)")
                }
            }
            else -> ProactiveResult.Silent(reason.ifEmpty { "No suggestion" })
        }
    }

    private fun buildContext(context: Context): String {
        val sb = StringBuilder()
        // Time
        val time = java.text.SimpleDateFormat("HH:mm EEE", java.util.Locale.US)
            .format(java.util.Date())
        sb.append("Time: $time\n")
        // Battery
        try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
            val level = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
            sb.append("Battery: $level%\n")
        } catch (_: Exception) {}
        // Recent patterns (last 5)
        val recent = ProactiveObserver.getEstablishedPatterns(context).take(5)
        if (recent.isNotEmpty()) {
            sb.append("Recent patterns:\n")
            recent.forEach { sb.append("- ${it.trigger} → ${it.suggestedAction}\n") }
        }
        return sb.toString()
    }

    private fun parseJson(content: String): org.json.JSONObject? {
        val s = content.trim()
            .replace(Regex("^```(?:json)?\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*```\\s*$", RegexOption.IGNORE_CASE), "")
            .trim()
        return try {
            org.json.JSONObject(s)
        } catch (_: Exception) {
            val f = s.indexOf('{')
            val l = s.lastIndexOf('}')
            if (f != -1 && l > f) try { org.json.JSONObject(s.substring(f, l + 1)) } catch (_: Exception) { null }
            else null
        }
    }
}

sealed class ProactiveResult {
    data class Suggest(val action: String, val reason: String) : ProactiveResult()
    data class Execute(val action: String, val reason: String) : ProactiveResult()
    data class Silent(val reason: String) : ProactiveResult()
}
