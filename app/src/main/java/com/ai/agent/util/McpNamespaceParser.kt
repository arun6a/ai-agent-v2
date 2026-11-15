package com.ai.agent.util

/**
 * v4.0.100: MCP namespace parser — extracted from McpManager.resolveTool.
 *
 * Parses "mcp__serverName__toolName" into (serverName, toolName).
 * Pure function, no Android dependencies — fully unit-testable.
 *
 * Known issue: if serverName contains "__", the split breaks.
 * These tests document that behavior.
 */
object McpNamespaceParser {

    private const val PREFIX = "mcp__"

    /**
     * Parse a namespaced tool name into (serverName, toolName).
     * Returns null if the name doesn't start with "mcp__" or has no "__" separator.
     *
     * Uses split("__", limit = 2) — so only the FIRST "__" is the separator.
     * If the tool name contains "__", it's preserved.
     * If the server name contains "__", it will be split incorrectly.
     */
    fun parse(namespacedName: String): Pair<String, String>? {
        if (!namespacedName.startsWith(PREFIX)) return null
        val withoutPrefix = namespacedName.removePrefix(PREFIX)
        val parts = withoutPrefix.split("__", limit = 2)
        if (parts.size != 2) return null
        val serverName = parts[0]
        val toolName = parts[1]
        if (serverName.isEmpty() || toolName.isEmpty()) return null
        return Pair(serverName, toolName)
    }

    /**
     * Build a namespaced tool name from server + tool.
     */
    fun build(serverName: String, toolName: String): String {
        return "${PREFIX}${serverName}__${toolName}"
    }

    /**
     * Check if a name is a valid MCP namespaced tool name.
     */
    fun isMcpNamespaced(name: String): Boolean {
        return name.startsWith(PREFIX) && parse(name) != null
    }
}
