package com.ai.agent.llm

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONObject
import java.io.File

object PluginManager {
    private const val TAG = "PluginManager"
    private const val PREFS = "ai_agent_plugins"
    private const val KEY_ENABLED_PREFIX = "plugin_enabled_"
    private const val BUILT_IN_DIR = "plugins"
    private const val USER_DIR = "plugins"

    data class Plugin(
        val name: String,
        val description: String,
        val version: String,
        val tools: List<ToolDef>,
        val promptSection: String,
        val source: String,
        var enabled: Boolean
    ) {
        data class ToolDef(
            val name: String,
            val params: List<String>,
            val description: String
        )
    }

    private var plugins: List<Plugin>? = null

    fun loadPlugins(context: Context): List<Plugin> {
        plugins?.let { return it }

        val result = mutableListOf<Plugin>()

        // Load built-in plugins from assets
        try {
            val assetFiles = context.assets.list(BUILT_IN_DIR) ?: emptyArray()
            for (file in assetFiles) {
                if (file.endsWith(".json")) {
                    try {
                        val content = context.assets.open("$BUILT_IN_DIR/$file").bufferedReader().use { it.readText() }
                        val plugin = parsePlugin(content, "builtin")
                        if (plugin != null) result.add(plugin)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to load built-in plugin: $file", e)
                    }
                }
            }
            Log.i(TAG, "Loaded ${result.size} built-in plugins from assets")
        } catch (e: Exception) {
            Log.w(TAG, "No built-in plugins directory found", e)
        }

        // Load user-installed plugins from filesDir
        val userDir = File(context.filesDir, USER_DIR)
        if (userDir.exists()) {
            userDir.listFiles()?.forEach { file ->
                if (file.extension == "json") {
                    try {
                        val content = file.readText()
                        val plugin = parsePlugin(content, "user")
                        if (plugin != null) result.add(plugin)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to load user plugin: ${file.name}", e)
                    }
                }
            }
            Log.i(TAG, "Loaded user plugins from ${userDir.absolutePath}")
        }

        // Apply enabled/disabled state from SharedPreferences
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        result.forEach { plugin ->
            val savedEnabled = prefs.getBoolean(KEY_ENABLED_PREFIX + plugin.name, plugin.enabled)
            plugin.enabled = savedEnabled
        }

        plugins = result
        return result
    }

    private fun parsePlugin(jsonStr: String, source: String): Plugin? {
        return try {
            val json = JSONObject(jsonStr)
            val name = json.optString("name", "")
            if (name.isEmpty()) return null

            val description = json.optString("description", "")
            val version = json.optString("version", "1.0")
            val promptSection = json.optString("prompt_section", "")
            val enabledByDefault = json.optBoolean("enabled", true)

            val toolsList = mutableListOf<Plugin.ToolDef>()
            val toolsArray = json.optJSONArray("tools")
            if (toolsArray != null) {
                for (i in 0 until toolsArray.length()) {
                    val toolObj = toolsArray.optJSONObject(i) ?: continue
                    val toolName = toolObj.optString("name", "")
                    if (toolName.isEmpty()) continue

                    val paramsList = mutableListOf<String>()
                    val paramsArray = toolObj.optJSONArray("params")
                    if (paramsArray != null) {
                        for (j in 0 until paramsArray.length()) {
                            paramsList.add(paramsArray.optString(j, ""))
                        }
                    }

                    val toolDesc = toolObj.optString("description", "")
                    toolsList.add(Plugin.ToolDef(toolName, paramsList, toolDesc))
                }
            }

            Plugin(name, description, version, toolsList, promptSection, source, enabledByDefault)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse plugin JSON", e)
            null
        }
    }

    fun getAllPlugins(context: Context): List<Plugin> {
        return loadPlugins(context)
    }

    fun getEnabledPlugins(context: Context): List<Plugin> {
        return loadPlugins(context).filter { it.enabled }
    }

    fun setPluginEnabled(context: Context, pluginName: String, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_ENABLED_PREFIX + pluginName, enabled).apply()
        plugins?.find { it.name == pluginName }?.enabled = enabled
        Log.i(TAG, "Plugin '$pluginName' ${if (enabled) "enabled" else "disabled"}")
    }

    fun getEnabledPluginsPrompt(context: Context): String {
        val enabled = getEnabledPlugins(context)
        if (enabled.isEmpty()) return ""

        val sb = StringBuilder()
        sb.append("\n\n## Active Plugins\n")
        for (plugin in enabled) {
            if (plugin.promptSection.isNotEmpty()) {
                sb.append(plugin.promptSection)
                sb.append("\n\n")
            }
        }
        return sb.toString()
    }

    fun getEnabledToolNames(context: Context): Set<String> {
        val tools = mutableSetOf<String>()
        for (plugin in getEnabledPlugins(context)) {
            for (tool in plugin.tools) {
                tools.add(tool.name)
            }
        }
        return tools
    }

    fun isToolAvailable(context: Context, toolName: String): Boolean {
        return getEnabledToolNames(context).contains(toolName)
    }

    fun getPlugin(context: Context, name: String): Plugin? {
        return loadPlugins(context).find { it.name == name }
    }

    fun installPlugin(context: Context, jsonStr: String): Boolean {
        return try {
            val plugin = parsePlugin(jsonStr, "user") ?: return false
            val dir = File(context.filesDir, USER_DIR)
            if (!dir.exists()) dir.mkdirs()

            val file = File(dir, "${plugin.name}.json")
            file.writeText(jsonStr)

            plugins = null
            loadPlugins(context)

            Log.i(TAG, "Installed plugin: ${plugin.name}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install plugin", e)
            false
        }
    }

    fun uninstallPlugin(context: Context, name: String): Boolean {
        return try {
            val file = File(context.filesDir, "$USER_DIR/$name.json")
            if (file.exists()) {
                file.delete()
                plugins = null
                loadPlugins(context)
                Log.i(TAG, "Uninstalled plugin: $name")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to uninstall plugin: $name", e)
            false
        }
    }

    fun reload(context: Context): List<Plugin> {
        plugins = null
        return loadPlugins(context)
    }

    fun getStatusReport(context: Context): String {
        val all = loadPlugins(context)
        val enabled = all.filter { it.enabled }
        val totalTools = enabled.sumOf { it.tools.size }

        return buildString {
            append("=== Plugin Status ===\n\n")
            append("Total plugins: ${all.size}\n")
            append("Enabled: ${enabled.size}\n")
            append("Total tools from plugins: $totalTools\n\n")
            for (plugin in all) {
                val status = if (plugin.enabled) "[OK]" else "[OFF]"
                val source = if (plugin.source == "builtin") "built-in" else "user"
                append("$status ${plugin.name} ($source) - ${plugin.tools.size} tools\n")
                append("   ${plugin.description}\n")
            }
        }
    }
}
