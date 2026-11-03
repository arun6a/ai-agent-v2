package com.ai.agent.tools.categories

import android.content.Context
import com.ai.agent.accessibility.AgentAccessibilityService
import com.ai.agent.llm.LLMClient
import com.ai.agent.storage.AgentDatabase
import com.ai.agent.tools.ToolExecutor

/**
 * Memory tools category — extracted from ToolExecutor in v3.3.12.
 *
 * Tools: remember, recall, recallAll, recallSimilar, searchMemory,
 * saveToKnowledge, recallKnowledge, listKnowledge,
 * getClipboard, setClipboard.
 *
 * No behavior changes — code moved verbatim from ToolExecutor.kt.
 */
object MemoryTools {

    suspend fun execute(
        call: LLMClient.ToolCall,
        context: Context,
        @Suppress("UNUSED_PARAMETER") service: AgentAccessibilityService?,
        database: AgentDatabase
    ): ToolExecutor.ToolResult {
        return when (call.name) {
            "remember" -> {
                val key = call.args["key"] as? String ?: return ToolExecutor.ToolResult(false, "missing key")
                val value = call.args["value"] as? String ?: return ToolExecutor.ToolResult(false, "missing value")
                // v4.0.55: Reserve system-managed prefixes. The AI must not write directly
                // to pattern_*, avoid_*, or calib_* — these are managed by PatternLearner
                // which only saves after verifying success/failure. The AI was saving false
                // memories like pattern_idesetup_c="Successfully ran..." even when the
                // command timed out. Reject these + tell AI to use a different key.
                val reservedPrefixes = listOf("pattern_", "avoid_", "calib_", "workflow_")
                val reservedMatch = reservedPrefixes.find { key.startsWith(it) }
                if (reservedMatch != null) {
                    return ToolExecutor.ToolResult(false,
                        "Key prefix '$reservedMatch' is reserved for system-managed memories. " +
                        "Pattern memories (pattern_*) are saved automatically when complete(success=true) is called. " +
                        "Failure memories (avoid_*) are saved automatically when a tool gets stuck. " +
                        "Use a different key prefix, e.g., 'fact_*' or 'note_*' or 'user_*'.")
                }
                database.remember(key, value)
                ToolExecutor.ToolResult(true, "Remembered: $key = $value")
            }
            "recall" -> {
                val key = call.args["key"] as? String ?: return ToolExecutor.ToolResult(false, "missing key")
                val value = database.recall(key)
                ToolExecutor.ToolResult(true, if (value != null) "$key = $value" else "I don't remember $key")
            }
            "recallAll" -> {
                val memory = database.getAllMemory()
                val memoryStr = if (memory.isEmpty()) {
                    "I don't have any stored memories yet."
                } else {
                    memory.entries.joinToString("\n") { "${it.key}: ${it.value}" }
                }
                ToolExecutor.ToolResult(true, memoryStr)
            }
            // v4.0.29: getConversationHistory — AI calls this when it needs MORE context.
            // Default loads 10 messages; pass limit=N for more. Use when "try again" or
            // complex multi-step tasks need full history.
            "getConversationHistory" -> {
                val limit = (call.args["limit"] as? Number)?.toInt() ?: 20
                val sessionId = database.getCurrentSessionId()
                if (sessionId == 0L) {
                    ToolExecutor.ToolResult(true, "No active chat session.")
                } else {
                    val history = database.getConversationsForSession(sessionId).takeLast(limit)
                    if (history.isEmpty()) {
                        ToolExecutor.ToolResult(true, "No conversation history yet.")
                    } else {
                        val sb = StringBuilder("Conversation history (last ${history.size} messages):\n\n")
                        for ((role, text, timestamp) in history) {
                            val speaker = if (role == "user") "User" else "AI"
                            val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                                .format(java.util.Date(timestamp))
                            sb.append("[$time] $speaker: $text\n")
                        }
                        ToolExecutor.ToolResult(true, sb.toString())
                    }
                }
            }
            // v4.0.29: getFullMemory — AI calls this to read ALL memories (not just top 10).
            // recallSimilar only returns top N; this returns EVERYTHING. Use when the AI
            // needs to search for something recallSimilar might have missed.
            "getFullMemory" -> {
                val memory = database.getAllMemory()
                if (memory.isEmpty()) {
                    ToolExecutor.ToolResult(true, "No memories stored yet.")
                } else {
                    val sb = StringBuilder("All memories (${memory.size}):\n\n")
                    for ((key, value) in memory) {
                        sb.append("- $key: $value\n")
                    }
                    ToolExecutor.ToolResult(true, sb.toString())
                }
            }
            // v4.0.41: getConversationSummary — AI reads WhatsApp/messaging conversation history.
            // Used when user asks: "what messages did I get?" / "who texted me?" / "summarize replies"
            "getConversationSummary" -> {
                val contact = call.args["contact"] as? String ?: "all"
                val summary = com.ai.agent.rules.ConversationMemory.getConversationForAI(context, contact)
                ToolExecutor.ToolResult(true, summary)
            }
            "recallSimilar" -> {
                val query = call.args["query"] as? String ?: return ToolExecutor.ToolResult(false, "missing query")
                val results = database.recallSimilar(query)
                if (results.isEmpty()) {
                    ToolExecutor.ToolResult(true, "No similar memories found for: $query")
                } else {
                    val sb = StringBuilder("Similar memories (top ${results.size}):\n")
                    for ((key, value, score) in results) {
                        sb.append("- $key: $value (similarity: ${"%.0f".format(score * 100)}%)\n")
                    }
                    ToolExecutor.ToolResult(true, sb.toString())
                }
            }
            "searchMemory" -> {
                val query = call.args["query"] as? String ?: return ToolExecutor.ToolResult(false, "missing query")
                val results = database.searchMemory(query)
                if (results.isEmpty()) {
                    ToolExecutor.ToolResult(true, "No memories found containing: $query")
                } else {
                    val sb = StringBuilder("Found ${results.size} memories:\n")
                    for ((key, value) in results) {
                        sb.append("- $key: $value\n")
                    }
                    ToolExecutor.ToolResult(true, sb.toString())
                }
            }
            // v4.0.74: recallMemory — on-demand memory search for the pull architecture.
            // Wraps recallSimilar (FTS5) with a clean name + limit param.
            // Model calls this when it needs context from past experiences.
            "recallMemory" -> {
                val query = call.args["query"] as? String
                    ?: return ToolExecutor.ToolResult(false, "missing query. Example: recallMemory(query=\"twitter post\")")
                val limit = (call.args["limit"] as? Number)?.toInt() ?: 5
                val results = try {
                    database.recallSimilar(query, limit)
                } catch (_: Exception) {
                    emptyList()
                }
                if (results.isEmpty()) {
                    ToolExecutor.ToolResult(true, "No relevant memories found for '$query'.")
                } else {
                    val sb = StringBuilder("Relevant memories (top ${results.size}):\n")
                    for ((key, value, score) in results) {
                        sb.append("- $key: $value\n")
                    }
                    ToolExecutor.ToolResult(true, sb.toString())
                }
            }
            "saveToKnowledge" -> {
                val name = call.args["name"] as? String ?: return ToolExecutor.ToolResult(false, "missing name")
                val content = call.args["content"] as? String ?: return ToolExecutor.ToolResult(false, "missing content")
                val source = call.args["source"] as? String ?: "manual"
                database.saveKnowledge(name, content, source)
                ToolExecutor.ToolResult(true, "Saved knowledge: $name (${content.length} chars, source: $source)")
            }
            "recallKnowledge" -> {
                val query = call.args["query"] as? String ?: return ToolExecutor.ToolResult(false, "missing query")
                val results = database.recallKnowledge(query)
                if (results.isEmpty()) {
                    ToolExecutor.ToolResult(true, "No knowledge found for: $query")
                } else {
                    val sb = StringBuilder("Knowledge (top ${results.size}):\n")
                    for ((name, content, score) in results) {
                        sb.append("- $name (similarity: ${"%.0f".format(score * 100)}%): ${content.take(200)}...\n")
                    }
                    ToolExecutor.ToolResult(true, sb.toString())
                }
            }
            "listKnowledge" -> {
                val all = database.getAllKnowledge()
                if (all.isEmpty()) {
                    ToolExecutor.ToolResult(true, "No knowledge stored yet.")
                } else {
                    val sb = StringBuilder("Knowledge (${all.size}):\n")
                    for ((name, content, source) in all) {
                        sb.append("- $name (${content.length} chars, source: $source)\n")
                    }
                    ToolExecutor.ToolResult(true, sb.toString())
                }
            }
            "getClipboard" -> {
                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: "(clipboard empty)"
                ToolExecutor.ToolResult(true, "Clipboard: $text")
            }
            "setClipboard" -> {
                val text = call.args["text"] as? String ?: return ToolExecutor.ToolResult(false, "missing text")
                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("AI Agent", text))
                ToolExecutor.ToolResult(true, "Copied to clipboard: $text")
            }
            // v4.0.76: recallConversationStep — model requests a specific old iteration.
            // Used when conversation compression is ON and model forgot early steps.
            "recallConversationStep" -> {
                val step = (call.args["step"] as? Number)?.toInt()
                    ?: return ToolExecutor.ToolResult(false, "missing step. Example: recallConversationStep(step=3)")
                val entry = com.ai.agent.tools.planning.ConversationStore.getIteration(step)
                if (entry != null) {
                    ToolExecutor.ToolResult(true, "Iteration ${entry.step}:\n" +
                        "Brain: ${entry.brainReply}\n" +
                        "Tools: ${entry.toolCallsSummary}\n" +
                        "Results: ${entry.toolResultsSummary}")
                } else {
                    val total = com.ai.agent.tools.planning.ConversationStore.size()
                    ToolExecutor.ToolResult(false, "Iteration $step not found. Only $total iterations exist (1 to $total).")
                }
            }
            // v4.0.76: summarizeProgress — model requests a summary of progress so far.
            "summarizeProgress" -> {
                val cached = com.ai.agent.tools.planning.ConversationStore.getProgressSummary()
                if (cached != null) {
                    ToolExecutor.ToolResult(true, "Progress summary:\n$cached")
                } else {
                    val recent = com.ai.agent.tools.planning.ConversationStore.getRecentIterations(5)
                    if (recent.isEmpty()) {
                        ToolExecutor.ToolResult(true, "No iterations completed yet.")
                    } else {
                        val sb = StringBuilder("Recent progress (${recent.size} iterations):\n")
                        for (entry in recent) {
                            sb.append("Step ${entry.step}: ${entry.brainReply.take(100)}\n")
                            sb.append("  Tools: ${entry.toolCallsSummary}\n")
                        }
                        ToolExecutor.ToolResult(true, sb.toString())
                    }
                }
            }
            else -> ToolExecutor.ToolResult(false, "Unknown memory tool: ${call.name}")
        }
    }
}
