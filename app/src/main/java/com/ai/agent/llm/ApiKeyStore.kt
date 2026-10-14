package com.ai.agent.llm

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * v3.2.0: ApiKeyStore — stores API keys per provider (encrypted via app sandbox).
 *
 * Keys are stored in SharedPreferences as JSON.
 * Each key has: provider, apiKey, alias, baseUrl, isVerified.
 *
 * Used by:
 * - AI Settings → API Keys tab (add/edit/test/delete)
 * - SavedModelsStore (checks if key exists for a provider before allowing model use)
 * - LLMClient (uses the key when making API calls)
 */
object ApiKeyStore {
    private const val TAG = "ApiKeyStore"
    private const val PREFS_NAME = "ai_agent_api_keys"
    private const val KEY_STORE = "api_keys_json"

    data class ApiKey(
        val id: String = System.currentTimeMillis().toString(),
        val providerId: String,     // "zai", "openai", "groq", "custom", etc.
        val providerName: String,   // "Z.AI", "OpenAI", "Custom"
        val apiKey: String,
        val alias: String = "",      // user-friendly name
        val baseUrl: String = "",    // custom base URL (for custom providers)
        val isVerified: Boolean = false,
    )

    private var keys: MutableList<ApiKey> = mutableListOf()
    private var loaded = false

    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val jsonStr = prefs.getString(KEY_STORE, "") ?: ""
            if (jsonStr.isNotEmpty()) {
                val arr = JSONArray(jsonStr)
                keys.clear()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    keys.add(ApiKey(
                        id = o.optString("id"),
                        providerId = o.optString("providerId"),
                        providerName = o.optString("providerName"),
                        apiKey = o.optString("apiKey"),
                        alias = o.optString("alias"),
                        baseUrl = o.optString("baseUrl"),
                        isVerified = o.optBoolean("isVerified"),
                    ))
                }
            } else {
                // v3.2.4: Pre-fill sandbox URL as a Custom API key on first launch
                // User can long-press → delete it if they don't want it
                keys.add(ApiKey(
                    id = "sandbox_prefill",
                    providerId = "custom",
                    providerName = "Sandbox (Z.ai Proxy)",
                    apiKey = "sandbox",
                    alias = "Sandbox AI",
                    baseUrl = "https://preview-chat-1855dd56-e3a8-4ac4-a21c-824fbab8e552.space-z.ai",
                ))
                save(context)
            }
            loaded = true
            Log.i(TAG, "Loaded ${keys.size} API keys")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load API keys", e)
        }
    }

    @Synchronized
    fun save(context: Context) {
        try {
            val arr = JSONArray()
            for (k in keys) {
                arr.put(JSONObject().apply {
                    put("id", k.id)
                    put("providerId", k.providerId)
                    put("providerName", k.providerName)
                    put("apiKey", k.apiKey)
                    put("alias", k.alias)
                    put("baseUrl", k.baseUrl)
                    put("isVerified", k.isVerified)
                })
            }
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_STORE, arr.toString()).apply()
            Log.i(TAG, "Saved ${keys.size} API keys")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save API keys", e)
        }
    }

    fun getAllKeys(): List<ApiKey> = keys.toList()

    fun getKey(providerId: String): ApiKey? = keys.find { it.providerId == providerId }

    fun hasKey(providerId: String): Boolean = keys.any { it.providerId == providerId }

    @Synchronized
    fun addKey(context: Context, key: ApiKey) {
        keys.add(key)
        save(context)
    }

    @Synchronized
    fun updateKey(context: Context, key: ApiKey) {
        val idx = keys.indexOfFirst { it.id == key.id }
        if (idx >= 0) {
            keys[idx] = key
        } else {
            keys.add(key)
        }
        save(context)
    }

    @Synchronized
    fun deleteKey(context: Context, id: String) {
        keys.removeAll { it.id == id }
        save(context)
    }

    @Synchronized
    fun setVerified(context: Context, id: String, verified: Boolean) {
        val idx = keys.indexOfFirst { it.id == id }
        if (idx >= 0) {
            keys[idx] = keys[idx].copy(isVerified = verified)
            save(context)
        }
    }
}
