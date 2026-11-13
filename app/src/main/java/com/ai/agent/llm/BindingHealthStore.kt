package com.ai.agent.llm

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * v3.3.2: BindingHealthStore — tracks per-binding health (success rate, latency,
 * failure timestamps) so the routing layer can avoid unhealthy bindings.
 *
 * Persisted to SharedPreferences so the app remembers "key X was rate-limited
 * 10 minutes ago" across restarts. Health entries expire after 5 minutes of
 * inactivity (no calls), so a binding that was rate-limited becomes healthy
 * again after the cooldown.
 *
 * Health data is keyed by bindingId (which is unique per (model, provider, key)).
 */
object BindingHealthStore {

    private const val TAG = "BindingHealthStore"
    private const val PREFS_NAME = "ai_agent_binding_health"
    private const val KEY_HEALTH = "health_json"
    private const val COOLDOWN_MS = 5L * 60 * 1000  // 5 minutes
    private const val FAILURE_THRESHOLD = 3  // consecutive failures before unhealthy

    data class BindingHealth(
        val bindingId: String,
        var lastSuccessAt: Long = 0,
        var lastErrorAt: Long = 0,
        var consecutiveFailures: Int = 0,
        var totalCalls: Int = 0,
        var totalSuccesses: Int = 0,
        var totalLatencyMs: Long = 0,
        var lastError: String? = null,
    ) {
        val avgLatencyMs: Long
            get() = if (totalSuccesses > 0) totalLatencyMs / totalSuccesses else 0

        val successRate: Float
            get() = if (totalCalls > 0) totalSuccesses.toFloat() / totalCalls else 1f

        /**
         * A binding is unhealthy if it had >= FAILURE_THRESHOLD consecutive failures
         * AND the last error was less than COOLDOWN_MS ago.
         */
        val isHealthy: Boolean
            get() {
                if (consecutiveFailures < FAILURE_THRESHOLD) return true
                val cooldownEnd = lastErrorAt + COOLDOWN_MS
                return System.currentTimeMillis() > cooldownEnd
            }
    }

    private val health: MutableMap<String, BindingHealth> = mutableMapOf()
    private var loaded = false

    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val jsonStr = prefs.getString(KEY_HEALTH, "") ?: ""
            if (jsonStr.isNotEmpty()) {
                val arr = JSONArray(jsonStr)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("bindingId")
                    health[id] = BindingHealth(
                        bindingId = id,
                        lastSuccessAt = o.optLong("lastSuccessAt", 0),
                        lastErrorAt = o.optLong("lastErrorAt", 0),
                        consecutiveFailures = o.optInt("consecutiveFailures", 0),
                        totalCalls = o.optInt("totalCalls", 0),
                        totalSuccesses = o.optInt("totalSuccesses", 0),
                        totalLatencyMs = o.optLong("totalLatencyMs", 0),
                        lastError = if (o.has("lastError") && !o.isNull("lastError"))
                            o.optString("lastError") else null,
                    )
                }
            }
            loaded = true
            Log.i(TAG, "Loaded health for ${health.size} bindings")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load binding health", e)
        }
    }

    @Synchronized
    fun save(context: Context) {
        try {
            val arr = JSONArray()
            for ((id, h) in health) {
                arr.put(JSONObject().apply {
                    put("bindingId", id)
                    put("lastSuccessAt", h.lastSuccessAt)
                    put("lastErrorAt", h.lastErrorAt)
                    put("consecutiveFailures", h.consecutiveFailures)
                    put("totalCalls", h.totalCalls)
                    put("totalSuccesses", h.totalSuccesses)
                    put("totalLatencyMs", h.totalLatencyMs)
                    put("lastError", h.lastError ?: JSONObject.NULL)
                })
            }
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_HEALTH, arr.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save binding health", e)
        }
    }

    /**
     * Get the health record for a binding (creates one if missing).
     */
    fun get(bindingId: String): BindingHealth {
        return health.getOrPut(bindingId) { BindingHealth(bindingId) }
    }

    /**
     * Is this binding currently healthy? (no recent failures above threshold)
     */
    fun isHealthy(bindingId: String): Boolean {
        return get(bindingId).isHealthy
    }

    /**
     * Record a successful call to this binding.
     */
    @Synchronized
    fun recordSuccess(bindingId: String, latencyMs: Long, context: Context? = null) {
        val h = get(bindingId)
        h.totalCalls++
        h.totalSuccesses++
        h.consecutiveFailures = 0
        h.lastSuccessAt = System.currentTimeMillis()
        h.totalLatencyMs += latencyMs
        context?.let { save(it) }
    }

    /**
     * Record a failed call to this binding.
     */
    @Synchronized
    fun recordFailure(bindingId: String, error: String, context: Context? = null) {
        val h = get(bindingId)
        h.totalCalls++
        h.consecutiveFailures++
        h.lastErrorAt = System.currentTimeMillis()
        h.lastError = error
        Log.w(TAG, "Binding $bindingId failed (${h.consecutiveFailures}x): $error")
        context?.let { save(it) }
    }

    /**
     * Reset a binding's health (used when user manually re-enables a binding).
     */
    @Synchronized
    fun reset(bindingId: String, context: Context? = null) {
        health.remove(bindingId)
        context?.let { save(it) }
    }

    /**
     * Reset all health data (used when user toggles auto-fallback off/on).
     */
    @Synchronized
    fun resetAll(context: Context) {
        health.clear()
        save(context)
    }
}
