package com.ai.agent.llm

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import com.ai.agent.llm.ApiUsageTracker

/**
 * LLM client that supports multiple AI providers:
 * - OpenRouter (free models, OpenAI-compatible)
 * - Google Gemini (free tier, different format)
 * - Z.ai proxy (sandbox, no API key)
 * - Custom (any OpenAI-compatible endpoint)
 *
 * The provider config is read from SharedPreferences (AIProvider).
 * The user can switch providers in Settings without rebuilding the app.
 */
class LLMClient(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)  // longer for big models
        .build()

    private val jsonMediaType = "application/json".toMediaType()

    data class LLMResponse(
        val reply: String,
        val toolCalls: List<ToolCall>
    )

    data class ToolCall(
        val name: String,
        val args: Map<String, Any>
    )

    /**
     * Sends a chat message and returns the LLM's response.
     * Uses the provider configured in Settings.
     */
    suspend fun chat(userMessage: String, systemPrompt: String): LLMResponse = withContext(Dispatchers.IO) {
        val provider = AIProvider.getCurrentProvider(context)
        val apiKey = AIProvider.getApiKey(context)
        val endpoint = AIProvider.getEndpoint(context)
        val model = AIProvider.getModel(context)

        Log.i("LLMClient", "Provider: ${provider.id}, Model: $model, Endpoint: $endpoint")

        if (provider.needsApiKey && apiKey.isEmpty()) {
            return@withContext LLMResponse(
                "No API key set. Open Settings → AI Provider to add your ${provider.name} API key.",
                emptyList()
            )
        }

        var lastError: String? = null

        // Retry up to 3 times with increasing delay
        for (attempt in 1..3) {
            try {
                val requestBody = when (provider.id) {
                    "gemini" -> buildGeminiRequest(model, userMessage, systemPrompt)
                    else -> buildOpenAIRequest(model, userMessage, systemPrompt, provider.id)
                }

                // For Gemini, construct the full URL with model + API key
                val finalUrl = if (provider.id == "gemini") {
                    "$endpoint/models/$model:generateContent?key=$apiKey"
                } else {
                    endpoint
                }

                val requestBuilder = Request.Builder()
                    .url(finalUrl)
                    .header("Content-Type", "application/json")

                // Add auth headers based on provider
                when (provider.id) {
                    "openrouter" -> {
                        requestBuilder.header("Authorization", "Bearer $apiKey")
                        requestBuilder.header("HTTP-Referer", "https://github.com/arun6a/ai-agent-apk")
                        requestBuilder.header("X-Title", "AI Agent Phone")
                    }
                    "zai_direct", "groq", "together", "openai", "cerebras", "mistral",
                    "deepinfra", "fireworks", "anthropic" -> {
                        // All OpenAI-compatible — Bearer token
                        requestBuilder.header("Authorization", "Bearer $apiKey")
                        // Anthropic also needs anthropic-version header
                        if (provider.id == "anthropic") {
                            requestBuilder.header("anthropic-version", "2023-06-01")
                        }
                    }
                    "gemini" -> {
                        // Gemini uses query param, not header — handled in endpoint
                    }
                    "custom" -> {
                        if (apiKey.isNotEmpty()) {
                            requestBuilder.header("Authorization", "Bearer $apiKey")
                        }
                    }
                    "zai" -> {
                        // Z.ai proxy needs no auth from the phone (sandbox handles it)
                    }
                }

                val request = requestBuilder.post(requestBody.toRequestBody(jsonMediaType)).build()

                Log.d("LLMClient", "Request attempt $attempt to $endpoint")
                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                if (response.code == 429) {
                    val waitMs = attempt * 5000L
                    Log.w("LLMClient", "Rate limited (429), waiting ${waitMs}ms before retry $attempt/3")
                    lastError = "Rate limited (429). Daily quota may be exhausted."
                    Thread.sleep(waitMs)
                    continue
                }

                if (!response.isSuccessful) {
                    Log.e("LLMClient", "API error ${response.code}: ${responseBody.take(300)}")
                    if (attempt < 3) {
                        Thread.sleep(2000)
                        lastError = "API returned ${response.code}"
                        continue
                    }
                    return@withContext LLMResponse(
                        "Error: API returned ${response.code}. ${responseBody.take(200)}",
                        emptyList()
                    )
                }

                // Parse response based on provider format
                val llmResponse = when (provider.id) {
                    "gemini" -> parseGeminiResponse(responseBody)
                    else -> parseOpenAIResponse(responseBody)
                }

                Log.d("LLMClient", "Response: ${(llmResponse.reply + llmResponse.toolCalls.toString()).take(200)}...")
                return@withContext llmResponse
            } catch (e: Exception) {
                Log.e("LLMClient", "Request failed (attempt $attempt)", e)
                lastError = e.message
                if (attempt < 3) Thread.sleep(2000)
            }
        }

        LLMResponse("Error: $lastError. Please try again in a moment.", emptyList())
    }

    /**
     * Build request body for OpenAI-compatible APIs (OpenRouter, Z.ai, custom).
     */
    private fun buildOpenAIRequest(model: String, userMessage: String, systemPrompt: String, providerId: String): String {
        val messages = JSONArray()
        messages.put(JSONObject().apply {
            put("role", "system")
            put("content", systemPrompt)
        })
        messages.put(JSONObject().apply {
            put("role", "user")
            put("content", userMessage)
        })

        val body = JSONObject().apply {
            put("model", model)
            put("messages", messages)
            // Z.ai (both proxy and direct) uses "thinking" param
            if (providerId == "zai" || providerId == "zai_direct") {
                put("thinking", JSONObject().put("type", "disabled"))
            }
            // For providers that support native function calling, use it.
            // This is MUCH more reliable than asking the LLM to write JSON in the reply.
            // The LLM returns tool_calls as a structured field, not as text.
            if (providerId in setOf("groq", "openrouter", "together", "custom", "openai", "cerebras", "mistral", "deepinfra", "fireworks", "zai_direct")) {
                put("tools", buildToolsArray())
                put("tool_choice", "auto")
                // Some providers (Groq, OpenRouter) support response_format for stricter output
                if (providerId == "groq") {
                    put("response_format", JSONObject().put("type", "json_object"))
                }
            }
        }

        return body.toString()
    }

    /**
     * Build the OpenAI-compatible "tools" array describing all available tools.
     * Used with native function calling (Groq, OpenRouter, Together, Custom).
     *
     * This is FAR more reliable than asking the LLM to write JSON in the reply text —
     * the model returns structured tool_calls directly, no parsing needed.
     */
    private fun buildToolsArray(): JSONArray {
        val tools = JSONArray()
        val toolNames = com.ai.agent.tools.ToolExecutor.getAvailableToolNames()
        for (name in toolNames) {
            val parameters = JSONObject()
                .put("type", "object")
                .put("properties", JSONObject())
                .put("additionalProperties", true)

            val function = JSONObject()
                .put("name", name)
                .put("description", "Execute the $name tool on the Android phone")
                .put("parameters", parameters)

            val tool = JSONObject()
                .put("type", "function")
                .put("function", function)

            tools.put(tool)
        }
        return tools
    }

    /**
     * Build request body for Gemini API (different format).
     * Endpoint: https://generativelanguage.googleapis.com/v1beta/models/<model>:generateContent?key=<key>
     */
    private fun buildGeminiRequest(model: String, userMessage: String, systemPrompt: String): String {
        val body = JSONObject().apply {
            put("system_instruction", JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
            })
            put("contents", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(JSONObject().put("text", userMessage)))
            }))
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.3)  // v6.4.2: lower temp for more consistent JSON
                put("maxOutputTokens", 8192)  // v6.4.2: was 1024 (too low — JSON got cut off!)
                put("topP", 0.9)
                put("topK", 40)
            })
        }
        return body.toString()
    }

    /**
     * Parse OpenAI-format response (used by OpenRouter, Z.ai, custom).
     */
    /**
     * Parse OpenAI-compatible response.
     * Handles BOTH:
     * - Native function calling: tool_calls in message.tool_calls (Groq, OpenRouter, etc.)
     * - Manual JSON in content: {"reply": "...", "tool_calls": [...]} (Z.ai, older models)
     */
    private fun parseOpenAIResponse(responseBody: String): LLMResponse {
        val json = JSONObject(responseBody)

        // Track token usage
        try {
            val usage = json.optJSONObject("usage")
            if (usage != null) {
                val promptTokens = usage.optInt("prompt_tokens", 0)
                val completionTokens = usage.optInt("completion_tokens", 0)
                val provider = AIProvider.getProviderId(context)
                ApiUsageTracker.recordCall(context, promptTokens, completionTokens, provider)
            }
        } catch (e: Exception) {
            Log.w("LLMClient", "Could not parse usage: ${e.message}")
        }

        val message = json
            .optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")

        if (message == null) {
            return LLMResponse("No response from AI", emptyList())
        }

        // Path 1: Native function calling — tool_calls is a JSON array in the message
        val nativeToolCalls = message.optJSONArray("tool_calls")
        if (nativeToolCalls != null && nativeToolCalls.length() > 0) {
            val toolCalls = mutableListOf<ToolCall>()
            for (i in 0 until nativeToolCalls.length()) {
                val tc = nativeToolCalls.optJSONObject(i) ?: continue
                val function = tc.optJSONObject("function") ?: continue
                val name = function.optString("name", "")
                val argsStr = function.optString("arguments", "{}")
                val args = mutableMapOf<String, Any>()
                try {
                    val argsJson = JSONObject(argsStr)
                    val keys = argsJson.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        args[key] = argsJson.get(key)
                    }
                } catch (e: Exception) {
                    Log.w("LLMClient", "Could not parse tool args: $argsStr")
                }
                if (name.isNotEmpty()) {
                    toolCalls.add(ToolCall(name, args))
                }
            }
            val content = message.optString("content", "")
            Log.i("LLMClient", "Native function calling: ${toolCalls.size} tool call(s): ${toolCalls.map { it.name }}")
            return LLMResponse(content.ifEmpty { "Executing..." }, toolCalls)
        }

        // Path 2: Manual JSON in content (Z.ai, older models)
        val content = message.optString("content")
        if (content.isBlank()) {
            return LLMResponse("(empty response)", emptyList())
        }
        return parseResponse(content)
    }

    /**
     * Parse Gemini-format response.
     */
    private fun parseGeminiResponse(responseBody: String): LLMResponse {
        val json = JSONObject(responseBody)

        // Track token usage (Gemini returns usageMetadata)
        try {
            val usage = json.optJSONObject("usageMetadata")
            if (usage != null) {
                val promptTokens = usage.optInt("promptTokenCount", 0)
                val completionTokens = usage.optInt("candidatesTokenCount", 0)
                ApiUsageTracker.recordCall(context, promptTokens, completionTokens, "gemini")
            }
        } catch (e: Exception) {
            Log.w("LLMClient", "Could not parse Gemini usage: ${e.message}")
        }

        val candidates = json.optJSONArray("candidates")
        val content = candidates
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?.optJSONObject(0)
            ?.optString("text")
            ?: "No response from Gemini"
        // Gemini uses manual JSON format (no native function calling in our impl)
        return parseResponse(content)
    }

    /**
     * Parses the LLM's response content into the phone's expected format.
     * Expected: {"reply": "...", "tool_calls": [{"name": "...", "args": {...}}]}
     */
    private fun parseResponse(content: String): LLMResponse {
        var cleaned = content.trim()

        // Step 1: Remove <think>...</think> tags (some models output these)
        cleaned = cleaned.replace(Regex("<think>[\\s\\S]*?</think>", RegexOption.IGNORE_CASE), "")
        
        // Step 2: Remove markdown code fences
        if (cleaned.contains("```")) {
            cleaned = cleaned.replace(Regex("^```(?:json)?\\s*"), "")
                .replace(Regex("\\s*```$"), "")
        }

        // Step 3: Try to extract JSON from the content
        // The LLM might output text before/after the JSON, so we find the first { and last }
        val jsonStr = extractJson(cleaned)

        return try {
            val json = JSONObject(jsonStr)
            val reply = json.optString("reply", "Done.")
            val toolCalls = mutableListOf<ToolCall>()
            val callsArray = json.optJSONArray("tool_calls")
            if (callsArray != null) {
                for (i in 0 until callsArray.length()) {
                    val call = callsArray.optJSONObject(i) ?: continue
                    val name = call.optString("name", "")
                    val argsObj = call.optJSONObject("args") ?: JSONObject()
                    val args = mutableMapOf<String, Any>()
                    val keys = argsObj.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        args[key] = argsObj.get(key)
                    }
                    if (name.isNotEmpty()) {
                        toolCalls.add(ToolCall(name, args))
                    }
                }
            }

            // FALLBACK: If JSON parsing found no tool_calls, scan the reply text for
            // tool calls written in plain text format. Some LLMs (especially smaller
            // models) write tool calls as text instead of JSON, e.g.:
            //   "Tool: searchInApp(package=com.google.android.youtube, query=BLACKPINK)"
            //   "Calling: tap(970, 180)"
            //   "I'll use searchInApp(package=youtube, query=BLACKPINK)"
            // This extracts them so the agent loop can actually execute them.
            if (toolCalls.isEmpty()) {
                val textCalls = extractToolCallsFromText(content)
                if (textCalls.isNotEmpty()) {
                    Log.i("LLMClient", "Recovered ${textCalls.size} tool call(s) from plain text: ${textCalls.map { it.name }}")
                    toolCalls.addAll(textCalls)
                }
            }

            LLMResponse(reply, toolCalls)
        } catch (e: Exception) {
            // JSON parsing failed — try to extract tool calls from plain text
            val textCalls = extractToolCallsFromText(content)
            if (textCalls.isNotEmpty()) {
                Log.i("LLMClient", "JSON parse failed but recovered ${textCalls.size} tool call(s) from text: ${textCalls.map { it.name }}")
                // Strip the "Tool: ..." text from the reply so the user doesn't see raw tool syntax
                val cleanReply = content
                    .replace(Regex("(?i)(tool|calling|action)\\s*:\\s*\\w+\\s*\\([^)]*\\)"), "")
                    .replace(Regex("\\b\\w+\\([^)]*package=[^)]*\\)"), "")
                    .trim()
                LLMResponse(cleanReply.ifEmpty { "Executing..." }, textCalls)
            } else {
                Log.w("LLMClient", "Failed to parse JSON from response: ${content.take(200)}")
                LLMResponse(content, emptyList())
            }
        }
    }

    /**
     * Extract tool calls from plain text when the LLM writes them as text instead of JSON.
     * Detects patterns like:
     *   "Tool: searchInApp(package=com.google.android.youtube, query=BLACKPINK)"
     *   "calling: tap(970, 180)"
     *   "I'll use type(text=hello)"
     *
     * Returns a list of ToolCall objects. Empty list if none found.
     */
    private fun extractToolCallsFromText(text: String): List<ToolCall> {
        val calls = mutableListOf<ToolCall>()

        // Pattern: optional "Tool:" or "Calling:" prefix, then toolName(key=value, key=value, ...)
        // Also matches: toolName("value", 123, key=value)
        val pattern = Regex(
            "(?i)(?:tool|calling|action|use|using)?\\s*[:]?\\s*([a-z][a-z0-9_]*)\\s*\\(\\s*([^)]*)\\)"
        )

        for (match in pattern.findAll(text)) {
            val toolName = match.groupValues[1].lowercase()
            val argsStr = match.groupValues[2].trim()

            // Skip common false positives (English words that look like function calls)
            if (toolName in setOf("e.g", "example", "see", "note", "if", "when", "for", "step", "the", "this", "that")) {
                continue
            }

            // Parse the args string: "package=com.google.android.youtube, query=BLACKPINK"
            // or: "970, 180" (positional args for tap, swipe, etc.)
            // or: "text=hello, count=3"
            val args = mutableMapOf<String, Any>()
            val parts = argsStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            for ((index, part) in parts.withIndex()) {
                val eqIdx = part.indexOf('=')
                if (eqIdx > 0) {
                    val key = part.substring(0, eqIdx).trim().lowercase()
                    val value = part.substring(eqIdx + 1).trim().trim('"').trim('\'')
                    args[key] = value
                } else {
                    // Positional arg — use generic key based on position
                    // For tap(x, y): args[0]=x, args[1]=y
                    val value = part.trim('"').trim('\'')
                    // Try to convert to number if it looks like one
                    args[index.toString()] = value.toIntOrNull() ?: value
                }
            }

            if (args.isNotEmpty() || toolName in setOf("submitinput", "pressenter", "pressback", "presshome", "readscreen", "readscreens", "recallall")) {
                calls.add(ToolCall(toolName, args))
            }
        }

        return calls
    }

    /**
     * Extract the first valid JSON object from a string that may contain
     * other text before or after the JSON.
     * 
     * Example: "I'll search for that.\n{"reply": "...", "tool_calls": [...]}"
     * Returns: '{"reply": "...", "tool_calls": [...]}'
     */
    private fun extractJson(text: String): String {
        val trimmed = text.trim()
        
        // Fast path: if the entire string is already valid JSON, return it
        try {
            JSONObject(trimmed)
            return trimmed
        } catch (e: Exception) {
            // Not pure JSON — need to extract
        }

        // Find the first { and try to find the matching }
        val firstBrace = trimmed.indexOf('{')
        if (firstBrace < 0) return trimmed  // no JSON at all

        // Try progressively longer substrings until we find valid JSON
        var depth = 0
        var inString = false
        var escape = false
        for (i in firstBrace until trimmed.length) {
            val c = trimmed[i]
            if (escape) {
                escape = false
                continue
            }
            when (c) {
                '\\' -> escape = true
                '"' -> inString = !inString
                '{' -> if (!inString) depth++
                '}' -> {
                    if (!inString) {
                        depth--
                        if (depth == 0) {
                            // Found the complete JSON object
                            val candidate = trimmed.substring(firstBrace, i + 1)
                            try {
                                JSONObject(candidate)  // verify it's valid
                                return candidate
                            } catch (e: Exception) {
                                // Not valid JSON — keep looking
                            }
                        }
                    }
                }
            }
        }

        // Fallback: return everything from first { to end
        return trimmed.substring(firstBrace)
    }
}
