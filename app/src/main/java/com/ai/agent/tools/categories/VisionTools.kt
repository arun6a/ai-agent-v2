package com.ai.agent.tools.categories

import android.content.Context
import android.util.Log
import com.ai.agent.accessibility.AgentAccessibilityService
import com.ai.agent.llm.AIProvider
import com.ai.agent.llm.ApiKeyStore
import com.ai.agent.llm.LLMClient
import com.ai.agent.llm.ProviderEndpoints
import com.ai.agent.llm.SavedModelsStore
import com.ai.agent.storage.AgentDatabase
import com.ai.agent.tools.ToolExecutor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Vision (VLM) tools category — extracted from ToolExecutor in v3.3.13.
 *
 * Tools: analyzeScreen, findElement, translateText.
 *
 * Includes all VLM helper functions (moved verbatim from ToolExecutor.kt):
 *   - analyzeScreenWithVLM
 *   - analyzeScreenWithVLMBase64
 *   - analyzeScreenWithVLMBase64Legacy
 *   - analyzeScreenWithBase64VLM
 *   - findElementWithVLM
 *   - captureAndOCR
 *   - isVisionCapableModel
 *   - visionClient
 *
 * The VLM helpers are exposed as `internal` so that ToolExecutor.readScreen
 * and BrowserTools.browserScreenshot can still call them.
 *
 * No behavior changes — code moved verbatim from ToolExecutor.kt.
 */
object VisionTools {

    private const val TAG = "VisionTools"

    internal val visionClient = okhttp3.OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    suspend fun execute(
        call: LLMClient.ToolCall,
        context: Context,
        service: AgentAccessibilityService?,
        @Suppress("UNUSED_PARAMETER") database: AgentDatabase
    ): ToolExecutor.ToolResult {
        return when (call.name) {
            // Vision tools
            "analyzeScreen" -> {
                val prompt = call.args["prompt"] as? String ?: "Describe what's on the screen."
                ToolExecutor.ToolResult(true, analyzeScreenWithVLM(context, service!!, prompt))
            }
            "findElement" -> {
                val description = call.args["description"] as? String ?: return ToolExecutor.ToolResult(false, "missing description")
                val coords = findElementWithVLM(context, service!!, description)
                if (coords != null) {
                    ToolExecutor.ToolResult(true, "Found at x=${coords.first}, y=${coords.second}")
                } else {
                    ToolExecutor.ToolResult(false, "Could not find: $description")
                }
            }
            // === TRANSLATE (via LLM) ===
            "translateText" -> {
                val text = call.args["text"] as? String ?: return ToolExecutor.ToolResult(false, "missing text")
                val targetLang = call.args["targetLanguage"] as? String ?: "English"
                ToolExecutor.ToolResult(true, "Please translate this text to $targetLang:\n$text")
            }
            else -> ToolExecutor.ToolResult(false, "Unknown vision tool: ${call.name}")
        }
    }

    /**
     * v2.5.2: Analyze screenshot (already captured as base64) using VLM.
     * Reuses the existing screenshot — no second capture.
     */
    internal suspend fun analyzeScreenWithVLMBase64(
        context: Context,
        screenshotBase64: String,
        prompt: String
    ): String {
        Log.d(TAG, "VLM (reusing screenshot): ${screenshotBase64.length} chars")

        // v3.3.11: use the VISION model from Vision Settings, not the main chat model.
        // This lets the user pick a cheap VLM (e.g. Gemini Flash) for screen reading
        // while using a different model (e.g. GLM-4.6) for chat.
        val visionModel = SavedModelsStore.getVisionModel(context)
        if (visionModel == null) {
            Log.w(TAG, "VLM call requested but no vision model configured — falling back to main model")
            return analyzeScreenWithVLMBase64Legacy(context, screenshotBase64, prompt)
        }

        // Resolve the vision model's binding (same hybrid routing as chat).
        SavedModelsStore.load(context)
        ApiKeyStore.load(context)
        val resolved = SavedModelsStore.resolveBinding(context, visionModel, false)
        if (resolved == null) {
            Log.w(TAG, "Vision model '${visionModel.modelName}' has no resolvable binding — falling back to main model")
            return analyzeScreenWithVLMBase64Legacy(context, screenshotBase64, prompt)
        }
        val (binding, key) = resolved
        val endpoint = SavedModelsStore.resolveEndpointForBinding(visionModel, binding, key)
        val actualModelId = SavedModelsStore.resolveActualModelIdForBinding(context, visionModel, binding)

        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val escapedPrompt = prompt.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
                val bodyStr = """{"model":"$actualModelId","messages":[{"role":"user","content":[{"type":"text","text":"$escapedPrompt"},{"type":"image_url","image_url":{"url":"data:image/jpeg;base64,$screenshotBase64"}}]}]}"""
                val body = bodyStr.toRequestBody("application/json".toMediaType())

                val requestBuilder = okhttp3.Request.Builder()
                    .url(endpoint)
                    .header("Content-Type", "application/json")

                // v3.3.11: use the binding's auth scheme via ProviderEndpoints.
                val endpointInfo = ProviderEndpoints.byId(binding.providerId)
                val authScheme = endpointInfo?.authScheme ?: ProviderEndpoints.AuthScheme.BEARER
                when (authScheme) {
                    ProviderEndpoints.AuthScheme.BEARER -> {
                        if (key.apiKey.isNotEmpty() && key.apiKey != "sandbox") {
                            requestBuilder.header("Authorization", "Bearer ${key.apiKey}")
                        }
                    }
                    ProviderEndpoints.AuthScheme.X_API_KEY -> {
                        if (key.apiKey.isNotEmpty()) {
                            requestBuilder.header("x-api-key", key.apiKey)
                            requestBuilder.header("anthropic-version", "2023-06-01")
                        }
                    }
                    ProviderEndpoints.AuthScheme.QUERY_PARAM -> {
                        // Gemini uses ?key=<key> — but vision calls use a different endpoint shape.
                        // For Gemini vision, the endpoint is /models/<model>:generateContent?key=<key>
                        // Fall back to legacy for Gemini for now.
                    }
                    ProviderEndpoints.AuthScheme.NONE -> { /* no auth */ }
                }

                val request = requestBuilder.post(body).build()
                val response = visionClient.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    Log.e(TAG, "VLM API error ${response.code}: ${responseBody.take(200)}")
                    return@withContext "VLM error: ${response.code}"
                }

                val json = org.json.JSONObject(responseBody)
                val content = json
                    .optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content", "")
                    ?: ""

                Log.i(TAG, "VLM result: ${content.length} chars (model: $actualModelId via ${binding.providerName})")
                content
            } catch (e: Exception) {
                Log.e(TAG, "VLM call failed", e)
                "VLM error: ${e.message}"
            }
        }
    }

    /**
     * v3.3.11: Legacy VLM call using the MAIN chat model (pre-v3.3.11 behavior).
     * Used as a fallback when no vision model is configured.
     */
    private suspend fun analyzeScreenWithVLMBase64Legacy(
        context: Context,
        screenshotBase64: String,
        prompt: String
    ): String {
        val provider = AIProvider.getCurrentProvider(context)
        val model = AIProvider.getModel(context)
        val apiKey = AIProvider.getApiKey(context)
        val isVisionModel = isVisionCapableModel(provider.id, model)

        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val escapedPrompt = prompt.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
                val bodyStr = """{"model":"$model","messages":[{"role":"user","content":[{"type":"text","text":"$escapedPrompt"},{"type":"image_url","image_url":{"url":"data:image/jpeg;base64,$screenshotBase64"}}]}]}"""
                val body = bodyStr.toRequestBody("application/json".toMediaType())

                val requestBuilder = okhttp3.Request.Builder()
                    .header("Content-Type", "application/json")

                when {
                    provider.id == "openrouter" && isVisionModel -> {
                        requestBuilder.url(provider.defaultEndpoint)
                        requestBuilder.header("Authorization", "Bearer $apiKey")
                        requestBuilder.header("HTTP-Referer", "https://github.com/arun6a/ai-agent-apk")
                        requestBuilder.header("X-Title", "AI Agent Phone")
                    }
                    provider.id == "groq" && isVisionModel -> {
                        requestBuilder.url(provider.defaultEndpoint)
                        requestBuilder.header("Authorization", "Bearer $apiKey")
                    }
                    else -> {
                        requestBuilder.url(AIProvider.getVisionUrl(context))
                    }
                }

                val request = requestBuilder.post(body).build()
                val response = visionClient.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    Log.e(TAG, "VLM API error ${response.code}: ${responseBody.take(200)}")
                    return@withContext "VLM error: ${response.code}"
                }

                val json = org.json.JSONObject(responseBody)
                val content = json
                    .optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content", "")
                    ?: ""

                Log.i(TAG, "VLM result (legacy): ${content.length} chars")
                content
            } catch (e: Exception) {
                Log.e(TAG, "VLM call failed (legacy)", e)
                "VLM error: ${e.message}"
            }
        }
    }

    /**
     * v2.2.3: Capture screenshot via accessibility service + run ML Kit OCR.
     * Returns recognized text, or null if capture/OCR failed.
     * Free, offline, ~200-500ms. No API calls.
     */
    @Suppress("unused")
    private suspend fun captureAndOCR(service: AgentAccessibilityService): String? {
        val screenshotDeferred = CompletableDeferred<String?>()
        service.captureScreen { base64 ->
            if (base64 != null) {
                try {
                    val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
                    val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bitmap != null) {
                        val mlText = com.ai.agent.ml.MLKitHelper.tryRecognize(bitmap)
                        screenshotDeferred.complete(mlText)
                    } else {
                        screenshotDeferred.complete(null)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "captureAndOCR: bitmap decode failed", e)
                    screenshotDeferred.complete(null)
                }
            } else {
                screenshotDeferred.complete(null)
            }
        }
        return screenshotDeferred.await()
    }

    /**
     * Analyze the screen using the VLM (Vision Language Model).
     * Uses the current provider if it's vision-capable, otherwise falls back
     * to the sandbox /api/llm/vision endpoint.
     *
     * Costs 1 API call. Use sparingly — readScreen() should be tried first
     * (it's free via accessibility + ML Kit).
     */
    internal suspend fun analyzeScreenWithVLM(
        context: Context,
        service: AgentAccessibilityService,
        prompt: String
    ): String {
        // Take screenshot — use CompletableDeferred to bridge callback to coroutine
        val screenshotDeferred = CompletableDeferred<String?>()
        service.captureScreen { base64 ->
            screenshotDeferred.complete(base64)
        }
        val screenshotBase64 = screenshotDeferred.await()

        if (screenshotBase64 == null) {
            return "Failed to capture screenshot"
        }

        Log.d(TAG, "Sending screenshot to VLM: ${screenshotBase64.length} chars")

        // Decide where to send the request based on current provider + model
        val provider = AIProvider.getCurrentProvider(context)
        val model = AIProvider.getModel(context)
        val apiKey = AIProvider.getApiKey(context)

        val isVisionModel = isVisionCapableModel(provider.id, model)
        Log.d(TAG, "VLM routing: provider=${provider.id}, model=$model, isVisionModel=$isVisionModel")

        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val escapedPrompt = prompt.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
                val bodyStr = """{"model":"$model","messages":[{"role":"user","content":[{"type":"text","text":"$escapedPrompt"},{"type":"image_url","image_url":{"url":"data:image/jpeg;base64,$screenshotBase64"}}]}]}"""
                val body = bodyStr.toRequestBody("application/json".toMediaType())

                val requestBuilder = okhttp3.Request.Builder()
                    .header("Content-Type", "application/json")

                when {
                    // OpenRouter with a vision model → call OpenRouter directly
                    provider.id == "openrouter" && isVisionModel -> {
                        requestBuilder.url(provider.defaultEndpoint)
                        requestBuilder.header("Authorization", "Bearer $apiKey")
                        requestBuilder.header("HTTP-Referer", "https://github.com/arun6a/ai-agent-apk")
                        requestBuilder.header("X-Title", "AI Agent Phone")
                    }
                    // Groq with vision model → call Groq directly
                    provider.id == "groq" && isVisionModel -> {
                        requestBuilder.url(provider.defaultEndpoint)
                        requestBuilder.header("Authorization", "Bearer $apiKey")
                    }
                    // Gemini → use sandbox proxy (Gemini's multimodal format is different — TODO)
                    // Z.ai proxy, custom, or non-vision models → use sandbox proxy
                    else -> {
                        requestBuilder.url(AIProvider.getVisionUrl(context))
                    }
                }

                val request = requestBuilder.post(body).build()
                val response = visionClient.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    Log.e(TAG, "VLM error ${response.code}: ${responseBody.take(300)}")
                    return@withContext "VLM error: ${response.code}. Body: ${responseBody.take(200)}"
                }

                val json = org.json.JSONObject(responseBody)
                val content = json
                    .optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")
                    ?: "No response from VLM"

                Log.d(TAG, "VLM response: ${content.take(200)}...")
                content
            } catch (e: Exception) {
                Log.e(TAG, "VLM call failed", e)
                "Vision error: ${e.message}"
            }
        }
    }

    /**
     * Analyze a base64 image (from browser screenshot, not from accessibility service).
     * Same logic as analyzeScreenWithVLM but takes base64 directly.
     */
    internal suspend fun analyzeScreenWithBase64VLM(
        context: Context,
        screenshotBase64: String,
        prompt: String
    ): String {
        if (screenshotBase64.isEmpty()) return "Empty screenshot"

        Log.d(TAG, "VLM (base64 input): ${screenshotBase64.length} chars")

        val provider = AIProvider.getCurrentProvider(context)
        val model = AIProvider.getModel(context)
        val apiKey = AIProvider.getApiKey(context)
        val isVisionModel = isVisionCapableModel(provider.id, model)
        Log.d(TAG, "VLM routing: provider=${provider.id}, model=$model, isVisionModel=$isVisionModel")

        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val escapedPrompt = prompt.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
                val bodyStr = """{"model":"$model","messages":[{"role":"user","content":[{"type":"text","text":"$escapedPrompt"},{"type":"image_url","image_url":{"url":"data:image/jpeg;base64,$screenshotBase64"}}]}]}"""
                val body = bodyStr.toRequestBody("application/json".toMediaType())

                val requestBuilder = okhttp3.Request.Builder()
                    .header("Content-Type", "application/json")

                when {
                    provider.id == "openrouter" && isVisionModel -> {
                        requestBuilder.url(provider.defaultEndpoint)
                        requestBuilder.header("Authorization", "Bearer $apiKey")
                        requestBuilder.header("HTTP-Referer", "https://github.com/arun6a/ai-agent-apk")
                        requestBuilder.header("X-Title", "AI Agent Phone")
                    }
                    provider.id == "groq" && isVisionModel -> {
                        requestBuilder.url(provider.defaultEndpoint)
                        requestBuilder.header("Authorization", "Bearer $apiKey")
                    }
                    else -> {
                        requestBuilder.url(AIProvider.getVisionUrl(context))
                    }
                }

                val request = requestBuilder.post(body).build()
                val response = visionClient.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    Log.e(TAG, "VLM error ${response.code}: ${responseBody.take(300)}")
                    return@withContext "VLM error: ${response.code}"
                }

                val json = org.json.JSONObject(responseBody)
                json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content") ?: "No response from VLM"
            } catch (e: Exception) {
                Log.e(TAG, "VLM (base64) failed", e)
                "Vision error: ${e.message}"
            }
        }
    }

    /**
     * Check if a (providerId, modelName) combo is vision-capable.
     * Conservative heuristic — when in doubt, return false (fall back to sandbox proxy).
     */
    private fun isVisionCapableModel(providerId: String, model: String): Boolean {
        val m = model.lowercase()
        return when (providerId) {
            "openrouter" -> {
                m.contains("gemma-4") || m.contains("nemotron-3-nano-omni") ||
                m.contains("vision") || m.contains("vl") || m.contains("llava") ||
                m.contains("qwen2.5-vl") || m.contains("qwen2-vl")
            }
            "groq" -> m.contains("vision")
            "gemini" -> true  // all Gemini models are vision-capable
            "together" -> m.contains("vision") || m.contains("vl") || m.contains("llava")
            else -> false  // zai, custom → use sandbox proxy
        }
    }

    /**
     * Finds a UI element by visual description using the VLM.
     * Returns (x, y) coordinates scaled to the real screen size.
     */
    private suspend fun findElementWithVLM(
        context: Context,
        service: AgentAccessibilityService,
        description: String
    ): Pair<Float, Float>? {
        val result = analyzeScreenWithVLM(
            context,
            service,
            "Find the UI element that matches: \"$description\". " +
            "Respond with ONLY the coordinates in format: x,y (e.g., 540,1200). " +
            "The screen is 1080x2400 pixels. If not found, respond with: NOT_FOUND"
        )

        if (result.contains("NOT_FOUND") || result.contains("error")) {
            return null
        }

        // Parse coordinates from VLM response
        val regex = Regex("(\\d+)\\s*,\\s*(\\d+)")
        val match = regex.find(result)
        if (match != null) {
            val x = match.groupValues[1].toFloat()
            val y = match.groupValues[2].toFloat()
            Log.d(TAG, "findElement: $description → ($x, $y)")
            return Pair(x, y)
        }

        return null
    }
}
