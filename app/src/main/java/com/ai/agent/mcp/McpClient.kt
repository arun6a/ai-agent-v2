package com.ai.agent.mcp

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * v4.0.79: MCP Client — core JSON-RPC 2.0 communication with MCP servers.
 *
 * Supports two transports:
 * 1. HTTP (Streamable HTTP) — sends JSON-RPC via HTTP POST
 * 2. stdio — (Phase 2) spawns subprocess via runOnTerminal, communicates via stdin/stdout
 *
 * Protocol flow:
 * 1. initialize → server responds with capabilities
 * 2. tools/list → server returns tool schemas
 * 3. tools/call → server executes tool, returns result
 *
 * The client handles:
 * - JSON-RPC request/response correlation (via id field)
 * - Per-server timeout (60s default)
 * - Health tracking (success/failure counts)
 * - OAuth bearer token injection
 */
object McpClient {
    private const val TAG = "McpClient"
    private const val DEFAULT_TIMEOUT_MS = 60000L  // 60 seconds
    private const val PROTOCOL_VERSION = "2025-06-18"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val requestIdCounter = AtomicInteger(1)

    /**
     * Initialize a connection to an MCP server.
     * Sends the JSON-RPC initialize request + caches tool list.
     *
     * Returns the list of tools exposed by the server.
     */
    suspend fun initialize(config: McpServerConfig): List<McpToolInfo> {
        Log.i(TAG, "Initializing MCP server: ${config.name} (${config.transport})")

        // Step 1: Send initialize request
        val initResponse = sendRequest(config, "initialize", JSONObject().apply {
            put("protocolVersion", PROTOCOL_VERSION)
            put("capabilities", JSONObject())
            put("clientInfo", JSONObject().apply {
                put("name", "Aira")
                put("version", "4.0.79")
            })
        })

        if (initResponse == null) {
            config.lastError = "Initialize failed (no response or timeout)"
            return emptyList()
        }

        Log.i(TAG, "Initialized ${config.name}: ${initResponse.toString().take(200)}")

        // Step 2: Send initialized notification (no response expected)
        sendNotification(config, "notifications/initialized")

        // Step 3: Get tool list
        val toolsResponse = sendRequest(config, "tools/list", JSONObject())
        if (toolsResponse == null) {
            config.lastError = "tools/list failed (no response)"
            return emptyList()
        }

        val toolsArray = toolsResponse.optJSONArray("tools") ?: JSONArray()
        val tools = mutableListOf<McpToolInfo>()
        for (i in 0 until toolsArray.length()) {
            val tool = toolsArray.optJSONObject(i) ?: continue
            val name = tool.optString("name", "")
            val desc = tool.optString("description", "")
            val schema = tool.optJSONObject("inputSchema")?.toString() ?: "{}"
            if (name.isNotEmpty()) {
                tools.add(McpToolInfo(name, desc, schema))
            }
        }

        config.tools = tools
        config.connected = true
        config.lastError = ""
        config.successCount++

        Log.i(TAG, "${config.name}: ${tools.size} tools available")
        return tools
    }

    /**
     * Call a tool on an MCP server.
     * Sends JSON-RPC tools/call and returns the result.
     */
    suspend fun callTool(config: McpServerConfig, toolName: String, args: Map<String, Any>): McpToolResult {
        val namespacedName = config.namespacedToolName(toolName)
        Log.i(TAG, "Calling MCP tool: $namespacedName")

        return withTimeoutOrNull(DEFAULT_TIMEOUT_MS) {
            val argsJson = JSONObject()
            for ((key, value) in args) {
                argsJson.put(key, value)
            }

            val response = sendRequest(config, "tools/call", JSONObject().apply {
                put("name", toolName)
                put("arguments", argsJson)
            })

            if (response == null) {
                config.failureCount++
                config.lastError = "Tool call '$toolName' timed out"
                McpToolResult(false, "MCP tool '$toolName' on '${config.name}' timed out after ${DEFAULT_TIMEOUT_MS / 1000}s. The server may be offline.")
            } else {
                // Check for error
                val error = response.optJSONObject("error")
                if (error != null) {
                    config.failureCount++
                    config.lastError = error.optString("message", "Unknown error")
                    McpToolResult(false, "MCP error: ${config.lastError}")
                } else {
                    // Extract result content
                    val content = response.optJSONArray("content")
                    val resultText = if (content != null && content.length() > 0) {
                        val sb = StringBuilder()
                        for (i in 0 until content.length()) {
                            val item = content.optJSONObject(i)
                            val text = item?.optString("text", "") ?: ""
                            if (text.isNotEmpty()) sb.append(text).append("\n")
                        }
                        sb.toString().trim()
                    } else {
                        response.toString()
                    }

                    config.successCount++
                    config.lastCallTime = System.currentTimeMillis()
                    McpToolResult(true, resultText)
                }
            }
        } ?: run {
            config.failureCount++
            config.lastError = "Tool call timed out"
            McpToolResult(false, "MCP tool '$toolName' timed out. Server '${config.name}' may be unreachable.")
        }
    }

    /**
     * Send a JSON-RPC request to the server and wait for the response.
     * Only HTTP transport is implemented (stdio is Phase 2).
     */
    private suspend fun sendRequest(config: McpServerConfig, method: String, params: JSONObject): JSONObject? {
        if (config.transport != "http") {
            Log.w(TAG, "stdio transport not yet implemented for ${config.name}")
            return null
        }

        return withContext(Dispatchers.IO) {
            try {
                val requestId = requestIdCounter.getAndIncrement()
                val jsonRpc = JSONObject().apply {
                    put("jsonrpc", "2.0")
                    put("id", requestId)
                    put("method", method)
                    put("params", params)
                }

                val requestBuilder = Request.Builder()
                    .url(config.url)
                    .post(jsonRpc.toString().toRequestBody("application/json".toMediaType()))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")

                // Add auth header
                when (config.authType) {
                    "bearer" -> requestBuilder.header("Authorization", "Bearer ${config.bearerToken}")
                    "oauth" -> {
                        if (config.accessToken.isNotEmpty()) {
                            requestBuilder.header("Authorization", "Bearer ${config.accessToken}")
                        }
                    }
                }

                val response = httpClient.newCall(requestBuilder.build()).execute()
                val body = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    Log.w(TAG, "${config.name} $method: HTTP ${response.code}")
                    if (response.code == 401) {
                        config.lastError = "Unauthorized — token may be expired"
                    }
                    return@withContext null
                }

                // Handle SSE response (text/event-stream) — extract the data JSON
                val jsonStr = if (body.startsWith("data:")) {
                    // SSE format: data: {...}\n\n
                    body.lines()
                        .filter { it.startsWith("data:") }
                        .joinToString("") { it.removePrefix("data:").trim() }
                } else {
                    body
                }

                val json = JSONObject(jsonStr)
                // Check if this is a JSON-RPC response (has "result" or "error")
                if (json.has("result")) {
                    json.getJSONObject("result")
                } else if (json.has("error")) {
                    json  // return the whole thing — caller checks for error
                } else {
                    json  // might be the result directly
                }
            } catch (e: Exception) {
                Log.e(TAG, "${config.name} $method failed: ${e.message}")
                config.lastError = e.message ?: "Unknown error"
                null
            }
        }
    }

    /**
     * Send a JSON-RPC notification (no response expected).
     */
    private suspend fun sendNotification(config: McpServerConfig, method: String) {
        if (config.transport != "http") return

        withContext(Dispatchers.IO) {
            try {
                val jsonRpc = JSONObject().apply {
                    put("jsonrpc", "2.0")
                    put("method", method)
                }

                val requestBuilder = Request.Builder()
                    .url(config.url)
                    .post(jsonRpc.toString().toRequestBody("application/json".toMediaType()))
                    .header("Content-Type", "application/json")

                when (config.authType) {
                    "bearer" -> requestBuilder.header("Authorization", "Bearer ${config.bearerToken}")
                    "oauth" -> if (config.accessToken.isNotEmpty()) {
                        requestBuilder.header("Authorization", "Bearer ${config.accessToken}")
                    }
                }

                httpClient.newCall(requestBuilder.build()).execute().close()
                Log.i(TAG, "Sent notification: $method to ${config.name}")
            } catch (e: Exception) {
                Log.w(TAG, "Notification $method to ${config.name} failed: ${e.message}")
            }
        }
    }

    /**
     * Disconnect from a server.
     * For HTTP transport, just marks as disconnected (no persistent connection to close).
     */
    fun disconnect(config: McpServerConfig) {
        config.connected = false
        Log.i(TAG, "Disconnected from ${config.name}")
    }
}

/**
 * Result of an MCP tool call.
 */
data class McpToolResult(
    val success: Boolean,
    val output: String,
)
