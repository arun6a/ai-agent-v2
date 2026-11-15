package com.ai.agent.tools.custom

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * v4.0.88: ConnectorExecutor — runs OAuth PKCE flows + API calls for AI-created connectors.
 *
 * Two main entry points:
 *   - startOAuthFlow(): opens browser for user to authorize a connector
 *   - callEndpoint(): makes an authenticated API call to a connector's endpoint
 *
 * The OAuth callback is caught by OAuthCallbackActivity (via aira://callback deep link),
 * which calls completeOAuthFlow() to finish the token exchange.
 */
object ConnectorExecutor {
    private const val TAG = "ConnectorExecutor"
    private const val OAUTH_TIMEOUT_MS = 300000L  // 5 minutes

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** Pending OAuth flows keyed by state parameter (for CSRF + routing). */
    private val pendingFlows = mutableMapOf<String, PendingFlow>()

    private data class PendingFlow(
        val connectorName: String,
        val codeVerifier: String,
        val deferred: CompletableDeferred<Boolean>,
    )

    /**
     * Start the OAuth PKCE flow for a connector.
     * Opens the browser for the user to authorize. Returns true on success.
     */
    suspend fun startOAuthFlow(context: Context, connector: ConnectorStore.Connector): Boolean {
        val oauth = connector.oauth ?: run {
            Log.w(TAG, "${connector.name} has no OAuth config")
            return false
        }

        val verifier = generateCodeVerifier()
        val challenge = generateCodeChallenge(verifier)
        val state = generateState()

        val deferred = CompletableDeferred<Boolean>()
        pendingFlows[state] = PendingFlow(connector.name, verifier, deferred)

        // Build the authorize URL with PKCE + state
        val authUrl = buildString {
            append(oauth.authorizeUrl)
            append("?response_type=code")
            append("&client_id=").append(URLEncoder.encode(oauth.clientId, "UTF-8"))
            append("&redirect_uri=").append(URLEncoder.encode(oauth.redirectUri, "UTF-8"))
            append("&code_challenge=").append(challenge)
            append("&code_challenge_method=S256")
            if (oauth.scopes.isNotEmpty()) {
                append("&scope=").append(URLEncoder.encode(oauth.scopes, "UTF-8"))
            }
            append("&state=").append(state)
        }

        Log.i(TAG, "Opening browser for OAuth: ${connector.name}")
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(authUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "No browser available: ${e.message}")
            pendingFlows.remove(state)
            return false
        }

        // Wait for OAuthCallbackActivity to complete the flow
        val success = withTimeoutOrNull(OAUTH_TIMEOUT_MS) { deferred.await() } ?: false
        pendingFlows.remove(state)
        return success
    }

    /**
     * Called by OAuthCallbackActivity when the browser redirects to aira://callback.
     * Exchanges the authorization code for access + refresh tokens.
     */
    fun handleCallback(context: Context, code: String?, state: String?, error: String?) {
        if (state == null) {
            Log.w(TAG, "OAuth callback missing state parameter")
            return
        }
        val pending = pendingFlows[state]
        if (pending == null) {
            Log.w(TAG, "OAuth callback for unknown state: $state")
            return
        }

        if (error != null) {
            Log.w(TAG, "OAuth error: $error")
            pending.deferred.complete(false)
            return
        }
        if (code == null) {
            Log.w(TAG, "OAuth callback missing code")
            pending.deferred.complete(false)
            return
        }

        // Exchange code for token in background
        val connector = ConnectorStore.get(context, pending.connectorName)
        if (connector == null || connector.oauth == null) {
            pending.deferred.complete(false)
            return
        }

        Thread {
            val tokens = exchangeCodeForToken(
                connector.oauth!!.tokenUrl,
                connector.oauth!!.clientId,
                code,
                pending.codeVerifier,
                connector.oauth!!.redirectUri,
            )
            if (tokens != null) {
                ConnectorStore.updateTokens(
                    context, pending.connectorName,
                    accessToken = tokens.accessToken,
                    refreshToken = tokens.refreshToken,
                    expiresIn = tokens.expiresIn,
                )
                pending.deferred.complete(true)
                Log.i(TAG, "✅ OAuth success: ${pending.connectorName}")
            } else {
                pending.deferred.complete(false)
                Log.w(TAG, "❌ Token exchange failed: ${pending.connectorName}")
            }
        }.start()
    }

    private data class TokenResponse(
        val accessToken: String,
        val refreshToken: String,
        val expiresIn: Long,
    )

    private fun exchangeCodeForToken(
        tokenUrl: String,
        clientId: String,
        code: String,
        verifier: String,
        redirectUri: String,
    ): TokenResponse? {
        return try {
            val body = buildString {
                append("grant_type=authorization_code")
                append("&code=").append(URLEncoder.encode(code, "UTF-8"))
                append("&code_verifier=").append(URLEncoder.encode(verifier, "UTF-8"))
                append("&redirect_uri=").append(URLEncoder.encode(redirectUri, "UTF-8"))
                append("&client_id=").append(URLEncoder.encode(clientId, "UTF-8"))
            }
            val request = Request.Builder()
                .url(tokenUrl)
                .post(body.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                .build()
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "Token exchange failed: ${response.code}")
                return null
            }
            val json = JSONObject(response.body?.string() ?: "")
            TokenResponse(
                accessToken = json.optString("access_token", ""),
                refreshToken = json.optString("refresh_token", "").takeIf { it.isNotEmpty() } ?: "",
                expiresIn = json.optLong("expires_in", 3600),
            )
        } catch (e: Exception) {
            Log.e(TAG, "Token exchange error: ${e.message}")
            null
        }
    }

    /**
     * Call an endpoint on a connector. Handles auth header (API key or Bearer token).
     * Substitutes {param} placeholders in path and body.
     */
    suspend fun callEndpoint(
        context: Context,
        connector: ConnectorStore.Connector,
        endpoint: ConnectorStore.Endpoint,
        args: Map<String, Any>,
    ): ConnectorResult {
        // Check auth state
        if (connector.authType == "oauth_pkce" && !connector.connected) {
            return ConnectorResult(false, "${connector.name} is not connected. Tell the user to connect it first.")
        }

        // Build the full URL with param substitution
        var path = endpoint.path
        for ((k, v) in args) {
            path = path.replace("{$k}", URLEncoder.encode(v.toString(), "UTF-8"))
        }
        val fullUrl = connector.apiBase.trimEnd('/') + "/" + path.trimStart('/')

        // Build the request
        val requestBuilder = Request.Builder().url(fullUrl)

        // Add auth header
        when (connector.authType) {
            "apikey" -> {
                val headerValue = if (connector.apiKeyPrefix.isNotEmpty()) {
                    "${connector.apiKeyPrefix} ${connector.apiKey}"
                } else {
                    connector.apiKey
                }
                requestBuilder.header(connector.apiKeyHeader, headerValue)
            }
            "oauth_pkce" -> {
                // Refresh token if expired
                val token = ensureValidToken(context, connector)
                if (token == null) {
                    return ConnectorResult(false, "${connector.name} token expired and refresh failed. Reconnect needed.")
                }
                requestBuilder.header("Authorization", "Bearer $token")
            }
            "none" -> { /* no auth */ }
        }

        // Add body for POST/PUT
        when (endpoint.method.uppercase()) {
            "GET", "DELETE" -> {
                // Query params from args not in path
                val extraParams = args.filterKeys { k -> !endpoint.path.contains("{$k}") }
                if (extraParams.isNotEmpty()) {
                    val queryString = extraParams.entries.joinToString("&") {
                        "${it.key}=${URLEncoder.encode(it.value.toString(), "UTF-8")}"
                    }
                    requestBuilder.url("$fullUrl?$queryString")
                }
                if (endpoint.method.uppercase() == "GET") requestBuilder.get()
                else requestBuilder.delete()
            }
            "POST", "PUT", "PATCH" -> {
                var body = endpoint.bodyTemplate
                for ((k, v) in args) {
                    body = body.replace("{$k}", v.toString())
                }
                if (body.isEmpty()) body = "{}"
                requestBuilder.method(endpoint.method, body.toRequestBody("application/json".toMediaType()))
            }
        }

        return withContext(Dispatchers.IO) {
            try {
                val response = httpClient.newCall(requestBuilder.build()).execute()
                val responseBody = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    ConnectorResult(true, responseBody.take(2000))
                } else {
                    ConnectorResult(false, "HTTP ${response.code}: ${responseBody.take(500)}")
                }
            } catch (e: Exception) {
                ConnectorResult(false, "Request failed: ${e.message}")
            }
        }
    }

    /** Refresh the access token if it's expired. Returns the valid access token. */
    private suspend fun ensureValidToken(
        context: Context,
        connector: ConnectorStore.Connector,
    ): String? {
        // Still valid?
        if (System.currentTimeMillis() < connector.tokenExpiresAt - 60000) {
            return connector.accessToken
        }
        // Need to refresh
        val oauth = connector.oauth ?: return null
        if (connector.refreshToken.isEmpty()) return null

        return withContext(Dispatchers.IO) {
            try {
                val body = buildString {
                    append("grant_type=refresh_token")
                    append("&refresh_token=").append(URLEncoder.encode(connector.refreshToken, "UTF-8"))
                    append("&client_id=").append(URLEncoder.encode(oauth.clientId, "UTF-8"))
                }
                val request = Request.Builder()
                    .url(oauth.tokenUrl)
                    .post(body.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                    .build()
                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) return@withContext null

                val json = JSONObject(response.body?.string() ?: "")
                val newAccess = json.optString("access_token", "")
                if (newAccess.isEmpty()) return@withContext null

                val expiresIn = json.optLong("expires_in", 3600)
                val newRefresh = json.optString("refresh_token", "").ifEmpty { connector.refreshToken }

                ConnectorStore.updateTokens(
                    context, connector.name,
                    accessToken = newAccess,
                    refreshToken = newRefresh,
                    expiresIn = expiresIn,
                )
                newAccess
            } catch (e: Exception) {
                Log.e(TAG, "Token refresh failed: ${e.message}")
                null
            }
        }
    }

    data class ConnectorResult(val success: Boolean, val output: String)

    // ---- PKCE helpers ----

    private fun generateCodeVerifier(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun generateCodeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    private fun generateState(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
