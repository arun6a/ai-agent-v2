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
            dynamicRegister(metadata.registrationEndpoint, config.name)
        } else null

        val verifier = generateCodeVerifier()
        val challenge = generateCodeChallenge(verifier)

        val callbackCode = CompletableDeferred<String>()
        val callbackServer = startCallbackListener(callbackCode)

        val redirectUri = "http://localhost:$CALLBACK_PORT$CALLBACK_PATH"
        val authUrl = buildAuthUrl(metadata.authorizationEndpoint, clientId, challenge, redirectUri)

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

    private fun buildAuthUrl(authEndpoint: String, clientId: String?, challenge: String, redirectUri: String): String {
        val params = StringBuilder()
        params.append("response_type=code")
        if (clientId != null) params.append("&client_id=").append(java.net.URLEncoder.encode(clientId, "UTF-8"))
        params.append("&redirect_uri=").append(java.net.URLEncoder.encode(redirectUri, "UTF-8"))
        params.append("&code_challenge=").append(challenge)
        params.append("&code_challenge_method=S256")
        params.append("&scope=mcp")
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
