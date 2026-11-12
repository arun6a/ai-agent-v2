package com.ai.agent.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for ConnectorUrlBuilder — pure URL/PKCE/body logic extracted from
 * ConnectorExecutor (v4.0.88). Includes the official RFC 7636 Appendix B
 * test vector for the S256 code challenge.
 *
 * Tests marked GAP-N assert the CURRENT (buggy) behavior they document —
 * see ConnectorUrlBuilder.kt header.
 */
class ConnectorUrlBuilderTest {

    // === PKCE — RFC 7636 ===

    @Test
    fun `code challenge matches RFC 7636 Appendix B vector`() {
        // Official test vector from RFC 7636 Appendix B
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        val challenge = ConnectorUrlBuilder.generateCodeChallenge(verifier)
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", challenge)
    }

    @Test
    fun `code challenge is deterministic`() {
        val v = ConnectorUrlBuilder.generateCodeVerifier()
        assertEquals(
            ConnectorUrlBuilder.generateCodeChallenge(v),
            ConnectorUrlBuilder.generateCodeChallenge(v)
        )
    }

    @Test
    fun `different verifiers give different challenges`() {
        val v1 = ConnectorUrlBuilder.generateCodeVerifier()
        val v2 = ConnectorUrlBuilder.generateCodeVerifier()
        assertTrue(ConnectorUrlBuilder.generateCodeChallenge(v1) != ConnectorUrlBuilder.generateCodeChallenge(v2))
    }

    @Test
    fun `code verifier is 43 chars`() {
        // 32 random bytes → base64url without padding → exactly 43 chars
        repeat(10) {
            assertEquals(43, ConnectorUrlBuilder.generateCodeVerifier().length)
        }
    }

    @Test
    fun `code verifier uses base64url charset only`() {
        repeat(10) {
            val v = ConnectorUrlBuilder.generateCodeVerifier()
            assertTrue(v.matches(Regex("^[A-Za-z0-9_-]+$")))
        }
    }

    @Test
    fun `code verifiers are unique across 100 generations`() {
        val set = mutableSetOf<String>()
        repeat(100) { set.add(ConnectorUrlBuilder.generateCodeVerifier()) }
        assertEquals(100, set.size)
    }

    @Test
    fun `oauth state is 22 chars with base64url charset`() {
        // 16 random bytes → 22 base64url chars
        repeat(10) {
            val s = ConnectorUrlBuilder.generateState()
            assertEquals(22, s.length)
            assertTrue(s.matches(Regex("^[A-Za-z0-9_-]+$")))
        }
    }

    @Test
    fun `oauth states are unique across 100 generations`() {
        val set = mutableSetOf<String>()
        repeat(100) { set.add(ConnectorUrlBuilder.generateState()) }
        assertEquals(100, set.size)
    }

    // === AUTHORIZE URL ===

    @Test
    fun `authorize URL contains all required PKCE params`() {
        val url = ConnectorUrlBuilder.buildAuthorizeUrl(
            authorizeUrl = "https://auth.example.com/authorize",
            clientId = "my_app",
            redirectUri = "aira://callback",
            codeChallenge = "CHALLENGE123",
            scopes = "read write",
            state = "STATE789",
        )
        assertTrue(url.startsWith("https://auth.example.com/authorize?response_type=code"))
        assertTrue(url.contains("client_id=my_app"))
        assertTrue(url.contains("redirect_uri=aira%3A%2F%2Fcallback"))
        assertTrue(url.contains("code_challenge=CHALLENGE123"))
        assertTrue(url.contains("code_challenge_method=S256"))
        assertTrue(url.contains("scope=read+write"))
        assertTrue(url.contains("state=STATE789"))
    }

    @Test
    fun `authorize URL omits scope when empty`() {
        val url = ConnectorUrlBuilder.buildAuthorizeUrl(
            "https://a.com/authorize", "app", "aira://callback", "CH", "", "ST"
        )
        assertFalse(url.contains("scope="))
    }

    @Test
    fun `authorize URL encodes spaces as plus`() {
        val url = ConnectorUrlBuilder.buildAuthorizeUrl(
            "https://a.com/authorize", "my app", "aira://callback", "CH", "", "ST"
        )
        assertTrue(url.contains("client_id=my+app"))
    }

    @Test
    fun `GAP-4 authorize URL with existing query gets double question mark`() {
        // DOCUMENTS BUG: some providers pre-fill query params on the authorize URL.
        val url = ConnectorUrlBuilder.buildAuthorizeUrl(
            "https://a.com/authorize?prompt=consent", "app", "aira://callback", "CH", "", "ST"
        )
        assertTrue(url.contains("?prompt=consent?response_type=code"))
    }

    // === TOKEN REQUEST BODIES ===

    @Test
    fun `authorization code body has all required grant params`() {
        val body = ConnectorUrlBuilder.buildAuthorizationCodeBody(
            code = "ABC123",
            verifier = "VERIFIER43",
            redirectUri = "aira://callback",
            clientId = "app",
        )
        assertEquals(
            "grant_type=authorization_code&code=ABC123&code_verifier=VERIFIER43" +
                "&redirect_uri=aira%3A%2F%2Fcallback&client_id=app",
            body
        )
    }

    @Test
    fun `authorization code body encodes special chars`() {
        val body = ConnectorUrlBuilder.buildAuthorizationCodeBody(
            "a b/c+d", "v", "aira://callback", "app"
        )
        assertTrue(body.contains("code=a+b%2Fc%2Bd"))
    }

    @Test
    fun `refresh token body has correct grant type`() {
        val body = ConnectorUrlBuilder.buildRefreshTokenBody("REFRESH1", "app")
        assertEquals("grant_type=refresh_token&refresh_token=REFRESH1&client_id=app", body)
    }

    // === PATH SUBSTITUTION ===

    @Test
    fun `path substitution replaces all params`() {
        val path = ConnectorUrlBuilder.substitutePath(
            "/gmail/v1/{userId}/messages/{id}",
            mapOf("userId" to "me", "id" to "abc123")
        )
        assertEquals("/gmail/v1/me/messages/abc123", path)
    }

    @Test
    fun `path substitution encodes space as plus - GAP-3`() {
        // DOCUMENTS BUG: URLEncoder is form-encoding (+) not path-encoding (%20)
        val path = ConnectorUrlBuilder.substitutePath(
            "/users/{name}", mapOf("name" to "john doe")
        )
        assertEquals("/users/john+doe", path)
    }

    @Test
    fun `path substitution encodes slash`() {
        val path = ConnectorUrlBuilder.substitutePath(
            "/files/{path}", mapOf("path" to "a/b")
        )
        assertEquals("/files/a%2Fb", path)
    }

    @Test
    fun `path substitution leaves missing param untouched - GAP-2`() {
        // DOCUMENTS BUG: literal {missing} is sent to the API
        val path = ConnectorUrlBuilder.substitutePath("/x/{missing}", emptyMap())
        assertEquals("/x/{missing}", path)
    }

    @Test
    fun `path substitution handles param names that are prefixes of others`() {
        val path = ConnectorUrlBuilder.substitutePath(
            "/a/{id}/{idx}", mapOf("id" to "1", "idx" to "2")
        )
        assertEquals("/a/1/2", path)
    }

    @Test
    fun `path substitution encodes unicode values`() {
        val path = ConnectorUrlBuilder.substitutePath("/search/{q}", mapOf("q" to "हैलो"))
        assertTrue(path.startsWith("/search/%E0%A4%B9"))
    }

    @Test
    fun `path substitution renders Int args`() {
        val path = ConnectorUrlBuilder.substitutePath("/items/{n}", mapOf("n" to 42))
        assertEquals("/items/42", path)
    }

    @Test
    fun `path substitution with empty template unchanged`() {
        assertEquals("/", ConnectorUrlBuilder.substitutePath("/", emptyMap()))
    }

    // === ENDPOINT URL JOINING ===

    @Test
    fun `endpoint url joins base and path`() {
        assertEquals(
            "https://api.x.com/v1/users",
            ConnectorUrlBuilder.buildEndpointUrl("https://api.x.com", "/v1/users")
        )
    }

    @Test
    fun `endpoint url tolerates trailing slash on base`() {
        assertEquals(
            "https://api.x.com/v1/users",
            ConnectorUrlBuilder.buildEndpointUrl("https://api.x.com/", "v1/users")
        )
    }

    @Test
    fun `endpoint url tolerates slashes on both sides`() {
        assertEquals(
            "https://api.x.com/v1/users",
            ConnectorUrlBuilder.buildEndpointUrl("https://api.x.com/", "/v1/users")
        )
    }

    @Test
    fun `endpoint url keeps internal double slashes in path`() {
        assertEquals(
            "https://api.x.com/v1//users",
            ConnectorUrlBuilder.buildEndpointUrl("https://api.x.com///", "//v1//users")
        )
    }

    @Test
    fun `GAP-5 empty apiBase produces relative URL`() {
        // DOCUMENTS BUG: "/v1" is not a valid URL for OkHttp Request.Builder
        assertEquals("/v1", ConnectorUrlBuilder.buildEndpointUrl("", "/v1"))
    }

    @Test
    fun `endpoint url with root path`() {
        assertEquals(
            "https://api.x.com/",
            ConnectorUrlBuilder.buildEndpointUrl("https://api.x.com", "/")
        )
    }

    // === QUERY STRING (GET/DELETE extra params) ===

    @Test
    fun `query string returns null when all args are in path`() {
        assertNull(ConnectorUrlBuilder.buildQueryString(mapOf("id" to "7"), "/items/{id}"))
    }

    @Test
    fun `query string returns null for no args`() {
        assertNull(ConnectorUrlBuilder.buildQueryString(emptyMap(), "/items"))
    }

    @Test
    fun `query string encodes values`() {
        val qs = ConnectorUrlBuilder.buildQueryString(
            linkedMapOf("q" to "ai agents", "limit" to 50), "/search"
        )
        assertEquals("q=ai+agents&limit=50", qs)
    }

    @Test
    fun `query string includes only args missing from path template`() {
        val qs = ConnectorUrlBuilder.buildQueryString(
            linkedMapOf("id" to "7", "q" to "x y"), "/items/{id}"
        )
        assertEquals("q=x+y", qs)
    }

    @Test
    fun `GAP-6 query string does not encode keys`() {
        // DOCUMENTS BUG: key keeps its raw space (values are encoded, keys not)
        val qs = ConnectorUrlBuilder.buildQueryString(mapOf("a b" to "1"), "/search")
        assertEquals("a b=1", qs)
    }

    // === BODY TEMPLATE ===

    @Test
    fun `body template substitutes string value`() {
        assertEquals(
            "{\"msg\":\"hi\"}",
            ConnectorUrlBuilder.renderBodyTemplate("{\"msg\":\"{message}\"}", mapOf("message" to "hi"))
        )
    }

    @Test
    fun `body template substitutes multiple values`() {
        val body = ConnectorUrlBuilder.renderBodyTemplate(
            "{\"to\":\"{to}\",\"subject\":\"{subj}\"}",
            linkedMapOf("to" to "a@b.c", "subj" to "Hello")
        )
        assertEquals("{\"to\":\"a@b.c\",\"subject\":\"Hello\"}", body)
    }

    @Test
    fun `body template substitutes Int values`() {
        assertEquals(
            "{\"count\":42}",
            ConnectorUrlBuilder.renderBodyTemplate("{\"count\":{n}}", mapOf("n" to 42))
        )
    }

    @Test
    fun `body template removes missing params - FIXED v4 0 100 (GAP-2)`() {
        // v4.0.100: Leftover {placeholders} are now stripped.
        assertEquals(
            "{\"a\":\"\"}",
            ConnectorUrlBuilder.renderBodyTemplate("{\"a\":\"{x}\"}", emptyMap())
        )
    }

    @Test
    fun `GAP-1 body template JSON-escapes quotes - FIXED v4 0 100`() {
        // v4.0.100: Values are now JSON-escaped before substitution.
        val body = ConnectorUrlBuilder.renderBodyTemplate(
            "{\"msg\":\"{message}\"}", mapOf("message" to "He said \"hi\"")
        )
        assertEquals("{\"msg\":\"He said \\\"hi\\\"\"}", body)
    }

    @Test
    fun `body template empty template stays empty`() {
        assertEquals("", ConnectorUrlBuilder.renderBodyTemplate("", mapOf("a" to "b")))
    }

    @Test
    fun `body template leaves unmatched braces alone`() {
        assertEquals("{}", ConnectorUrlBuilder.renderBodyTemplate("{}", mapOf("a" to "b")))
    }

    // === API KEY HEADER ===

    @Test
    fun `api key header combines prefix and key`() {
        assertEquals("Bearer sk-123", ConnectorUrlBuilder.headerValueForApiKey("Bearer", "sk-123"))
    }

    @Test
    fun `api key header with empty prefix returns raw key`() {
        assertEquals("sk-123", ConnectorUrlBuilder.headerValueForApiKey("", "sk-123"))
    }

    @Test
    fun `api key header supports custom prefix`() {
        assertEquals("Token abc", ConnectorUrlBuilder.headerValueForApiKey("Token", "abc"))
    }

    @Test
    fun `api key header with empty key still has prefix`() {
        assertEquals("Bearer ", ConnectorUrlBuilder.headerValueForApiKey("Bearer", ""))
    }

    // === TOKEN EXPIRY (60s clock-skew guard) ===

    @Test
    fun `token valid when more than 60s remain`() {
        assertFalse(ConnectorUrlBuilder.isTokenExpired(tokenExpiresAt = 100_000L, nowMs = 39_000L))
    }

    @Test
    fun `token refreshes at exactly 60s remaining`() {
        // now == expiresAt - 60000 → expired (refresh triggered)
        assertTrue(ConnectorUrlBuilder.isTokenExpired(tokenExpiresAt = 100_000L, nowMs = 40_000L))
    }

    @Test
    fun `token refreshes just under 60s remaining`() {
        assertTrue(ConnectorUrlBuilder.isTokenExpired(tokenExpiresAt = 100_000L, nowMs = 41_000L))
    }

    @Test
    fun `token expired at exact expiry time`() {
        assertTrue(ConnectorUrlBuilder.isTokenExpired(tokenExpiresAt = 100_000L, nowMs = 100_000L))
    }

    @Test
    fun `token expired well after expiry`() {
        assertTrue(ConnectorUrlBuilder.isTokenExpired(tokenExpiresAt = 100_000L, nowMs = 200_000L))
    }

    @Test
    fun `zero expiry timestamp is always expired`() {
        assertTrue(ConnectorUrlBuilder.isTokenExpired(tokenExpiresAt = 0L, nowMs = 1_000L))
    }

    @Test
    fun `fresh token with one hour left is valid`() {
        assertFalse(ConnectorUrlBuilder.isTokenExpired(tokenExpiresAt = 3_600_000L, nowMs = 0L))
    }
}
