package com.ai.agent.rules

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.ai.agent.llm.LLMClient
import com.ai.agent.tools.ToolExecutor
import com.ai.agent.storage.AgentDatabase

/**
 * v2.5.4: RuleWorker — executes rules DIRECTLY in the worker thread.
 *
 * Why not start AgentService from the worker?
 * - Android 14 blocks startForegroundService() from background for dataSync type
 * - Even WorkManager.setForegroundAsync() can't bypass the dataSync restriction
 * - The ONLY reliable approach: execute the rule HERE in the worker
 *
 * The worker runs on a background thread → safe for network calls + tool execution.
 * No foreground service needed for direct rules (setBrightness, sendSMS, etc.).
 * For smart rules (LLM needed), we call the LLM directly here.
 */
class RuleWorker(
    context: Context,
    workerParams: WorkerParameters
) : Worker(context, workerParams) {

    companion object {
        private const val TAG = "RuleWorker"
        const val KEY_RULE_ID = "rule_id"
        const val KEY_RULE_NAME = "rule_name"
        const val KEY_RULE_ACTION = "rule_action"
        const val KEY_RULE_TRIGGER = "rule_trigger"
        const val KEY_RULE_CONTEXT = "rule_context"
    }

    override fun doWork(): Result {
        val ruleName = inputData.getString(KEY_RULE_NAME) ?: "Unknown rule"
        val ruleAction = inputData.getString(KEY_RULE_ACTION) ?: ""
        val ruleTrigger = inputData.getString(KEY_RULE_TRIGGER) ?: "time"
        val ruleContext = inputData.getString(KEY_RULE_CONTEXT) ?: ""

        Log.i(TAG, "=== RULE WORKER START ===")
        Log.i(TAG, "Rule: $ruleName")
        Log.i(TAG, "Action: $ruleAction")
        Log.i(TAG, "Trigger: $ruleTrigger")
        Log.i(TAG, "Context: $ruleContext")

        if (ruleAction.isEmpty()) {
            Log.w(TAG, "Empty rule action — skipping")
            return Result.success()
        }

        return try {
            // Log to database
            val db = AgentDatabase(applicationContext)
            db.logAction("RuleWorker executing: $ruleName — action: $ruleAction")

            // Check if this is a DIRECT rule (tool call syntax) or SMART rule (natural language)
            val isDirectRule = ruleAction.matches(Regex("^[a-zA-Z_][a-zA-Z0-9_]*\\(.*\\)$")) ||
                               ruleAction.startsWith("direct:") ||
                               ruleAction.startsWith("tool:")

            val cleanAction = when {
                ruleAction.startsWith("direct:") -> ruleAction.removePrefix("direct:").trim()
                ruleAction.startsWith("tool:") -> ruleAction.removePrefix("tool:").trim()
                else -> ruleAction
            }

            if (isDirectRule) {
                // DIRECT RULE: execute tools directly (no AI needed, works offline)
                Log.i(TAG, "Direct rule — executing tools: $cleanAction")
                kotlinx.coroutines.runBlocking { executeDirectRule(cleanAction) }
            } else {
                // SMART RULE: needs AI (LLM call)
                Log.i(TAG, "Smart rule — calling LLM")

                // Check if online
                if (!NetworkHelper.isOnline(applicationContext)) {
                    Log.w(TAG, "Phone is offline — cannot call LLM for smart rule")
                    db.logAction("Smart rule '$ruleName' skipped — phone offline")
                    return Result.success()
                }

                // Execute smart rule with LLM
                kotlinx.coroutines.runBlocking { executeSmartRule(ruleName, cleanAction, ruleTrigger, ruleContext) }
            }

            Log.i(TAG, "=== RULE WORKER DONE ===")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Rule execution failed: $ruleName", e)
            if (runAttemptCount < 2) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }

    /**
     * Execute a DIRECT rule — parse tool calls + execute them via ToolExecutor.
     * No AI call needed. Works offline.
     */
    private suspend fun executeDirectRule(actionStr: String) {
        val tools = actionStr.split(";").map { it.trim() }.filter { it.isNotEmpty() }
        val toolExecutor = ToolExecutor(applicationContext)

        for (toolCall in tools) {
            try {
                val match = Regex("^(\\w+)\\((.*)\\)$").find(toolCall)
                if (match == null) {
                    Log.w(TAG, "Couldn't parse: $toolCall")
                    continue
                }
                val toolName = match.groupValues[1]
                val argsStr = match.groupValues[2]
                val args = mutableMapOf<String, Any>()

                if (argsStr.isNotEmpty()) {
                    val parts = argsStr.split(",").map { it.trim() }
                    parts.forEachIndexed { i, part ->
                        if (part.contains("=")) {
                            val (k, v) = part.split("=", limit = 2)
                            val cleanV = v.trim().trim('"').trim('\'')
                            args[k.trim()] = cleanV.toIntOrNull() ?: cleanV
                        } else {
                            val cleanV = part.trim().trim('"').trim('\'')
                            args[i.toString()] = cleanV.toIntOrNull() ?: cleanV
                        }
                    }
                }

                Log.i(TAG, "Executing: $toolName($args)")
                val results = toolExecutor.executeTools(
                    listOf(com.ai.agent.llm.LLMClient.ToolCall(toolName, args))
                )
                val result = results.firstOrNull()
                Log.i(TAG, "Result: ${if (result?.success == true) "✓" else "✗"} ${result?.output?.take(200) ?: "no result"}")
            } catch (e: Exception) {
                Log.e(TAG, "Tool call failed: $toolCall", e)
            }
        }
    }

    /**
     * Execute a SMART rule — call LLM with the rule action + context.
     */
    private suspend fun executeSmartRule(ruleName: String, ruleAction: String, triggerType: String, triggerContext: String) {
        try {
            val context = applicationContext
            val llmClient = LLMClient(context)

            // Build system prompt for rule execution
            // v3.9.12: Switched from system_prompt.txt (old 623-line) to system_prompt_smart.txt
            // Unifies rule brain + chat brain on the same clean prompt.
            val basePrompt = try {
                context.assets.open("system_prompt_smart.txt").bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                "You are an AI agent controlling an Android phone."
            }

            val contextBlock = buildString {
                append("\n\n## Rule Execution\n")
                append("- Rule name: $ruleName\n")
                append("- Trigger: $triggerType\n")
                if (triggerContext.isNotEmpty()) {
                    append("- Context: $triggerContext\n")
                }
                append("\nThe user is not actively chatting — this rule fired automatically. ")
                append("Execute the action efficiently. Be brief.\n\n")
                append("Rule action: $ruleAction\n")
            }

            val systemPrompt = basePrompt + contextBlock

            Log.i(TAG, "Calling LLM for smart rule: $ruleName")
            val response = llmClient.chat(ruleAction, systemPrompt)

            Log.i(TAG, "LLM response: reply=${response.reply.take(100)}, tools=${response.toolCalls.size}")

            // Execute any tool calls the LLM made
            if (response.toolCalls.isNotEmpty()) {
                val toolExecutor = ToolExecutor(context)
                val results = toolExecutor.executeTools(response.toolCalls)
                results.forEachIndexed { i, result ->
                    Log.i(TAG, "Tool ${response.toolCalls[i].name}: ${if (result.success) "✓" else "✗"} ${result.output.take(100)}")
                }
            }

            // Log completion
            val db = AgentDatabase(context)
            db.logAction("Smart rule '$ruleName' completed: ${response.reply.take(100)}")
        } catch (e: Exception) {
            Log.e(TAG, "Smart rule execution failed", e)
        }
    }
}
