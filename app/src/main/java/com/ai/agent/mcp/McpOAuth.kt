package com.ai.agent.mcp

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
import org.json.JSONArray
import org.json.JSONObject
import java.net.ServerSocket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * v4.0.80: MCP OAuth 2.1 + PKCE + Dynamic Client Registration.
 *
 * Flow (RFC 7591 DCR + RFC 7636 PKCE):
 * 1. Discover OAuth metadata (RFC 9728)
 * 2. Dynamic Client Registration (get client_id)
 * 3. Generate PKCE verifier + challenge
 * 4. Open browser to authorize URL
 * 5. User authorizes → redirect to localhost callback
 * 6. Capture authorization code
 * 7. Exchange code for tokens (access + refresh)
 * 8. Save tokens to config
 */
object McpOAuth {
    private const val TAG = "McpOAuth"
    private const val CALLBACK_PORT = 8765
    private const val CALLBACK_PATH = "/callback"
    private const val TIMEOUT_MS = 300000L

    /**
     * v4.0.89: Known OAuth providers — hardcoded endpoints for services that
     * don't expose /.well-known/oauth-authorization-server.
     *
     * When RFC 9728 discovery finds an authorization_server URL that's in this
     * map, we use these endpoints directly instead of failing.
     *
     * These providers also DON'T support DCR (Dynamic Client Registration), so
     * the user must provide a client_id (register an OAuth app with the provider).
     * Keyed by the authorization_server URL from resource_metadata.
     */
    private val KNOWN_PROVIDERS: Map<String, OAuthMetadata> = mapOf(
        // GitHub MCP → authorization_servers: ["https://github.com/login/oauth"]
        "https://github.com/login/oauth" to OAuthMetadata(
            authorizationEndpoint = "https://github.com/login/oauth/authorize",
            tokenEndpoint = "https://github.com/login/oauth/access_token",
            registrationEndpoint = null,  // GitHub doesn't support DCR
        ),
    )

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    data class OAuthMetadata(
        val authorizationEndpoint: String,
        val tokenEndpoint: String,
        val registrationEndpoint: String?,
    )

    data class TokenResponse(
        val accessToken: String,
        val refreshToken: String?,
        val expiresIn: Long,
    )

    /**
     * Run the full OAuth flow for an MCP server.
     */
    suspend fun startPairingFlow(context: Context, config: McpServerConfig): Boolean {
        Log.i(TAG, "Starting OAuth flow for ${config.name} (${config.url})")

        val metadata = discoverMetadata(config.url)
        if (metadata == null) {
            config.lastError = "No OAuth metadata found at ${config.url}"
            Log.w(TAG, config.lastError)
            return false
        }

        val clientId = if (metadata.registrationEndpoint != null) {
            // DCR supported — auto-register a client
            dynamicRegister(metadata.registrationEndpoint, config.name)
        } else {
            // v4.0.89: No DCR — use the user-provided client_id (if set on the config).
            // For providers like GitHub that don't support DCR, the user must register
            // an OAuth app with the provider and pass the client_id via the config.
            if (config.clientId.isNotEmpty()) {
                config.clientId
            } else {
                config.lastError = "This server doesn't support auto-registration (DCR). " +
                    "Register an OAuth app with the provider and pass the client_id. " +
                    "For GitHub: github.com/settings/developers → New OAuth App → " +
                    "redirect URI = http://localhost:$CALLBACK_PORT$CALLBACK_PATH"
                Log.w(TAG, config.lastError)
                return false
            }
        }

        val verifier = generateCodeVerifier()
        val challenge = generateCodeChallenge(verifier)

        val callbackCode = CompletableDeferred<String>()
        val callbackServer = startCallbackListener(callbackCode)

        val redirectUri = "http://localhost:$CALLBACK_PORT$CALLBACK_PATH"
        // v4.0.86: FIX — Add OAuth state parameter for CSRF protection (RFC 6749 §10.12).
        val state = Base64.getUrlEncoder().withoutPadding().encodeToString(
            ByteArray(16).also { SecureRandom().nextBytes(it) }
        )
        val authUrl = buildAuthUrl(metadata.authorizationEndpoint, clientId, challenge, redirectUri, state)

        Log.i(TAG, "Opening browser for OAuth: ${authUrl.take(100)}...")
        try {
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(authUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(browserIntent)
        } catch (e: Exception) {
            Log.e(TAG, "No browser available: ${e.message}")
            callbackServer?.close()
            config.lastError = "No browser app available for OAuth"
            return false
        }

        val code = withTimeoutOrNull(TIMEOUT_MS) { callbackCode.await() }
        callbackServer?.close()

        if (code == null) {
            config.lastError = "OAuth timed out — user didn't authorize in 5 minutes"
            return false
        }

        val tokens = exchangeCodeForToken(metadata.tokenEndpoint, clientId, code, verifier, redirectUri)
        if (tokens == null) {
            config.lastError = "Token exchange failed"
            return false
        }

        config.accessToken = tokens.accessToken
        config.refreshToken = tokens.refreshToken ?: ""
        config.tokenExpiresAt = System.currentTimeMillis() + (tokens.expiresIn * 1000)
        config.clientId = clientId ?: ""

        Log.i(TAG, "OAuth success for ${config.name}: token expires in ${tokens.expiresIn}s")
        return true
    }

    suspend fun refreshToken(context: Context, config: McpServerConfig): Boolean {
        if (config.refreshToken.isEmpty()) return false
        val metadata = discoverMetadata(config.url) ?: return false

        return withContext(Dispatchers.IO) {
            try {
                val body = "grant_type=refresh_token&refresh_token=${config.refreshToken}" +
                    if (config.clientId.isNotEmpty()) "&client_id=${config.clientId}" else ""

                val request = Request.Builder()
                    .url(metadata.tokenEndpoint)
                    .post(body.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                    .build()

                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) return@withContext false

                val json = JSONObject(response.body?.string() ?: "")
                config.accessToken = json.optString("access_token", "")
                val expiresIn = json.optLong("expires_in", 3600)
                config.tokenExpiresAt = System.currentTimeMillis() + (expiresIn * 1000)
                val newRefresh = json.optString("refresh_token", "")
                if (newRefresh.isNotEmpty()) config.refreshToken = newRefresh
                true
            } catch (e: Exception) {
                Log.e(TAG, "Token refresh failed: ${e.message}")
                false
            }
        }
    }

    private suspend fun discoverMetadata(serverUrl: String): OAuthMetadata? {
        return withContext(Dispatchers.IO) {
            // v4.0.89: RFC 9728 — Modern MCP OAuth discovery flow.
            //
            // Modern MCP servers (GitHub, Smithery-hosted, etc.) DON'T expose
            // /.well-known/oauth-authorization-server directly. Instead they:
            //   1. Return 401 on the MCP endpoint with a www-authenticate header:
            //      `Bearer resource_metadata="<url>"`
            //   2. That resource_metadata URL returns JSON with `authorization_servers`
            //   3. Each authorization_server has its own /.well-known/oauth-authorization-server
            //
            // Older servers (Notion, Linear, Zapier) still use RFC 8414 directly.
            // We try RFC 9728 first, then fall back to RFC 8414.

            // ---- Step 1: Try RFC 9728 (resource_metadata indirection) ----
            val rfc9728Result = discoverViaRfc9728(serverUrl)
            if (rfc9728Result != null) return@withContext rfc9728Result

            // ---- Step 2: Fall back to RFC 8414 (direct .well-known) ----
            try {
                val wellKnownUrl = getBaseUrl(serverUrl) + "/.well-known/oauth-authorization-server"
                val request = Request.Builder().url(wellKnownUrl).build()
                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) return@withContext null
                val json = JSONObject(response.body?.string() ?: "")
                OAuthMetadata(
                    authorizationEndpoint = json.optString("authorization_endpoint", ""),
                    tokenEndpoint = json.optString("token_endpoint", ""),
                    registrationEndpoint = json.optString("registration_endpoint").takeIf { it.isNotEmpty() },
                )
            } catch (e: Exception) {
                Log.w(TAG, "OAuth metadata discovery failed: ${e.message}")
                null
            }
        }
    }

    /**
     * v4.0.89: RFC 9728 discovery — the modern MCP OAuth flow.
     *
     * 1. GET the MCP server URL → expect 401 with www-authenticate header
     * 2. Parse resource_metadata URL from the header
     * 3. GET that URL → returns {authorization_servers: [...]}
     * 4. For each authorization server, try its /.well-known/oauth-authorization-server
     *
     * Returns null if any step fails (caller falls back to RFC 8414).
     */
    private suspend fun discoverViaRfc9728(serverUrl: String): OAuthMetadata? {
        return withContext(Dispatchers.IO) {
            try {
                // Step 1: GET the MCP endpoint, expect 401 with resource_metadata
                val request = Request.Builder().url(serverUrl).build()
                val response = httpClient.newCall(request).execute()
                // We expect 401, but proceed regardless — check the header
                val wwwAuthHeader = response.header("www-authenticate") ?: return@withContext null
                response.close()

                // Step 2: Parse resource_metadata URL from the header
                // Header format: Bearer error="...", resource_metadata="https://..."
                val resourceMetadataUrl = Regex("resource_metadata=\"([^\"]+)\"")
                    .find(wwwAuthHeader)?.groupValues?.getOrNull(1)
                    ?: return@withContext null

                Log.i(TAG, "RFC 9728: resource_metadata at $resourceMetadataUrl")

                // Step 3: GET the resource metadata URL → get authorization_servers
                val resourceRequest = Request.Builder().url(resourceMetadataUrl).build()
                val resourceResponse = httpClient.newCall(resourceRequest).execute()
                if (!resourceResponse.isSuccessful) return@withContext null
                val resourceJson = JSONObject(resourceResponse.body?.string() ?: "")
                val authServers = resourceJson.optJSONArray("authorization_servers")
                    ?: return@withContext null

                // Step 4: For each authorization server, try its .well-known
                for (i in 0 until authServers.length()) {
                    val authServerUrl = authServers.getString(i)
                    // First try .well-known/oauth-authorization-server
                    val metadata = fetchAuthServerMetadata(authServerUrl)
                    if (metadata != null) {
                        Log.i(TAG, "RFC 9728: found OAuth metadata via $authServerUrl")
                        return@withContext metadata
                    }
                    // v4.0.89: Fall back to known providers (GitHub, etc. don't expose .well-known)
                    val knownMetadata = KNOWN_PROVIDERS[authServerUrl]
                    if (knownMetadata != null) {
                        Log.i(TAG, "RFC 9728: using known provider config for $authServerUrl")
                        return@withContext knownMetadata
                    }
                }
                null
            } catch (e: Exception) {
                Log.w(TAG, "RFC 9728 discovery failed: ${e.message}")
                null
            }
        }
    }

    /** Fetch /.well-known/oauth-authorization-server from an authorization server URL. */
    private suspend fun fetchAuthServerMetadata(authServerUrl: String): OAuthMetadata? {
        return withContext(Dispatchers.IO) {
            try {
                val wellKnownUrl = authServerUrl.trimEnd('/') +
                    "/.well-known/oauth-authorization-server"
                val request = Request.Builder().url(wellKnownUrl).build()
                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) return@withContext null
                val json = JSONObject(response.body?.string() ?: "")
                val authEndpoint = json.optString("authorization_endpoint", "")
                val tokenEndpoint = json.optString("token_endpoint", "")
                if (authEndpoint.isEmpty() || tokenEndpoint.isEmpty()) return@withContext null
                OAuthMetadata(
                    authorizationEndpoint = authEndpoint,
                    tokenEndpoint = tokenEndpoint,
                    registrationEndpoint = json.optString("registration_endpoint")
                        .takeIf { it.isNotEmpty() },
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    private suspend fun dynamicRegister(registerEndpoint: String, clientName: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                val body = JSONObject().apply {
                    put("client_name", "Aira")
                    put("redirect_uris", JSONArray().put("http://localhost:$CALLBACK_PORT$CALLBACK_PATH"))
                    put("grant_types", JSONArray().put("authorization_code").put("refresh_token"))
                    put("token_endpoint_auth_method", "none")
                }.toString()

                val request = Request.Builder()
                    .url(registerEndpoint)
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()

                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) return@withContext null
                val json = JSONObject(response.body?.string() ?: "")
                json.optString("client_id", "").takeIf { it.isNotEmpty() }
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun generateCodeVerifier(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun generateCodeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    private fun buildAuthUrl(authEndpoint: String, clientId: String?, challenge: String, redirectUri: String, state: String): String {
        val params = StringBuilder()
        params.append("response_type=code")
        if (clientId != null) params.append("&client_id=").append(java.net.URLEncoder.encode(clientId, "UTF-8"))
        params.append("&redirect_uri=").append(java.net.URLEncoder.encode(redirectUri, "UTF-8"))
        params.append("&code_challenge=").append(challenge)
        params.append("&code_challenge_method=S256")
        params.append("&scope=mcp")
        params.append("&state=").append(state)  // v4.0.86: CSRF protection
        return "$authEndpoint?$params"
    }

    private suspend fun exchangeCodeForToken(
        tokenEndpoint: String, clientId: String?, code: String, verifier: String, redirectUri: String
    ): TokenResponse? {
        return withContext(Dispatchers.IO) {
            try {
                val params = StringBuilder()
                params.append("grant_type=authorization_code")
                params.append("&code=").append(java.net.URLEncoder.encode(code, "UTF-8"))
                params.append("&code_verifier=").append(verifier)
                params.append("&redirect_uri=").append(java.net.URLEncoder.encode(redirectUri, "UTF-8"))
                if (clientId != null) params.append("&client_id=").append(java.net.URLEncoder.encode(clientId, "UTF-8"))

                val request = Request.Builder()
                    .url(tokenEndpoint)
                    .post(params.toString().toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                    .build()

                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) return@withContext null

                val json = JSONObject(response.body?.string() ?: "")
                TokenResponse(
                    accessToken = json.optString("access_token", ""),
                    refreshToken = json.optString("refresh_token", "").takeIf { it.isNotEmpty() },
                    expiresIn = json.optLong("expires_in", 3600),
                )
            } catch (e: Exception) {
                Log.e(TAG, "Token exchange failed: ${e.message}")
                null
            }
        }
    }

    private fun startCallbackListener(callbackCode: CompletableDeferred<String>): ServerSocket? {
        return try {
            val server = ServerSocket(CALLBACK_PORT)
            Thread {
                try {
                    val client = server.accept()
                    val reader = client.getInputStream().bufferedReader()
                    val requestLine = reader.readLine() ?: ""
                    val code = Regex("code=([^&\\s]+)").find(requestLine)?.groupValues?.get(1)
                    val response = if (code != null) {
                        "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n\r\n<html><body><h1>Authorized!</h1><p>Aira can now connect. Close this tab.</p></body></html>"
                    } else {
                        "HTTP/1.1 400 Bad Request\r\nContent-Type: text/html\r\n\r\n<html><body><h1>Authorization failed</h1></body></html>"
                    }
                    client.getOutputStream().write(response.toByteArray())
                    client.getOutputStream().flush()
                    client.close()
                    if (code != null) callbackCode.complete(code)
                    else callbackCode.completeExceptionally(Exception("No code in callback"))
                } catch (e: Exception) {
                    callbackCode.completeExceptionally(e)
                }
            }.start()
            server
        } catch (e: Exception) {
            Log.w(TAG, "Could not start callback listener: ${e.message}")
            null
        }
    }

    private fun getBaseUrl(url: String): String {
        val uri = Uri.parse(url)
        return "${uri.scheme}://${uri.host}${if (uri.port > 0) ":${uri.port}" else ""}"
    }
}
