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

            // v4.0.20: Use the REAL system prompt (ContextualPromptBuilder) instead
            // of a 200-line inline mini-prompt with only 25 hardcoded tools.
            // The real prompt has all 137 tools + rules/skills sections + the
            // proper Response Format section. This gives smart rules the same
            // brain power as interactive chat.
            val basePrompt = com.ai.agent.llm.ContextualPromptBuilder.buildPrompt(
                context, rule.action  // use the rule action as the "user message" for tier selection
            )
            val systemPrompt = """$basePrompt

## Current Task: Rule Execution
You are executing a RULE that fired automatically on the user's phone.
The rule was created earlier and its trigger condition just fired.

Execute the rule action by calling the appropriate tool(s). Work in a LOOP:
call tools → see results → decide next step → repeat until done.

When the task is complete, call complete(success=true, reason="...").
If the task is impossible, call complete(success=false, reason="...")."""

            // Build conversation for agent loop
            val conversation = StringBuilder()
            conversation.append("Rule name: ${rule.name}\n")
            conversation.append("Trigger: ${rule.triggerType}\n")
            if (triggerContext.isNotEmpty()) {
                conversation.append("Context: $triggerContext\n")
            }
            conversation.append("\nRule action: ${rule.action}")

            Log.i(TAG, "Calling LLM for smart rule: ${rule.name}")

            // v4.0.20: Compute contextual tool filter so we don't send 137 tool
            // definitions when the rule only needs 5 tools.
            val contextualEnabled = com.ai.agent.llm.AIProvider.isContextualPromptsEnabled(context)
            val toolFilter = if (contextualEnabled) {
                com.ai.agent.llm.ContextualPromptBuilder.getRelevantToolNames(rule.action)
            } else null

            // AGENT LOOP — max 10 rounds
            val maxIterations = 10
            for (iteration in 1..maxIterations) {
                Log.i(TAG, "--- Smart rule iteration $iteration/${maxIterations} ---")

                // v4.0.20: Use chatWithFailover (was chat()) for multi-binding
                // failover. If the active binding fails (quota/auth), this
                // automatically tries the next healthy binding.
                val response = llmClient.chatWithFailover(conversation.toString(), systemPrompt, toolFilter)

                Log.i(TAG, "LLM reply: ${response.reply.take(100)}")
                Log.i(TAG, "LLM tool calls: ${response.toolCalls.size}")

                // If no tool calls → task is done
                if (response.toolCalls.isEmpty()) {
                    Log.i(TAG, "✅ Smart rule complete: ${rule.name}")
                    db.logAction("Smart rule '${rule.name}' completed: ${response.reply.take(200)}")
                    break
                }

                // v4.0.20: If the model called "complete", stop the loop.
                if (response.toolCalls.any { it.name == "complete" }) {
                    val completeCall = response.toolCalls.find { it.name == "complete" }
                    val success = completeCall?.args?.get("success") as? Boolean ?: true
                    val reason = completeCall?.args?.get("reason") as? String ?: ""
                    Log.i(TAG, "✅ Smart rule ${if (success) "completed" else "failed"}: ${rule.name} — $reason")
                    db.logAction("Smart rule '${rule.name}' ${if (success) "completed" else "failed"}: $reason")
                    // Execute the complete tool (sets the flag) + any sibling calls
                    val results = toolExecutor.executeTools(response.toolCalls)
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
                conversation.append("\n\nBased on these results, decide the next step. If done, call complete(success=true).")

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
