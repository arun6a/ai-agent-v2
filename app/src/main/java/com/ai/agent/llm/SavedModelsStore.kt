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

    /**
     * v3.3.2: A single provider-binding for a saved model.
     *
     * A model can have multiple bindings (e.g. GLM-4.6 via Z.AI direct +
     * OpenRouter + Groq). The routing layer picks one based on priority +
     * health + the auto-fallback toggle.
     */
    data class ModelProviderBinding(
        val id: String = System.currentTimeMillis().toString(),
        val providerId: String,           // "openai", "groq", "zai", "custom"
        val providerName: String,          // "OpenAI", "Groq", etc.
        val apiKeyId: String,             // ApiKeyStore.ApiKey.id
        val endpointOverride: String? = null,  // null = use ProviderEndpoint default
        val priority: Int = 0,            // 0 = highest (user orders them)
        val enabled: Boolean = true,
    )

    data class SavedModel(
        val id: String = System.currentTimeMillis().toString(),
        val modelId: String,          // "glm-4.6"
        val modelName: String,         // "GLM-4.6"
        val providerId: String,       // "zai" (primary, from catalog)
        val providerName: String,     // "Z.AI"
        @Deprecated("Use bindings instead. Kept for migration — if non-null, treated as binding #0.")
        val apiKeyId: String?,        // legacy single-key reference
        val capabilities: Set<ModelCapability>,
        val contextLength: Int = 0,
        val inputPrice: Double = 0.0,
        val outputPrice: Double = 0.0,
        val apiUrl: String = "",
        // v3.3.2: list of provider bindings — the routing layer picks one.
        val bindings: List<ModelProviderBinding> = emptyList(),
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

        /**
         * v3.3.2: All bindings that are currently enabled (in priority order).
         * If bindings is empty, falls back to a single virtual binding from the
         * legacy apiKeyId (for backward compat with pre-v3.3.2 saved models).
         */
        fun activeBindings(): List<ModelProviderBinding> {
            val enabled = bindings.filter { it.enabled }.sortedBy { it.priority }
            if (enabled.isNotEmpty()) return enabled
            // Legacy fallback: synthesize a binding from apiKeyId.
            if (!apiKeyId.isNullOrEmpty()) {
                return listOf(ModelProviderBinding(
                    id = "legacy_$apiKeyId",
                    providerId = providerId,
                    providerName = providerName,
                    apiKeyId = apiKeyId,
                    priority = 0,
                    enabled = true,
                ))
            }
            return emptyList()
        }
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
                        bindings = parseBindings(o.optJSONArray("bindings")),
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
                    // v3.3.2: serialize bindings list.
                    val bindsArr = JSONArray()
                    for (b in m.bindings) {
                        bindsArr.put(JSONObject().apply {
                            put("id", b.id)
                            put("providerId", b.providerId)
                            put("providerName", b.providerName)
                            put("apiKeyId", b.apiKeyId)
                            put("endpointOverride", b.endpointOverride ?: JSONObject.NULL)
                            put("priority", b.priority)
                            put("enabled", b.enabled)
                        })
                    }
                    put("bindings", bindsArr)
                })
            }
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_STORE, arr.toString()).apply()
            Log.i(TAG, "Saved ${models.size} models")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save models", e)
        }
    }

    /**
     * v3.3.2: Parse a bindings JSON array (from load()). Returns empty list if null.
     */
    private fun parseBindings(arr: JSONArray?): List<ModelProviderBinding> {
        if (arr == null) return emptyList()
        val list = mutableListOf<ModelProviderBinding>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            list.add(ModelProviderBinding(
                id = o.optString("id"),
                providerId = o.optString("providerId"),
                providerName = o.optString("providerName"),
                apiKeyId = o.optString("apiKeyId"),
                endpointOverride = if (o.has("endpointOverride") && !o.isNull("endpointOverride"))
                    o.optString("endpointOverride") else null,
                priority = o.optInt("priority", 0),
                enabled = o.optBoolean("enabled", true),
            ))
        }
        return list
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

    /**
     * v3.2.11: Rebind a saved model to a different API key.
     *
     * Pass null for apiKeyId to clear the binding (resolution will fall back
     * to the default providerId match or first custom key).
     *
     * If this model is currently the default, also re-propagate the new key
     * to AIProvider so the live chat picks up the change immediately.
     */
    @Synchronized
    fun updateModelApiKey(context: Context, modelId: String, apiKeyId: String?) {
        val idx = models.indexOfFirst { it.id == modelId }
        if (idx < 0) return
        models[idx] = models[idx].copy(apiKeyId = apiKeyId)
        save(context)
        // If this model is the default, re-apply so AIProvider gets the new key.
        val defaultId = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_DEFAULT, null)
        if (defaultId == modelId) {
            applyModelToProvider(context, modelId)
        }
    }

    /**
     * v3.3.2: Add a provider binding to a saved model.
     *
     * Used by the multi-provider UI — e.g. bind GLM-4.6 to Z.AI + OpenRouter +
     * Groq so the routing layer can pick one and failover on errors.
     */
    @Synchronized
    fun addBinding(
        context: Context,
        modelId: String,
        binding: ModelProviderBinding,
    ) {
        val idx = models.indexOfFirst { it.id == modelId }
        if (idx < 0) return
        val existing = models[idx].bindings.toMutableList()
        // Auto-assign next priority if not specified.
        val newBinding = if (existing.isEmpty() && binding.priority == 0) binding
        else binding.copy(priority = existing.size)
        existing.add(newBinding)
        models[idx] = models[idx].copy(bindings = existing)
        save(context)
        // If this is the default model, re-apply so the new binding is considered.
        val defaultId = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_DEFAULT, null)
        if (defaultId == modelId) {
            applyModelToProvider(context, modelId)
        }
    }

    /**
     * v3.3.2: Remove a binding from a saved model.
     */
    @Synchronized
    fun removeBinding(context: Context, modelId: String, bindingId: String) {
        val idx = models.indexOfFirst { it.id == modelId }
        if (idx < 0) return
        val existing = models[idx].bindings.filter { it.id != bindingId }
        models[idx] = models[idx].copy(bindings = existing)
        save(context)
        // Re-apply if default.
        val defaultId = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_DEFAULT, null)
        if (defaultId == modelId) {
            applyModelToProvider(context, modelId)
        }
        // Also drop the binding's health record.
        BindingHealthStore.reset(bindingId, context)
    }

    /**
     * v3.3.2: Update a binding (toggle enabled, change priority, override endpoint).
     */
    @Synchronized
    fun updateBinding(
        context: Context,
        modelId: String,
        bindingId: String,
        enabled: Boolean? = null,
        priority: Int? = null,
        endpointOverride: String? = null,
        clearEndpointOverride: Boolean = false,
    ) {
        val idx = models.indexOfFirst { it.id == modelId }
        if (idx < 0) return
        val existing = models[idx].bindings.toMutableList()
        val bIdx = existing.indexOfFirst { it.id == bindingId }
        if (bIdx < 0) return
        val b = existing[bIdx]
        existing[bIdx] = b.copy(
            enabled = enabled ?: b.enabled,
            priority = priority ?: b.priority,
            endpointOverride = if (clearEndpointOverride) null else (endpointOverride ?: b.endpointOverride),
        )
        models[idx] = models[idx].copy(bindings = existing)
        save(context)
        // Re-apply if default.
        val defaultId = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_DEFAULT, null)
        if (defaultId == modelId) {
            applyModelToProvider(context, modelId)
        }
        // If user re-enabled a binding, reset its health so we give it another chance.
        if (enabled == true) {
            BindingHealthStore.reset(bindingId, context)
        }
    }

    /**
     * v3.3.2: Reorder bindings (move a binding up or down in priority).
     */
    @Synchronized
    fun reorderBinding(
        context: Context,
        modelId: String,
        bindingId: String,
        direction: ReorderDirection,
    ) {
        val idx = models.indexOfFirst { it.id == modelId }
        if (idx < 0) return
        val existing = models[idx].bindings.sortedBy { it.priority }.toMutableList()
        val bIdx = existing.indexOfFirst { it.id == bindingId }
        if (bIdx < 0) return
        val newIdx = when (direction) {
            ReorderDirection.UP -> (bIdx - 1).coerceAtLeast(0)
            ReorderDirection.DOWN -> (bIdx + 1).coerceAtMost(existing.size - 1)
        }
        if (newIdx == bIdx) return
        // Swap.
        val tmp = existing[bIdx]
        existing[bIdx] = existing[newIdx]
        existing[newIdx] = tmp
        // Reassign priorities 0,1,2,...
        val reordered = existing.mapIndexed { i, b -> b.copy(priority = i) }
        models[idx] = models[idx].copy(bindings = reordered)
        save(context)
        // Re-apply if default.
        val defaultId = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_DEFAULT, null)
        if (defaultId == modelId) {
            applyModelToProvider(context, modelId)
        }
    }

    enum class ReorderDirection { UP, DOWN }

    /**
     * v3.3.2: Apply a SPECIFIC binding to AIProvider (used by LLMClient.chatWithFailover
     * when failing over to the next binding).
     *
     * Unlike applyModelToProvider (which uses resolveBinding to pick the best),
     * this forces a specific binding regardless of health.
     */
    fun applyBindingForFailover(context: Context, modelId: String, bindingId: String) {
        val model = models.find { it.id == modelId } ?: return
        val binding = model.bindings.find { it.id == bindingId } ?: return
        ApiKeyStore.load(context)
        val key = ApiKeyStore.getAllKeys().find { it.id == binding.apiKeyId } ?: return
        AIProvider.setProvider(context, binding.providerId)
        // v3.3.5: resolve actual model ID for this binding's provider.
        val actualModelId = resolveActualModelIdForBinding(context, model, binding)
        AIProvider.setModel(context, actualModelId)
        val endpoint = resolveEndpointForBinding(model, binding, key)
        if (endpoint.isNotEmpty()) AIProvider.setEndpoint(context, endpoint)
        AIProvider.setApiKey(context, key.apiKey)
    }

    /**
     * v3.3.4: Shared endpoint resolver — used by applyModelToProvider,
     * applyBindingForFailover, and testSavedModel so they all agree on which
     * endpoint URL to hit.
     *
     * Priority:
     *   1. binding.endpointOverride (user can override per-binding)
     *   2. saved key's baseUrl (e.g. sandbox proxy full URL or Gemini base)
     *   3. ProviderEndpoint.chatEndpoint from the registry (e.g. OpenAI's
     *      https://api.openai.com/v1/chat/completions)
     *   4. model's apiUrl (catalog default — usually empty for popular providers)
     *   5. PROVIDERS legacy defaultEndpoint
     */
    fun resolveEndpointForBinding(
        model: SavedModel,
        binding: ModelProviderBinding,
        key: ApiKeyStore.ApiKey,
    ): String {
        return when {
            !binding.endpointOverride.isNullOrEmpty() -> binding.endpointOverride!!
            key.baseUrl.isNotEmpty() -> key.baseUrl
            ProviderEndpoints.byId(binding.providerId)?.chatEndpoint != null ->
                ProviderEndpoints.byId(binding.providerId)!!.chatEndpoint
            model.apiUrl.isNotEmpty() -> model.apiUrl
            else -> AIProvider.PROVIDERS.find { it.id == binding.providerId }?.defaultEndpoint ?: ""
        }
    }

    /**
     * v3.3.5: Resolve the actual model ID for a specific binding.
     *
     * Gateway providers (Cline, OpenRouter) prefix model IDs:
     *   catalog: "grok-4.7"  →  Cline expects: "x-ai/grok-4.7"
     *
     * The saved model's `modelId` field is the catalog ID (or the resolved
     * ID from when it was saved). At call time, we look up the cached
     * /v1/models response for the binding's key and return the matching ID.
     * Falls back to the stored modelId if no cache match.
     *
     * This needs a Context (to read ProviderModelsStore cache) so it's not
     * a pure data-class method — it lives here.
     */
    fun resolveActualModelIdForBinding(
        context: Context,
        model: SavedModel,
        binding: ModelProviderBinding,
    ): String {
        return ProviderModelsStore.resolveActualModelId(context, binding.apiKeyId, model.modelId)
    }

    /**
     * v3.3.6: Apply a specific saved model to AIProvider WITHOUT changing the
     * saved default. Used by SmartRouting to apply the tier-picked model
     * (e.g. Fast/Cheap for "hi", Advanced for complex tasks) for one message,
     * while keeping the user's configured default unchanged.
     */
    fun applyModelForRouting(context: Context, modelId: String) {
        applyModelToProvider(context, modelId)
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
     * v3.3.2: Hybrid routing — pick the best binding for this model.
     *
     * Hybrid D (user's choice):
     *   1. If "auto-pick cheapest healthy" toggle is ON → return the cheapest
     *      healthy binding (lowest inputPrice + outputPrice).
     *   2. Else → return the highest-priority enabled binding that's healthy.
     *   3. If all enabled bindings are unhealthy AND auto-fallback is ON →
     *      return the highest-priority one anyway (let it fail + recover).
     *   4. If all enabled bindings are unhealthy AND auto-fallback is OFF →
     *      return the highest-priority one (same as before — no failover).
     *
     * Returns null if no bindings exist.
     */
    fun resolveBinding(
        context: Context,
        model: SavedModel,
        autoPickCheapest: Boolean = false,
    ): Pair<ModelProviderBinding, ApiKeyStore.ApiKey>? {
        val active = model.activeBindings()
        if (active.isEmpty()) return null

        // Make sure stores are loaded.
        ApiKeyStore.load(context)
        BindingHealthStore.load(context)
        val allKeys = ApiKeyStore.getAllKeys()

        // Helper: resolve an ApiKey for a binding.
        fun keyFor(binding: ModelProviderBinding): ApiKeyStore.ApiKey? =
            allKeys.find { it.id == binding.apiKeyId }

        // Hybrid D step 1: auto-pick cheapest healthy (if toggle is on).
        if (autoPickCheapest) {
            val cheapestHealthy = active
                .filter { BindingHealthStore.isHealthy(it.id) }
                .filter { keyFor(it) != null }
                .minByOrNull { it ->
                    // Estimated cost: model's inputPrice + outputPrice.
                    // (binding itself doesn't carry price — model does.)
                    model.inputPrice + model.outputPrice
                }
            if (cheapestHealthy != null) {
                val key = keyFor(cheapestHealthy)!!
                return cheapestHealthy to key
            }
        }

        // Hybrid D step 2: highest-priority healthy binding.
        val firstHealthy = active.firstOrNull { BindingHealthStore.isHealthy(it.id) }
        if (firstHealthy != null) {
            val key = keyFor(firstHealthy) ?: return null
            return firstHealthy to key
        }

        // Step 3/4: all unhealthy OR no key resolved. Return highest-priority
        // (let LLMClient try it — it will failover if auto-fallback is on).
        val first = active.first()
        val key = keyFor(first) ?: return null
        return first to key
    }

    /**
     * v3.2.7 → v3.3.2: Push a saved model's settings into AIProvider (the
     * active config LLMClient reads on every chat call).
     *
     * Now uses resolveBinding() (hybrid routing) to pick the best binding,
     * then writes that binding's provider/endpoint/api_key into AIProvider.
     */
    private fun applyModelToProvider(context: Context, id: String) {
        val model = models.find { it.id == id } ?: return

        // v3.3.2: Read the auto-fallback toggles from AIProvider prefs.
        val autoFallbackKeys = AutoFallbackSettings.isEnabled(context)
        val autoPickCheapest = AutoFallbackSettings.isAutoPickCheapestEnabled(context)

        val resolved = resolveBinding(context, model, autoPickCheapest)
        if (resolved == null) {
            android.util.Log.w("SavedModelsStore",
                "No resolvable binding for model '${model.modelName}' — chat will fail")
            // Clear AIProvider so the chat gate (isConfigured=false) catches it.
            AIProvider.setProvider(context, model.providerId)
            AIProvider.setModel(context, model.modelId)
            AIProvider.setApiKey(context, "")
            return
        }

        val (binding, key) = resolved
        val effectiveProviderId = binding.providerId
        AIProvider.setProvider(context, effectiveProviderId)
        // v3.3.5: resolve the actual model ID for this binding's provider.
        // Gateway providers (Cline, OpenRouter) prefix model IDs:
        //   catalog: "grok-4.7"  →  Cline expects: "x-ai/grok-4.7"
        val actualModelId = resolveActualModelIdForBinding(context, model, binding)
        AIProvider.setModel(context, actualModelId)

        // v3.3.4: use the shared endpoint resolver.
        val endpoint = resolveEndpointForBinding(model, binding, key)
        if (endpoint.isNotEmpty()) {
            AIProvider.setEndpoint(context, endpoint)
        }
        AIProvider.setApiKey(context, key.apiKey)

        android.util.Log.i("SavedModelsStore",
            "Applied model '${model.modelName}' binding #${binding.priority} " +
                    "(${binding.providerName}): endpoint=${endpoint.take(70)}, " +
                    "key=${if (key.apiKey.isNotEmpty()) "set" else "EMPTY"}, " +
                    "autoFallbackKeys=$autoFallbackKeys, autoPickCheapest=$autoPickCheapest")
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
