package com.ai.agent.tools.custom

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * v4.0.88: Connector system — AI-created connectors with full OAuth support.
 *
 * A Connector is a named SERVICE with:
 *   - An auth method (none / apikey / oauth_pkce)
 *   - Multiple API endpoints (auto-registered as {name}__{endpoint} tools)
 *   - Persisted OAuth tokens (auto-refreshed when expired)
 *
 * The AI creates connectors at runtime via createConnector(). When the user
 * says "connect my Gmail" or "connect my home server at 192.168.1.50", the AI
 * discovers the API endpoints (from docs/web search) and calls createConnector.
 *
 * Storage: /data/data/com.ai.agent.v2/files/connectors.json
 *
 * Design principle ("teacher, not recipe"):
 *   The system prompt does NOT hardcode Gmail/GitHub endpoints. It teaches the
 *   AI HOW to discover them (webSearch the API docs, read OpenAPI specs, etc.).
 */
object ConnectorStore {
    private const val TAG = "ConnectorStore"
    private const val FILE_NAME = "connectors.json"
    const val REDIRECT_URI = "aira://callback"

    data class Connector(
        val name: String,            // "gmail", "home_assistant"
        val description: String,     // human-readable
        val icon: String = "🔌",     // emoji
        val authType: String,        // "none" | "apikey" | "oauth_pkce"
        // For apikey auth:
        val apiKey: String = "",
        val apiKeyHeader: String = "Authorization",
        val apiKeyPrefix: String = "Bearer",
        // For oauth_pkce auth:
        val oauth: OAuthConfig? = null,
        // API base + endpoints:
        val apiBase: String = "",
        val endpoints: List<Endpoint> = emptyList(),
        // Runtime OAuth state (persists after user approves):
        var accessToken: String = "",
        var refreshToken: String = "",
        var tokenExpiresAt: Long = 0L,
        var connected: Boolean = false,
        var lastError: String = "",
        val createdAt: Long = System.currentTimeMillis(),
    )

    data class OAuthConfig(
        val authorizeUrl: String,
        val tokenUrl: String,
        val scopes: String,
        val clientId: String,
        val redirectUri: String = REDIRECT_URI,
    )

    data class Endpoint(
        val name: String,        // "send_email" → tool: {connector}__send_email
        val method: String,      // "GET" / "POST" / "PUT" / "DELETE"
        val path: String,        // "/gmail/v1/users/me/messages/send"
        val description: String = "",
        val params: List<String> = emptyList(),
        val bodyTemplate: String = "",
    )

    // ---- Persistence ----

    fun load(context: Context): List<Connector> {
        val file = getFile(context)
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i -> jsonToConnector(arr.getJSONObject(i)) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load connectors", e)
            emptyList()
        }
    }

    private fun save(context: Context, connectors: List<Connector>) {
        try {
            val arr = JSONArray()
            for (c in connectors) arr.put(connectorToJson(c))
            val file = getFile(context)
            file.parentFile?.mkdirs()
            file.writeText(arr.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save connectors", e)
        }
    }

    fun add(context: Context, connector: Connector): Boolean {
        val connectors = load(context).toMutableList()
        if (connectors.any { it.name == connector.name }) {
            connectors.removeAll { it.name == connector.name }
        }
        connectors.add(connector)
        save(context, connectors)
        Log.i(TAG, "✅ Connector saved: ${connector.name} (${connector.authType}, ${connector.endpoints.size} endpoints)")
        return true
    }

    fun remove(context: Context, name: String): Boolean {
        val connectors = load(context).toMutableList()
        val removed = connectors.removeAll { it.name == name }
        if (removed) save(context, connectors)
        return removed
    }

    fun get(context: Context, name: String): Connector? {
        return load(context).find { it.name == name }
    }

    /** Update OAuth tokens after a successful authorize flow. */
    fun updateTokens(
        context: Context, name: String,
        accessToken: String, refreshToken: String, expiresIn: Long,
    ) {
        val connectors = load(context).toMutableList()
        val idx = connectors.indexOfFirst { it.name == name }
        if (idx >= 0) {
            connectors[idx] = connectors[idx].copy(
                accessToken = accessToken,
                refreshToken = refreshToken,
                tokenExpiresAt = System.currentTimeMillis() + (expiresIn * 1000),
                connected = true,
                lastError = "",
            )
            save(context, connectors)
            Log.i(TAG, "🔑 Tokens updated for connector: $name")
        }
    }

    /** Get all tool names generated by connectors (e.g., gmail__send_email). */
    fun getToolNames(context: Context): List<String> {
        return load(context).flatMap { c ->
            c.endpoints.map { ep -> "${c.name}__${ep.name}" }
        }
    }

    /** Find a connector + endpoint by tool name (e.g., "gmail__send_email"). */
    fun resolveTool(context: Context, toolName: String): Pair<Connector, Endpoint>? {
        if (!toolName.contains("__")) return null
        val parts = toolName.split("__", limit = 2)
        val connector = get(context, parts[0]) ?: return null
        val endpoint = connector.endpoints.find { it.name == parts[1] } ?: return null
        return connector to endpoint
    }

    // ---- JSON serialization ----

    private fun jsonToConnector(o: JSONObject): Connector {
        val oauthObj = o.optJSONObject("oauth")
        val oauth = if (oauthObj != null) OAuthConfig(
            authorizeUrl = oauthObj.optString("authorizeUrl"),
            tokenUrl = oauthObj.optString("tokenUrl"),
            scopes = oauthObj.optString("scopes"),
            clientId = oauthObj.optString("clientId"),
            redirectUri = oauthObj.optString("redirectUri", REDIRECT_URI),
        ) else null

        val endpointsArr = o.optJSONArray("endpoints")
        val endpoints = if (endpointsArr != null) {
            (0 until endpointsArr.length()).map { i ->
                val e = endpointsArr.getJSONObject(i)
                val paramsArr = e.optJSONArray("params")
                Endpoint(
                    name = e.optString("name"),
                    method = e.optString("method"),
                    path = e.optString("path"),
                    description = e.optString("description", ""),
                    params = if (paramsArr != null)
                        (0 until paramsArr.length()).map { paramsArr.getString(it) }
                    else emptyList(),
                    bodyTemplate = e.optString("bodyTemplate", ""),
                )
            }
        } else emptyList()

        return Connector(
            name = o.optString("name"),
            description = o.optString("description"),
            icon = o.optString("icon", "🔌"),
            authType = o.optString("authType"),
            apiKey = o.optString("apiKey"),
            apiKeyHeader = o.optString("apiKeyHeader", "Authorization"),
            apiKeyPrefix = o.optString("apiKeyPrefix", "Bearer"),
            oauth = oauth,
            apiBase = o.optString("apiBase"),
            endpoints = endpoints,
            accessToken = o.optString("accessToken"),
            refreshToken = o.optString("refreshToken"),
            tokenExpiresAt = o.optLong("tokenExpiresAt"),
            connected = o.optBoolean("connected"),
            lastError = o.optString("lastError", ""),
            createdAt = o.optLong("createdAt"),
        )
    }

    private fun connectorToJson(c: Connector): JSONObject {
        val o = JSONObject()
        o.put("name", c.name)
        o.put("description", c.description)
        o.put("icon", c.icon)
        o.put("authType", c.authType)
        o.put("apiKey", c.apiKey)
        o.put("apiKeyHeader", c.apiKeyHeader)
        o.put("apiKeyPrefix", c.apiKeyPrefix)
        if (c.oauth != null) {
            o.put("oauth", JSONObject().apply {
                put("authorizeUrl", c.oauth.authorizeUrl)
                put("tokenUrl", c.oauth.tokenUrl)
                put("scopes", c.oauth.scopes)
                put("clientId", c.oauth.clientId)
                put("redirectUri", c.oauth.redirectUri)
            })
        }
        o.put("apiBase", c.apiBase)
        o.put("endpoints", JSONArray().apply {
            for (ep in c.endpoints) {
                put(JSONObject().apply {
                    put("name", ep.name)
                    put("method", ep.method)
                    put("path", ep.path)
                    put("description", ep.description)
                    put("params", JSONArray(ep.params))
                    put("bodyTemplate", ep.bodyTemplate)
                })
            }
        })
        o.put("accessToken", c.accessToken)
        o.put("refreshToken", c.refreshToken)
        o.put("tokenExpiresAt", c.tokenExpiresAt)
        o.put("connected", c.connected)
        o.put("lastError", c.lastError)
        o.put("createdAt", c.createdAt)
        return o
    }

    private fun getFile(context: Context): File {
        return File(context.filesDir, FILE_NAME)
    }
}
