package com.ai.agent.llm

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * v3.2.0: ModelsDevRepository — fetches + caches the models.dev catalog.
 *
 * Source: https://models.dev/api.json (free, no API key, 12,542+ models)
 * Cache: /data/data/com.ai.agent.v2/files/models_dev/api.json (24h TTL)
 *
 * Provides:
 * - allModels() — all 12,542 models
 * - allProviders() — all 229 providers
 * - getModelsByProvider() — models for a specific provider
 * - searchModels() — search by name
 * - getModelsByCapability() — filter by vision/tools/reasoning/etc
 */
object ModelsDevRepository {
    private const val TAG = "ModelsDevRepo"
    private const val API_URL = "https://models.dev/api.json"
    private const val CACHE_DIR = "models_dev"
    private const val CACHE_FILE = "api.json"
    private const val CACHE_TTL_MS = 24 * 60 * 60 * 1000L  // 24 hours

    data class CatalogModel(
        val id: String,
        val name: String,
        val providerId: String,
        val providerName: String,
        val contextLength: Int = 0,
        val maxOutput: Int = 0,
        val inputPrice: Double = 0.0,
        val outputPrice: Double = 0.0,
        val reasoning: Boolean = false,
        val toolCall: Boolean = false,
        val supportsVision: Boolean = false,
        val supportsImageGen: Boolean = false,
        val isFree: Boolean = false,
        val apiUrl: String = "",
        val envVar: String = "",
        val npmPackage: String = "",
    ) {
        val capabilities: Set<ModelCapability>
            get() = buildSet {
                add(ModelCapability.TEXT)
                if (supportsVision) add(ModelCapability.VISION)
                if (toolCall) add(ModelCapability.TOOLS)
                if (reasoning) add(ModelCapability.REASONING)
                if (supportsImageGen) add(ModelCapability.IMAGE_GEN)
                if (supportsVision) add(ModelCapability.MULTIMODAL)
                if (isFree) add(ModelCapability.FREE)
            }

        val priceStr: String
            get() = if (isFree) "FREE" else "$$inputPrice/$$outputPrice per M"

        val contextStr: String
            get() = if (contextLength >= 1000000) "${contextLength / 1000000}M ctx"
                    else "${contextLength / 1000}K ctx"
    }

    data class CatalogProvider(
        val id: String,
        val name: String,
        val apiUrl: String,
        val envVar: String,
        val npmPackage: String,
    )

    @Volatile
    private var models: List<CatalogModel> = emptyList()
    @Volatile
    private var providers: List<CatalogProvider> = emptyList()
    @Volatile
    private var loaded = false

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Load catalog from cache (fast) or fetch from network (slow).
     * Call this on app start or when AI Settings opens.
     */
    suspend fun load(context: Context, forceRefresh: Boolean = false): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val cacheFile = getCacheFile(context)

                // Try cache first (if not expired and not forced)
                if (!forceRefresh && cacheFile.exists()) {
                    val age = System.currentTimeMillis() - cacheFile.lastModified()
                    if (age < CACHE_TTL_MS) {
                        Log.i(TAG, "Loading catalog from cache (${age / 1000 / 60}min old)")
                        parseJson(cacheFile.readText())
                        loaded = true
                        return@withContext true
                    }
                }

                // Fetch from network
                Log.i(TAG, "Fetching catalog from $API_URL...")
                val request = Request.Builder().url(API_URL).build()
                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: ""

                if (!response.isSuccessful || body.isEmpty()) {
                    Log.e(TAG, "Failed to fetch catalog: HTTP ${response.code}")
                    // Try stale cache as fallback
                    if (cacheFile.exists()) {
                        Log.w(TAG, "Using stale cache as fallback")
                        parseJson(cacheFile.readText())
                        loaded = true
                        return@withContext true
                    }
                    return@withContext false
                }

                // Save to cache
                cacheFile.parentFile?.mkdirs()
                cacheFile.writeText(body)
                Log.i(TAG, "Catalog saved to cache (${body.length} bytes)")

                // Parse
                parseJson(body)
                loaded = true
                Log.i(TAG, "Catalog loaded: ${models.size} models, ${providers.size} providers")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load catalog", e)
                // Try stale cache
                val cacheFile = getCacheFile(context)
                if (cacheFile.exists()) {
                    Log.w(TAG, "Using stale cache after error")
                    parseJson(cacheFile.readText())
                    loaded = true
                    return@withContext true
                }
                false
            }
        }
    }

    /**
     * Parse the models.dev JSON into in-memory lists.
     */
    private fun parseJson(jsonStr: String) {
        val json = JSONObject(jsonStr)
        val modelList = mutableListOf<CatalogModel>()
        val providerList = mutableListOf<CatalogProvider>()

        for (providerKey in json.keys()) {
            val providerObj = json.optJSONObject(providerKey) ?: continue
            val providerName = providerObj.optString("name", providerKey)
            val apiUrl = providerObj.optString("api", "")
            val npmPkg = providerObj.optString("npm", "")
            val envList = providerObj.optJSONArray("env")
            val envVar = if (envList != null && envList.length() > 0) envList.optString(0) else ""

            providerList.add(CatalogProvider(providerKey, providerName, apiUrl, envVar, npmPkg))

            val modelsObj = providerObj.optJSONObject("models")
            if (modelsObj != null) {
                for (modelKey in modelsObj.keys()) {
                    val m = modelsObj.optJSONObject(modelKey) ?: continue
                    val modelName = m.optString("name", modelKey)

                    // Parse limit/context
                    val limitObj = m.optJSONObject("limit")
                    val ctxLen = limitObj?.optInt("context", 0) ?: 0
                    val maxOut = limitObj?.optInt("output", 0) ?: 0

                    // Parse cost
                    val costObj = m.optJSONObject("cost")
                    val inPrice = costObj?.optDouble("input", 0.0) ?: 0.0
                    val outPrice = costObj?.optDouble("output", 0.0) ?: 0.0

                    // Parse capabilities
                    val reasoning = m.optBoolean("reasoning", false)
                    val toolCall = m.optBoolean("tool_call", false)

                    // Parse modalities
                    val modObj = m.optJSONObject("modalities")
                    val inputMods = modObj?.optJSONArray("input")
                    val supportsVision = inputMods?.let { arr ->
                        (0 until arr.length()).any { arr.optString(it) == "image" }
                    } ?: false
                    val outputMods = modObj?.optJSONArray("output")
                    val supportsImageGen = outputMods?.let { arr ->
                        (0 until arr.length()).any { arr.optString(it) == "image" }
                    } ?: false

                    val isFree = inPrice == 0.0 && outPrice == 0.0

                    modelList.add(CatalogModel(
                        id = modelKey,
                        name = modelName,
                        providerId = providerKey,
                        providerName = providerName,
                        contextLength = ctxLen,
                        maxOutput = maxOut,
                        inputPrice = inPrice,
                        outputPrice = outPrice,
                        reasoning = reasoning,
                        toolCall = toolCall,
                        supportsVision = supportsVision,
                        supportsImageGen = supportsImageGen,
                        isFree = isFree,
                        apiUrl = apiUrl,
                        envVar = envVar,
                        npmPackage = npmPkg,
                    ))
                }
            }
        }

        models = modelList.sortedByDescending { it.providerName }
        providers = providerList.sortedBy { it.name }
    }

    fun isLoaded(): Boolean = loaded

    fun allModels(): List<CatalogModel> = models

    fun allProviders(): List<CatalogProvider> = providers

    fun getModelsByProvider(providerId: String): List<CatalogModel> =
        models.filter { it.providerId == providerId }

    fun searchModels(query: String): List<CatalogModel> {
        val q = query.lowercase().trim()
        if (q.isEmpty()) return models
        return models.filter {
            it.name.lowercase().contains(q) || it.id.lowercase().contains(q) ||
            it.providerName.lowercase().contains(q) || it.providerId.lowercase().contains(q)
        }
    }

    fun getModelsByCapability(capability: ModelCapability): List<CatalogModel> =
        models.filter { capability in it.capabilities }

    fun getModelsByCapabilities(capabilities: Set<ModelCapability>): List<CatalogModel> =
        models.filter { m -> capabilities.any { it in m.capabilities } }

    fun findModel(modelId: String): CatalogModel? =
        models.find { it.id == modelId }

    private fun getCacheFile(context: Context): File {
        return File(context.filesDir, "$CACHE_DIR/$CACHE_FILE")
    }
}
