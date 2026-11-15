package com.ai.agent.tools.planning

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * v4.0.62: TodoStore — persistent todo list for the agent loop.
 *
 * When the AI calls todoWrite(todos=...), the list is stored here.
 * ChatViewModel reads it each iteration + injects "## Current Plan"
 * into the prompt so the AI always sees its progress.
 *
 * This is the equivalent of Z.ai Code's TodoWrite — it gives Aira
 * structured planning + progress tracking across iterations.
 *
 * The list survives across iterations within a task. It is cleared
 * when a new task starts (new user message).
 */
object TodoStore {
    private const val TAG = "TodoStore"

    data class TodoItem(
        val content: String,
        val status: String,  // "pending" | "in_progress" | "completed"
    )

    @Volatile
    private var todos: List<TodoItem> = emptyList()

    /**
     * Update the todo list. Called by the AI via the todoWrite tool.
     * Parses the JSON array + stores the items.
     *
     * Returns the formatted list string (for the tool result).
     */
    fun update(todosJson: String): String {
        try {
            val arr = JSONArray(todosJson)
            val newTodos = mutableListOf<TodoItem>()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val content = item.optString("content", "").trim()
                val status = item.optString("status", "pending").lowercase().trim()
                if (content.isNotEmpty()) {
                    val normalizedStatus = when (status) {
                        "in_progress", "in-progress", "inprogress", "current", "active" -> "in_progress"
                        "completed", "complete", "done", "finished" -> "completed"
                        else -> "pending"
                    }
                    newTodos.add(TodoItem(content, normalizedStatus))
                }
            }
            // v4.0.62: Validate — only ONE item should be in_progress at a time.
            // If multiple are in_progress, keep only the first, reset others to pending.
            val inProgressCount = newTodos.count { it.status == "in_progress" }
            if (inProgressCount > 1) {
                var foundFirst = false
                newTodos.forEachIndexed { idx, item ->
                    if (item.status == "in_progress") {
                        if (foundFirst) {
                            newTodos[idx] = item.copy(status = "pending")
                        }
                        foundFirst = true
                    }
                }
            }
            todos = newTodos
            Log.i(TAG, "Todo list updated: ${todos.size} items (${todos.count { it.status == "completed" }} completed)")
            return formatForPrompt()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse todos JSON: $todosJson", e)
            return "Error parsing todos: ${e.message}. Expected JSON array: [{\"content\":\"...\",\"status\":\"pending\"}]"
        }
    }

    /**
     * Get the current todo list formatted for prompt injection.
     * Returns empty string if no todos are set.
     *
     * Example output:
     * ## Current Plan (from todoWrite)
     * ✅ 1. Search for wallpaper URL
     * 🔄 2. Download image to /sdcard/Pictures/
     * ⏳ 3. Set as wallpaper
     * ⏳ 4. Clean up temp files
     */
    fun formatForPrompt(): String {
        if (todos.isEmpty()) return ""

        val sb = StringBuilder()
        sb.append("\n\n## Current Plan (from todoWrite)\n")
        val completed = todos.count { it.status == "completed" }
        sb.append("Progress: $completed/${todos.size} steps completed\n\n")
        todos.forEachIndexed { i, item ->
            val icon = when (item.status) {
                "completed" -> "✅"
                "in_progress" -> "🔄"
                else -> "⏳"
            }
            sb.append("$icon ${i + 1}. ${item.content}\n")
        }
        return sb.toString()
    }

    /**
     * Check if a todo list is currently set.
     */
    fun hasTodos(): Boolean = todos.isNotEmpty()

    /**
     * Clear the todo list. Called when a new task starts (new user message).
     */
    fun clear() {
        if (todos.isNotEmpty()) {
            Log.i(TAG, "Clearing todo list (${todos.size} items)")
        }
        todos = emptyList()
    }

    /**
     * Get the current todo items (for UI display if needed).
     */
    fun getTodos(): List<TodoItem> = todos
}
