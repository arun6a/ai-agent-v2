package com.ai.agent.llm

import android.content.Context
import android.util.Log
import com.ai.agent.util.LogCapture

/**
 * v3.3.6: SmartRouting — picks the right saved model for a user message
 * based on the tier (1/2/3) classification from ContextualPromptBuilder.
 *
 * Also builds the cross-model fallback chain — when the picked model's
 * bindings all fail, try the next model in this order:
 *   Fast → Default → Advanced → Fast
 *
 * Slots:
 *   Tier 1 (greetings)   → Fast/Cheap model (AutoFallbackSettings.getFastModelId)
 *   Tier 2 (matched tools) → Default model (SavedModelsStore.getDefaultModel)
 *   Tier 3 (complex)     → Advanced model (AutoFallbackSettings.getAdvancedModelId)
 *
 * If a slot is not configured, falls back to the Default model.
 */
object SmartRouting {

    private const val TAG = "SmartRouting"

    /**
     * Pick the primary model for this user message (based on tier).
     * Returns the SavedModel or null if no models are configured at all.
     */
    fun pickModelForMessage(context: Context, userMessage: String): SavedModelsStore.SavedModel? {
        SavedModelsStore.load(context)
        val tier = ContextualPromptBuilder.getTier(userMessage)
        val default = SavedModelsStore.getDefaultModel(context)

        val picked = when (tier) {
            1 -> {
                // Tier 1: try Fast/Cheap slot, fall back to Default.
                val fastId = AutoFallbackSettings.getFastModelId(context)
                val fast = fastId?.let { id -> SavedModelsStore.getAllModels().find { it.id == id } }
                fast ?: default
            }
            2 -> {
                // Tier 2: always the Default model.
                default
            }
            3 -> {
                // Tier 3: try Advanced slot, fall back to Default.
                val advId = AutoFallbackSettings.getAdvancedModelId(context)
                val adv = advId?.let { id -> SavedModelsStore.getAllModels().find { it.id == id } }
                adv ?: default
            }
            else -> default
        }

        LogCapture.i(TAG, "Tier $tier → picked '${picked?.modelName ?: "none"}'")
        Log.i(TAG, "Tier $tier → picked '${picked?.modelName ?: "none"}'")
        return picked
    }

    /**
     * v3.3.9: Get the model that was tier-picked + applied to AIProvider by
     * ChatViewModel.runAgentLoop → SmartRouting.pickModelForMessage →
     * SavedModelsStore.applyModelForRouting.
     *
     * This reads the AIProvider prefs (provider + model) that were just written
     * + finds the matching SavedModel. This is what chatWithFailover should
     * use to know which model's bindings to try — NOT getDefaultModel() which
     * always returns the saved default (ignoring the tier-picked model).
     *
     * Returns null if no saved model matches the current AIProvider config
     * (e.g. user hasn't set up Smart Routing yet, or the routed model was
     * deleted).
     */
    fun getCurrentlyRoutedModel(context: Context): SavedModelsStore.SavedModel? {
        SavedModelsStore.load(context)
        // First try: find by the tier-picked modelId we stored.
        val allModels = SavedModelsStore.getAllModels()
        if (allModels.isEmpty()) return SavedModelsStore.getDefaultModel(context)

        // The tier-picked model was applied via applyModelForRouting which
        // writes the model's modelId to AIProvider.KEY_MODEL. Find the
        // SavedModel whose modelId matches what's in AIProvider prefs.
        val currentModelId = AIProvider.getModel(context)
        val currentProviderId = AIProvider.getProviderId(context)

        // Try exact match by modelId first.
        val exact = allModels.find { it.modelId == currentModelId }
        if (exact != null) return exact

        // Fall back: the saved default (for backward compat when Smart Routing
        // hasn't been configured or the picked model isn't in SavedModelsStore).
        return SavedModelsStore.getDefaultModel(context)
    }

    /**
     * Build the cross-model fallback chain when the primary model fails entirely.
     *
     * The chain tries the OTHER tiers' models (excluding the primary):
     *   If primary = Fast → chain = [Default, Advanced]
     *   If primary = Default → chain = [Advanced, Fast]
     *   If primary = Advanced → chain = [Default, Fast]
     *
     * Returns an ordered list of fallback models (excluding the primary).
     * Empty list if no fallback models are configured or auto-fallback-models is OFF.
     */
    fun pickFallbackChain(
        context: Context,
        primary: SavedModelsStore.SavedModel,
    ): List<SavedModelsStore.SavedModel> {
        // If cross-model fallback is disabled, return empty.
        if (!AutoFallbackSettings.isAutoFallbackModelsEnabled(context)) return emptyList()

        SavedModelsStore.load(context)
        val default = SavedModelsStore.getDefaultModel(context)
        val fastId = AutoFallbackSettings.getFastModelId(context)
        val fast = fastId?.let { id -> SavedModelsStore.getAllModels().find { it.id == id } }
        val advId = AutoFallbackSettings.getAdvancedModelId(context)
        val adv = advId?.let { id -> SavedModelsStore.getAllModels().find { it.id == id } }

        // Build the chain based on what the primary was.
        val chain = mutableListOf<SavedModelsStore.SavedModel>()
        when {
            primary.id == fast?.id -> {
                // Primary was Fast → try Default, then Advanced.
                default?.let { chain.add(it) }
                adv?.let { chain.add(it) }
            }
            primary.id == default?.id -> {
                // Primary was Default → try Advanced, then Fast.
                adv?.let { chain.add(it) }
                fast?.let { chain.add(it) }
            }
            primary.id == adv?.id -> {
                // Primary was Advanced → try Default, then Fast.
                default?.let { chain.add(it) }
                fast?.let { chain.add(it) }
            }
            else -> {
                // Primary was some other model → try all three in order.
                fast?.let { chain.add(it) }
                default?.let { chain.add(it) }
                adv?.let { chain.add(it) }
            }
        }
        // Filter out the primary itself (don't retry it).
        val filtered = chain.filter { it.id != primary.id }
        LogCapture.i(TAG, "Fallback chain for '${primary.modelName}': ${filtered.map { it.modelName }}")
        Log.i(TAG, "Fallback chain for '${primary.modelName}': ${filtered.map { it.modelName }}")
        return filtered
    }

    /**
     * Get a human-readable summary of the current routing configuration.
     * Used by the Smart Routing settings page for display.
     */
    fun getConfigSummary(context: Context): String {
        val fastId = AutoFallbackSettings.getFastModelId(context)
        val fast = fastId?.let { id -> SavedModelsStore.getAllModels().find { it.id == id } }
        val default = SavedModelsStore.getDefaultModel(context)
        val advId = AutoFallbackSettings.getAdvancedModelId(context)
        val adv = advId?.let { id -> SavedModelsStore.getAllModels().find { it.id == id } }

        return buildString {
            append("Fast: ${fast?.modelName ?: "— (uses Default)"}\n")
            append("Default: ${default?.modelName ?: "— (not set)"}\n")
            append("Advanced: ${adv?.modelName ?: "— (uses Default)"}\n")
            append("Auto-fallback keys: ${if (AutoFallbackSettings.isEnabled(context)) "ON" else "OFF"}\n")
            append("Auto-fallback models: ${if (AutoFallbackSettings.isAutoFallbackModelsEnabled(context)) "ON" else "OFF"}\n")
            append("Auto-pick cheapest: ${if (AutoFallbackSettings.isAutoPickCheapestEnabled(context)) "ON" else "OFF"}")
        }
    }
}
