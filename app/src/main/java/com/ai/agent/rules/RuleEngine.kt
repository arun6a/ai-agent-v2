package com.ai.agent.rules

import android.content.Context
import android.util.Log
import com.ai.agent.llm.LLMClient
import com.ai.agent.storage.AgentDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * v3.0.0: RuleEngine — the CLEAN rule execution system.
 *
 * ONE entry point for ALL rule execution. No WorkManager, no AgentService, no wrapping.
 *
 * Flow:
 *   Trigger fires → RuleEngine.execute(context, rule) → Done
 *
 * Direct rules (setBrightness(50), speak("hello"), replyToNotification(...)):
 *   → Parse tool call → Execute via ToolExecutor → Done (0 API calls, instant, offline)
 *
 * Smart rules (natural language like "Reply politely"):
 *   → Call LLM → Execute returned tool calls → Done (1 API call, needs internet)
 *
 * This replaces: RuleWorker, RuleTriggerReceiver.triggerRuleAction, AgentService.processRuleAction
 */
object RuleEngine {
    private const val TAG = "RuleEngine"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Execute a rule. Called from ANY trigger (battery, notification, time, etc.)
     * Runs on background thread — safe for network + tool execution.
     */
    fun execute(context: Context, rule: AgentDatabase.Rule, triggerContext: String = "") {
        Log.i(TAG, "=== RULE EXECUTE ===")
        Log.i(TAG, "Rule: ${rule.name}")
        Log.i(TAG, "Action: ${rule.action}")
        Log.i(TAG, "Trigger: ${rule.triggerType}")
        if (triggerContext.isNotEmpty()) Log.i(TAG, "Context: $triggerContext")

        // Log to database
        try {
            val db = AgentDatabase(context)
            db.logAction("Rule fired: ${rule.name} — ${rule.action}" +
                if (triggerContext.isNotEmpty()) " | $triggerContext" else "")
        } catch (_: Exception) {}

        scope.launch {
            try {
                val action = rule.action
                val isDirect = isDirectRule(action)

                if (isDirect) {
                    executeDirect(context, cleanAction(action))
                } else {
                    executeSmart(context, rule, triggerContext)
                }
                Log.i(TAG, "=== RULE DONE: ${rule.name} ===")
            } catch (e: Exception) {
                Log.e(TAG, "Rule execution failed: ${rule.name}", e)
            }
        }
    }

    /**
     * Check if action is a DIRECT rule (tool call syntax, no AI needed).
     */
    fun isDirectRule(action: String): Boolean {
        val cleaned = action.removePrefix("direct:").removePrefix("tool:").trim()
        // Matches: toolName(args) — e.g., setBrightness(50), speak("hello"), sendSMS(+91..., msg)
        return cleaned.matches(Regex("^[a-zA-Z_][a-zA-Z0-9_]*\\(.*\\)$"))
    }

    /**
     * Strip the prefix from a direct rule action.
     */
    private fun cleanAction(action: String): String {
        return action.removePrefix("direct:").removePrefix("tool:").trim()
    }

    /**
     * Execute a DIRECT rule — parse tool calls + execute via ToolExecutor.
     * No AI needed. Works offline. Instant.
     */
    private suspend fun executeDirect(context: Context, actionStr: String) {
        val tools = actionStr.split(";").map { it.trim() }.filter { it.isNotEmpty() }
        val toolExecutor = com.ai.agent.tools.ToolExecutor(context)

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
     * LLM decides which tools to call.
     */
    private suspend fun executeSmart(context: Context, rule: AgentDatabase.Rule, triggerContext: String) {
        try {
            // Check if online
            if (!NetworkHelper.isOnline(context)) {
                Log.w(TAG, "Phone offline — cannot call LLM for smart rule")
                // Queue for later
                OfflineRuleQueue.enqueue(
                    context,
                    OfflineRuleQueue.PendingRule(
                        ruleName = rule.name,
                        ruleAction = rule.action,
                        triggerType = rule.triggerType,
                        notifTitle = "",
                        notifText = triggerContext,
                    )
                )
                return
            }

            val llmClient = LLMClient(context)

            // Build a MINIMAL prompt — not the full 47KB system prompt
            // This is faster, cheaper, and the LLM knows it's executing a rule
            val systemPrompt = """You are an AI agent executing a RULE that fired automatically.
Execute the rule action by calling the appropriate tool(s). Respond in JSON only.

Available tools (most relevant for rules):
- speak(text) — speak text aloud via TTS
- sendSMS(num, msg) — send SMS
- replyToNotification(package, message) — reply to WhatsApp/Telegram notification
- setBrightness(level) — set screen brightness (0-100 percent)
- setVolume(level) — set media volume (0-15)
- toggleFlashlight() — toggle flashlight
- openWhatsAppChat(phone, msg?) — open WhatsApp chat
- makePhoneCall(num) — make a phone call
- setAlarm(hour, minute) — set alarm
- setTimer(seconds, message) — set timer
- getCurrentTime() — get current time
- getWeather() — get weather
- remember(key, value) — save to memory
- recall(key) — recall from memory

Response format: {"reply":"short msg","tool_calls":[{"name":"...","args":{...}}]}
Complete: {"reply":"Done!","tool_calls":[]}"""

            val userMessage = buildString {
                append("Rule name: ${rule.name}\n")
                append("Trigger: ${rule.triggerType}\n")
                if (triggerContext.isNotEmpty()) {
                    append("Context: $triggerContext\n")
                }
                append("\nRule action: ${rule.action}")
            }

            Log.i(TAG, "Calling LLM for smart rule: ${rule.name}")
            val response = llmClient.chat(userMessage, systemPrompt)

            Log.i(TAG, "LLM reply: ${response.reply.take(100)}")
            Log.i(TAG, "LLM tool calls: ${response.toolCalls.size}")

            // Execute tool calls
            if (response.toolCalls.isNotEmpty()) {
                val toolExecutor = com.ai.agent.tools.ToolExecutor(context)
                val results = toolExecutor.executeTools(response.toolCalls)
                results.forEachIndexed { i, result ->
                    Log.i(TAG, "Tool ${response.toolCalls[i].name}: ${if (result.success) "✓" else "✗"} ${result.output.take(100)}")
                }
            }

            // Log completion
            try {
                val db = AgentDatabase(context)
                db.logAction("Smart rule '${rule.name}' completed: ${response.reply.take(100)}")
            } catch (_: Exception) {}
        } catch (e: Exception) {
            Log.e(TAG, "Smart rule execution failed", e)
        }
    }
}
