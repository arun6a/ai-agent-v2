package com.ai.agent.util

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * v4.0.99-collab3: ConnectorUrlBuilder — pure URL/PKCE/body logic extracted
 * from ConnectorExecutor (v4.0.88) so the Connectors system can be unit-tested
 * on the JVM. ConnectorExecutor.callEndpoint() / startOAuthFlow() should
 * delegate here (wiring GAP below).
 *
 * Covers:
 *   - PKCE (RFC 7636): verifier generation, S256 challenge, OAuth state
 *   - OAuth authorize URL construction
 *   - Token request bodies (authorization_code + refresh_token grants)
 *   - Endpoint URL building: {param} path substitution, query-string building,
 *     body template rendering, apiBase joining
 *   - API-key header value composition
 *   - Token expiry check (60s clock-skew guard)
 *
 * ===== DOCUMENTED BUGS / GAPS (found while extracting — for the other AI) =====
 *
 * GAP-1 (HIGH — real bug): renderBodyTemplate does NOT JSON-escape values.
 *     bodyTemplate `{"msg":"{message}"}` with message `He said "hi"` produces
 *     `{"msg":"He said "hi""}` → invalid JSON → connector API returns 400.
 *     FIX: escape \ " and control chars before substitution (or build the JSON
 *     with org.json and put() the values).
 *
 * GAP-2 (MEDIUM): leftover {placeholders} are never removed.
 *     If an arg is missing, the literal `{param}` stays in the URL/body and is
 *     sent to the API. FIX: after substitution, strip remaining `\{[a-zA-Z0-9_]+\}`
 *     or return a validation error listing missing params.
 *
 * GAP-3 (MEDIUM): path substitution uses URLEncoder.encode (form encoding).
 *     Space becomes "+" instead of "%20", "/" becomes "%2F". For path SEGMENTS
 *     this is wrong per RFC 3986 (should be Uri.encode / percent-encoding).
 *     Most servers tolerate it; strict ones 404.
 *
 * GAP-4 (LOW): buildAuthorizeUrl appends "?" blindly — if authorizeUrl already
 *     contains a query (some providers pre-fill prompt=consent), the result has
 *     two "?" and the second one lands inside param values. FIX: detect
 *     existing "?" and join with "&".
 *
 * GAP-5 (LOW): buildEndpointUrl with empty apiBase yields "/path..." — an
 *     invalid URL that makes OkHttp Request.Builder throw in callEndpoint.
 *     FIX: validate apiBase non-empty when adding a connector with endpoints.
 *
 * GAP-6 (INFO): query-string keys are not URL-encoded (values are). Keys come
 *     from tool args so they are usually safe identifiers.
 */
object ConnectorUrlBuilder {

    // ---------- PKCE (RFC 7636) ----------

    /** 32 random bytes → 43-char base64url string (no padding). */
    fun generateCodeVerifier(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** S256 challenge = BASE64URL(SHA256(verifier)). RFC 7636 Appendix B compatible. */
    fun generateCodeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    /** 16 random bytes → 22-char base64url state token. */
    fun generateState(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    // ---------- OAuth URLs ----------

    /** Build the authorize URL exactly like ConnectorExecutor.startOAuthFlow. */
    fun buildAuthorizeUrl(
        authorizeUrl: String,
        clientId: String,
        redirectUri: String,
        codeChallenge: String,
        scopes: String,
        state: String,
    ): String = buildString {
        append(authorizeUrl)
        append("?response_type=code")
        append("&client_id=").append(encode(clientId))
        append("&redirect_uri=").append(encode(redirectUri))
        append("&code_challenge=").append(codeChallenge)
        append("&code_challenge_method=S256")
        if (scopes.isNotEmpty()) {
            append("&scope=").append(encode(scopes))
        }
        append("&state=").append(state)
    }

    /** Body for the authorization_code token exchange. */
    fun buildAuthorizationCodeBody(
        code: String,
        verifier: String,
        redirectUri: String,
        clientId: String,
    ): String = buildString {
        append("grant_type=authorization_code")
        append("&code=").append(encode(code))
        append("&code_verifier=").append(encode(verifier))
        append("&redirect_uri=").append(encode(redirectUri))
        append("&client_id=").append(encode(clientId))
    }

    /** Body for the refresh_token grant (ConnectorExecutor.ensureValidToken). */
    fun buildRefreshTokenBody(refreshToken: String, clientId: String): String = buildString {
        append("grant_type=refresh_token")
        append("&refresh_token=").append(encode(refreshToken))
        append("&client_id=").append(encode(clientId))
    }

    // ---------- Endpoint calls ----------

    /** Substitute {param} placeholders in a path template (URL-encoded values). */
    fun substitutePath(pathTemplate: String, args: Map<String, Any>): String {
        var path = pathTemplate
        for ((k, v) in args) {
            path = path.replace("{$k}", encode(v.toString()))
        }
        return path
    }

    /** Join apiBase + path the same way callEndpoint does. */
    fun buildEndpointUrl(apiBase: String, path: String): String =
        apiBase.trimEnd('/') + "/" + path.trimStart('/')

    /**
     * Query string for GET/DELETE: args NOT already in the path template.
     * Returns null when there is nothing to append (matches callEndpoint's
     * `if (extraParams.isNotEmpty())` guard).
     */
    fun buildQueryString(args: Map<String, Any>, pathTemplate: String): String? {
        val extraParams = args.filterKeys { k -> !pathTemplate.contains("{$k}") }
        if (extraParams.isEmpty()) return null
        return extraParams.entries.joinToString("&") { "${it.key}=${encode(it.value.toString())}" }
    }

    /**
     * Render a JSON body template by replacing {param} placeholders.
     * v4.0.100: Values are now JSON-escaped before substitution (GAP-1 fix).
     * Also removes leftover {placeholders} that have no matching arg (GAP-2 fix).
     */
    fun renderBodyTemplate(template: String, args: Map<String, Any>): String {
        var body = template
        // v4.0.100: GAP-1 fix — JSON-escape values before substitution
        for ((k, v) in args) {
            val escaped = jsonEscape(v.toString())
            body = body.replace("{$k}", escaped)
        }
        // v4.0.100: GAP-2 fix — remove any leftover {placeholders}
        body = body.replace(Regex("\\{[a-zA-Z0-9_]+\\}"), "")
        return body
    }

    /** v4.0.100: Escape a string for safe inclusion in a JSON value. */
    private fun jsonEscape(s: String): String {
        val sb = StringBuilder()
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (ch.code < 0x20) sb.append("\\u${ch.code.toString(16).padStart(4, '0')}") else sb.append(ch)
            }
        }
        return sb.toString()
    }

    /** API-key header value: "prefix key" when prefix is set, else the raw key. */
    fun headerValueForApiKey(prefix: String, key: String): String =
        if (prefix.isNotEmpty()) "$prefix $key" else key

    /**
     * Token expiry with 60s clock-skew guard (callEndpoint refreshes when
     * `now >= expiresAt - 60s`).
     */
    fun isTokenExpired(tokenExpiresAt: Long, nowMs: Long): Boolean =
        nowMs >= tokenExpiresAt - 60_000

    /** UTF-8 form encoding, as used everywhere in ConnectorExecutor. */
    private fun encode(v: String): String = java.net.URLEncoder.encode(v, "UTF-8")
}
