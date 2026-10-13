package com.ai.agent.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.NotificationCompat
import com.ai.agent.R
import com.ai.agent.accessibility.AgentAccessibilityService
import com.ai.agent.llm.LLMClient
import com.ai.agent.tools.ToolExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Foreground service that:
 * 1. Keeps the AI Agent alive in the background
 * 2. Manages the floating overlay button
 * 3. Processes voice commands from the overlay
 */
class AgentService : Service() {

    companion object {
        const val CHANNEL_ID = "ai_agent_channel"
        const val NOTIFICATION_ID = 1
        private const val TAG = "AgentService"

        // v2.2.2: Track task running state for notification + background tasks screen
        @Volatile
        var taskRunning: Boolean = false
            private set

        const val ACTION_START = "com.ai.agent.START"
        const val ACTION_STOP = "com.ai.agent.STOP"
        const val ACTION_PROCESS_COMMAND = "com.ai.agent.PROCESS_COMMAND"
        const val EXTRA_COMMAND = "command"

        // v2.2.2: Called by ChatViewModel when a task starts
        fun notifyTaskStarted() {
            taskRunning = true
        }

        // v2.2.2: Called by ChatViewModel when a task completes/stops
        fun notifyTaskCompleted() {
            taskRunning = false
            TaskStopReceiver.reset()
        }
        // Rules — fired by AlarmManager (time rules) or NotificationListener (notification rules)
        const val ACTION_PROCESS_RULE = "com.ai.agent.PROCESS_RULE"
        const val EXTRA_RULE_NAME = "rule_name"
        const val EXTRA_RULE_ACTION = "rule_action"
        const val EXTRA_RULE_TRIGGER_TYPE = "rule_trigger_type"  // "time" | "notification" | ...
        const val EXTRA_NOTIFICATION_TITLE = "notification_title"
        const val EXTRA_NOTIFICATION_TEXT = "notification_text"

        private var overlayManager: OverlayManager? = null

        fun isOverlayVisible(): Boolean = overlayManager?.isVisible() == true
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var llmClient: LLMClient? = null
    private var toolExecutor: ToolExecutor? = null
    private var tts: TextToSpeech? = null
    private var ruleEngine: com.ai.agent.rules.RuleEngine? = null

    // Programmatic receivers (can't be in manifest for these actions)
    private var incomingCallReceiver: com.ai.agent.rules.IncomingCallReceiver? = null
    private var screenReceiver: android.content.BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "AgentService created")
        createNotificationChannel()

        // Initialize TTS for voice commands
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("en", "IN")
            }
        }

        llmClient = LLMClient(this)
        toolExecutor = ToolExecutor(this)

        // Start rule engine (legacy handler-based for foreground time rules — kept for back-compat)
        ruleEngine = com.ai.agent.rules.RuleEngine(this)
        ruleEngine?.onRuleTriggered = { rule ->
            Log.i(TAG, "Rule triggered: ${rule.name}")
            speak("Rule triggered: ${rule.name}")
            processVoiceCommand(rule.action)  // execute the rule's action
        }
        ruleEngine?.start()

        // Register programmatic receivers (cannot be in AndroidManifest.xml for these actions)
        registerProgrammaticReceivers()
    }

    /**
     * Register receivers that must be registered at runtime (can't be in manifest):
     * - IncomingCallReceiver (uses TelephonyManager.listen, not a broadcast)
     * - ScreenReceiver (ACTION_SCREEN_ON/OFF/USER_PRESENT can't be in manifest)
     */
    private fun registerProgrammaticReceivers() {
        try {
            // Incoming call listener (uses PhoneStateListener, not BroadcastReceiver)
            incomingCallReceiver = com.ai.agent.rules.IncomingCallReceiver(this)
            incomingCallReceiver?.start()

            // Screen on/off + user present
            screenReceiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
                    if (context != null && intent != null) {
                        com.ai.agent.rules.ScreenReceiver()
                            .onReceive(context, intent)
                    }
                }
            }
            val filter = android.content.IntentFilter().apply {
                addAction(android.content.Intent.ACTION_SCREEN_ON)
                addAction(android.content.Intent.ACTION_SCREEN_OFF)
                addAction(android.content.Intent.ACTION_USER_PRESENT)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(screenReceiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(screenReceiver, filter)
            }
            Log.i(TAG, "Registered programmatic receivers (call + screen)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register programmatic receivers", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForeground(NOTIFICATION_ID, createNotification())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground", e)
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PROCESS_COMMAND -> {
                val command = intent.getStringExtra(EXTRA_COMMAND) ?: ""
                if (command.isNotEmpty()) {
                    processVoiceCommand(command)
                }
            }
            ACTION_PROCESS_RULE -> {
                val ruleName = intent.getStringExtra(EXTRA_RULE_NAME) ?: "Unnamed rule"
                val ruleAction = intent.getStringExtra(EXTRA_RULE_ACTION) ?: ""
                val triggerType = intent.getStringExtra(EXTRA_RULE_TRIGGER_TYPE) ?: "time"
                val notifTitle = intent.getStringExtra(EXTRA_NOTIFICATION_TITLE) ?: ""
                val notifText = intent.getStringExtra(EXTRA_NOTIFICATION_TEXT) ?: ""
                val fromQueue = intent.getBooleanExtra("from_offline_queue", false)
                if (ruleAction.isNotEmpty()) {
                    processRuleAction(ruleName, ruleAction, triggerType, notifTitle, notifText, fromQueue)
                }
            }
            else -> {
                // v6.1.4: Don't show overlay from service — MainActivity handles overlay
                // This prevents duplicate floating buttons
                Log.i(TAG, "Service started — overlay handled by MainActivity")
            }
        }
        return START_STICKY
    }

    /**
     * Process a voice command from the overlay.
     * Calls the LLM, executes tools, speaks the result.
     */
    private fun processVoiceCommand(command: String) {
        Log.i(TAG, "Processing voice command: $command")
        speak("Got it. Processing your request.")

        scope.launch {
            try {
                val systemPrompt = """
                    You are an AI agent controlling an Android phone. You help the user by executing actions on their device.

                    Available tools:
                    - readScreen() — returns all text on screen
                    - tap(x, y) — taps at coordinates (screen is 1080x2400)
                    - clickByText(text) — clicks a UI element by text
                    - type(text) — types text into focused field
                    - swipe(x1, y1, x2, y2) — swipes
                    - pressBack(), pressHome(), pressEnter(), submitInput()
                    - launchApp(package) — launches an app
                    - wait(seconds)

                    Common apps: YouTube=com.google.android.youtube, WhatsApp=com.whatsapp, Chrome=com.android.chrome, Instagram=com.instagram.android

                    Respond in JSON: {"reply": "short message", "tool_calls": [{"name": "...", "args": {...}}]}

                    After typing in a search box, call submitInput().
                    If no tools needed, use empty tool_calls.
                """.trimIndent()

                val response = llmClient?.chat(command, systemPrompt)
                if (response != null) {
                    speak(response.reply)
                    // Execute tools if any
                    if (response.toolCalls.isNotEmpty()) {
                        toolExecutor?.executeTools(response.toolCalls)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing command", e)
                speak("Sorry, I had an error: ${e.message}")
            }
        }
    }

    /**
     * Process a rule action. This is called when:
     * - A time-based rule fires (via AlarmManager → RuleTriggerReceiver → ACTION_PROCESS_RULE intent)
     * - A notification-based rule fires (via NotificationListener → ACTION_PROCESS_RULE intent)
     *
     * Uses the FULL system prompt (same as the chat agent loop) so rules get the smart agent,
     * not the simplified voice command prompt.
     */
    private fun processRuleAction(
        ruleName: String,
        ruleAction: String,
        triggerType: String,
        notifTitle: String,
        notifText: String,
        fromQueue: Boolean = false
    ) {
        Log.i(TAG, "Processing rule action: ruleName=$ruleName, trigger=$triggerType")

        // v2.5.0: OFFLINE RULE SUPPORT
        // Check if rule action is "direct" (no AI needed) or "smart" (needs cloud AI).
        // - Direct: starts with tool name like "setBrightness(50)" or "sendSMS(...)" — execute directly.
        // - Smart: starts with natural language like "Reply politely" or "Give me a briefing" — needs AI.
        //
        // If smart + offline → queue for later (ConnectivityReceiver processes when reconnects).
        val isDirectRule = ruleAction.matches(Regex("^[a-zA-Z_][a-zA-Z0-9_]*\\(.*\\)$")) ||
                           ruleAction.startsWith("direct:") ||
                           ruleAction.startsWith("tool:")

        val cleanAction = when {
            ruleAction.startsWith("direct:") -> ruleAction.removePrefix("direct:").trim()
            ruleAction.startsWith("tool:") -> ruleAction.removePrefix("tool:").trim()
            else -> ruleAction
        }

        if (!isDirectRule) {
            // SMART RULE — needs AI (cloud)
            if (!com.ai.agent.rules.NetworkHelper.isOnline(this)) {
                if (fromQueue) {
                    // Already tried from queue + still offline — drop, don't re-queue forever
                    Log.w(TAG, "Smart rule '$ruleName' still offline after queue retry — dropping")
                    speak("Rule $ruleName couldn't run — still offline.")
                    return
                }
                Log.w(TAG, "Smart rule '$ruleName' fired while offline — queueing for later")
                com.ai.agent.rules.OfflineRuleQueue.enqueue(
                    this,
                    com.ai.agent.rules.OfflineRuleQueue.PendingRule(
                        ruleName = ruleName,
                        ruleAction = ruleAction,
                        triggerType = triggerType,
                        notifTitle = notifTitle,
                        notifText = notifText,
                    )
                )
                speak("Rule $ruleName queued — will run when back online.")
                return
            }
        }

        speak("Rule triggered: $ruleName")

        // v2.5.0: Direct rules — execute tools directly without calling AI.
        // Format: "tool_name(arg1, arg2, ...)" or multiple tools separated by ";".
        // Example: "setBrightness(50); sendSMS(+91..., Battery low)"
        if (isDirectRule) {
            Log.i(TAG, "Direct rule (no AI): $cleanAction")
            executeDirectRule(cleanAction)
            return
        }

        scope.launch {
            try {
                val basePrompt = try {
                    assets.open("system_prompt.txt").bufferedReader().use { it.readText() }
                } catch (e: Exception) {
                    "You are an AI agent controlling an Android phone."
                }

                // Inject context that's relevant to a rule (vs a chat message)
                val contextBlock = buildString {
                    append("\n\n## Current Rule Trigger\n")
                    append("- Rule name: $ruleName\n")
                    append("- Trigger type: $triggerType\n")
                    if (notifTitle.isNotEmpty() || notifText.isNotEmpty()) {
                        append("- Triggering notification:\n")
                        append("  Title: $notifTitle\n")
                        append("  Text: $notifText\n")
                    }
                    append("\nThe user is not actively chatting — this rule fired automatically. ")
                    append("Execute the action efficiently. If it requires the screen, call readScreen() first. ")
                    append("If the action would reply to a notification, be brief (the user may be busy).")
                }

                val appsPrompt = com.ai.agent.llm.AppProfileManager.getActiveAppsPromptSection(this@AgentService)
                val skillPrompt = com.ai.agent.skills.SkillManager(this@AgentService).also { it.loadSkills() }.generatePromptSection()
                val systemPrompt = basePrompt + appsPrompt + skillPrompt + contextBlock

                val llm = llmClient ?: LLMClient(this@AgentService)
                val tools = toolExecutor ?: ToolExecutor(this@AgentService)

                // Run the agent loop (simplified — no UI updates, just log + TTS)
                var conversation = "Rule action: $ruleAction"
                var iteration = 0
                val maxIterations = 25
                val workLog = StringBuilder()

                while (iteration < maxIterations) {
                    iteration++
                    val response = llm.chat(conversation, systemPrompt)

                    if (response.toolCalls.isEmpty()) {
                        // Task complete
                        speak(response.reply.take(500))
                        android.util.Log.i(TAG, "Rule '$ruleName' completed: ${response.reply}")
                        break
                    }

                    val results = tools.executeTools(response.toolCalls)
                    val resultsForLLM = StringBuilder("\n\n[Tool results from step $iteration]:\n")
                    results.forEachIndexed { i, result ->
                        val call = response.toolCalls[i]
                        val argsStr = call.args.entries.joinToString(", ") { "${it.key}=${it.value}" }
                        workLog.append("\n• ${call.name}($argsStr) → ${if (result.success) "✓" else "✗"}")
                        resultsForLLM.append("Tool: ${call.name}($argsStr)\n")
                        resultsForLLM.append("Result: ${result.output}\n\n")
                    }
                    conversation += resultsForLLM.toString()
                    conversation += "\nBased on these results, decide the next step. If task is complete, reply with just a final message and no tool_calls."
                }

                if (iteration >= maxIterations) {
                    speak("Rule $ruleName did not complete in $maxIterations steps")
                    android.util.Log.w(TAG, "Rule '$ruleName' exceeded $maxIterations iterations")
                }

                val db = com.ai.agent.storage.AgentDatabase(this@AgentService)
                db.logAction("Rule '$ruleName' executed: $workLog")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to process rule action", e)
            }
        }
    }

    /**
     * v2.5.0: Execute a DIRECT rule (no AI needed).
     * Parses tool calls like "setBrightness(50); sendSMS(+91..., Battery low)"
     * and runs them via ToolExecutor directly.
     *
     * Format:
     *   - Single tool: "setBrightness(50)"
     *   - Multiple tools: "setBrightness(50); setVolume(10); toggleFlashlight()"
     *   - Prefix optional: "direct:setBrightness(50)" or "tool:setBrightness(50)"
     *
     * Tool args are simple strings (no nested commas in values for now).
     */
    private fun executeDirectRule(actionStr: String) {
        val tools = actionStr.split(";").map { it.trim() }.filter { it.isNotEmpty() }
        val toolExecutor = toolExecutor ?: com.ai.agent.tools.ToolExecutor(this)

        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            val results = StringBuilder()
            for (toolCall in tools) {
                try {
                    // Parse: toolName(arg1, arg2, ...)
                    val match = Regex("^(\\w+)\\((.*)\\)$").find(toolCall)
                    if (match == null) {
                        results.append("✗ Couldn't parse: $toolCall\n")
                        continue
                    }
                    val toolName = match.groupValues[1]
                    val argsStr = match.groupValues[2]
                    val args = mutableMapOf<String, Any>()

                    if (argsStr.isNotEmpty()) {
                        val parts = argsStr.split(",").map { it.trim() }
                        parts.forEachIndexed { i, part ->
                            if (part.contains("=")) {
                                // Named arg: key=value
                                val (k, v) = part.split("=", limit = 2)
                                val cleanV = v.trim().trim('"').trim('\'')
                                args[k.trim()] = cleanV.toIntOrNull() ?: cleanV
                            } else {
                                // v2.5.1: Positional arg — use NUMERIC key ("0", "1", "2")
                                // so ToolExecutor.normalizePositionalArgs() maps it to the correct
                                // tool param name (e.g., "level" for setBrightness).
                                val cleanV = part.trim().trim('"').trim('\'')
                                args[i.toString()] = cleanV.toIntOrNull() ?: cleanV
                            }
                        }
                    }
                    android.util.Log.i(TAG, "Direct rule executing: $toolName($args)")
                    val toolResults = toolExecutor.executeTools(
                        listOf(com.ai.agent.llm.LLMClient.ToolCall(toolName, args))
                    )
                    val result = toolResults.firstOrNull()
                    results.append("${if (result?.success == true) "✓" else "✗"} $toolName: ${result?.output?.take(100) ?: "no result"}\n")
                } catch (e: Exception) {
                    results.append("✗ $toolCall failed: ${e.message}\n")
                }
            }
            android.util.Log.i(TAG, "Direct rule results:\n$results")
            speak("Rule complete.")
        }
    }

    private fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "service_msg")
    }

    private fun createNotification(): Notification {
        // v2.2.5: Use isEnabled(context) instead of isRunning() — the in-memory
        // instance can be null after ANR/BrowserActivity, but the system setting
        // still shows enabled. This fixes "A11y OFF" in notification when it's ON.
        val a11yStatus = if (com.ai.agent.accessibility.AgentAccessibilityService.isEnabled(this)) "ON" else "OFF"
        val taskText = if (taskRunning) "Executing task..." else "Ready"

        // v2.2.2: Add Stop Task action button to notification
        val stopIntent = android.content.Intent(this, TaskStopReceiver::class.java).apply {
            action = TaskStopReceiver.ACTION_STOP_TASK
        }
        val stopPendingIntent = android.app.PendingIntent.getBroadcast(
            this, 0, stopIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Aira")
            .setContentText("$taskText • A11y $a11yStatus")
            .setSmallIcon(R.drawable.ic_mic)
            .addAction(R.drawable.ic_mic, "Stop Task", stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AI Agent Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the AI Agent running in the background"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun showOverlay() {
        try {
            if (overlayManager == null) {
                overlayManager = OverlayManager(this)
            }
            overlayManager?.show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show overlay", e)
        }
    }

    fun hideOverlay() {
        overlayManager?.hide()
    }

    fun toggleOverlay() {
        if (overlayManager?.isVisible() == true) {
            overlayManager?.hide()
        } else {
            showOverlay()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ruleEngine?.stop()
        overlayManager?.destroy()
        overlayManager = null
        tts?.stop()
        tts?.shutdown()

        // Unregister programmatic receivers
        try {
            incomingCallReceiver?.stop()
            incomingCallReceiver = null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to stop call receiver", e)
        }
        try {
            screenReceiver?.let { unregisterReceiver(it) }
            screenReceiver = null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister screen receiver", e)
        }

        Log.i(TAG, "AgentService destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
