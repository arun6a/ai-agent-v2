package com.ai.agent.agent

import android.content.Context
import android.util.Log
import com.ai.agent.llm.LLMClient
import com.ai.agent.tools.ToolExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * v4.0.69: SubAgentRunner — runs a mini agent loop for delegated tasks.
 *
 * Like Z.ai Code's Task tool — spawns a focused sub-agent with:
 * - Limited tools (only what the task needs)
 * - Focused system prompt (short, not the full 3500-token prompt)
 * - Max 5 iterations (not 80)
 * - Returns ONE result to the parent agent
 *
 * This saves tokens: instead of the main agent doing 10 calls at 10k each,
 * a sub-agent does 5 calls at 2k each = 10k vs 100k.
 */
object SubAgentRunner {
    private const val TAG = "SubAgentRunner"
    // v4.0.99: Increased from 5 to 10. Screen automation tasks need more steps:
    // launchApp → readScreen → clickByText → type → submitInput → readScreen → clickElement → complete
    // That's 8 tool calls, which exceeds the old 5-iteration limit.
    // Screen-type agents get 15 (complex flows like YouTube search → play).
    private const val MAX_ITERATIONS_DEFAULT = 10
    private const val MAX_ITERATIONS_SCREEN = 15
    private const val MAX_ITERATIONS_RESEARCH = 8

    data class SubAgentResult(
        val success: Boolean,
        val reply: String,
        val iterations: Int,
        val toolCalls: Int,
    )

    /**
     * Run a sub-agent task.
     *
     * @param context Android context
     * @param task The task description (what the sub-agent should do)
     * @param agentType Type of agent: "research", "terminal", "screen", "general"
     * @return SubAgentResult with the final reply + metadata
     */
    suspend fun run(
        context: Context,
        task: String,
        agentType: String = "general",
    ): SubAgentResult = withContext(Dispatchers.IO) {
        Log.i(TAG, "Sub-agent started: type=$agentType, task=${task.take(80)}")

        val systemPrompt = getPromptForType(agentType)
        val toolFilter = getToolsForType(agentType)

        val llmClient = LLMClient(context)
        val toolExecutor = ToolExecutor(context)
        val conversation = StringBuilder()

        conversation.append("Task: $task\n\n")
        conversation.append("Complete this task efficiently. Call complete(success=true, reason=\"...\") when done.\n")

        var iteration = 0
        var totalToolCalls = 0

        // v4.0.99: Per-agentType iteration limit.
        val maxIterations = when (agentType) {
            "screen" -> MAX_ITERATIONS_SCREEN
            "research" -> MAX_ITERATIONS_RESEARCH
            else -> MAX_ITERATIONS_DEFAULT
        }

        while (iteration < maxIterations) {
            iteration++
            Log.i(TAG, "Sub-agent iteration $iteration/$maxIterations")

            val response = try {
                llmClient.chatWithFailover(conversation.toString(), systemPrompt, toolFilter)
            } catch (e: Exception) {
                Log.e(TAG, "Sub-agent LLM call failed", e)
                return@withContext SubAgentResult(false, "LLM error: ${e.message}", iteration, totalToolCalls)
            }

            // Check if sub-agent called complete
            val completeCall = response.toolCalls.find { it.name == "complete" }
            if (completeCall != null) {
                val success = completeCall.args["success"] as? Boolean ?: false
                val reason = completeCall.args["reason"] as? String ?: "No reason"
                Log.i(TAG, "Sub-agent completed: success=$success, iterations=$iteration, tools=$totalToolCalls")
                return@withContext SubAgentResult(success, reason, iteration, totalToolCalls)
            }

            // No complete call — execute tool calls
            if (response.toolCalls.isEmpty()) {
                // No tools + no complete -> sub-agent is done (answered in text)
                val reply = response.reply.ifBlank { "Sub-agent gave no response" }
                Log.i(TAG, "Sub-agent finished (no tools): $reply")
                return@withContext SubAgentResult(true, reply, iteration, totalToolCalls)
            }

            // Add AI reply to conversation
            if (response.reply.isNotBlank()) {
                conversation.append("\nAI: ${response.reply}\n")
            }

            // Execute tools
            val results = toolExecutor.executeTools(response.toolCalls)
            totalToolCalls += response.toolCalls.size

            // Add results to conversation
            conversation.append("\nTool results:\n")
            results.forEachIndexed { i, result ->
                val call = response.toolCalls[i]
                conversation.append("${call.name}(${call.args}) -> ${if (result.success) "OK" else "FAIL"} ${result.output.take(500)}\n")
            }
        }

        // Max iterations reached
        Log.w(TAG, "Sub-agent reached max iterations ($maxIterations)")
        SubAgentResult(false, "Sub-agent reached max iterations ($maxIterations) without completing", maxIterations, totalToolCalls)
    }

    /**
     * Get the focused system prompt for an agent type.
     * These are SHORT (~500 tokens) — not the full 3500-token prompt.
     */
    private fun getPromptForType(type: String): String {
        return when (type) {
            "research" -> """
You are a research assistant. Your job: search the web, find information, download files.

Available tools: webSearch, fetchPageText, browserSearch, downloadFile, listFiles, complete.

Rules:
1. Search first, then download/extract content
2. Be efficient — use the fewest calls possible
3. Call complete(success=true, reason="...") when the task is done
4. Call complete(success=false, reason="...") if the task is impossible

Respond in JSON: {"reply":"...","tool_calls":[{"name":"...","args":{...}}]}
""".trimIndent()

            "terminal" -> """
You are a terminal assistant. Your job: run shell commands via the terminal server.

Available tools: connectTerminal, runOnTerminal, listTerminals, runShellCommand, writeFile, readFile, complete.

Rules:
1. Check listTerminals first — if no terminal connected, tell the user
2. Use runOnTerminal for commands (has pkg, python3, node, git)
3. Use runShellCommand only for simple system commands (am, pm, settings)
4. Call complete(success=true, reason="...") when done
5. Call complete(success=false, reason="...") if the task is impossible

Respond in JSON: {"reply":"...","tool_calls":[{"name":"...","args":{...}}]}
""".trimIndent()

            "screen" -> """
You are a phone UI assistant. Your job: control the phone screen via accessibility.

Available tools: readScreen, readScreenStructured, clickElement, clickByText, tap, type,
swipe, scrollDown, scrollUp, pressBack, pressHome, pressEnter, submitInput,
launchApp, searchInApp, openUrl, complete.

Rules:
1. ALWAYS use readScreenStructured before clicking — never guess coordinates
2. Use searchInApp for app searches (1 call, not 5)
3. After actions, call readScreen to verify the result
4. Call complete(success=true, reason="...") when done
5. Call complete(success=false, reason="...") if the task is impossible

Respond in JSON: {"reply":"...","tool_calls":[{"name":"...","args":{...}}]}
""".trimIndent()

            else -> """
You are a focused assistant. Complete the task efficiently using available tools.

Rules:
1. Think before acting — plan the steps
2. Use the fewest tool calls possible
3. Verify results before claiming success
4. Call complete(success=true, reason="...") when done
5. Call complete(success=false, reason="...") if the task is impossible

Respond in JSON: {"reply":"...","tool_calls":[{"name":"...","args":{...}}]}
""".trimIndent()
        }
    }

    /**
     * Get the tool filter for an agent type.
     * Only includes tools relevant to the agent's task.
     */
    private fun getToolsForType(type: String): List<String>? {
        return when (type) {
            "research" -> listOf(
                "webSearch", "fetchPageText", "browserSearch", "browserOpen", "browserReadStructured",
                "downloadFile", "listFiles", "readFile", "complete", "searchTools"
            )
            "terminal" -> listOf(
                "connectTerminal", "runOnTerminal", "listTerminals", "disconnectTerminal",
                "runShellCommand", "writeFile", "readFile", "complete", "searchTools"
            )
            "screen" -> listOf(
                "readScreen", "readScreenStructured", "clickElement", "clickByText", "tap",
                "type", "swipe", "scrollDown", "scrollUp", "pressBack", "pressHome",
                "pressEnter", "submitInput", "launchApp", "searchInApp", "openUrl",
                "takeScreenshotToGallery", "complete", "searchTools"
            )
            else -> null  // all tools
        }
    }
}
