package com.ai.agent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.ai.agent.ui.screens.ChatScreen
import com.ai.agent.ui.theme.AIAgentTheme

// ============================================================================
// MainActivity — v2 entry point
// ----------------------------------------------------------------------------
// Fresh Compose-based rebuild. No v1 chat logic is wired in yet — this just
// mounts the theme + ChatScreen placeholder so we can iterate on the visual
// layer. Backend (LLMClient, ToolExecutor, Accessibility, rules…) is carried
// over from v1 and can be plugged in later from a ViewModel.
// ============================================================================

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // enableEdgeToEdge() must be called BEFORE super.onCreate so that the
        // Activity window is configured for transparent system bars + draws
        // behind them. Our theme then sets the status-bar / nav-bar colors
        // via WindowCompat inside AIAgentTheme.
        enableEdgeToEdge()

        super.onCreate(savedInstanceState)

        setContent {
            AIAgentTheme {
                ChatScreen()
            }
        }
    }
}
