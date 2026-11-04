package com.ai.agent.mcp

/**
 * v4.0.79: MCP Server Configuration — data class for a connected/known MCP server.
 *
 * Stores all info needed to connect + communicate with an MCP server:
 * - Transport type (stdio or HTTP)
 * - Connection details (URL or command+args)
 * - Auth (none, bearer, OAuth 2.1)
 * - Cached tool list (from tools/list)
 * - Health tracking (success/failure counts)
 *
 * Persisted to /sdcard/Aira/mcp_servers.json by McpManager.
 */
data class McpServerConfig(
    val name: String,           // User-friendly name: "gmail", "filesystem", "my-laptop"
    val transport: String,      // "stdio" or "http"
    // For stdio transport:
    val command: String = "",   // e.g. "npx"
    val args: String = "",      // e.g. "-y @modelcontextprotocol/server-filesystem /sdcard"
    val terminalName: String = "", // Which terminal to spawn in (e.g. "androidide-1793...")
    // For HTTP transport:
    val url: String = "",       // e.g. "https://mcp.notion.com/mcp"
    // Auth:
    val authType: String = "none",  // "none", "bearer", "oauth"
    val bearerToken: String = "",   // for bearer auth
    // OAuth tokens (for oauth auth):
    var accessToken: String = "",
    var refreshToken: String = "",
    var tokenExpiresAt: Long = 0L,  // epoch millis
    var clientId: String = "",     // from Dynamic Client Registration
    // State:
    var connected: Boolean = false,
    var tools: List<McpToolInfo> = emptyList(),
    var lastError: String = "",
    // Health tracking:
    var successCount: Int = 0,
    var failureCount: Int = 0,
    var lastCallTime: Long = 0L,
    // Metadata:
    val addedBy: String = "manual",  // "ai_auto", "manual_search", "manual_url"
    val addedAt: Long = System.currentTimeMillis(),
    // Registry metadata (if found via searchMcpServers):
    val registryName: String = "",   // e.g. "com.googleapis.firestore/mcp"
    val description: String = "",    // from registry
    val publisher: String = "",      // e.g. "com.googleapis" (official Google)
    val isOfficial: Boolean = false,  // true if from a known official publisher
) {
    /**
     * Check if this server is healthy (few failures recently).
     */
    fun isHealthy(): Boolean {
        if (failureCount >= 3 && successCount == 0) return false
        if (failureCount >= 5) return false
        return true
    }

    /**
     * Check if OAuth token is expired or about to expire.
     */
    fun isTokenExpired(): Boolean {
        if (authType != "oauth") return false
        if (tokenExpiresAt == 0L) return false
        // Consider expired if < 5 minutes left
        return System.currentTimeMillis() > (tokenExpiresAt - 300000)
    }

    /**
     * Get the full namespaced tool name: mcp__<serverName>__<toolName>
     */
    fun namespacedToolName(toolName: String): String = "mcp__${name}__$toolName"

    /**
     * Short display string for UI.
     */
    fun displayString(): String {
        val toolCount = tools.size
        val status = if (connected) "🟢" else "🔴"
        val transportStr = when (transport) {
            "stdio" -> "stdio"
            else -> "HTTP"
        }
        return "$status $name ($transportStr, $toolCount tools)"
    }
}

/**
 * Info about a single tool exposed by an MCP server.
 * Cached from tools/list response.
 */
data class McpToolInfo(
    val name: String,           // Tool name as the server knows it: "read_file"
    val description: String,    // What the tool does
    val inputSchema: String,    // JSON schema for parameters (as string)
) {
    /**
     * Get the namespaced name: mcp__<server>__<tool>
     */
    fun fullName(serverName: String): String = "mcp__${serverName}__$name"
}

/**
 * Search result from the MCP registry.
 */
data class McpSearchResult(
    val name: String,           // Registry name: "com.googleapis.firestore/mcp"
    val description: String,    // What it does
    val publisher: String,      // Namespace: "com.googleapis"
    val isOfficial: Boolean,    // Known official publisher
    val transport: String,     // "streamable-http", "sse", or "stdio"
    val url: String,            // Connection URL (if HTTP)
    val version: String,        // Server version
    val isLatest: Boolean,      // Is this the latest version?
    val canAutoConnect: Boolean,// Has URL + transport → can connect directly
    val source: String,         // "official_registry" or "community_web_search"
    val repositoryUrl: String = "", // GitHub repo if available
)
