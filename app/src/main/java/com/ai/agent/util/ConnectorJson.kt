package com.ai.agent.util

import com.ai.agent.tools.custom.ConnectorStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * v4.0.99-collab4: ConnectorJson — the JSON serialization of ConnectorStore
 * (v4.0.88) extracted so the persistence format can be round-trip tested on
 * the JVM. ConnectorStore.jsonToConnector/connectorToJson are private; after
 * wiring, they should delegate here (single source of truth for the format).
 *
 * Storage format: a JSONArray of connector objects at
 * /data/data/com.ai.agent.v2/files/connectors.json.
 *
 * org.json is available in unit tests (testImplementation org.json:json).
 *
 * GAP-1 (MEDIUM): jsonToConnector reads `createdAt` with optLong default 0 —
 *     a hand-edited file without createdAt yields epoch-1970, which sorts
 *     first in any "recently added" UI.
 *
 * GAP-2 (LOW): no schema/version field. Adding or renaming keys later needs
 *     migration logic that today does not exist (older installs just lose
 *     unknown fields silently — optString behavior).
 *
 * GAP-3 (LOW): connector names are not normalized. A connector saved as
 *     "Gmail" and a tool call resolved via `name__endpoint` split on "__"
 *     (case-sensitive find) — "gmail__x" ≠ "Gmail__x". Same bug family as
 *     McpNamespaceParser's `__`-in-server-name issue.
 */
object ConnectorJson {

    /** Serialize a connector exactly like ConnectorStore.connectorToJson. */
    fun connectorToJson(c: ConnectorStore.Connector): JSONObject {
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

    /** Parse a connector exactly like ConnectorStore.jsonToConnector. */
    fun jsonToConnector(o: JSONObject): ConnectorStore.Connector {
        val oauthObj = o.optJSONObject("oauth")
        val oauth = if (oauthObj != null) ConnectorStore.OAuthConfig(
            authorizeUrl = oauthObj.optString("authorizeUrl"),
            tokenUrl = oauthObj.optString("tokenUrl"),
            scopes = oauthObj.optString("scopes"),
            clientId = oauthObj.optString("clientId"),
            redirectUri = oauthObj.optString("redirectUri", ConnectorStore.REDIRECT_URI),
        ) else null

        val endpointsArr = o.optJSONArray("endpoints")
        val endpoints = if (endpointsArr != null) {
            (0 until endpointsArr.length()).map { i ->
                val e = endpointsArr.getJSONObject(i)
                val paramsArr = e.optJSONArray("params")
                ConnectorStore.Endpoint(
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

        return ConnectorStore.Connector(
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
}
