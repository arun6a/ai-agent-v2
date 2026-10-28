package com.ai.agent.tools.categories

import android.content.Context
import android.util.Log
import com.ai.agent.accessibility.AgentAccessibilityService
import com.ai.agent.llm.LLMClient
import com.ai.agent.storage.AgentDatabase
import com.ai.agent.tools.ToolExecutor

/**
 * v4.0.32: Deep link navigation — lets the AI open any screen inside Aira.
 *
 * The AI can navigate to:
 * - Settings (LLM config, API keys, search method, model selection)
 * - Permissions (accessibility, notification listener, storage, etc.)
 * - Hub (rules, skills, logs, memory, files)
 * - Browser
 * - Background tasks
 *
 * This gives the AI the ability to:
 * - "Switch to Groq provider" → opens LLM settings
 * - "Add your API key" → opens API key settings
 * - "Check permissions" → opens permissions page
 * - "Show my rules" → opens Hub → Rules section
 *
 * Uses a navigation callback registered by AppNavigation (same pattern as BrowserController).
 */
object DeepLinkTools {
    private const val TAG = "DeepLinkTools"

    /**
     * Navigation callback — set by AppNavigation when the app starts.
     * When the AI calls navigateToAppScreen, this callback is invoked with the route.
     */
    @Volatile
    private var navigationCallback: ((String) -> Unit)? = null

    /**
     * Register the navigation callback (called by AppNavigation.kt on app start).
     */
    fun setNavigationCallback(callback: (String) -> Unit) {
        navigationCallback = callback
        Log.i(TAG, "Navigation callback registered — AI can now navigate to app screens")
    }

    /**
     * All available deep link routes the AI can navigate to.
     */
    private val AVAILABLE_ROUTES = mapOf(
        // Main screens
        "chat" to "chat",
        "hub" to "hub",
        "browser" to "browser",
        "background_tasks" to "background_tasks",
        // Settings
        "settings" to "hub/settings",
        "llm_settings" to "provider_settings",
        "provider_settings" to "provider_settings",
        "api_keys" to "provider_settings",
        "system_prompt" to "system_prompt_editor",
        "system_prompt_editor" to "system_prompt_editor",
    )

    suspend fun execute(
        call: LLMClient.ToolCall,
        context: Context,
        @Suppress("UNUSED_PARAMETER") service: AgentAccessibilityService?,
        @Suppress("UNUSED_PARAMETER") database: AgentDatabase
    ): ToolExecutor.ToolResult {
        return when (call.name) {
            "navigateToAppScreen" -> navigateToScreen(call, context)
            "listAppScreens" -> listAppScreens()
            else -> ToolExecutor.ToolResult(false, "Unknown deep link tool: ${call.name}")
        }
    }

    /**
     * Navigate to an app screen by route name.
     */
    private fun navigateToScreen(call: LLMClient.ToolCall, context: Context): ToolExecutor.ToolResult {
        val screen = call.args["screen"] as? String
            ?: return ToolExecutor.ToolResult(false, "missing screen. Use listAppScreens() to see available screens.")

        val route = AVAILABLE_ROUTES[screen.lowercase()]
            ?: return ToolExecutor.ToolResult(false,
                "unknown screen: '$screen'. Available: ${AVAILABLE_ROUTES.keys.joinToString(", ")}. " +
                    "Use listAppScreens() for details.")

        val callback = navigationCallback
            ?: return ToolExecutor.ToolResult(false,
                "Navigation not available — the app UI is not ready. " +
                    "This happens if the app was just started. Try again in a moment.")

        return try {
            // Run on main thread (navigation must happen on main)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                callback(route)
            }
            ToolExecutor.ToolResult(true,
                "✅ Navigated to: $screen\n" +
                    "The screen is now open. Use readScreen() to see what's displayed, " +
                    "or guide the user through the settings.")
        } catch (e: Exception) {
            Log.e(TAG, "Navigation failed", e)
            ToolExecutor.ToolResult(false, "Navigation failed: ${e.message}")
        }
    }

    /**
     * List all available app screens the AI can navigate to.
     */
    private fun listAppScreens(): ToolExecutor.ToolResult {
        val sb = StringBuilder("Available app screens (use with navigateToAppScreen):\n\n")
        sb.append("Main screens:\n")
        sb.append("  • chat — the chat interface\n")
        sb.append("  • hub — Hub with rules, skills, logs, memory, files\n")
        sb.append("  • browser — in-app browser\n")
        sb.append("  • background_tasks — background task queue\n\n")
        sb.append("Settings screens:\n")
        sb.append("  • settings — main settings page\n")
        sb.append("  • llm_settings / provider_settings / api_keys — LLM provider config + API keys\n")
        sb.append("  • system_prompt — system prompt editor\n\n")
        sb.append("Example: navigateToAppScreen(screen=\"llm_settings\")\n")
        sb.append("After navigating, use readScreen() to see the current screen state.")
        return ToolExecutor.ToolResult(true, sb.toString())
    }
}
