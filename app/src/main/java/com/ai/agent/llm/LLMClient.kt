package com.ai.agent.llm

import android.content.Context
import android.util.Log
import com.ai.agent.util.LogCapture
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

    // v4.0.21: Track the tool filter size from the LAST request so the parser
    // knows whether text-extracted tool calls are valid. When toolFilter is
    // empty (Tier 1 greeting), the model was NEVER asked to call tools, so
    // any "tool calls" found in text are false positives (e.g., "I can help
    // with apps(open, tap, navigate)" → fake "apps" tool call). Skip them.
    @Volatile
    private var lastToolFilterSize: Int = -1  // -1 = all tools (null), 0 = none, N = filtered

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
     *
     * v3.2.9: toolFilter — optional list of tool names to expose via OpenAI
     * function-calling. When null, all available tools are sent (Tier 3
     * full-prompt behavior). When empty, no tools array is sent at all
     * (Tier 1 greeting). When non-empty, only those tools are sent.
     */
    suspend fun chat(
        userMessage: String,
        systemPrompt: String,
        toolFilter: List<String>? = null,
    ): LLMResponse = withContext(Dispatchers.IO) {
        val provider = AIProvider.getCurrentProvider(context)
        val apiKey = AIProvider.getApiKey(context)
        val endpoint = AIProvider.getEndpoint(context)
        val model = AIProvider.getModel(context)

        LogCapture.i("LLMClient", "Provider: ${provider.id}, Model: $model, Endpoint: $endpoint, tools=${toolFilter?.size ?: "all"}")

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
                // v3.8.18: max_tokens per tier — INCREASED for chain-of-thought reasoning.
                // Tier 1 (empty filter = no tools) → 100 tokens (short reply, no reasoning needed)
                // Tier 2 (non-empty filter = contextual tools) → 1500 tokens (room for reasoning + tool call)
                // Tier 3 (null filter = all tools) → 3000 tokens (complex tasks need deep thinking)
                // Reasoning tokens are CHEAP. Tool call iterations are EXPENSIVE. Think more, call fewer tools.
                val maxTokens = when {
                    toolFilter != null && toolFilter.isEmpty() -> 100
                    toolFilter != null -> 1500
                    else -> 3000
                }
                val requestBody = when (provider.id) {
                    "gemini" -> buildGeminiRequest(model, userMessage, systemPrompt)
                    else -> buildOpenAIRequest(model, userMessage, systemPrompt, provider.id, toolFilter, maxTokens)
                }

                // v4.0.21: Record tool filter size for the parser (skip false-positive
                // text tool calls when 0 tools were sent).
                lastToolFilterSize = toolFilter?.size ?: -1

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
                    "deepinfra", "fireworks", "anthropic", "apikeys_in", "cline" -> {
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
                    // v3.2.4: "zai" sandbox provider removed. Only "zai_direct" + "custom" remain.
                    // If user has a custom API key with sandbox URL, it uses "custom" provider path.
                    "zai" -> {
                        // Legacy fallback — treat as custom (no auth header)
                    }
                }

                val request = requestBuilder.post(requestBody.toRequestBody(jsonMediaType)).build()

                Log.d("LLMClient", "Request attempt $attempt to $endpoint")
                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                // v2.1.1: Smarter rate-limit + error handling.
                if (response.code == 429) {
                    // Gemini returns 429 for BOTH per-minute (15/min free) AND daily (1500/day) limits.
                    // Detect which one + wait appropriately.
                    val isDaily = responseBody.contains("quota", ignoreCase = true) ||
                            responseBody.contains("daily", ignoreCase = true) ||
                            responseBody.contains("exhausted", ignoreCase = true)
                    val waitMs = if (isDaily) {
                        // Daily quota exhausted — no point retrying, return error immediately.
                        LogCapture.w("LLMClient", "Daily quota exhausted (429) — not retrying")
                        lastError = "Daily quota exhausted. Try a different provider or wait until tomorrow."
                        return@withContext LLMResponse(
                            "⚠️ Daily quota exhausted for ${provider.name}.\n\n" +
                                    "What to do:\n" +
                                    "• Switch to a different provider (Hub → LLM → Configure)\n" +
                                    "• Or wait until tomorrow (resets at midnight Pacific time)\n" +
                                    "• Or use Z.ai Direct (300 req/day free)",
                            emptyList()
                        )
                    } else {
                        // Per-minute limit — wait longer between retries (Gemini free tier = 15/min).
                        attempt * 8000L  // 8s, 16s, 24s
                    }
                    LogCapture.w("LLMClient", "Rate limited (429) ${if (isDaily) "[daily]" else "[per-minute]"}, waiting ${waitMs}ms before retry $attempt/3")
                    lastError = "Rate limited (429). ${if (isDaily) "Daily quota exhausted." else "Per-minute limit — waiting..."}"
                    if (!isDaily) {
                        Thread.sleep(waitMs)
                        continue
                    }
                }

                // v2.1.1: Handle Cline 402 (insufficient credits) + other payment-required errors.
                if (response.code == 402) {
                    LogCapture.w("LLMClient", "Payment required (402): $responseBody")
                    return@withContext LLMResponse(
                        "⚠️ Insufficient credits on ${provider.name}.\n\n" +
                                responseBody.take(300).let {
                                    if (it.contains("balance", ignoreCase = true)) it
                                    else "Add credits at the provider's dashboard."
                                } +
                                "\n\nFor Cline: app.cline.bot/credits",
                        emptyList()
                    )
                }

                if (!response.isSuccessful) {
                    // v2.1.2: Strip HTML from error body (was showing raw HTML to user).
                    val cleanedBody = cleanErrorBody(responseBody)
                    val code = response.code
                    LogCapture.e("LLMClient", "API error $code: ${cleanedBody.take(200)}")

                    // 502/503/504 = provider server down/overloaded. Retry with longer wait.
                    if (code in setOf(500, 502, 503, 504)) {
                        val waitMs = attempt * 4000L  // 4s, 8s, 12s
                        LogCapture.w("LLMClient", "Server error $code (attempt $attempt/3), waiting ${waitMs}ms")
                        lastError = "Server error $code"
                        if (attempt < 3) {
                            Thread.sleep(waitMs)
                            continue
                        }
                        return@withContext LLMResponse(
                            "⚠️ ${provider.name} server error ($code).\n\n" +
                                    "${describeServerError(code)}\n\n" +
                                    "What to do:\n" +
                                    "• Wait a few minutes + retry (server overloaded)\n" +
                                    "• Or switch provider (Hub → LLM → Configure)\n" +
                                    "• Recommended backup: Groq (fast, 14,400/day free)",
                            emptyList()
                        )
                    }

                    // 401 / 403 = auth error
                    if (code == 401 || code == 403) {
                        return@withContext LLMResponse(
                            "⚠️ Authentication failed ($code) for ${provider.name}.\n\n" +
                                    "Check your API key in Hub → LLM → Configure.\n" +
                                    (if (cleanedBody.isNotBlank()) "Server: ${cleanedBody.take(150)}" else ""),
                            emptyList()
                        )
                    }

                    // Other errors (400, 404, etc.)
                    if (attempt < 3) {
                        Thread.sleep(2000)
                        lastError = "API returned $code"
                        continue
                    }
                    return@withContext LLMResponse(
                        "Error: API returned $code. ${cleanedBody.take(200)}",
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
     * v3.3.2: chatWithFailover — wraps chat() with multi-binding auto-failover.
     *
     * When the active binding fails permanently (429 daily quota / 402 payment
     * required / 401 auth / 403 forbidden), this function:
     *   1. Records the failure in BindingHealthStore (marks it unhealthy).
     *   2. If auto-fallback is enabled, picks the next enabled healthy binding
     *      via SavedModelsStore.resolveBinding().
     *   3. Re-applies that binding to AIProvider + retries the chat call.
     *   4. If all bindings fail, returns the last error.
     *
     * If auto-fallback is DISABLED, behaves exactly like chat() (no failover).
     */
    suspend fun chatWithFailover(
        userMessage: String,
        systemPrompt: String,
        toolFilter: List<String>? = null,
    ): LLMResponse = withContext(Dispatchers.IO) {
        val autoFallback = AutoFallbackSettings.isEnabled(context)
        if (!autoFallback) {
            // Auto-fallback disabled — single call, no failover.
            val resp = chat(userMessage, systemPrompt, toolFilter)
            // Still record success/failure for health tracking.
            recordHealthFromResponse(resp)
            return@withContext resp
        }

        // v3.3.9: Read the tier-picked model that SmartRouting.applyModelForRouting
        // just wrote to AIProvider prefs — NOT the saved default. This is what
        // makes Tier 1 (Fast/Cheap) + Tier 3 (Advanced) routing actually work.
        // Previously this called getDefaultModel() which always returned GLM-4.6
        // (the saved default), ignoring the tier-picked Gemini Flash / Grok 4.7.
        val pickedModel = SmartRouting.getCurrentlyRoutedModel(context) ?: run {
            // No routed model — fall back to chat() directly.
            return@withContext chat(userMessage, systemPrompt, toolFilter)
        }
        SavedModelsStore.load(context)
        BindingHealthStore.load(context)
        val activeBindings = pickedModel.activeBindings()
        if (activeBindings.isEmpty()) {
            // No bindings — try chat() directly (might use legacy apiKeyId path).
            val resp = chat(userMessage, systemPrompt, toolFilter)
            recordHealthFromResponse(resp)
            return@withContext resp
        }

        // Try each enabled binding until one succeeds or all fail.
        var lastResp: LLMResponse? = null
        val triedBindings = mutableSetOf<String>()
        for (binding in activeBindings) {
            if (binding.id in triedBindings) continue
            triedBindings.add(binding.id)
            // Apply this binding to AIProvider (so chat() uses it).
            SavedModelsStore.applyBindingForFailover(context, pickedModel.id, binding.id)
            LogCapture.i("LLMClient", "Failover: trying binding ${binding.providerName} (#${binding.priority}) for ${pickedModel.modelName}")
            val resp = chat(userMessage, systemPrompt, toolFilter)
            if (resp.reply.isNotEmpty() && !isPermanentFailure(resp)) {
                // Success — record + return.
                BindingHealthStore.recordSuccess(binding.id, 0, context)
                return@withContext resp
            }
            // Permanent failure — record + try next.
            BindingHealthStore.recordFailure(binding.id, resp.reply.take(100), context)
            lastResp = resp
            LogCapture.w("LLMClient", "Binding ${binding.providerName} failed: ${resp.reply.take(80)}")
        }
        // v3.3.7: All bindings of the picked model failed — try cross-model fallback.
        // This is the missing piece for "Sandbox goes down → try Gemini Flash".
        if (AutoFallbackSettings.isAutoFallbackModelsEnabled(context)) {
            val fallbackChain = SmartRouting.pickFallbackChain(context, pickedModel)
            for (fallbackModel in fallbackChain) {
                LogCapture.i("LLMClient", "Cross-model failover: trying '${fallbackModel.modelName}'")
                SavedModelsStore.applyModelForRouting(context, fallbackModel.id)
                val fallbackBindings = fallbackModel.activeBindings()
                for (binding in fallbackBindings) {
                    SavedModelsStore.applyBindingForFailover(context, fallbackModel.id, binding.id)
                    val resp = chat(userMessage, systemPrompt, toolFilter)
                    if (resp.reply.isNotEmpty() && !isPermanentFailure(resp)) {
                        BindingHealthStore.recordSuccess(binding.id, 0, context)
                        Log.i("LLMClient", "Cross-model failover succeeded with ${fallbackModel.modelName}")
                        return@withContext resp
                    }
                    BindingHealthStore.recordFailure(binding.id, resp.reply.take(100), context)
                    lastResp = resp
                }
            }
        }
        // All bindings + all fallback models failed.
        lastResp ?: LLMResponse("All bindings failed.", emptyList())
    }

    /**
     * Detect if a response indicates a permanent failure (worth failing over).
     */
    private fun isPermanentFailure(resp: LLMResponse): Boolean {
        val r = resp.reply
        return r.startsWith("⚠️ Daily quota exhausted") ||
               r.startsWith("⚠️ Insufficient credits") ||
               r.startsWith("⚠️ Authentication failed") ||
               r.startsWith("⚠️ ${AIProvider.getCurrentProvider(context).name} server error") ||
               r.startsWith("Error: API returned 4") ||
               r.startsWith("Error: API returned 5")
    }

    /**
     * Record success/failure to the currently-active binding's health.
     * v3.3.9: uses the tier-picked routed model, not the saved default.
     */
    private fun recordHealthFromResponse(resp: LLMResponse) {
        val routedModel = SmartRouting.getCurrentlyRoutedModel(context) ?: return
        val active = routedModel.activeBindings().firstOrNull() ?: return
        if (isPermanentFailure(resp)) {
            BindingHealthStore.recordFailure(active.id, resp.reply.take(100), context)
        } else if (resp.reply.isNotEmpty()) {
            BindingHealthStore.recordSuccess(active.id, 0, context)
        }
    }

    /**
     * v3.3.2: Build request body for OpenAI-compatible APIs (OpenRouter, Z.ai, custom).
     */
    private fun buildOpenAIRequest(
        model: String,
        userMessage: String,
        systemPrompt: String,
        providerId: String,
        toolFilter: List<String>? = null,
        maxTokens: Int = 3000,  // v3.8.18: increased for chain-of-thought reasoning
    ): String {
        val supportsNativeTools = providerId in setOf(
            "openrouter", "groq", "openai", "together", "deepinfra",
            "fireworks", "cerebras", "mistral", "apikeys_in", "cline", "custom"
        ) || (providerId == "zai_direct" && model.contains("/"))

        // v4.0.20: Resolve the prompt/tools conflict.
        // The system prompt says "ALWAYS respond in valid JSON only" but when we
        // send the tools array + tool_choice=auto, the API tells the model to use
        // native tool_calls. These contradict — models get confused.
        // Fix: when native tools are sent, replace the JSON Response Format section
        // with a native function calling instruction.
        val effectivePrompt = if (supportsNativeTools) {
            adaptPromptForNativeTools(systemPrompt)
        } else {
            systemPrompt
        }

        val messages = JSONArray()
        messages.put(JSONObject().apply {
            put("role", "system")
            put("content", effectivePrompt)
        })
        messages.put(JSONObject().apply {
            put("role", "user")
            put("content", userMessage)
        })

        val body = JSONObject().apply {
            put("model", model)
            put("messages", messages)
            // v3.6.2: max_tokens per tier — stops brain from rambling on simple tasks.
            put("max_tokens", maxTokens)
            // Z.ai (both proxy and direct) uses "thinking" param
            // v3.9.2: ENABLED thinking — was "disabled" since v3.7.1, which turned OFF
            // the model's reasoning capability. This was the root cause of 18 versions
            // of brain failures. The model CAN reason — we just turned it off.
            if (providerId == "zai" || providerId == "zai_direct") {
                put("thinking", JSONObject().put("type", "enabled"))
            }
            // v4.0.16: RE-ENABLED for providers with native function calling.
            // v4.0.20: supportsNativeTools is now computed at the top of this function.
            if (supportsNativeTools) {
                val tools = buildToolsArray(toolFilter)
                if (tools.length() > 0) {
                    put("tools", tools)
                    put("tool_choice", "auto")
                }
            }
            // v4.0.20: Only force JSON response format for Groq when NOT using native
            // tools. When native tools are active, we want tool_calls in the response
            // (not JSON in content). Forcing json_object would conflict with tool_calls.
            if (providerId == "groq" && !supportsNativeTools) {
                put("response_format", JSONObject().put("type", "json_object"))
            }
        }

        return body.toString()
    }

    /**
     * v4.0.20: Adapt the system prompt for native function calling.
     *
     * The base prompt says "ALWAYS respond in valid JSON only" with
     * {"reply":"...","tool_calls":[...]} format. But when we send the tools array
     * + tool_choice=auto, the API expects the model to return native tool_calls in
     * the message object, NOT JSON in content. These two instructions conflict.
     *
     * This function replaces the JSON Response Format section with a native
     * function calling instruction that tells the model:
     * - Put reasoning/planning in the text reply
     * - Call tools via the tool_calls array (API handles format)
     * - Use the "complete" tool to signal task completion
     */
    private fun adaptPromptForNativeTools(prompt: String): String {
        // The native function calling instruction that replaces the JSON section.
        val nativeSection = """## Response Format
You have native function calling enabled. Tool definitions are provided via the
tools array. Call tools using the standard tool_calls format.

- Put your FULL reasoning + plan in your text reply (can be very long — think deeply)
- Call tools via the tool_calls array — the API handles the JSON format for you
- Do NOT write tool calls as JSON text in your reply — use the native format
- To COMPLETE a task: call the "complete" tool with {success: true/false, reason: "..."}
- If no tools are needed (just answering a question): reply with text only, no tool_calls
- You can call MULTIPLE tools in one reply (batch read-only tools for efficiency)"""

        // Replace the "## Response Format" section (from the header to end of prompt).
        val marker = "## Response Format"
        val markerIdx = prompt.indexOf(marker)
        if (markerIdx < 0) return prompt  // no Response Format section — leave as-is

        return prompt.substring(0, markerIdx) + nativeSection
    }

    /**
     * Build the OpenAI-compatible "tools" array describing all available tools.
     * Used with native function calling (Groq, OpenRouter, Together, Custom).
     *
     * This is FAR more reliable than asking the LLM to write JSON in the reply text —
     * the model returns structured tool_calls directly, no parsing needed.
     *
     * v3.2.9: filter — optional list of tool names. When non-null, only tools
     * whose name is in the filter are included. When empty, the result is an
     * empty array (no tools at all — Tier 1 greeting case).
     */
    private fun buildToolsArray(filter: List<String>? = null): JSONArray {
        // v3.7.3: Use real parameter schemas from ToolSchemas (was empty {} before).
        // The brain can now SEE what parameters each tool needs instead of guessing.
        // Covers all 130 tools — see ToolSchemas.kt for the full registry.
        return com.ai.agent.llm.ToolSchemas.buildToolsArray(filter)
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

        // v3.2.10: Extract content as a string, handling BOTH:
        //   - Plain string content (OpenAI/Groq/Z.ai: message.content = "...")
        //   - Array of content blocks (Anthropic Claude via OpenRouter:
        //     message.content = [{type:"text", text:"..."}, {type:"text", text:"..."}])
        val contentStr = extractMessageContent(message)

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
                    // v4.0.20: Model returned non-JSON arguments (e.g., "name=foo, action=bar")
                    // Parse as key=value pairs, handling values with colons/commas
                    Log.w("LLMClient", "Args not JSON, trying key=value parse: $argsStr")
                    parseArgsAsKeyValue(argsStr, args)
                }
                if (name.isNotEmpty()) {
                    toolCalls.add(ToolCall(name, args))
                }
            }
            Log.i("LLMClient", "Native function calling: ${toolCalls.size} tool call(s): ${toolCalls.map { it.name }}")
            return LLMResponse(contentStr.ifEmpty { "Executing..." }, toolCalls)
        }

        // Path 2: Manual JSON in content (Z.ai, older models, Claude without tools)
        if (contentStr.isBlank()) {
            return LLMResponse("(empty response)", emptyList())
        }
        return parseResponse(contentStr)
    }

    /**
     * v3.2.10: Extract the message content as a single string, handling both
     * OpenAI-style plain-string content AND Anthropic Claude's array-of-blocks
     * content (which OpenRouter passes through).
     *
     * OpenAI/Groq/Z.ai:
     *   message.content = "Hello"
     *
     * Anthropic Claude (via OpenRouter):
     *   message.content = [
     *     {"type": "text", "text": "Hello "},
     *     {"type": "text", "text": "world"}
     *   ]
     *
     * Some providers also return reasoning blocks (type="thinking" for Claude,
     * type="reasoning" for DeepSeek R1) — we skip those to keep the visible
     * reply clean.
     */
    private fun extractMessageContent(message: JSONObject): String {
        // Fast path: content is a plain string (most providers).
        val contentStr = message.optString("content", "")
        if (contentStr.isNotEmpty() && !contentStr.startsWith("[")) {
            return contentStr
        }
        // Slow path: content might be an array of blocks.
        val contentArr = message.optJSONArray("content") ?: return contentStr
        val sb = StringBuilder()
        for (i in 0 until contentArr.length()) {
            val block = contentArr.optJSONObject(i) ?: continue
            val type = block.optString("type", "text")
            // Skip reasoning/thinking blocks — we only want the visible reply text.
            if (type == "thinking" || type == "reasoning") continue
            // Text block: {"type":"text", "text":"..."}
            val text = block.optString("text", "")
            if (text.isNotEmpty()) {
                if (sb.isNotEmpty()) sb.append("\n")
                sb.append(text)
            }
        }
        return sb.toString()
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
            //
            // v4.0.21: SKIP this when 0 tools were sent (Tier 1 greeting). The model
            // was never asked to call tools, so any "tool calls" found in text are
            // false positives (e.g., "I can help with apps(open, tap, navigate)").
            if (toolCalls.isEmpty() && lastToolFilterSize != 0) {
                val textCalls = extractToolCallsFromText(content)
                if (textCalls.isNotEmpty()) {
                    Log.i("LLMClient", "Recovered ${textCalls.size} tool call(s) from plain text: ${textCalls.map { it.name }}")
                    toolCalls.addAll(textCalls)
                }
            }

            LLMResponse(reply, toolCalls)
        } catch (e: Exception) {
            // JSON parsing failed — try to extract tool calls from plain text
            // v4.0.21: Skip when 0 tools were sent (Tier 1 — false positives).
            val textCalls = if (lastToolFilterSize != 0) extractToolCallsFromText(content) else emptyList()
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

            // v4.0.20: Use the robust arg parser (handles commas/colons inside values).
            // Was: naive split(",") which broke values like action=reply: Hi Mom, I'll call back
            val args = mutableMapOf<String, Any>()
            parseArgsAsKeyValue(argsStr, args)

            if (args.isNotEmpty() || toolName in setOf("submitinput", "pressenter", "pressback", "presshome", "readscreen", "readscreens", "recallall")) {
                calls.add(ToolCall(toolName, args))
            }
        }

        return calls
    }

    /**
     * v4.0.20: Robust key=value parser for tool call arguments.
     *
     * Handles values containing commas, colons, and spaces — which the old naive
     * split(",") approach broke. Used by:
     *   1. Native function calling fallback (when model returns non-JSON args)
     *   2. Text tool call extraction (extractToolCallsFromText)
     *
     * A comma is treated as a separator ONLY when followed by a `key=` pattern.
     * Otherwise it's part of the current value.
     *
     * Examples:
     *   "name=WhatsApp Auto-Reply, triggerType=notification, action=reply: Hi, I'm busy"
     *     → {name: "WhatsApp Auto-Reply", triggerType: "notification", action: "reply: Hi, I'm busy"}
     *   "970, 180"
     *     → {"0": 970, "1": 180}  (positional args)
     */
    private fun parseArgsAsKeyValue(argsStr: String, out: MutableMap<String, Any>) {
        val s = argsStr.trim()
        if (s.isEmpty()) return

        // Split on commas, but only when the comma is followed by key= pattern.
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var quoteChar = '"'
        var i = 0

        while (i < s.length) {
            val c = s[i]
            when {
                (c == '"' || c == '\'') && (!inQuotes || c == quoteChar) -> {
                    if (inQuotes) inQuotes = false else { inQuotes = true; quoteChar = c }
                    current.append(c)
                }
                c == ',' && !inQuotes -> {
                    // Look ahead: is the remainder "word=" (a new key=value pair)?
                    val remainder = s.substring(i + 1).trimStart()
                    val nextKeyMatch = Regex("^([a-zA-Z_][a-zA-Z0-9_]*)\\s*=").find(remainder)
                    if (nextKeyMatch != null) {
                        parts.add(current.toString().trim())
                        current.clear()
                    } else {
                        current.append(c)
                    }
                }
                else -> current.append(c)
            }
            i++
        }
        if (current.toString().trim().isNotEmpty()) {
            parts.add(current.toString().trim())
        }

        for ((index, part) in parts.withIndex()) {
            val eqIdx = part.indexOf('=')
            if (eqIdx > 0) {
                // v4.0.20: Preserve key case — tool handlers expect camelCase
                // (triggerType, not triggertype). Was lowercasing which broke matching.
                val key = part.substring(0, eqIdx).trim()
                var value = part.substring(eqIdx + 1).trim()
                // Strip surrounding quotes
                if (value.length >= 2 && ((value.startsWith('"') && value.endsWith('"')) ||
                        (value.startsWith('\'') && value.endsWith('\'')))) {
                    value = value.substring(1, value.length - 1)
                }
                out[key] = coerceValue(value)
            } else {
                // Positional arg — use generic key based on position
                val value = part.trim().trim('"').trim('\'')
                out[index.toString()] = coerceValue(value)
            }
        }
    }

    /**
     * v4.0.20: Coerce a string value to its native type (Int, Long, Boolean, or String).
     */
    private fun coerceValue(s: String): Any {
        s.toIntOrNull()?.let { return it }
        s.toLongOrNull()?.let { return it }
        if (s == "true") return true
        if (s == "false") return false
        return s
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

    // v2.1.2: Strip HTML from error response bodies so users don't see raw <html>...
    companion object ErrorHelpers {
        /** Strip HTML tags + collapse whitespace. Returns plain text. */
        fun cleanErrorBody(body: String): String {
            if (body.isBlank()) return ""
            // Quick check: if no HTML tags, return as-is (truncated)
            if (!body.contains("<") || !body.contains(">")) {
                return body.trim().take(500)
            }
            // Remove <head>...</head>, <style>...</style>, <script>...</script> entirely
            var cleaned = body
                .replace(Regex("(?is)<head>.*?</head>"), " ")
                .replace(Regex("(?is)<style[^>]*>.*?</style>"), " ")
                .replace(Regex("(?is)<script[^>]*>.*?</script>"), " ")
            // Strip all remaining tags
            cleaned = cleaned.replace(Regex("(?s)<[^>]+>"), " ")
            // Collapse whitespace
            cleaned = cleaned.replace(Regex("\\s+"), " ").trim()
            // Decode common HTML entities
            cleaned = cleaned
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#x27;", "'")
                .replace("&#39;", "'")
            return cleaned.take(500)
        }

        /** Human-readable description for HTTP server errors. */
        fun describeServerError(code: Int, provider: com.ai.agent.llm.AIProvider.ProviderInfo? = null): String {
            val providerName = provider?.name ?: "the provider"
            return when (code) {
                500 -> "$providerName's server had an internal error. This is usually temporary — try again in a minute."
                502 -> "Bad Gateway — $providerName's upstream server is down. The provider may be having an outage."
                503 -> "Service Unavailable — $providerName is temporarily overloaded or in maintenance. Try again shortly."
                504 -> "Gateway Timeout — $providerName took too long to respond. The model may be busy; try a smaller request or wait."
                else -> "$providerName returned an unexpected server error."
            }
        }
    }
}
