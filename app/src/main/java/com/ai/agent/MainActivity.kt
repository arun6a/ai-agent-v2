package com.ai.agent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.ai.agent.ui.navigation.AppNavigation
import com.ai.agent.ui.theme.AIAgentTheme

// ============================================================================
// MainActivity — v2 entry point
// ----------------------------------------------------------------------------
// Mounts the theme + AppNavigation, which routes between the 4 top-level
// screens (Chat / Browse / Hub / Settings via BottomNav).
//
// The ChatScreen now owns a ChatViewModel that drives the real agent loop:
// LLMClient → ToolExecutor → AgentDatabase → VoiceManager.
// Backend (LLMClient, ToolExecutor, Accessibility, rules…) is carried over
// from v1 and instantiated inside ChatViewModelFactory.
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
                AppNavigation()
            }
        }
    }
}
