package com.ai.agent.util

import com.ai.agent.tools.custom.ConnectorStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip tests for ConnectorJson — the persistence format of
 * ConnectorStore (v4.0.88) extracted for JVM testing. org.json is available
 * in unit tests (testImplementation org.json:json:20240303).
 *
 * Covers: full/minimal round trips, OAuth config, endpoints, defaults for
 * missing keys, and the GAPs documented in ConnectorJson.kt.
 */
class ConnectorJsonTest {

    private fun fullConnector() = ConnectorStore.Connector(
        name = "gmail",
        description = "Gmail API connector",
        icon = "📧",
        authType = "oauth_pkce",
        apiKey = "",
        apiKeyHeader = "Authorization",
        apiKeyPrefix = "Bearer",
        oauth = ConnectorStore.OAuthConfig(
            authorizeUrl = "https://accounts.google.com/o/oauth2/auth",
            tokenUrl = "https://oauth2.googleapis.com/token",
            scopes = "https://mail.google.com/",
            clientId = "my-client-id",
            redirectUri = "aira://callback",
        ),
        apiBase = "https://gmail.googleapis.com",
        endpoints = listOf(
            ConnectorStore.Endpoint(
                name = "send_email",
                method = "POST",
                path = "/gmail/v1/users/me/messages/send",
                description = "Send an email",
                params = listOf("to", "subject", "body"),
                bodyTemplate = "{\"to\":\"{to}\",\"subject\":\"{subject}\",\"body\":\"{body}\"}",
            ),
            ConnectorStore.Endpoint(
                name = "list_messages",
                method = "GET",
                path = "/gmail/v1/users/me/messages",
                description = "List messages",
            ),
        ),
        accessToken = "ACCESS123",
        refreshToken = "REFRESH456",
        tokenExpiresAt = 1700000000000L,
        connected = true,
        lastError = "",
        createdAt = 1700000001000L,
    )

    private fun roundTrip(c: ConnectorStore.Connector): ConnectorStore.Connector =
        ConnectorJson.jsonToConnector(ConnectorJson.connectorToJson(c))

    // === ROUND TRIPS ===

    @Test
    fun `full oauth connector round trips losslessly`() {
        val original = fullConnector()
        val back = roundTrip(original)
        assertEquals(original, back)
    }

    @Test
    fun `minimal apikey connector round trips losslessly`() {
        val c = ConnectorStore.Connector(
            name = "home_server",
            description = "Local HA",
            authType = "apikey",
            apiKey = "secret-key",
            apiBase = "http://192.168.1.50:8123/api",
            endpoints = listOf(
                ConnectorStore.Endpoint("lights", "GET", "/lights", params = listOf("room"))
            ),
        )
        assertEquals(c, roundTrip(c))
    }

    @Test
    fun `no-auth connector round trips losslessly`() {
        val c = ConnectorStore.Connector(
            name = "public_api",
            description = "No auth",
            authType = "none",
            apiBase = "https://api.example.com",
        )
        assertEquals(c, roundTrip(c))
    }

    @Test
    fun `oauth config round trips all five fields`() {
        val back = roundTrip(fullConnector())
        val oauth = back.oauth!!
        assertEquals("https://accounts.google.com/o/oauth2/auth", oauth.authorizeUrl)
        assertEquals("https://oauth2.googleapis.com/token", oauth.tokenUrl)
        assertEquals("https://mail.google.com/", oauth.scopes)
        assertEquals("my-client-id", oauth.clientId)
        assertEquals("aira://callback", oauth.redirectUri)
    }

    @Test
    fun `endpoints round trip with params and body template`() {
        val back = roundTrip(fullConnector())
        assertEquals(2, back.endpoints.size)
        val send = back.endpoints[0]
        assertEquals("send_email", send.name)
        assertEquals("POST", send.method)
        assertEquals("/gmail/v1/users/me/messages/send", send.path)
        assertEquals(listOf("to", "subject", "body"), send.params)
        assertEquals(
            "{\"to\":\"{to}\",\"subject\":\"{subject}\",\"body\":\"{body}\"}",
            send.bodyTemplate
        )
    }

    @Test
    fun `runtime oauth tokens round trip`() {
        val back = roundTrip(fullConnector())
        assertEquals("ACCESS123", back.accessToken)
        assertEquals("REFRESH456", back.refreshToken)
        assertEquals(1700000000000L, back.tokenExpiresAt)
        assertTrue(back.connected)
    }

    @Test
    fun `unicode description round trips`() {
        val c = fullConnector().copy(description = "வணக்கம் — Gmail connector हिंदी")
        assertEquals(c.description, roundTrip(c).description)
    }

    @Test
    fun `lastError round trips`() {
        val c = fullConnector().copy(lastError = "HTTP 401: token expired")
        assertEquals("HTTP 401: token expired", roundTrip(c).lastError)
    }

    @Test
    fun `disconnected connector round trips`() {
        val c = fullConnector().copy(connected = false, accessToken = "", refreshToken = "")
        val back = roundTrip(c)
        assertFalse(back.connected)
        assertEquals(c, back)
    }

    @Test
    fun `empty endpoints list round trips`() {
        val c = fullConnector().copy(endpoints = emptyList())
        assertEquals(emptyList<ConnectorStore.Endpoint>(), roundTrip(c).endpoints)
    }

    // === JSON SHAPE ===

    @Test
    fun `serialized JSON contains all expected keys`() {
        val o = ConnectorJson.connectorToJson(fullConnector())
        val expected = listOf(
            "name", "description", "icon", "authType", "apiKey", "apiKeyHeader",
            "apiKeyPrefix", "oauth", "apiBase", "endpoints", "accessToken",
            "refreshToken", "tokenExpiresAt", "connected", "lastError", "createdAt"
        )
        expected.forEach { key -> assertTrue("missing key: $key", o.has(key)) }
    }

    @Test
    fun `no-auth connector serializes WITHOUT oauth object`() {
        val c = fullConnector().copy(oauth = null, authType = "none")
        assertFalse(ConnectorJson.connectorToJson(c).has("oauth"))
    }

    @Test
    fun `oauth object contains all five fields`() {
        val o = ConnectorJson.connectorToJson(fullConnector()).getJSONObject("oauth")
        assertEquals(5, o.length())
        assertTrue(o.has("authorizeUrl"))
        assertTrue(o.has("tokenUrl"))
        assertTrue(o.has("scopes"))
        assertTrue(o.has("clientId"))
        assertTrue(o.has("redirectUri"))
    }

    @Test
    fun `endpoint object contains all six fields`() {
        val o = ConnectorJson.connectorToJson(fullConnector())
            .getJSONArray("endpoints").getJSONObject(0)
        assertEquals(6, o.length())
        assertTrue(o.has("name"))
        assertTrue(o.has("method"))
        assertTrue(o.has("path"))
        assertTrue(o.has("description"))
        assertTrue(o.has("params"))
        assertTrue(o.has("bodyTemplate"))
    }

    // === DEFAULTS FOR MISSING KEYS (hand-edited / older files) ===

    @Test
    fun `empty JSON object yields empty connector with library defaults`() {
        val c = ConnectorJson.jsonToConnector(JSONObject())
        assertEquals("", c.name)
        assertEquals("", c.authType)
        assertEquals("🔌", c.icon)                 // library default icon
        assertEquals("Authorization", c.apiKeyHeader)
        assertEquals("Bearer", c.apiKeyPrefix)
        assertNull(c.oauth)
        assertEquals(emptyList<ConnectorStore.Endpoint>(), c.endpoints)
        assertFalse(c.connected)
    }

    @Test
    fun `missing oauth yields null oauth config`() {
        val o = ConnectorJson.connectorToJson(fullConnector())
        o.remove("oauth")
        assertNull(ConnectorJson.jsonToConnector(o).oauth)
    }

    @Test
    fun `missing endpoints yields empty list`() {
        val o = ConnectorJson.connectorToJson(fullConnector())
        o.remove("endpoints")
        assertEquals(emptyList<ConnectorStore.Endpoint>(), ConnectorJson.jsonToConnector(o).endpoints)
    }

    @Test
    fun `endpoint without params yields empty params list`() {
        val o = ConnectorJson.connectorToJson(fullConnector())
        o.getJSONArray("endpoints").getJSONObject(0).remove("params")
        val back = ConnectorJson.jsonToConnector(o)
        assertEquals(emptyList<String>(), back.endpoints[0].params)
    }

    @Test
    fun `missing apiKeyHeader defaults to Authorization`() {
        val o = ConnectorJson.connectorToJson(fullConnector())
        o.remove("apiKeyHeader")
        assertEquals("Authorization", ConnectorJson.jsonToConnector(o).apiKeyHeader)
    }

    @Test
    fun `missing apiKeyPrefix defaults to Bearer`() {
        val o = ConnectorJson.connectorToJson(fullConnector())
        o.remove("apiKeyPrefix")
        assertEquals("Bearer", ConnectorJson.jsonToConnector(o).apiKeyPrefix)
    }

    @Test
    fun `missing icon defaults to plug emoji`() {
        val o = ConnectorJson.connectorToJson(fullConnector())
        o.remove("icon")
        assertEquals("🔌", ConnectorJson.jsonToConnector(o).icon)
    }

    @Test
    fun `oauth without redirectUri defaults to aira callback`() {
        val oauthJson = ConnectorJson.connectorToJson(fullConnector()).getJSONObject("oauth")
        oauthJson.remove("redirectUri")
        val o = ConnectorJson.connectorToJson(fullConnector())
        o.put("oauth", oauthJson)
        assertEquals(
            ConnectorStore.REDIRECT_URI,
            ConnectorJson.jsonToConnector(o).oauth?.redirectUri
        )
    }

    @Test
    fun `GAP-1 missing createdAt yields zero epoch`() {
        // DOCUMENTS BUG: hand-edited file without createdAt → epoch 1970
        val o = ConnectorJson.connectorToJson(fullConnector())
        o.remove("createdAt")
        assertEquals(0L, ConnectorJson.jsonToConnector(o).createdAt)
    }

    // === NAMESPACE EDGE CASES ===

    @Test
    fun `GAP-3 connector name containing __ round trips but is ambiguous`() {
        // DOCUMENTS BUG FAMILY (same as McpNamespaceParser): "my__gmail" would
        // split as connector "my" + endpoint "gmail" in resolveTool.
        val c = fullConnector().copy(name = "my__gmail")
        assertEquals("my__gmail", roundTrip(c).name)
    }

    @Test
    fun `connector with zero endpoints still round trips`() {
        val c = ConnectorStore.Connector(
            name = "shell", description = "placeholder", authType = "none"
        )
        assertEquals(c, roundTrip(c))
    }

    @Test
    fun `token expiry timestamp survives JSON as a long`() {
        val c = fullConnector().copy(tokenExpiresAt = Long.MAX_VALUE)
        assertEquals(Long.MAX_VALUE, roundTrip(c).tokenExpiresAt)
    }
}
