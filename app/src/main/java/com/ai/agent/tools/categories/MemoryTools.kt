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
            else -> ToolExecutor.ToolResult(false, "Unknown memory tool: ${call.name}")
        }
    }
}
