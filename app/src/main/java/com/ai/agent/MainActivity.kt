package com.ai.agent

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.ai.agent.service.AgentService
import com.ai.agent.ui.navigation.AppNavigation
import com.ai.agent.ui.theme.AIAgentTheme
import com.ai.agent.ui.theme.ThemeManager

// ============================================================================
// MainActivity — v2 entry point
// ----------------------------------------------------------------------------
// Mounts the theme + AppNavigation, which routes between splash → onboarding →
// chat / browser / hub / settings via BottomNav. The ChatScreen owns a
// ChatViewModel that drives the real agent loop: LLMClient → ToolExecutor →
// AgentDatabase → VoiceManager.
//
// ThemeManager is initialized before setContent so the user's theme preference
// (System / Light / Dark) is honored from the very first frame.
//
// v2.0.5: Re-introduced v1-equivalent startup wiring so the app behaves like
// v1 on launch — folders, foreground service, HTTP server, rule scheduler,
// floating overlay, and a once-only notification-permission prompt.
// ============================================================================

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val NOTIF_PERM_REQUEST_CODE = 100
    }

    private var overlayManager: com.ai.agent.service.OverlayManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // enableEdgeToEdge() must be called BEFORE super.onCreate so that the
        // Activity window is configured for transparent system bars + draws
        // behind them. Our theme then sets the status-bar / nav-bar colors
        // via WindowCompat inside AIAgentTheme.
        enableEdgeToEdge()

        super.onCreate(savedInstanceState)

        // ===== v2.0.5: Start all v1-equivalent services =====
        // Order matters: folders first so any service that writes to disk has
        // a place to write to; then the foreground service (which owns the
        // rule engine); then the HTTP server; then schedule time rules; then
        // show the floating overlay button; finally verify permissions.
        createAppFolders()                                  // Task 59
        startAgentService()                                 // Task 56
        startHttpServer()                                   // Task 57
        startRuleEngine()                                   // Task 58
        showOverlay()                                       // Task 61
        checkCriticalPermissions()                          // Task 60

        // Load the persisted theme mode (System / Light / Dark).
        ThemeManager.init(this)

        setContent {
            AIAgentTheme {
                AppNavigation()
            }
        }
    }

    // -------------------------------------------------------------------------
    // Task 59: Create app folders on first launch.
    // Mirrors v1's createAppFolders() — the workspace lives at
    // /storage/emulated/0/Documents/ai-workspace/ with subdirs for skills,
    // knowledge, attachments, downloads, and screenshots. .nomedia prevents
    // the gallery from indexing these directories.
    // -------------------------------------------------------------------------
    private fun createAppFolders() {
        try {
            val basePath = "/storage/emulated/0/Documents/ai-workspace"
            val folders = listOf("attachments", "skills", "knowledge", "downloads", "screenshots")
            for (folder in folders) {
                val dir = java.io.File("$basePath/$folder")
                if (!dir.exists()) {
                    dir.mkdirs()
                    Log.i(TAG, "Created folder: ${dir.absolutePath}")
                }
            }
            // Create .nomedia to prevent media scanning
            val nomedia = java.io.File(basePath, ".nomedia")
            if (!nomedia.exists()) {
                try { nomedia.createNewFile() } catch (_: Exception) {}
            }
            Log.i(TAG, "App folders ready at $basePath")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create app folders", e)
        }
    }

    // -------------------------------------------------------------------------
    // Task 56: Start the foreground AgentService.
    // On Android O+ this MUST use startForegroundService() because AgentService
    // runs as a foreground data-sync service with a persistent notification.
    // -------------------------------------------------------------------------
    private fun startAgentService() {
        try {
            val intent = Intent(this, AgentService::class.java).apply {
                action = AgentService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            Log.i(TAG, "AgentService started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AgentService", e)
        }
    }

    // -------------------------------------------------------------------------
    // Task 57: Start the mini HTTP server (port 8080) for remote screen
    // control. The server exposes /tap, /swipe, /type, /screen, /screenshot,
    // /pressBack, /pressHome, /pressEnter, /scrollDown, /scrollUp,
    // /submitInput, /launchApp — all used by the chat agent loop + external
    // MCP-style integrations.
    // -------------------------------------------------------------------------
    private fun startHttpServer() {
        try {
            com.ai.agent.service.AgentHttpServer.start(this)
            Log.i(TAG, "AgentHttpServer started on port 8080")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AgentHttpServer", e)
        }
    }

    // -------------------------------------------------------------------------
    // Task 58: Schedule all time-based rules via AlarmManager.
    // RuleScheduler reads enabled time rules from AgentDatabase and sets exact
    // (allow-while-idle) alarms for each. Also prunes completed WorkManager
    // entries so the job table doesn't grow unbounded.
    // -------------------------------------------------------------------------
    private fun startRuleEngine() {
        try {
            val scheduler = com.ai.agent.rules.RuleScheduler(this)
            scheduler.scheduleAllTimeRules()
            Log.i(TAG, "Scheduled all time rules with AlarmManager")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule time rules", e)
        }

        // Prune old WorkManager entries
        try {
            androidx.work.WorkManager.getInstance(this).pruneWork()
        } catch (e: Exception) {
            Log.w(TAG, "WorkManager prune failed", e)
        }
    }

    // -------------------------------------------------------------------------
    // Task 61: Show the floating overlay button (status orb).
    // The overlay is owned by MainActivity, NOT AgentService (the service
    // explicitly defers this — see AgentService.onStartCommand line 159-161
    // which says "Don't show overlay from service — MainActivity handles
    // overlay"). We keep a single OverlayManager instance across recreations
    // by checking isVisible() before re-creating.
    //
    // Overlay permission (SYSTEM_ALERT_WINDOW) must be granted by the user
    // via system Settings — we don't prompt here, Hub → Permissions handles
    // the request flow.
    // -------------------------------------------------------------------------
    private fun showOverlay() {
        // Check overlay permission
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Overlay permission not granted — skipping floating button")
            // Don't prompt here — Hub → Permissions handles it now
            return
        }
        // v2.0.7: Read the user's overlay-enabled preference (default ON).
        // User can toggle this from Hub → Settings → Floating Overlay.
        val prefs = getSharedPreferences("ai_agent_prefs", MODE_PRIVATE)
        val overlayEnabled = prefs.getBoolean("overlay_enabled", true)
        if (!overlayEnabled) {
            Log.i(TAG, "Overlay disabled by user — skipping")
            return
        }
        try {
            // v2.0.7: Use static flag to prevent duplicate overlay creation
            // across MainActivity recreation (rotation, app restart, config change).
            // Old code only checked the instance flag, which resets on every new
            // OverlayManager() — so a new instance would create a SECOND overlay button.
            if (com.ai.agent.service.OverlayManager.isStaticVisible()) {
                Log.i(TAG, "Overlay already visible (static flag) — skipping")
                return
            }
            if (overlayManager?.isVisible() != true) {
                if (overlayManager == null) {
                    overlayManager = com.ai.agent.service.OverlayManager(this)
                }
                overlayManager?.show()
                Log.i(TAG, "Overlay shown (single instance)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show overlay", e)
        }
    }

    // -------------------------------------------------------------------------
    // Task 60: Check critical permissions on every launch (not just onboarding).
    //
    // We don't auto-prompt for everything — runtime perms are requested
    // individually from the Hub → Permissions screen so the user has context.
    // The ONE exception is POST_NOTIFICATIONS on Android 13+ (TIRAMISU): we
    // prompt for it once on the first launch after install, because without
    // it the AgentService foreground notification won't show and the OS will
    // kill the service within seconds.
    // -------------------------------------------------------------------------
    private fun checkCriticalPermissions() {
        val critical = listOf(
            android.Manifest.permission.RECORD_AUDIO,
            android.Manifest.permission.READ_EXTERNAL_STORAGE,
            android.Manifest.permission.POST_NOTIFICATIONS,
        )
        val missing = critical.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            Log.w(TAG, "Missing permissions: $missing — requesting now")
            // v3.0.6: Actually REQUEST permissions (was just logging before — button did nothing)
            ActivityCompat.requestPermissions(
                this,
                missing.toTypedArray(),
                1001,
            )
        }

        // For POST_NOTIFICATIONS on Android 13+, prompt once on first launch.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val notifGranted = ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!notifGranted) {
                val prefs = getSharedPreferences("ai_agent_prefs", MODE_PRIVATE)
                if (!prefs.getBoolean("notif_perm_asked", false)) {
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                        NOTIF_PERM_REQUEST_CODE,
                    )
                    prefs.edit().putBoolean("notif_perm_asked", true).apply()
                }
            }
        }

        // v3.0.6: Request "All files access" on Android 11+ (needed for writeFile, readFile, etc.)
        // WRITE_EXTERNAL_STORAGE doesn't work on API 30+ — must use MANAGE_EXTERNAL_STORAGE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!android.os.Environment.isExternalStorageManager()) {
                Log.w(TAG, "⚠️ All files access NOT granted — requesting")
                try {
                    val intent = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = android.net.Uri.parse("package:$packageName")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to request all files access", e)
                }
            } else {
                Log.i(TAG, "✅ All files access granted")
            }
        }
    }
}

// ============================================================================
// VoiceInputActivity — transparent host for overlay-triggered speech input.
// ----------------------------------------------------------------------------
// Minimal host referenced by OverlayManager.onOverlayLongPressed(). Opens
// invisibly, kicks off ACTION_RECOGNIZE_SPEECH, and forwards the result to
// the agent service via OverlayManager.processVoiceCommand(text). Declared in
// the root package so OverlayManager's `com.ai.agent.VoiceInputActivity`
// fully-qualified reference resolves.
//
// Task 62: Voice command from overlay — fully wired. Long-press the floating
// button → this activity starts → speech recognition → result forwarded to
// OverlayManager.processVoiceCommand(text) → processCommandInternal(text)
// runs the full agent loop (LLMClient → ToolExecutor → TTS).
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
