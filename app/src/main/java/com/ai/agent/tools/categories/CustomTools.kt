package com.ai.agent.tools.categories

import android.content.Context
import android.util.Log
import com.ai.agent.accessibility.AgentAccessibilityService
import com.ai.agent.llm.LLMClient
import com.ai.agent.storage.AgentDatabase
import com.ai.agent.tools.ToolExecutor
import com.ai.agent.tools.custom.CustomToolStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * v4.0.26: Custom Tools executor.
 *
 * When the AI calls a custom tool (created via createCustomTool), this file:
 * 1. Looks up the tool definition from CustomToolStore
 * 2. Substitutes {param} placeholders with actual arg values
 * 3. Executes the handler (shell command or HTTP request)
 * 4. Returns the result
 *
 * Also contains the management tools: createCustomTool, listCustomTools, deleteCustomTool.
 */
object CustomTools {
    private const val TAG = "CustomTools"
    // v4.0.49: Bumped readTimeout from 30s → 305s to support long-running commands
    // (builds, package installs). The server-side default is 120s, max 600s.
    // 305s = 300s (5 min build) + 5s buffer for network overhead.
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(305, TimeUnit.SECONDS)
        .callTimeout(310, TimeUnit.SECONDS)
        .build()
    private val jsonMediaType = "application/json".toMediaType()

    suspend fun execute(
        call: LLMClient.ToolCall,
        context: Context,
        @Suppress("UNUSED_PARAMETER") service: AgentAccessibilityService?,
        @Suppress("UNUSED_PARAMETER") database: AgentDatabase
    ): ToolExecutor.ToolResult {
        return when (call.name) {
            "createCustomTool" -> createCustomTool(call, context)
            "listCustomTools" -> listCustomTools(context)
            "deleteCustomTool" -> deleteCustomTool(call, context)
            // Dynamic dispatch: if the tool name matches a custom tool, execute it
            else -> executeCustomTool(call, context)
        }
    }

    // ==================== MANAGEMENT TOOLS ====================

    private fun createCustomTool(call: LLMClient.ToolCall, context: Context): ToolExecutor.ToolResult {
        val name = call.args["name"] as? String
            ?: return ToolExecutor.ToolResult(false, "missing name")
        val description = call.args["description"] as? String
            ?: return ToolExecutor.ToolResult(false, "missing description")
        val handlerType = call.args["handlerType"] as? String
            ?: return ToolExecutor.ToolResult(false, "missing handlerType (use 'shell' or 'http')")
        val handler = call.args["handler"] as? String
            ?: return ToolExecutor.ToolResult(false, "missing handler (shell command template or HTTP JSON config)")
        val parameters = (call.args["parameters"] as? String)?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: emptyList()

        if (handlerType !in setOf("shell", "http")) {
            return ToolExecutor.ToolResult(false, "invalid handlerType. Use 'shell' or 'http'.")
        }

        // Validate tool name: only lowercase letters, digits, underscore
        if (!name.matches(Regex("^[a-z][a-z0-9_]*$"))) {
            return ToolExecutor.ToolResult(false, "invalid name. Use lowercase letters, digits, underscore. Must start with a letter. Example: 'check_server_status'")
        }

        // Check it doesn't conflict with a built-in tool
        val builtInTools = ToolExecutor.getAvailableToolNames()
        if (name in builtInTools) {
            return ToolExecutor.ToolResult(false, "name '$name' conflicts with a built-in tool. Choose a different name.")
        }

        val tool = CustomToolStore.CustomTool(
            name = name,
            description = description,
            handlerType = handlerType,
            handler = handler,
            parameters = parameters,
        )

        val added = CustomToolStore.add(context, tool)
        return if (added) {
            ToolExecutor.ToolResult(true,
                "✅ Custom tool '$name' created successfully!\n" +
                "   Type: $handlerType\n" +
                "   Parameters: ${if (parameters.isEmpty()) "(none)" else parameters.joinToString(", ")}\n" +
                "   Handler: ${handler.take(200)}\n\n" +
                "The tool is now available. You can call $name(${if (parameters.isEmpty()) "" else parameters.joinToString(", ") { "$it=..." }}) directly.\n" +
                "It will persist across app restarts."
            )
        } else {
            ToolExecutor.ToolResult(false, "Custom tool '$name' already exists. Use deleteCustomTool(name=\"$name\") first if you want to recreate it.")
        }
    }

    private fun listCustomTools(context: Context): ToolExecutor.ToolResult {
        val tools = CustomToolStore.load(context)
        return if (tools.isEmpty()) {
            ToolExecutor.ToolResult(true, "No custom tools created yet. Use createCustomTool(name, description, handlerType, handler, parameters) to create one.")
        } else {
            val sb = StringBuilder("Custom Tools (${tools.size}):\n\n")
            for (t in tools) {
                sb.append("• ${t.name}(${t.parameters.joinToString(", ")})\n")
                sb.append("  Description: ${t.description}\n")
                sb.append("  Type: ${t.handlerType}\n")
                sb.append("  Handler: ${t.handler.take(150)}\n\n")
            }
            ToolExecutor.ToolResult(true, sb.toString())
        }
    }

    private fun deleteCustomTool(call: LLMClient.ToolCall, context: Context): ToolExecutor.ToolResult {
        val name = call.args["name"] as? String
            ?: return ToolExecutor.ToolResult(false, "missing name")
        val removed = CustomToolStore.remove(context, name)
        return if (removed) {
            ToolExecutor.ToolResult(true, "Deleted custom tool '$name'")
        } else {
            ToolExecutor.ToolResult(false, "Custom tool '$name' not found")
        }
    }

    // ==================== DYNAMIC EXECUTION ====================

    /**
     * Execute a custom tool by name. This is called when the AI calls a tool
     * that isn't a built-in tool but matches a custom tool.
     */
    private suspend fun executeCustomTool(call: LLMClient.ToolCall, context: Context): ToolExecutor.ToolResult {
        val tool = CustomToolStore.get(context, call.name)
            ?: return ToolExecutor.ToolResult(false, "unknown tool: ${call.name}")

        return when (tool.handlerType) {
            "shell" -> executeShell(tool, call.args)
            "http" -> executeHttp(tool, call.args)
            else -> ToolExecutor.ToolResult(false, "unknown handlerType: ${tool.handlerType}")
        }
    }

    /**
     * Execute a shell-based custom tool.
     * Substitutes {param} placeholders in the handler with arg values.
     */
    private fun executeShell(tool: CustomToolStore.CustomTool, args: Map<String, Any>): ToolExecutor.ToolResult {
        try {
            var command = tool.handler
            for ((key, value) in args) {
                command = command.replace("{$key}", value.toString())
            }
            // v4.0.44: Fix regex error — was using Regex("\\{[^}]+}") which crashes on Android
            // because the closing } is not escaped. Use simple string check instead.
            val unresolved = mutableListOf<String>()
            var i = 0
            while (i < command.length) {
                if (command[i] == '{') {
                    val end = command.indexOf('}', i)
                    if (end > i) {
                        val placeholder = command.substring(i, end + 1)
                        unresolved.add(placeholder)
                        i = end + 1
                    } else {
                        i++
                    }
                } else {
                    i++
                }
            }
            if (unresolved.isNotEmpty()) {
                return ToolExecutor.ToolResult(false, "missing parameters: $unresolved. Required: ${tool.parameters}")
            }

            // v4.0.44: The handler might be "sh -c {cmd}" — we pass it to sh -c again,
            // which causes double-sh. Extract just the inner command if handler starts with "sh -c".
            val finalCommand = if (command.startsWith("sh -c ")) {
                command.removePrefix("sh -c ").trim().trim('"').trim('\'')
            } else {
                command
            }

            Log.i(TAG, "Executing custom shell tool '${tool.name}': $finalCommand")
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", finalCommand))
            val output = process.inputStream.bufferedReader().readText()
            val errors = process.errorStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            val result = buildString {
                if (output.isNotEmpty()) append(output)
                if (errors.isNotEmpty()) append("\n[STDERR]: $errors")
                append("\n[Exit: $exitCode]")
            }
            return ToolExecutor.ToolResult(exitCode == 0, result.take(2000))
        } catch (e: Exception) {
            return ToolExecutor.ToolResult(false, "Shell error: ${e.message}")
        }
    }

    /**
     * Execute an HTTP-based custom tool.
     * The handler is a JSON config: {"method":"GET","url":"...","headers":{...},"body":"..."}
     * Substitutes {param} placeholders in URL + body with arg values.
     */
    private fun executeHttp(tool: CustomToolStore.CustomTool, args: Map<String, Any>): ToolExecutor.ToolResult {
        try {
            // v4.0.46: Handle BOTH JSON config AND raw URL as handler.
            // The AI sometimes passes a raw URL ("http://...") instead of JSON config.
            var url: String
            var body = ""
            var method = "GET"
            var headersObj: JSONObject? = null

            if (tool.handler.trim().startsWith("{")) {
                // JSON config: {"method":"GET","url":"...","headers":{...},"body":"..."}
                val config = JSONObject(tool.handler)
                url = config.optString("url", "")
                body = config.optString("body", "")
                method = config.optString("method", "GET").uppercase()
                headersObj = config.optJSONObject("headers")
            } else {
                // v4.0.46: Raw URL — treat as GET request
                url = tool.handler.trim()
                method = "GET"
            }

            // Substitute {param} in URL and body
            for ((key, value) in args) {
                url = url.replace("{$key}", value.toString())
                body = body.replace("{$key}", value.toString())
            }

            // v4.0.49: Support optional parameters for HTTP tools.
            // Instead of failing when {param} placeholders remain unresolved,
            // strip them (and their &param= prefix) from the URL. This allows
            // tools like run_command to have optional params like timeout.
            val unresolved = mutableListOf<String>()
            var i = 0
            while (i < url.length) {
                if (url[i] == '{') {
                    val end = url.indexOf('}', i)
                    if (end > i) {
                        unresolved.add(url.substring(i, end + 1))
                        i = end + 1
                    } else { i++ }
                } else { i++ }
            }
            if (unresolved.isNotEmpty()) {
                // Strip each unresolved placeholder + its query param prefix
                for (placeholder in unresolved) {
                    val paramName = placeholder.trim('{', '}')
                    // Remove "&param={param}" (param in the middle of query string)
                    url = url.replace("&$paramName=$placeholder", "")
                    // Remove "?param={param}&" → "?" (param at start, followed by others)
                    url = url.replace("?$paramName=$placeholder&", "?")
                    // Remove "?param={param}" (param is the only query param)
                    url = url.replace("?$paramName=$placeholder", "")
                    // Remove any remaining literal placeholder (safety)
                    url = url.replace(placeholder, "")
                }
                Log.i(TAG, "HTTP tool '${tool.name}': stripped optional params $unresolved from URL")
                // Don't fail — proceed with cleaned URL. The server will use its defaults.
            }

            Log.i(TAG, "Executing custom HTTP tool '${tool.name}': $method $url")

            val reqBuilder = Request.Builder().url(url)
            // Add headers
            if (headersObj != null) {
                val keys = headersObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    reqBuilder.header(k, headersObj.getString(k))
                }
            }
            // Add body for POST/PUT
            if (method in setOf("POST", "PUT", "PATCH") && body.isNotEmpty()) {
                reqBuilder.post(body.toRequestBody(jsonMediaType))
            }

            val response = client.newCall(reqBuilder.build()).execute()
            val responseBody = response.body?.string() ?: ""
            val result = "$method $url → ${response.code}\n\n${responseBody.take(2000)}"
            return ToolExecutor.ToolResult(response.isSuccessful, result)
        } catch (e: Exception) {
            return ToolExecutor.ToolResult(false, "HTTP error: ${e.message}")
        }
    }
}
