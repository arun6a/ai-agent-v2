package com.ai.agent.mcp

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * v4.0.79: MCP Manager — singleton that manages all MCP server connections.
 *
 * Responsibilities:
 * 1. Connect/disconnect to MCP servers
 * 2. Persist server configs to /sdcard/Aira/mcp_servers.json
 * 3. Aggregate tools from all connected servers
 * 4. Route tool calls to the correct server
 * 5. Health tracking (auto-disconnect unhealthy servers)
 *
 * Used by:
 * - McpTools.kt (the tools the AI brain calls)
 * - UnifiedToolRegistry (to get all MCP tools for injection)
 * - ToolExecutor (to route mcp__ prefixed tool calls)
 */
object McpManager {
    private const val TAG = "McpManager"
    private const val CONFIG_PATH = "/sdcard/Aira/mcp_servers.json"

    @Volatile
    private var servers: MutableList<McpServerConfig> = mutableListOf()
    @Volatile
    private var loaded = false

    /**
     * Load saved server configs from disk.
     */
    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        try {
            val file = File(CONFIG_PATH)
            if (file.exists()) {
                val json = file.readText()
                if (json.isNotEmpty()) {
                    val arr = JSONArray(json)
                    servers.clear()
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val config = McpServerConfig(
                            name = o.optString("name"),
                            transport = o.optString("transport", "http"),
                            command = o.optString("command", ""),
                            args = o.optString("args", ""),
                            terminalName = o.optString("terminalName", ""),
                            url = o.optString("url", ""),
                            authType = o.optString("authType", "none"),
                            bearerToken = o.optString("bearerToken", ""),
                            accessToken = o.optString("accessToken", ""),
                            refreshToken = o.optString("refreshToken", ""),
                            tokenExpiresAt = o.optLong("tokenExpiresAt", 0),
                            clientId = o.optString("clientId", ""),
                            connected = false,  // always start disconnected
                            addedBy = o.optString("addedBy", "manual"),
                            addedAt = o.optLong("addedAt", System.currentTimeMillis()),
                            registryName = o.optString("registryName", ""),
                            description = o.optString("description", ""),
                            publisher = o.optString("publisher", ""),
                            isOfficial = o.optBoolean("isOfficial", false),
                        )
                        // Restore tool list (names only — schemas re-fetched on connect)
                        val toolsArr = o.optJSONArray("tools")
                        if (toolsArr != null) {
                            val tools = mutableListOf<McpToolInfo>()
                            for (j in 0 until toolsArr.length()) {
                                val t = toolsArr.optJSONObject(j)
                                tools.add(McpToolInfo(
                                    name = t?.optString("name", "") ?: "",
                                    description = t?.optString("description", "") ?: "",
                                    inputSchema = t?.optString("inputSchema", "{}") ?: "{}",
                                ))
                            }
                            config.tools = tools
                        }
                        servers.add(config)
                    }
                }
            }
            loaded = true
            Log.i(TAG, "Loaded ${servers.size} MCP server configs")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load MCP configs: ${e.message}")
        }
    }

    /**
     * Save server configs to disk.
     */
    @Synchronized
    fun save(context: Context) {
        try {
            val dir = File("/sdcard/Aira/")
            if (!dir.exists()) dir.mkdirs()
            val arr = JSONArray()
            for (config in servers) {
                arr.put(JSONObject().apply {
                    put("name", config.name)
                    put("transport", config.transport)
                    put("command", config.command)
                    put("args", config.args)
                    put("terminalName", config.terminalName)
                    put("url", config.url)
                    put("authType", config.authType)
                    put("bearerToken", config.bearerToken)
                    put("accessToken", config.accessToken)
                    put("refreshToken", config.refreshToken)
                    put("tokenExpiresAt", config.tokenExpiresAt)
                    put("clientId", config.clientId)
                    put("addedBy", config.addedBy)
                    put("addedAt", config.addedAt)
                    put("registryName", config.registryName)
                    put("description", config.description)
                    put("publisher", config.publisher)
                    put("isOfficial", config.isOfficial)
                    // Save tool names (not full schemas — re-fetched on connect)
                    val toolsArr = JSONArray()
                    for (tool in config.tools) {
                        toolsArr.put(JSONObject().apply {
                            put("name", tool.name)
                            put("description", tool.description)
                        })
                    }
                    put("tools", toolsArr)
                })
            }
            File(CONFIG_PATH).writeText(arr.toString())
            Log.i(TAG, "Saved ${servers.size} MCP server configs")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save MCP configs: ${e.message}")
        }
    }

    /**
     * Connect to an MCP server.
     * Sends initialize + tools/list, caches the tools.
     */
    suspend fun connect(context: Context, config: McpServerConfig): Boolean {
        load(context)
        // Check if already connected
        val existing = servers.find { it.name == config.name }
        if (existing != null && existing.connected) {
            Log.i(TAG, "${config.name} already connected")
            return true
        }

        // Add or update the config
        if (existing != null) {
            val idx = servers.indexOf(existing)
            servers[idx] = config
        } else {
            servers.add(config)
        }

        // Initialize (sends JSON-RPC initialize + tools/list)
        val tools = McpClient.initialize(config)
        if (tools.isEmpty() && !config.connected) {
            Log.w(TAG, "Failed to connect to ${config.name}: ${config.lastError}")
            save(context)
            return false
        }

        save(context)
        Log.i(TAG, "Connected to ${config.name}: ${config.tools.size} tools")
        return true
    }

    /**
     * Disconnect from a server.
     */
    fun disconnect(context: Context, name: String): Boolean {
        load(context)
        val config = servers.find { it.name == name } ?: return false
        McpClient.disconnect(config)
        save(context)
        Log.i(TAG, "Disconnected from $name")
        return true
    }

    /**
     * Call a tool on a connected server.
     */
    suspend fun callTool(serverName: String, toolName: String, args: Map<String, Any>): McpToolResult {
        val config = servers.find { it.name == serverName && it.connected }
            ?: return McpToolResult(false, "MCP server '$serverName' is not connected. Call listMcpServers() to see connected servers.")

        // Check health
        if (!config.isHealthy()) {
            config.connected = false
            return McpToolResult(false, "MCP server '$serverName' is unhealthy (${config.failureCount} failures). Try disconnecting and reconnecting.")
        }

        return McpClient.callTool(config, toolName, args)
    }

    /**
     * Get all connected servers.
     */
    fun getConnectedServers(): List<McpServerConfig> {
        return servers.filter { it.connected }
    }

    /**
     * Get all servers (connected or not).
     */
    fun getAllServers(): List<McpServerConfig> {
        return servers.toList()
    }

    /**
     * Get all tools from all connected servers.
     * Returns pairs of (serverName, tool) for injection into the tools array.
     */
    fun getAllTools(): List<Pair<String, McpToolInfo>> {
        return servers.filter { it.connected }
            .flatMap { config ->
                config.tools.map { tool -> Pair(config.name, tool) }
            }
    }

    /**
     * Get all tool names (namespaced) from all connected servers.
     */
    fun getAllToolNames(): List<String> {
        return getAllTools().map { (serverName, tool) ->
            "mcp__${serverName}__${tool.name}"
        }
    }

    /**
     * Find which server + tool a namespaced tool name refers to.
     * e.g. "mcp__gmail__send_email" → (server="gmail", tool="send_email")
     */
    fun resolveTool(namespacedName: String): Pair<String, String>? {
        if (!namespacedName.startsWith("mcp__")) return null
        val parts = namespacedName.removePrefix("mcp__").split("__", limit = 2)
        if (parts.size != 2) return null
        val serverName = parts[0]
        val toolName = parts[1]
        val config = servers.find { it.name == serverName && it.connected } ?: return null
        val tool = config.tools.find { it.name == toolName } ?: return null
        return Pair(serverName, toolName)
    }

    /**
     * Search tools across all connected MCP servers.
     */
    fun searchTools(query: String): List<Triple<String, McpToolInfo, Boolean>> {
        val queryLower = query.lowercase()
        return servers.filter { it.connected }.flatMap { config ->
            config.tools
                .filter { tool ->
                    tool.name.lowercase().contains(queryLower) ||
                    tool.description.lowercase().contains(queryLower)
                }
                .map { tool -> Triple(config.name, tool, config.isOfficial) }
        }
    }

    /**
     * Remove a server entirely (not just disconnect).
     */
    fun removeServer(context: Context, name: String): Boolean {
        load(context)
        val removed = servers.removeAll { it.name == name }
        if (removed) save(context)
        return removed
    }
}
