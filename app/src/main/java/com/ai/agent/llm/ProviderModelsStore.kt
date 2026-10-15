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
        val url = "${key.baseUrl.trimEnd('/')}/v1/models"
        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build()
        val request = okhttp3.Request.Builder()
            .url(url)
            .apply {
                if (key.apiKey.isNotEmpty() && key.apiKey != "sandbox") {
                    header("Authorization", "Bearer ${key.apiKey}")
                }
            }
            .build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            Log.w(TAG, "GET $url → HTTP ${response.code} for key ${key.id}")
            return emptyList()
        }
        val body = response.body?.string() ?: return emptyList()
        val json = JSONObject(body)
        val data = json.optJSONArray("data") ?: return emptyList()
        val ids = mutableListOf<String>()
        for (i in 0 until data.length()) {
            val obj = data.optJSONObject(i) ?: continue
            val id = obj.optString("id", "")
            if (id.isNotEmpty()) ids.add(id)
        }
        return ids
    }
}
