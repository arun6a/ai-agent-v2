package com.ai.agent.util

/**
 * v4.0.99-collab4: ToolNameResolver — extracted from ToolExecutor.executeTool
 * (v4.0.18) so case-insensitive tool name matching can be unit-tested.
 *
 * History (from the code comments):
 *   - The brain sometimes sends "createrule" instead of "createRule".
 *   - v4.0.16 tried lowercasing + capitalizing the first letter →
 *     "Createrule" ≠ "createRule" (broke everything).
 *   - v4.0.18 fixed it: look up the canonical name from the available tool
 *     list by case-insensitive equality; fall back to the trimmed raw name.
 *
 * GAP-1 (INFO): first case-insensitive match wins. If two available tools
 *     ever differ only by case, the first in the collection is picked —
 *     deterministic but arbitrary.
 *
 * GAP-2 (LOW): an unknown name is returned TRIMMED but otherwise unchanged —
 *     the caller later fails with "unknown tool". A did-you-mean suggestion
 *     (Levenshtein against available names) would turn hard failures into
 *     self-healing ones.
 */
object ToolNameResolver {

    /**
     * Resolve a raw tool name to the canonical name from [available].
     * Case-insensitive, whitespace-trimmed. Returns the trimmed raw name
     * when no available tool matches.
     */
    fun resolve(rawName: String, available: Collection<String>): String {
        val toolNameLower = rawName.trim().lowercase()
        return available.find { it.lowercase() == toolNameLower } ?: rawName.trim()
    }
}
