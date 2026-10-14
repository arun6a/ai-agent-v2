package com.ai.agent.llm

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * v3.2.0: SavedModelsStore — stores user's saved models with capability tags.
 *
 * When user taps [+] on a model in the catalog, it's saved here.
 * Each saved model has:
 * - Model details (id, name, provider)
 * - Associated API key (from ApiKeyStore)
 * - Capability tags (Text, Vision, Tools, Reasoning, etc.)
 * - Role assignment (which task uses this model)
 *
 * Used by:
 * - AI Settings → Saved Models tab
 * - LLMClient (uses the "default" model or role-specific model)
 * - Vision section (shows only Vision-tagged models)
 */
object SavedModelsStore {
    private const val TAG = "SavedModelsStore"
    private const val PREFS_NAME = "ai_agent_saved_models"
    private const val KEY_STORE = "saved_models_json"
    private const val KEY_DEFAULT = "default_model_id"
    private const val KEY_VISION_MODEL = "vision_model_id"
    private const val KEY_AUDIO_MODEL = "audio_model_id"

    data class SavedModel(
        val id: String = System.currentTimeMillis().toString(),
        val modelId: String,          // "glm-4.6"
        val modelName: String,         // "GLM-4.6"
        val providerId: String,       // "zai"
        val providerName: String,     // "Z.AI"
        val apiKeyId: String?,        // reference to ApiKeyStore entry
        val capabilities: Set<ModelCapability>,
        val contextLength: Int = 0,
        val inputPrice: Double = 0.0,
        val outputPrice: Double = 0.0,
        val apiUrl: String = "",
    ) {
        val priceStr: String
            get() = if (inputPrice == 0.0 && outputPrice == 0.0) "FREE"
                    else "$$inputPrice/$$outputPrice per M"

        val contextStr: String
            get() = if (contextLength >= 1000000) "${contextLength / 1000000}M ctx"
                    else if (contextLength >= 1000) "${contextLength / 1000}K ctx"
                    else "${contextLength}B ctx"

        val tagStr: String
            get() = capabilities.joinToString(", ") { it.name }
    }

    private var models: MutableList<SavedModel> = mutableListOf()
    private var loaded = false

    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val jsonStr = prefs.getString(KEY_STORE, "") ?: ""
            if (jsonStr.isNotEmpty()) {
                val arr = JSONArray(jsonStr)
                models.clear()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val capsArr = o.optJSONArray("capabilities") ?: JSONArray()
                    val caps = mutableSetOf<ModelCapability>()
                    for (j in 0 until capsArr.length()) {
                        try {
                            caps.add(ModelCapability.valueOf(capsArr.optString(j)))
                        } catch (_: Exception) {}
                    }
                    models.add(SavedModel(
                        id = o.optString("id"),
                        modelId = o.optString("modelId"),
                        modelName = o.optString("modelName"),
                        providerId = o.optString("providerId"),
                        providerName = o.optString("providerName"),
                        apiKeyId = if (o.has("apiKeyId") && !o.isNull("apiKeyId")) o.optString("apiKeyId") else null,
                        capabilities = caps,
                        contextLength = o.optInt("contextLength"),
                        inputPrice = o.optDouble("inputPrice", 0.0),
                        outputPrice = o.optDouble("outputPrice", 0.0),
                        apiUrl = o.optString("apiUrl"),
                    ))
                }
            }
            loaded = true
            Log.i(TAG, "Loaded ${models.size} saved models")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load saved models", e)
        }
    }

    @Synchronized
    fun save(context: Context) {
        try {
            val arr = JSONArray()
            for (m in models) {
                arr.put(JSONObject().apply {
                    put("id", m.id)
                    put("modelId", m.modelId)
                    put("modelName", m.modelName)
                    put("providerId", m.providerId)
                    put("providerName", m.providerName)
                    put("apiKeyId", m.apiKeyId ?: JSONObject.NULL)
                    val capsArr = JSONArray()
                    for (c in m.capabilities) capsArr.put(c.name)
                    put("capabilities", capsArr)
                    put("contextLength", m.contextLength)
                    put("inputPrice", m.inputPrice)
                    put("outputPrice", m.outputPrice)
                    put("apiUrl", m.apiUrl)
                })
            }
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_STORE, arr.toString()).apply()
            Log.i(TAG, "Saved ${models.size} models")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save models", e)
        }
    }

    fun getAllModels(): List<SavedModel> = models.toList()

    fun getModelsByCapability(capability: ModelCapability): List<SavedModel> =
        models.filter { capability in it.capabilities }

    fun getModelsByCapabilities(caps: Set<ModelCapability>): List<SavedModel> =
        models.filter { m -> caps.any { it in m.capabilities } }

    fun getDefaultModel(context: Context): SavedModel? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val defaultId = prefs.getString(KEY_DEFAULT, "") ?: ""
        return models.find { it.id == defaultId }
    }

    fun getVisionModel(context: Context): SavedModel? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val visionId = prefs.getString(KEY_VISION_MODEL, "") ?: ""
        return models.find { it.id == visionId }
    }

    @Synchronized
    fun addModel(context: Context, model: SavedModel) {
        models.add(model)
        // If first model, set as default
        if (models.size == 1) {
            setDefaultModel(context, model.id)
        }
        save(context)
    }

    @Synchronized
    fun removeModel(context: Context, id: String) {
        models.removeAll { it.id == id }
        save(context)
    }

    @Synchronized
    fun setDefaultModel(context: Context, id: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_DEFAULT, id).apply()
        // v3.2.6: propagate selection to AIProvider prefs so LLMClient.chat()
        // actually picks it up. Without this, picking a model here did nothing
        // for the live chat — LLMClient kept using the legacy AIProvider model.
        applyModelToProvider(context, id)
    }

    /**
     * v3.2.6: Push a saved model's settings into AIProvider (the active config
     * LLMClient reads on every chat call). Falls back gracefully if the model
     * or its API key are missing.
     */
    private fun applyModelToProvider(context: Context, id: String) {
        val model = models.find { it.id == id } ?: return
        // Make sure ApiKeyStore is loaded before we try to read keys from it.
        ApiKeyStore.load(context)
        AIProvider.setProvider(context, model.providerId)
        AIProvider.setModel(context, model.modelId)
        if (model.apiUrl.isNotEmpty()) {
            AIProvider.setEndpoint(context, model.apiUrl)
        } else {
            // Fall back to the provider's default endpoint if the saved model
            // didn't carry its own URL.
            val providerInfo = AIProvider.PROVIDERS.find { it.id == model.providerId }
            if (providerInfo != null) {
                AIProvider.setEndpoint(context, providerInfo.defaultEndpoint)
            }
        }
        // Look up the actual API key string from ApiKeyStore via apiKeyId.
        val apiKey = model.apiKeyId?.let { keyId ->
            ApiKeyStore.getAllKeys().find { it.id == keyId }?.apiKey
        } ?: ""
        if (apiKey.isNotEmpty()) {
            AIProvider.setApiKey(context, apiKey)
        }
        android.util.Log.i("SavedModelsStore",
            "Applied saved model '${model.modelName}' to AIProvider: " +
                    "provider=${model.providerId}, endpoint=${model.apiUrl}, modelId=${model.modelId}")
    }

    @Synchronized
    fun setVisionModel(context: Context, id: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_VISION_MODEL, id).apply()
    }

    @Synchronized
    fun setAudioModel(context: Context, id: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_AUDIO_MODEL, id).apply()
    }

    fun isAlreadySaved(modelId: String, providerId: String): Boolean =
        models.any { it.modelId == modelId && it.providerId == providerId }
}
