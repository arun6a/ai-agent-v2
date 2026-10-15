package com.ai.agent.llm

import android.content.Context

/**
 * v3.3.2: AutoFallbackSettings — user-toggled settings for the routing layer.
 *
 * Stored in AIProvider's SharedPreferences so they live next to the rest of
 * the AI config. Two toggles:
 *
 *   1. autoFallbackKeys (default ON):
 *      When the active binding fails (429/5xx), automatically try the next
 *      enabled binding for the same model. If all bindings fail, return the
 *      error to the user.
 *
 *   2. autoPickCheapest (default OFF):
 *      When ON, resolveBinding() picks the cheapest healthy binding (lowest
 *      inputPrice + outputPrice) instead of the user's priority order.
 *      Useful when the user has multiple bindings and wants to minimize cost.
 *
 *   3. autoFallbackModels (default OFF — comes in v3.3.3):
 *      When the active MODEL fails entirely (all bindings exhausted), fall
 *      back to a different saved model (e.g. default → advanced).
 *
 * These are USER TOGGLES — the user enables/disables them in AI Settings.
 * The routing layer reads them at resolveBinding() time.
 */
object AutoFallbackSettings {

    private const val PREFS = "ai_agent_provider"
    private const val KEY_AUTO_FALLBACK_KEYS = "auto_fallback_keys_v332"
    private const val KEY_AUTO_PICK_CHEAPEST = "auto_pick_cheapest_v332"
    private const val KEY_AUTO_FALLBACK_MODELS = "auto_fallback_models_v333"

    /**
     * When the active binding fails, try the next enabled binding.
     * Default: ON (this is the expected behavior — silent failover).
     */
    fun isEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_FALLBACK_KEYS, true)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AUTO_FALLBACK_KEYS, enabled).apply()
    }

    /**
     * Pick the cheapest healthy binding instead of priority order.
     * Default: OFF (user controls priority).
     */
    fun isAutoPickCheapestEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_PICK_CHEAPEST, false)
    }

    fun setAutoPickCheapestEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AUTO_PICK_CHEAPEST, enabled).apply()
    }

    /**
     * v3.3.3 (placeholder — used by tier-based routing later):
     * When the active MODEL fails entirely, fall back to a different model.
     * Default: OFF.
     */
    fun isAutoFallbackModelsEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_FALLBACK_MODELS, false)
    }

    fun setAutoFallbackModelsEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AUTO_FALLBACK_MODELS, enabled).apply()
    }
}
