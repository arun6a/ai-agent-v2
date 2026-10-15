package com.ai.agent.llm

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * v3.3.0: ProviderModelsStore — caches the models each saved API key can actually access.
 *
 * When a user adds/edits an API key in ApiKeyStore, we fetch {baseUrl}/v1/models
 * (or the sandbox proxy's /v1/models endpoint) with their key and cache the list
 * of model IDs. This lets the Model Catalog show "✅ Available with: {alias}"
 * badges on models the user's key actually has access to, and lets the user
 * filter the catalog down to "My Available Models".
 *
 * Cache layout (per ApiKey id):
 *   SharedPreferences "ai_agent_provider_models"
 *     key = "models_<apiKeyId>"
 *     value = JSON: {"models": ["glm-4.6", "gpt-4o", ...], "fetchedAt": epochMs}
 *
 * TTL: 24 hours (matching ModelsDevRepository's TTL).
 */
object ProviderModelsStore {

    private const val TAG = "ProviderModelsStore"
    private const val PREFS_NAME = "ai_agent_provider_models"
    private const val KEY_PREFIX = "models_"
    private const val CACHE_TTL_MS = 24L * 60 * 60 * 1000  // 24h

    data class CachedModels(
        val apiKeyId: String,
        val modelIds: List<String>,
        val fetchedAt: Long,
    ) {
        val isFresh: Boolean
            get() = System.currentTimeMillis() - fetchedAt < CACHE_TTL_MS
    }

    /**
     * Fetch the list of models accessible by a specific API key.
     * Hits {baseUrl}/v1/models with the key's auth header (or the sandbox
     * proxy URL which returns a curated list).
     *
     * Returns the list of model IDs on success, empty list on failure.
     * Caches the result regardless of success/failure so we don't hammer
     * the provider on every screen open.
     */
    suspend fun fetchAndCache(context: Context, key: ApiKeyStore.ApiKey): List<String> {
        return withContext(Dispatchers.IO) {
            try {
                val modelIds = fetchAvailableModels(key)
                cacheModels(context, key.id, modelIds)
                Log.i(TAG, "Fetched ${modelIds.size} models for key " +
                        "'${key.alias.ifEmpty { key.providerName }}' (${key.id})")
                modelIds
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch models for key ${key.id}: ${e.message}")
                // Cache an empty result so we don't retry on every screen open.
                cacheModels(context, key.id, emptyList())
                emptyList()
            }
        }
    }

    /**
     * Fetch models for ALL saved API keys. Used on app startup or when the
     * user pulls-to-refresh on the catalog. Runs in parallel.
     */
    suspend fun refreshAll(context: Context) {
        withContext(Dispatchers.IO) {
            ApiKeyStore.load(context)
            val keys = ApiKeyStore.getAllKeys()
            coroutineScope {
                keys.map { key ->
                    async { fetchAndCache(context, key) }
                }.awaitAll()
            }
            Log.i(TAG, "Refreshed models for ${keys.size} keys")
        }
    }

    /**
     * Get the cached models for a specific key. Returns null if not cached
     * or stale (caller can then call fetchAndCache to refresh).
     */
    fun getCached(context: Context, apiKeyId: String): CachedModels? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString("$KEY_PREFIX$apiKeyId", null) ?: return null
        return try {
            val json = JSONObject(jsonStr)
            val modelsArr = json.optJSONArray("models") ?: JSONArray()
            val modelIds = (0 until modelsArr.length()).map { modelsArr.optString(it) }
            CachedModels(
                apiKeyId = apiKeyId,
                modelIds = modelIds,
                fetchedAt = json.optLong("fetchedAt", 0),
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Get the cached models for a key as a plain list of IDs (empty if not cached).
     * Doesn't check freshness — caller can use this for instant UI rendering
     * and refresh in the background.
     */
    fun getCachedModelIds(context: Context, apiKeyId: String): List<String> {
        return getCached(context, apiKeyId)?.modelIds ?: emptyList()
    }

    /**
     * Returns true if the given model ID is available with the given key
     * (based on cached data). If not cached, returns false.
     *
     * Loose match: if the cached model ID contains the queried ID as a
     * substring (or vice versa), it counts as a match. This handles providers
     * that prefix model IDs (e.g. "openai/gpt-4o" on OpenRouter vs "gpt-4o"
     * in the catalog).
     */
    fun isAvailable(context: Context, apiKeyId: String, modelId: String): Boolean {
        val cached = getCached(context, apiKeyId) ?: return false
        val lowerQuery = modelId.lowercase()
        return cached.modelIds.any { cached ->
            val lower = cached.lowercase()
            lower == lowerQuery || lower.contains(lowerQuery) || lowerQuery.contains(lower)
        }
    }

    /**
     * Returns the list of API keys that have the given model available.
     * Used to show "✅ Available with: X, Y, Z" badges in the catalog.
     */
    fun keysWithModel(context: Context, modelId: String): List<ApiKeyStore.ApiKey> {
        ApiKeyStore.load(context)
        val allKeys = ApiKeyStore.getAllKeys()
        return allKeys.filter { key -> isAvailable(context, key.id, modelId) }
    }

    /**
     * v3.3.5: Returns the ACTUAL model ID a specific provider's API expects.
     *
     * Catalog model IDs are bare (e.g. "grok-4.7"), but gateway providers
     * like Cline/OpenRouter expect prefixed IDs (e.g. "x-ai/grok-4.7").
     * This looks up the cached /v1/models response for the given key and
     * returns the matching model ID. Falls back to the input if no match.
     *
     * Used by:
     *   - ModelCatalogTab.onAdd (store the provider-specific ID, not the bare one)
     *   - resolveEndpointForBinding / LLMClient (send the right ID in the request)
     */
    fun resolveActualModelId(context: Context, apiKeyId: String, catalogModelId: String): String {
        val cached = getCached(context, apiKeyId) ?: return catalogModelId
        val lowerQuery = catalogModelId.lowercase()
        // Exact match first.
        val exact = cached.modelIds.firstOrNull { it.lowercase() == lowerQuery }
        if (exact != null) return exact
        // Substring match (handles "x-ai/grok-4.7" contains "grok-4.7").
        // Prefer the SHORTEST match (closest to the catalog ID).
        val matches = cached.modelIds.filter { it.lowercase().contains(lowerQuery) }
            .sortedBy { it.length }
        return matches.firstOrNull() ?: catalogModelId
    }

    /**
     * Returns the union of all model IDs available across all cached keys.
     * Used by the "My Available Models" filter chip in the catalog.
     */
    fun getAllAvailableModelIds(context: Context): Set<String> {
        ApiKeyStore.load(context)
        val allKeys = ApiKeyStore.getAllKeys()
        val all = mutableSetOf<String>()
        for (key in allKeys) {
            all.addAll(getCachedModelIds(context, key.id))
        }
        return all
    }

    /**
     * Drop the cache for a specific key (e.g. when the user edits/deletes the key).
     */
    fun invalidate(context: Context, apiKeyId: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove("$KEY_PREFIX$apiKeyId").apply()
    }

    /**
     * Drop all caches. Used when the user manually refreshes.
     */
    fun invalidateAll(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().apply()
    }

    // ---- internals ----

    private fun cacheModels(context: Context, apiKeyId: String, modelIds: List<String>) {
        val json = JSONObject().apply {
            put("models", JSONArray(modelIds))
            put("fetchedAt", System.currentTimeMillis())
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString("$KEY_PREFIX$apiKeyId", json.toString()).apply()
    }

    private fun fetchAvailableModels(key: ApiKeyStore.ApiKey): List<String> {
        // v3.3.1: Use the ProviderEndpoints registry's modelsEndpoint URL when
        // the key's providerId matches a known popular provider. This fixes the
        // bug where {baseUrl}/v1/models was wrong for half the providers because
        // models.dev didn't include an `api` field for them.
        //
        // Resolution order:
        //   1. Sandbox proxy URL (contains "/api/llm/proxy") → POST-only, no
        //      /v1/models endpoint. Return curated list.
        //   2. ProviderEndpoint for this providerId → use its modelsEndpoint URL
        //      with the correct auth scheme.
        //   3. Fallback: append "/models" or "/v1/models" to the user-provided
        //      baseUrl (best effort for custom providers).
        if (key.baseUrl.contains("/api/llm/proxy")) {
            return listOf("glm-4.6", "glm-4-flash", "glm-4.5v", "glm-4.1v")
        }

        val endpoint = ProviderEndpoints.byId(key.providerId)
        val url = endpoint?.modelsEndpoint ?: buildModelsUrlFromBase(key.baseUrl)
        val authScheme = endpoint?.authScheme ?: ProviderEndpoints.AuthScheme.BEARER

        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build()
        val request = okhttp3.Request.Builder().url(url).apply {
            when (authScheme) {
                ProviderEndpoints.AuthScheme.BEARER -> {
                    if (key.apiKey.isNotEmpty() && key.apiKey != "sandbox") {
                        header("Authorization", "Bearer ${key.apiKey}")
                    }
                }
                ProviderEndpoints.AuthScheme.X_API_KEY -> {
                    if (key.apiKey.isNotEmpty()) {
                        header("x-api-key", key.apiKey)
                        // Anthropic also requires anthropic-version header.
                        header("anthropic-version", "2023-06-01")
                    }
                }
                ProviderEndpoints.AuthScheme.QUERY_PARAM -> {
                    // Gemini uses ?key=<key> — already in the URL if user set it.
                    // Otherwise append it here.
                    // (handled by url construction in ProviderEndpoints for gemini)
                }
                ProviderEndpoints.AuthScheme.NONE -> { /* no auth header */ }
            }
        }.build()

        return try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "GET $url → HTTP ${response.code} for key ${key.id}")
                return emptyList()
            }
            val body = response.body?.string() ?: return emptyList()
            // Gemini returns {models: [...]} with different shape — handle both.
            val json = JSONObject(body)
            val data = json.optJSONArray("data")
                ?: json.optJSONArray("models")  // Gemini shape
                ?: return emptyList()
            val ids = mutableListOf<String>()
            for (i in 0 until data.length()) {
                val obj = data.optJSONObject(i) ?: continue
                val id = obj.optString("id", "")
                    .ifEmpty { obj.optString("name", "") }  // Gemini uses "name"
                if (id.isNotEmpty()) ids.add(id)
            }
            ids
        } catch (e: Exception) {
            Log.w(TAG, "fetchAvailableModels failed for ${key.id}: ${e.message}")
            emptyList()
        }
    }

    /**
     * Best-effort: build a /models URL from a user-supplied baseUrl for custom
     * providers (where we don't have a ProviderEndpoint). Strips trailing
     * chat/completions or v1 path if present, then appends /v1/models or /models.
     */
    private fun buildModelsUrlFromBase(baseUrl: String): String {
        var base = baseUrl.trimEnd('/')
        // Strip /chat/completions if user pasted the full chat endpoint.
        base = base.replace(Regex("/chat/completions$"), "")
        // Strip /messages (Anthropic).
        base = base.replace(Regex("/messages$"), "")
        // If base ends with /v1, append /models.
        return if (base.endsWith("/v1")) "${base}/models"
        else "${base}/v1/models"
    }
}
