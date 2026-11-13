package com.ai.agent.mcp

import android.content.Context
import android.util.Log
import com.ai.agent.llm.LLMClient
import com.ai.agent.tools.ToolExecutor

/**
 * v4.0.79: MCP Tools — the 7 tools the AI brain uses to interact with MCP.
 *
 * These tools let the brain:
 * 1. Search for MCP servers (official registry + community)
 * 2. Connect to a server
 * 3. Disconnect from a server
 * 4. List connected servers
 * 5. List tools on connected servers
 * 6. Call a specific MCP tool
 *
 * When MCP is enabled, MCP tools from connected servers are also auto-injected
 * into the tools array (with mcp__server__tool namespacing).
 * The brain can call those directly — this callMcpTool is a fallback for explicit calls.
 */
object McpTools {
    private const val TAG = "McpTools"

    suspend fun execute(
        call: LLMClient.ToolCall,
        context: Context,
    ): ToolExecutor.ToolResult {
        // Check if MCP is enabled
        if (!com.ai.agent.llm.AIProvider.isMcpEnabled(context)) {
            return ToolExecutor.ToolResult(false,
                "MCP is disabled. Enable it in Settings → AI Behavior → MCP.")
        }

        return when (call.name) {
            "searchMcpServers" -> searchServers(call.args, context)
            "getMcpServerInfo" -> getServerInfo(call.args, context)
            "connectMcpServer" -> connectServer(call.args, context)
            "disconnectMcpServer" -> disconnectServer(call.args, context)
            "listMcpServers" -> listServers(context)
            "listMcpTools" -> listTools(call.args, context)
            "callMcpTool" -> callTool(call.args, context)
            else -> ToolExecutor.ToolResult(false, "Unknown MCP tool: ${call.name}")
        }
    }

    /**
     * Search the MCP registry for servers matching a query.
     * Searches both the official registry API and community registries via web search.
     */
    private suspend fun searchServers(args: Map<String, Any>, context: Context): ToolExecutor.ToolResult {
        val query = args["query"] as? String
            ?: return ToolExecutor.ToolResult(false, "missing query. Example: searchMcpServers(query=\"gmail\")")

        val results = McpRegistryClient.searchAll(query)
        if (results.isEmpty()) {
            return ToolExecutor.ToolResult(true,
                "No MCP servers found for '$query'.\n" +
                "Try a different keyword (e.g. 'email' instead of 'gmail').\n" +
                "You can also add a server manually by URL: connectMcpServer(name=\"custom\", url=\"...\")")
        }

        val sb = StringBuilder("MCP servers matching '$query' (${results.size} found):\n\n")
        for ((i, result) in results.withIndex()) {
            val officialTag = if (result.isOfficial) " ⭐[OFFICIAL]" else ""
            val autoTag = if (result.canAutoConnect) " ✅[can auto-connect]" else " ⚠️[needs manual setup]"
            val sourceTag = "[${result.source}]"

            sb.append("${i + 1}. ${result.name}$officialTag $autoTag\n")
            sb.append("   ${result.description}\n")
            if (result.url.isNotEmpty()) {
                sb.append("   URL: ${result.url}\n")
            }
            if (result.transport.isNotEmpty()) {
                sb.append("   Transport: ${result.transport}\n")
            }
            if (result.version.isNotEmpty()) {
                sb.append("   Version: ${result.version}\n")
            }
            sb.append("   Source: $sourceTag\n")
            if (result.repositoryUrl.isNotEmpty()) {
                sb.append("   Repo: ${result.repositoryUrl}\n")
            }
            sb.append("\n")
        }

        sb.append("To connect: connectMcpServer(name=\"<short-name>\", url=\"<url>\", auth=\"oauth\")\n")
        sb.append("Official servers with ⭐ are from known companies (Google, Notion, Stripe, etc.)\n")
        sb.append("Servers with ✅ have a direct URL and can be connected immediately.\n")
        sb.append("Servers with ⚠️ need manual setup — visit the URL to find connection details.")

        return ToolExecutor.ToolResult(true, sb.toString())
    }

    /**
     * Get detailed info about a specific server (by registry name).
     */
    private suspend fun getServerInfo(args: Map<String, Any>, context: Context): ToolExecutor.ToolResult {
        val name = args["name"] as? String
            ?: return ToolExecutor.ToolResult(false, "missing name. Example: getMcpServerInfo(name=\"com.googleapis.firestore/mcp\")")

        val results = McpRegistryClient.searchOfficialRegistry(name, 5)
        val result = results.find { it.name == name }
            ?: return ToolExecutor.ToolResult(false, "Server '$name' not found in registry.")

        val sb = StringBuilder("MCP Server Details:\n\n")
        sb.append("Name: ${result.name}\n")
        sb.append("Description: ${result.description}\n")
        sb.append("Publisher: ${result.publisher}\n")
        sb.append("Official: ${if (result.isOfficial) "Yes ⭐" else "No"}\n")
        sb.append("Transport: ${result.transport}\n")
        sb.append("URL: ${result.url}\n")
        sb.append("Version: ${result.version}\n")
        sb.append("Can auto-connect: ${if (result.canAutoConnect) "Yes ✅" else "No ⚠️"}\n")
        if (result.repositoryUrl.isNotEmpty()) {
            sb.append("Repository: ${result.repositoryUrl}\n")
        }
        sb.append("\nTo connect: connectMcpServer(name=\"<short-name>\", url=\"${result.url}\", auth=\"oauth\")")

        return ToolExecutor.ToolResult(true, sb.toString())
    }

    /**
     * Connect to an MCP server.
     * Triggers initialize + tools/list. If OAuth is needed, starts the OAuth flow.
     */
    private suspend fun connectServer(args: Map<String, Any>, context: Context): ToolExecutor.ToolResult {
        val name = args["name"] as? String
            ?: return ToolExecutor.ToolResult(false, "missing name. Example: connectMcpServer(name=\"gmail\", url=\"https://...\", auth=\"oauth\")")
        val transport = args["transport"] as? String ?: "http"
        val url = args["url"] as? String ?: ""
        val auth = args["auth"] as? String ?: "none"
        val token = args["token"] as? String ?: ""

        if (transport == "http" && url.isEmpty()) {
            return ToolExecutor.ToolResult(false, "missing url for HTTP transport. Example: connectMcpServer(name=\"gmail\", url=\"https://mcp.notion.com/mcp\", auth=\"oauth\")")
        }

        val config = McpServerConfig(
            name = name,
            transport = transport,
            url = url,
            authType = auth,
            bearerToken = token,
            addedBy = "ai_auto",
        )

        val connected = McpManager.connect(context, config)
        return if (connected) {
            val tools = config.tools
            ToolExecutor.ToolResult(true,
                "✅ Connected to MCP server '$name'!\n" +
                "Tools available: ${tools.size}\n" +
                tools.joinToString("\n") { "  - mcp__${name}__${it.name}: ${it.description.take(60)}" } +
                "\n\nYou can now call these tools directly. Example: mcp__${name}__${tools.firstOrNull()?.name ?: "tool_name"}")
        } else {
            ToolExecutor.ToolResult(false,
                "❌ Failed to connect to '$name': ${config.lastError}\n" +
                "Check:\n" +
                "  - URL is correct: $url\n" +
                "  - Auth type is correct (try 'none', 'bearer', or 'oauth')\n" +
                "  - Server is online + reachable\n" +
                "  - If OAuth: the OAuth flow may need to run (browser opens)")
        }
    }

    /**
     * Disconnect from a server.
     */
    private fun disconnectServer(args: Map<String, Any>, context: Context): ToolExecutor.ToolResult {
        val name = args["name"] as? String
            ?: return ToolExecutor.ToolResult(false, "missing name. Example: disconnectMcpServer(name=\"gmail\")")

        val success = McpManager.disconnect(context, name)
        return if (success) {
            ToolExecutor.ToolResult(true, "Disconnected from '$name'.")
        } else {
            ToolExecutor.ToolResult(false, "Server '$name' was not connected.")
        }
    }

    /**
     * List all connected MCP servers + their tool counts.
     */
    private fun listServers(context: Context): ToolExecutor.ToolResult {
        McpManager.load(context)
        val connected = McpManager.getConnectedServers()
        val all = McpManager.getAllServers()

        if (all.isEmpty()) {
            return ToolExecutor.ToolResult(true,
                "No MCP servers configured.\n" +
                "To find servers: searchMcpServers(query=\"gmail\")\n" +
                "To add manually: connectMcpServer(name=\"custom\", url=\"https://...\", auth=\"none\")")
        }

        val sb = StringBuilder("MCP Servers (${connected.size} connected, ${all.size} total):\n\n")
        for (config in all) {
            val status = if (config.connected) "🟢" else "🔴"
            val toolCount = if (config.connected) config.tools.size else 0
            val officialTag = if (config.isOfficial) " ⭐" else ""
            sb.append("$status ${config.name}$officialTag\n")
            sb.append("   Transport: ${config.transport} | Tools: $toolCount | Auth: ${config.authType}\n")
            if (config.url.isNotEmpty()) sb.append("   URL: ${config.url}\n")
            if (config.description.isNotEmpty()) sb.append("   ${config.description.take(80)}\n")
            if (!config.connected && config.lastError.isNotEmpty()) {
                sb.append("   Last error: ${config.lastError}\n")
            }
            sb.append("\n")
        }
        return ToolExecutor.ToolResult(true, sb.toString())
    }

    /**
     * List tools on connected server(s).
     */
    private fun listTools(args: Map<String, Any>, context: Context): ToolExecutor.ToolResult {
        val serverName = args["serverName"] as? String
        McpManager.load(context)

        val servers = if (serverName != null) {
            McpManager.getConnectedServers().filter { it.name == serverName }
        } else {
            McpManager.getConnectedServers()
        }

        if (servers.isEmpty()) {
            return ToolExecutor.ToolResult(true,
                "No connected MCP servers${if (serverName != null) " matching '$serverName'" else ""}.\n" +
                "Connect one first: connectMcpServer(name=\"...\", url=\"...\", auth=\"...\")")
        }

        val sb = StringBuilder("MCP Tools (${servers.size} servers):\n\n")
        for (config in servers) {
            sb.append("═══ ${config.name} (${config.tools.size} tools) ═══\n")
            for (tool in config.tools) {
                sb.append("  • mcp__${config.name}__${tool.name}: ${tool.description.take(80)}\n")
            }
            sb.append("\n")
        }
        return ToolExecutor.ToolResult(true, sb.toString())
    }

    /**
     * Call a specific MCP tool explicitly.
     * (Usually the brain calls mcp__server__tool directly — this is a fallback.)
     */
    private suspend fun callTool(args: Map<String, Any>, context: Context): ToolExecutor.ToolResult {
        val serverName = args["serverName"] as? String
            ?: return ToolExecutor.ToolResult(false, "missing serverName. Example: callMcpTool(serverName=\"gmail\", toolName=\"send_email\", args={\"to\":\"mom\"})")
        val toolName = args["toolName"] as? String
            ?: return ToolExecutor.ToolResult(false, "missing toolName")
        val toolArgs = (args["args"] as? Map<String, Any>) ?: emptyMap()

        val result = McpManager.callTool(serverName, toolName, toolArgs)
        return ToolExecutor.ToolResult(result.success, result.output)
    }
}
