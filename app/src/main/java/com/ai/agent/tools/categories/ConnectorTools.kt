package com.ai.agent.tools.categories

import android.content.Context
import com.ai.agent.accessibility.AgentAccessibilityService
import com.ai.agent.llm.LLMClient
import com.ai.agent.storage.AgentDatabase
import com.ai.agent.tools.ToolExecutor
import com.ai.agent.tools.custom.ConnectorExecutor
import com.ai.agent.tools.custom.ConnectorStore

/**
 * v4.0.88: ConnectorTools — handles createConnector, listConnectors, deleteConnector.
 *
 * Created connectors generate per-endpoint tools ({name}__{endpoint}) that are
 * auto-registered in ToolExecutor.executeTool() and ToolSchemas.buildToolsArray().
 */
object ConnectorTools {

    suspend fun execute(
        call: LLMClient.ToolCall,
        context: Context,
        @Suppress("UNUSED_PARAMETER") service: AgentAccessibilityService?,
        @Suppress("UNUSED_PARAMETER") database: AgentDatabase,
    ): ToolExecutor.ToolResult {
        return when (call.name) {
            "createConnector" -> createConnector(call.args, context)
            "listConnectors" -> listConnectors(context)
            "deleteConnector" -> deleteConnector(call.args, context)
            "connectConnector" -> connectConnector(call.args, context)
            else -> ToolExecutor.ToolResult(false, "Unknown connector tool: ${call.name}")
        }
    }

    /**
     * createConnector — the AI calls this to define a new connector.
     * Args:
     *   name (str): short identifier, e.g. "gmail", "home_assistant"
     *   description (str): what this connector does
     *   authType (str): "none" | "apikey" | "oauth_pkce"
     *   apiBase (str): e.g. "https://gmail.googleapis.com"
     *   endpoints (str): JSON array of [{name, method, path, description, params, bodyTemplate}]
     *   For apikey:
     *     apiKey (str), apiKeyHeader (str, default "Authorization"), apiKeyPrefix (str, default "Bearer")
     *   For oauth_pkce:
     *     authorizeUrl (str), tokenUrl (str), scopes (str), clientId (str)
     */
    private suspend fun createConnector(args: Map<String, Any>, context: Context): ToolExecutor.ToolResult {
        val name = args["name"] as? String
            ?: return ToolExecutor.ToolResult(false, "missing name")
        val description = args["description"] as? String
            ?: return ToolExecutor.ToolResult(false, "missing description")
        val authType = args["authType"] as? String ?: "none"
        val apiBase = args["apiBase"] as? String ?: ""

        val endpointsStr = args["endpoints"] as? String
            ?: return ToolExecutor.ToolResult(false, "missing endpoints (JSON array)")
        val endpoints = parseEndpoints(endpointsStr)
        if (endpoints.isEmpty()) {
            return ToolExecutor.ToolResult(false, "no valid endpoints parsed from: ${endpointsStr.take(100)}")
        }

        val oauth = if (authType == "oauth_pkce") {
            val authorizeUrl = args["authorizeUrl"] as? String
                ?: return ToolExecutor.ToolResult(false, "oauth_pkce needs authorizeUrl")
            val tokenUrl = args["tokenUrl"] as? String
                ?: return ToolExecutor.ToolResult(false, "oauth_pkce needs tokenUrl")
            val scopes = args["scopes"] as? String ?: ""
            val clientId = args["clientId"] as? String
                ?: return ToolExecutor.ToolResult(false, "oauth_pkce needs clientId (register an OAuth app with the provider, or use a public client_id)")
            ConnectorStore.OAuthConfig(
                authorizeUrl = authorizeUrl,
                tokenUrl = tokenUrl,
                scopes = scopes,
                clientId = clientId,
            )
        } else null

        val connector = ConnectorStore.Connector(
            name = name,
            description = description,
            authType = authType,
            oauth = oauth,
            apiBase = apiBase,
            endpoints = endpoints,
            apiKey = args["apiKey"] as? String ?: "",
            apiKeyHeader = args["apiKeyHeader"] as? String ?: "Authorization",
            apiKeyPrefix = args["apiKeyPrefix"] as? String ?: "Bearer",
        )

        ConnectorStore.add(context, connector)

        val toolNames = endpoints.joinToString(", ") { "${name}__${it.name}" }
        val authNote = when (authType) {
            "none" -> "no auth"
            "apikey" -> "API key auth"
            "oauth_pkce" -> "OAuth PKCE — call connectConnector(name=\"$name\") to open the login page"
            else -> authType
        }
        return ToolExecutor.ToolResult(true,
            "✅ Connector '$name' created with ${endpoints.size} endpoint(s).\n" +
            "Auth: $authNote\n" +
            "Tools: $toolNames\n" +
            if (authType == "oauth_pkce")
                "Next: call connectConnector(name=\"$name\") to start OAuth (opens browser)."
            else
                "Ready to use — call $toolNames directly."
        )
    }

    private fun listConnectors(context: Context): ToolExecutor.ToolResult {
        val connectors = ConnectorStore.load(context)
        if (connectors.isEmpty()) {
            return ToolExecutor.ToolResult(true,
                "No connectors created yet.\n" +
                "Create one: createConnector(name=\"my_api\", description=\"...\", authType=\"none\"|\"apikey\"|\"oauth_pkce\", apiBase=\"https://api.example.com\", endpoints=\"[{...}]\")")
        }
        val sb = StringBuilder("Connectors (${connectors.size}):\n\n")
        for (c in connectors) {
            val status = when (c.authType) {
                "none" -> "✓ ready"
                "apikey" -> "✓ ready (api key)"
                "oauth_pkce" -> if (c.connected) "✓ connected" else "⚠ not connected — call connectConnector(name=\"${c.name}\")"
                else -> c.authType
            }
            sb.append("${c.icon} ${c.name} — ${c.description}\n")
            sb.append("  Auth: ${c.authType} | Status: $status\n")
            sb.append("  Endpoints: ${c.endpoints.joinToString(", ") { it.name }}\n")
            sb.append("  Tools: ${c.endpoints.joinToString(", ") { "${c.name}__${it.name}" }}\n\n")
        }
        return ToolExecutor.ToolResult(true, sb.toString().trim())
    }

    private fun deleteConnector(args: Map<String, Any>, context: Context): ToolExecutor.ToolResult {
        val name = args["name"] as? String
            ?: return ToolExecutor.ToolResult(false, "missing name")
        return if (ConnectorStore.remove(context, name)) {
            ToolExecutor.ToolResult(true, "✅ Connector '$name' deleted (and all its tools).")
        } else {
            ToolExecutor.ToolResult(false, "Connector '$name' not found.")
        }
    }

    /**
     * connectConnector — starts the OAuth flow for a connector with authType="oauth_pkce".
     * Opens the browser for the user to log in + approve.
     */
    private suspend fun connectConnector(args: Map<String, Any>, context: Context): ToolExecutor.ToolResult {
        val name = args["name"] as? String
            ?: return ToolExecutor.ToolResult(false, "missing name")
        val connector = ConnectorStore.get(context, name)
            ?: return ToolExecutor.ToolResult(false, "Connector '$name' not found. Create it first with createConnector().")
        if (connector.authType != "oauth_pkce") {
            return ToolExecutor.ToolResult(false,
                "Connector '$name' uses authType='${connector.authType}' — no OAuth flow needed. " +
                "Call its tools directly: ${connector.endpoints.joinToString(", ") { "${name}__${it.name}" }}")
        }
        if (connector.connected && System.currentTimeMillis() < connector.tokenExpiresAt) {
            return ToolExecutor.ToolResult(true, "Connector '$name' is already connected. Call its tools directly.")
        }
        // Start OAuth flow (opens browser)
        val success = ConnectorExecutor.startOAuthFlow(context, connector)
        return if (success) {
            ToolExecutor.ToolResult(true,
                "✅ '$name' connected! You can now call: ${connector.endpoints.joinToString(", ") { "${name}__${it.name}" }}")
        } else {
            ToolExecutor.ToolResult(false,
                "OAuth flow for '$name' failed or timed out. The user may not have completed authorization.")
        }
    }

    /** Execute a connector endpoint call (e.g., gmail__send_email). */
    suspend fun executeEndpoint(
        context: Context,
        toolName: String,
        args: Map<String, Any>,
    ): ToolExecutor.ToolResult {
        val resolved = ConnectorStore.resolveTool(context, toolName) ?: return ToolExecutor.ToolResult(false,
            "Connector tool '$toolName' not found. Call listConnectors() to see available tools.")
        val (connector, endpoint) = resolved
        val result = ConnectorExecutor.callEndpoint(context, connector, endpoint, args)
        return ToolExecutor.ToolResult(result.success, result.output)
    }

    private fun parseEndpoints(jsonStr: String): List<ConnectorStore.Endpoint> {
        return try {
            val arr = org.json.JSONArray(jsonStr)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val paramsArr = o.optJSONArray("params")
                ConnectorStore.Endpoint(
                    name = o.optString("name"),
                    method = o.optString("method", "GET").uppercase(),
                    path = o.optString("path"),
                    description = o.optString("description", ""),
                    params = if (paramsArr != null)
                        (0 until paramsArr.length()).map { paramsArr.getString(it) }
                    else emptyList(),
                    bodyTemplate = o.optString("bodyTemplate", ""),
                )
            }.filter { it.name.isNotBlank() && it.path.isNotBlank() }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
