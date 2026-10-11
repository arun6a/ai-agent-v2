package com.ai.agent

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.ai.agent.ui.navigation.AppNavigation
import com.ai.agent.ui.theme.AIAgentTheme
import com.ai.agent.ui.theme.ThemeManager

// ============================================================================
// MainActivity — v2 entry point
// ----------------------------------------------------------------------------
// Mounts the theme + AppNavigation, which routes between splash → onboarding →
// chat / browser / hub / settings via BottomNav. The ChatScreen now owns a
// ChatViewModel that drives the real agent loop: LLMClient → ToolExecutor →
// AgentDatabase → VoiceManager.
//
// ThemeManager is initialized before setContent so the user's theme preference
// (System / Light / Dark) is honored from the very first frame.
// ============================================================================

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // enableEdgeToEdge() must be called BEFORE super.onCreate so that the
        // Activity window is configured for transparent system bars + draws
        // behind them. Our theme then sets the status-bar / nav-bar colors
        // via WindowCompat inside AIAgentTheme.
        enableEdgeToEdge()

        super.onCreate(savedInstanceState)

        // Load the persisted theme mode (System / Light / Dark).
        ThemeManager.init(this)

        setContent {
            AIAgentTheme {
                AppNavigation()
            }
        }
    }
}

// ============================================================================
// VoiceInputActivity — transparent host for overlay-triggered speech input.
// ----------------------------------------------------------------------------
// Minimal host referenced by OverlayManager.onOverlayLongPressed(). Opens
// invisibly, kicks off ACTION_RECOGNIZE_SPEECH, and forwards the result to
// the agent service. The full v1 implementation (with foreground service
// hand-off + broadcast) will be re-wired once the v2 overlay is upgraded.
// Declared in the root package so OverlayManager's `com.ai.agent.VoiceInputActivity`
// fully-qualified reference resolves.
// ============================================================================

class VoiceInputActivity : Activity() {

    companion object {
        const val REQUEST_SPEECH = 100
        private const val TAG = "VoiceInputActivity"
        const val EXTRA_LANG = "lang"
    }

    private var lang = "en-IN"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Make the activity completely transparent and non-interactive.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        )

        lang = intent.getStringExtra(EXTRA_LANG) ?: "en-IN"

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.e(TAG, "Speech recognition not available")
            finish()
            return
        }

        startSpeechRecognition()
    }

    private fun startSpeechRecognition() {
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak your command...")
            }
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQUEST_SPEECH)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start speech recognition", e)
            finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_SPEECH && resultCode == RESULT_OK && data != null) {
            val results =
                data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val text = results?.firstOrNull().orEmpty()
            Log.d(TAG, "Voice input received: $text")
            if (text.isNotEmpty()) {
                try {
                    com.ai.agent.service.OverlayManager.processVoiceCommand(text)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not forward voice command: ${e.message}")
                }
            }
        }
        finish()
    }
}
