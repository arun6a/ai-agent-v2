package com.ai.agent.util

import org.json.JSONArray
import org.json.JSONObject

/**
 * v4.0.99-collab5: TodoStoreParser — pure parsing/normalization/formatting
 * logic extracted from TodoStore so it can be unit-tested on the JVM.
 * TodoStore.update() should delegate here (wiring GAP below).
 *
 * What it does:
 *  1. parseTodos(json) — parse the JSON array, normalize statuses, drop
 *     empty content items.
 *  2. enforceSingleInProgress(items) — if more than one item is in_progress,
 *     keep only the FIRST, demote the rest to pending.
 *  3. formatForPrompt(items) — render the "## Current Plan" block with emoji.
 *
 * ===== DOCUMENTED BUGS / GAPS (found while extracting — for the other AI) =====
 *
 * GAP-1 (LOW): "in progress" (with a SPACE) is NOT recognized as in_progress
 *     — it falls through to "pending". The recognized variants are
 *     "in_progress", "in-progress", "inprogress", "current", "active" (no
 *     space variant). Real users/AIs do write "in progress". Test:
 *     `GAP-1 in progress with space becomes pending`.
 *
 * GAP-2 (LOW): statuses are matched case-insensitively (good, .lowercase()),
 *     but CONTENT is only .trim()'d — leading/trailing whitespace removed
 *     but INTERNAL collapse is not done. So "do   thing" survives with 3
 *     spaces. Cosmetic only.
 *
 * GAP-3 (INFO): an item with empty content after .trim() is silently
 *     dropped — no log, no error returned to the AI. If the AI sends 5 items
 *     and 2 have empty content, the AI sees a 3-item plan back with no
 *     explanation. Test: `empty content items are dropped`.
 *
 * GAP-4 (INFO): there's no length cap on content. An AI could write a
 *     10,000-char todo content and it'd be injected verbatim into the
 *     prompt. ConversationStore truncates at 500/1000 chars; TodoStore does
 *     not. Memory exhaustion / prompt-bloat risk.
 *
 * GAP-5 (INFO): the in_progress enforcement keeps the FIRST in_progress and
 *     demotes the rest. This is deterministic but may surprise the AI if it
 *     intentionally set two items to in_progress (parallel work). The AI
 *     gets no feedback that its second in_progress was demoted.
 */
object TodoStoreParser {

    data class TodoItem(
        val content: String,
        val status: String,  // "pending" | "in_progress" | "completed"
    )

    /** Parse + normalize. Returns the list (possibly empty). Throws on bad JSON. */
    fun parseTodos(todosJson: String): List<TodoItem> {
        val arr = JSONArray(todosJson)
        val out = mutableListOf<TodoItem>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val content = item.optString("content", "").trim()
            val status = item.optString("status", "pending").lowercase().trim()
            if (content.isNotEmpty()) {
                val normalized = when (status) {
                    "in_progress", "in-progress", "inprogress", "current", "active" -> "in_progress"
                    "completed", "complete", "done", "finished" -> "completed"
                    else -> "pending"
                }
                out.add(TodoItem(content, normalized))
            }
        }
        return enforceSingleInProgress(out)
    }

    /** Keep only the first in_progress; demote the rest to pending. */
    fun enforceSingleInProgress(items: List<TodoItem>): List<TodoItem> {
        val inProgressCount = items.count { it.status == "in_progress" }
        if (inProgressCount <= 1) return items.toList()
        var foundFirst = false
        return items.map { item ->
            if (item.status == "in_progress") {
                if (foundFirst) item.copy(status = "pending")
                else { foundFirst = true; item }
            } else item
        }
    }

    /** Render the "## Current Plan" prompt block. Empty string if no items. */
    fun formatForPrompt(items: List<TodoItem>): String {
        if (items.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("\n\n## Current Plan (from todoWrite)\n")
        val completed = items.count { it.status == "completed" }
        sb.append("Progress: $completed/${items.size} steps completed\n\n")
        items.forEachIndexed { i, item ->
            val icon = when (item.status) {
                "completed" -> "✅"
                "in_progress" -> "🔄"
                else -> "⏳"
            }
            sb.append("$icon ${i + 1}. ${item.content}\n")
        }
        return sb.toString()
    }

    /** Try to parse; on failure return an error message (mirrors TodoStore.update catch). */
    fun parseOrError(todosJson: String): Pair<List<TodoItem>, String?> {
        return try {
            parseTodos(todosJson) to null
        } catch (e: Exception) {
            emptyList<TodoItem>() to "Error parsing todos: ${e.message}. Expected JSON array: [{\"content\":\"...\",\"status\":\"pending\"}]"
        }
    }
}
