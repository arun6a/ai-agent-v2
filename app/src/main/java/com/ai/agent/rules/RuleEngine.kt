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
     * Execute a SMART rule — call LLM in a LOOP until task is done.
     * 
     * v3.0.4: AGENT LOOP — LLM calls tools → sees results → decides next step → repeat.
     * This enables complex multi-step rules like:
     *   - "Research AI news, create document, email it"
     *   - "Check weather, check calendar, give morning briefing"
     *   - "Read WhatsApp message, understand context, reply intelligently"
     *
     * Flow:
     *   Round 1: LLM → tool_calls → execute → results
     *   Round 2: LLM + results → tool_calls → execute → results
     *   Round 3: LLM + results → empty tool_calls → DONE
     */
    private suspend fun executeSmart(context: Context, rule: AgentDatabase.Rule, triggerContext: String) {
        try {
            // Check if online
            if (!NetworkHelper.isOnline(context)) {
                Log.w(TAG, "Phone offline — cannot call LLM for smart rule")
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
            val toolExecutor = com.ai.agent.tools.ToolExecutor(context)
            val db = AgentDatabase(context)

            // v3.0.4: FULL system prompt with tools + rules for autonomous execution
            val systemPrompt = """You are an AI agent executing a RULE that fired automatically on the user's phone.
Execute the rule action by calling the appropriate tool(s). You can call multiple tools in sequence.

Work in a LOOP: call tools → see results → decide next step → repeat until done.
When the task is complete, reply with a summary and NO tool_calls.

Available tools:
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
- recallSimilar(query) — semantic search memory
- webSearch(query, fetchTop=0) — search the web. fetchTop=3 gets top 3 pages' full text.
- fetchPageText(url) — fetch a URL and return clean text
- writeFile(path, content) — write text to a file on the phone
- readFile(path) — read a text file
- sendEmail(to, subject, body) — send an email
- composeEmail(to, subject, body) — open email composer
- getCalendarEvents() — get today's calendar events
- getBatteryLevel() — get battery percentage
- getCurrentLocation() — get GPS location

Response format: {"reply":"short msg","tool_calls":[{"name":"...","args":{...}}]}
Complete (no more tools): {"reply":"Done!","tool_calls":[]}

IMPORTANT: After each tool call, you'll see the results. Use them to decide the next step.
If a tool fails, try a different approach. Maximum 10 rounds."""

            // Build conversation for agent loop
            val conversation = StringBuilder()
            conversation.append("Rule name: ${rule.name}\n")
            conversation.append("Trigger: ${rule.triggerType}\n")
            if (triggerContext.isNotEmpty()) {
                conversation.append("Context: $triggerContext\n")
            }
            conversation.append("\nRule action: ${rule.action}")

            Log.i(TAG, "Calling LLM for smart rule: ${rule.name}")

            // AGENT LOOP — max 10 rounds
            val maxIterations = 10
            // v3.0.7: Prompt Caching — iteration 1 sends full prompt, iterations 2+ use tiny prompt
            val cachingEnabled = com.ai.agent.llm.AIProvider.isPromptCachingEnabled(context)
            for (iteration in 1..maxIterations) {
                Log.i(TAG, "--- Smart rule iteration $iteration/${maxIterations} ---")

                // v3.0.7: Use continuation prompt for iterations 2+ if caching is enabled
                val activePrompt = if (cachingEnabled && iteration > 1) {
                    Log.d(TAG, "Prompt caching: using continuation prompt (iteration $iteration)")
                    com.ai.agent.llm.AIProvider.getContinuationPrompt()
                } else {
                    systemPrompt
                }

                val response = llmClient.chat(conversation.toString(), activePrompt)

                Log.i(TAG, "LLM reply: ${response.reply.take(100)}")
                Log.i(TAG, "LLM tool calls: ${response.toolCalls.size}")

                // If no tool calls → task is done
                if (response.toolCalls.isEmpty()) {
                    Log.i(TAG, "✅ Smart rule complete: ${rule.name}")
                    db.logAction("Smart rule '${rule.name}' completed: ${response.reply.take(200)}")
                    break
                }

                // Execute tool calls
                val results = toolExecutor.executeTools(response.toolCalls)

                // Log results
                results.forEachIndexed { i, result ->
                    Log.i(TAG, "Tool ${response.toolCalls[i].name}: ${if (result.success) "✓" else "✗"} ${result.output.take(200)}")

                    // v3.0.5: If replyToNotification succeeded, save reply to conversation memory
                    if (response.toolCalls[i].name == "replyToNotification" && result.success) {
                        try {
                            val pkg = response.toolCalls[i].args["package"] as? String
                                ?: response.toolCalls[i].args["arg0"] as? String ?: ""
                            val msg = response.toolCalls[i].args["message"] as? String
                                ?: response.toolCalls[i].args["arg1"] as? String ?: ""
                            if (pkg.isNotEmpty() && msg.isNotEmpty()) {
                                val convKey = if (triggerContext.contains("notification from")) {
                                    // Extract package from trigger context
                                    val match = Regex("notification from ([^:]+)").find(triggerContext)
                                    match?.groupValues?.get(1) ?: pkg
                                } else pkg
                                ConversationMemory.addOutgoing(context, convKey, msg)
                                Log.i(TAG, "✓ Saved AI reply to conversation memory: $convKey → $msg")
                            }
                        } catch (_: Exception) {}
                    }
                }

                // Add results to conversation for next iteration
                conversation.append("\n\n[Round $iteration results]:")
                results.forEachIndexed { i, result ->
                    val call = response.toolCalls[i]
                    conversation.append("\nTool: ${call.name}(${call.args})")
                    conversation.append("\nResult: ${if (result.success) "✓" else "✗"} ${result.output.take(500)}")
                }
                conversation.append("\n\nBased on these results, decide the next step. If done, reply with no tool_calls.")

                // If last iteration, force stop
                if (iteration == maxIterations) {
                    Log.w(TAG, "⚠️ Max iterations reached for ${rule.name}")
                    db.logAction("Smart rule '${rule.name}' reached max iterations ($maxIterations)")
                }
            }

            // Save to memory that this rule ran
            try {
                db.remember("rule_last_run_${rule.name}", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date()))
            } catch (_: Exception) {}
        } catch (e: Exception) {
            Log.e(TAG, "Smart rule execution failed", e)
        }
    }
}
