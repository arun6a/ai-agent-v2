package com.ai.agent.ui.theme

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

// ============================================================================
// ThemeManager — global, observable theme-mode state.
// ----------------------------------------------------------------------------
// Lets the user override the system theme (System / Light / Dark) from the
// Settings section. The chosen value is persisted to SharedPreferences so it
// survives restarts, and is also exposed as a Compose `mutableStateOf` so the
// whole app recomposes the moment the user changes the setting.
//
// Usage:
//   - In MainActivity: pass `darkTheme = ThemeManager.darkTheme()` to AIAgentTheme.
//   - In Settings: call `ThemeManager.setMode(context, "dark")` — recomposition
//     fires automatically.
// ============================================================================

object ThemeManager {

    private const val PREFS = "ai_agent_prefs"
    private const val KEY_THEME_MODE = "theme_mode"

    /** The persisted user preference: "system" | "light" | "dark". */
    var mode: String by mutableStateOf("system")
        private set

    /**
     * Load the saved theme mode from SharedPreferences. Call once on app start
     * (e.g. in MainActivity.onCreate before setContent).
     */
    fun init(context: Context) {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        mode = prefs.getString(KEY_THEME_MODE, "system") ?: "system"
    }

    /**
     * Update the theme mode. Persists to prefs and bumps `mode` so Compose
     * observers (MainActivity → AIAgentTheme) recompose immediately.
     */
    fun setMode(context: Context, newMode: String) {
        val normalized = when (newMode) {
            "light", "dark" -> newMode
            else -> "system"
        }
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME_MODE, normalized)
            .apply()
        mode = normalized
    }

    /** True when the dark palette should be active given the current mode. */
    fun isDarkTheme(systemDark: Boolean): Boolean = when (mode) {
        "light" -> false
        "dark" -> true
        else -> systemDark
    }
}
