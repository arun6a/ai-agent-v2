package com.ai.agent.llm

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * v3.6.3: PromptCachingTester — tests whether a provider supports prompt caching.
 *
 * Prompt caching: when the same system prompt is sent twice, the second call
 * should be cheaper (cached_tokens > 0) or faster (latency drops 30%+).
 *
 * The test sends a ~2000-token system prompt twice and compares:
 *   1. cached_tokens reported in usage (most reliable)
 *   2. Latency drop of 30%+ on 2nd call (good signal)
 *   3. Fewer prompt tokens charged on 2nd call (some providers discount)
 *
 * Used by the "Test Caching" button in API Keys.
 */
object PromptCachingTester {

    private const val TAG = "PromptCachingTester"

    data class CachingTestResult(
        val supported: Boolean,
        val confidence: Float,           // 0..1
        val evidence: String,            // human-readable explanation
        val firstCallTokens: Int,
        val secondCallTokens: Int,
        val firstCallLatencyMs: Long,
        val secondCallLatencyMs: Long,
        val cachedTokensReported: Int,
    )

    /**
     * Run the caching test against a specific API key.
     * Sends the same ~2000-token system prompt twice, compares results.
     */
    suspend fun test(
        context: Context,
        apiKey: ApiKeyStore.ApiKey,
    ): CachingTestResult = withContext(Dispatchers.IO) {
        // Resolve the endpoint + auth scheme.
        val endpointInfo = ProviderEndpoints.byId(apiKey.providerId)
        val chatEndpoint = when {
            apiKey.baseUrl.isNotEmpty() -> apiKey.baseUrl
            endpointInfo != null -> endpointInfo.chatEndpoint
            else -> return@withContext CachingTestResult(
                supported = false, confidence = 0f,
                evidence = "No endpoint resolved for ${apiKey.providerName}",
                firstCallTokens = 0, secondCallTokens = 0,
                firstCallLatencyMs = 0, secondCallLatencyMs = 0,
                cachedTokensReported = 0,
            )
        }
        val authScheme = endpointInfo?.authScheme ?: ProviderEndpoints.AuthScheme.BEARER

        // Resolve a real model ID from ProviderModelsStore (avoids 400 error).
        val modelId = ProviderModelsStore.getCachedModelIds(context, apiKey.id)
            .firstOrNull() ?: "glm-4.6"  // fallback

        Log.i(TAG, "Testing caching on ${apiKey.providerName} (model=$modelId, endpoint=$chatEndpoint)")

        // Build a ~2000-token system prompt (unique enough to not be pre-cached).
        val testPrompt = buildString {
            append("This is a prompt caching test. Unique ID: ${System.currentTimeMillis()}\n")
            append("The following is filler content to reach the caching threshold:\n\n")
            repeat(300) { i ->
                append("Line $i: The quick brown fox jumps over the lazy dog. ")
                append("Pack my box with five dozen liquor jugs. ")
                append("How vexingly quick daft zebras jump!\n")
            }
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        // Call 1
        val (resp1, latency1) = try {
            makeCall(client, chatEndpoint, apiKey, authScheme, testPrompt, modelId)
        } catch (e: Exception) {
            return@withContext CachingTestResult(
                supported = false, confidence = 0f,
                evidence = "Call 1 failed: ${e.message}",
                firstCallTokens = 0, secondCallTokens = 0,
                firstCallLatencyMs = 0, secondCallLatencyMs = 0,
                cachedTokensReported = 0,
            )
        }

        if (!resp1.success) {
            return@withContext CachingTestResult(
                supported = false, confidence = 0f,
                evidence = "Call 1 returned HTTP error. ${resp1.rawBody.take(150)}",
                firstCallTokens = resp1.promptTokens, secondCallTokens = 0,
                firstCallLatencyMs = latency1, secondCallLatencyMs = 0,
                cachedTokensReported = 0,
            )
        }

        // Brief pause (some providers need the cache to settle).
        delay(500)

        // Call 2 (same prompt).
        val (resp2, latency2) = try {
            makeCall(client, chatEndpoint, apiKey, authScheme, testPrompt, modelId)
        } catch (e: Exception) {
            return@withContext CachingTestResult(
                supported = false, confidence = 0f,
                evidence = "Call 2 failed: ${e.message}",
                firstCallTokens = resp1.promptTokens, secondCallTokens = 0,
                firstCallLatencyMs = latency1, secondCallLatencyMs = 0,
                cachedTokensReported = 0,
            )
        }

        if (!resp2.success) {
            return@withContext CachingTestResult(
                supported = false, confidence = 0f,
                evidence = "Call 2 returned HTTP error. ${resp2.rawBody.take(150)}",
                firstCallTokens = resp1.promptTokens, secondCallTokens = resp2.promptTokens,
                firstCallLatencyMs = latency1, secondCallLatencyMs = latency2,
                cachedTokensReported = 0,
            )
        }

        // Analyze
        analyzeCaching(resp1, resp2, latency1, latency2)
    }

    private fun makeCall(
        client: OkHttpClient,
        endpoint: String,
        apiKey: ApiKeyStore.ApiKey,
        authScheme: ProviderEndpoints.AuthScheme,
        systemPrompt: String,
        modelId: String,
    ): Pair<CallResponse, Long> {
        val startTime = System.currentTimeMillis()

        val body = JSONObject().apply {
            put("model", modelId)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", "Reply with just: OK")
                })
            })
            put("max_tokens", 5)
        }

        val request = Request.Builder()
            .url(endpoint)
            .header("Content-Type", "application/json")
            .apply {
                when (authScheme) {
                    ProviderEndpoints.AuthScheme.BEARER -> {
                        if (apiKey.apiKey.isNotEmpty() && apiKey.apiKey != "sandbox") {
                            header("Authorization", "Bearer ${apiKey.apiKey}")
                        }
                    }
                    ProviderEndpoints.AuthScheme.X_API_KEY -> {
                        header("x-api-key", apiKey.apiKey)
                        header("anthropic-version", "2023-06-01")
                    }
                    ProviderEndpoints.AuthScheme.QUERY_PARAM -> {
                        // Gemini handled separately — skip for now.
                    }
                    ProviderEndpoints.AuthScheme.NONE -> { /* no auth */ }
                }
            }
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""
        val latency = System.currentTimeMillis() - startTime

        val json = try { JSONObject(responseBody) } catch (_: Exception) { JSONObject() }
        val usage = json.optJSONObject("usage")

        val promptTokens = usage?.optInt("prompt_tokens", 0) ?: 0
        val completionTokens = usage?.optInt("completion_tokens", 0) ?: 0
        // cached_tokens — OpenAI/Anthropic report this in different fields.
        val cachedTokens = usage?.optInt("cached_tokens", 0) ?: 0
        val promptTokensDetails = usage?.optJSONObject("prompt_tokens_details")
        val cachedFromDetails = promptTokensDetails?.optInt("cached_tokens", 0) ?: 0
        val totalCached = maxOf(cachedTokens, cachedFromDetails)

        return Pair(
            CallResponse(
                success = response.isSuccessful,
                promptTokens = promptTokens,
                completionTokens = completionTokens,
                cachedTokens = totalCached,
                rawBody = responseBody,
            ),
            latency
        )
    }

    private fun analyzeCaching(
        resp1: CallResponse, resp2: CallResponse,
        latency1: Long, latency2: Long
    ): CachingTestResult {
        // Method 1: cached_tokens explicitly reported on 2nd call (most reliable).
        if (resp2.cachedTokens > 0) {
            return CachingTestResult(
                supported = true,
                confidence = 1.0f,
                evidence = "✅ Provider reported cached_tokens=${resp2.cachedTokens} on 2nd call.\n" +
                    "Call 1: ${resp1.promptTokens} tokens, ${latency1}ms\n" +
                    "Call 2: ${resp2.promptTokens} tokens, ${latency2}ms (cached: ${resp2.cachedTokens})",
                firstCallTokens = resp1.promptTokens,
                secondCallTokens = resp2.promptTokens,
                firstCallLatencyMs = latency1,
                secondCallLatencyMs = latency2,
                cachedTokensReported = resp2.cachedTokens,
            )
        }

        // Method 2: latency dropped 30%+ on 2nd call (good signal).
        if (latency1 > 1000 && latency2 < latency1 * 0.7) {
            val dropPct = ((latency1 - latency2).toFloat() / latency1.toFloat() * 100).toInt()
            return CachingTestResult(
                supported = true,
                confidence = 0.7f,
                evidence = "✅ 2nd call was $dropPct% faster (${latency1}ms → ${latency2}ms).\n" +
                    "Provider likely caches, but didn't report cached_tokens.\n" +
                    "Call 1: ${resp1.promptTokens} tokens, ${latency1}ms\n" +
                    "Call 2: ${resp2.promptTokens} tokens, ${latency2}ms",
                firstCallTokens = resp1.promptTokens,
                secondCallTokens = resp2.promptTokens,
                firstCallLatencyMs = latency1,
                secondCallLatencyMs = latency2,
                cachedTokensReported = 0,
            )
        }

        // Method 3: 2nd call charged fewer prompt tokens (some providers discount cached).
        if (resp1.promptTokens > 0 && resp2.promptTokens < resp1.promptTokens * 0.9) {
            return CachingTestResult(
                supported = true,
                confidence = 0.6f,
                evidence = "✅ 2nd call charged ${resp2.promptTokens} prompt tokens vs ${resp1.promptTokens} on 1st.\n" +
                    "Likely cached + discounted.\n" +
                    "Call 1: ${resp1.promptTokens} tokens, ${latency1}ms\n" +
                    "Call 2: ${resp2.promptTokens} tokens, ${latency2}ms",
                firstCallTokens = resp1.promptTokens,
                secondCallTokens = resp2.promptTokens,
                firstCallLatencyMs = latency1,
                secondCallLatencyMs = latency2,
                cachedTokensReported = 0,
            )
        }

        // Not supported.
        return CachingTestResult(
            supported = false,
            confidence = 0.9f,
            evidence = "❌ No caching detected.\n" +
                "Same prompt tokens (${resp1.promptTokens} → ${resp2.promptTokens}),\n" +
                "similar latency (${latency1}ms → ${latency2}ms),\n" +
                "no cached_tokens reported.",
            firstCallTokens = resp1.promptTokens,
            secondCallTokens = resp2.promptTokens,
            firstCallLatencyMs = latency1,
            secondCallLatencyMs = latency2,
            cachedTokensReported = 0,
        )
    }

    private data class CallResponse(
        val success: Boolean,
        val promptTokens: Int,
        val completionTokens: Int,
        val cachedTokens: Int,
        val rawBody: String,
    )
}
