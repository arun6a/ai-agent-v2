package com.ai.agent.tools.custom

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * v4.0.26: Custom Tool system — lets the AI create new tools at runtime.
 *
 * The AI can call createCustomTool(name, description, handlerType, handler, parameters)
 * to define a new tool. The tool is saved to a JSON file and loaded on app startup.
 * Custom tools appear in the tool list + can be called like any built-in tool.
 *
 * Two handler types:
 *   - "shell": Runs a shell command with {param} substitution
 *     Example: handler="curl -s https://api.example.com/check"
 *     Example with params: handler="curl -s 'https://api.example.com?q={query}'"
 *
 *   - "http": Makes an HTTP request (GET/POST) with {param} substitution in URL + body
 *     Example: handler='{"method":"GET","url":"https://api.example.com/health","headers":{"Authorization":"Bearer xxx"}}'
 *     Example with params: handler='{"method":"POST","url":"https://api.example.com/send","body":"{\"device\":\"{device}\"}"}'
 *
 * Storage: /data/data/com.ai.agent.v2/files/custom-tools.json
 * Format: [{name, description, handlerType, handler, parameters, createdAt}]
 */
object CustomToolStore {
    private const val TAG = "CustomToolStore"
    private const val FILE_NAME = "custom-tools.json"

    data class CustomTool(
        val name: String,
        val description: String,
        val handlerType: String, // "shell" or "http"
        val handler: String,    // shell command template OR JSON config for HTTP
        val parameters: List<String>,
        val createdAt: Long = System.currentTimeMillis(),
    )

    /**
     * Load all custom tools from storage.
     * Called on app startup to register tools.
     */
    fun load(context: Context): List<CustomTool> {
        val file = getFile(context)
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                CustomTool(
                    name = o.optString("name"),
                    description = o.optString("description"),
                    handlerType = o.optString("handlerType"),
                    handler = o.optString("handler"),
                    parameters = o.optJSONArray("parameters")?.let { params ->
                        (0 until params.length()).map { params.getString(it) }
                    } ?: emptyList(),
                    createdAt = o.optLong("createdAt"),
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load custom tools", e)
            emptyList()
        }
    }

    /**
     * Save the full list of custom tools.
     */
    private fun save(context: Context, tools: List<CustomTool>) {
        try {
            val arr = JSONArray()
            for (t in tools) {
                arr.put(JSONObject().apply {
                    put("name", t.name)
                    put("description", t.description)
                    put("handlerType", t.handlerType)
                    put("handler", t.handler)
                    put("parameters", JSONArray(t.parameters))
                    put("createdAt", t.createdAt)
                })
            }
            val file = getFile(context)
            file.parentFile?.mkdirs()
            file.writeText(arr.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save custom tools", e)
        }
    }

    /**
     * Add a new custom tool. Returns false if a tool with the same name exists.
     */
    fun add(context: Context, tool: CustomTool): Boolean {
        val tools = load(context).toMutableList()
        if (tools.any { it.name == tool.name }) {
            Log.w(TAG, "Custom tool '${tool.name}' already exists — not overwriting")
            return false
        }
        tools.add(tool)
        save(context, tools)
        Log.i(TAG, "✅ Custom tool created: ${tool.name} (${tool.handlerType})")
        return true
    }

    /**
     * Remove a custom tool by name.
     */
    fun remove(context: Context, name: String): Boolean {
        val tools = load(context).toMutableList()
        val removed = tools.removeAll { it.name == name }
        if (removed) {
            save(context, tools)
            Log.i(TAG, "🗑️ Custom tool deleted: $name")
        }
        return removed
    }

    /**
     * Get a specific custom tool by name.
     */
    fun get(context: Context, name: String): CustomTool? {
        return load(context).find { it.name == name }
    }

    /**
     * Get all custom tool names (for dynamic registration in the tool list).
     */
    fun getToolNames(context: Context): List<String> {
        return load(context).map { it.name }
    }

    private fun getFile(context: Context): File {
        return File(context.filesDir, FILE_NAME)
    }
}
