package com.ai.agent.service

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.Toast
import com.ai.agent.R
import com.ai.agent.accessibility.AgentAccessibilityService
import com.ai.agent.llm.AIProvider
import com.ai.agent.llm.LLMClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import com.ai.agent.tools.ToolExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Manages a floating button that overlays all apps.
 * - Tap: reads current screen + describes via TTS
 * - Drag: move the button anywhere on screen
 */
class OverlayManager(private val context: Context) {

    private val TAG = "OverlayManager"
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var isVisible = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var llmClient: LLMClient? = null
    private var toolExecutor: ToolExecutor? = null
    private var tts: android.speech.tts.TextToSpeech? = null
    private val vlmClient = okhttp3.OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    companion object {
        private var instance: OverlayManager? = null

        /**
         * Process a voice command from anywhere.
         * Called by VoiceInputActivity when speech recognition completes.
         */
        fun processVoiceCommand(text: String) {
            instance?.let { manager ->
                manager.scope.launch {
                    manager.processCommandInternal(text)
                }
            }
        }
    }

    init {
        instance = this  // store reference for static access
        tts = android.speech.tts.TextToSpeech(context) { status ->
            if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                Log.d(TAG, "TTS initialized for overlay")
            }
        }

        llmClient = LLMClient(context)
        toolExecutor = ToolExecutor(context)
    }

    fun isVisible(): Boolean = isVisible

    fun show() {
        if (isVisible) return

        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 300
        }

        layoutParams = params
        overlayView = LayoutInflater.from(context).inflate(R.layout.overlay_button, null)

        setupTouchHandling(params)

        try {
            windowManager?.addView(overlayView, params)
            isVisible = true
            Log.i(TAG, "Overlay shown")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show overlay", e)
            Toast.makeText(context, "Overlay error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    fun hide() {
        if (!isVisible) return
        try {
            windowManager?.removeView(overlayView)
            isVisible = false
            Log.i(TAG, "Overlay hidden")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to hide overlay", e)
        }
        overlayView = null
        layoutParams = null
    }

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var downTime = 0L
    private var isDragging = false

    private fun setupTouchHandling(params: WindowManager.LayoutParams) {
        overlayView?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    downTime = System.currentTimeMillis()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    val moved = Math.sqrt((dx * dx + dy * dy).toDouble())
                    // If moved more than 15px, it's a drag — update position
                    if (moved > 15) {
                        isDragging = true
                        params.x = initialX + dx.toInt()
                        params.y = initialY + dy.toInt()
                        windowManager?.updateViewLayout(overlayView, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    val moved = Math.sqrt((dx * dx + dy * dy).toDouble())
                    val holdTime = System.currentTimeMillis() - downTime

                    when {
                        // Long press: held for > 800ms with minimal movement
                        holdTime > 800 && moved < 15 -> {
                            Log.d(TAG, "Overlay long-pressed (held=${holdTime}ms, moved=$moved px)")
                            onOverlayLongPressed()
                        }
                        // Tap: quick touch with minimal movement
                        moved < 15 -> {
                            Log.d(TAG, "Overlay tapped (moved=$moved px)")
                            onOverlayTapped()
                        }
                        // Drag: moved more than 15px — already handled in ACTION_MOVE
                        else -> {
                            Log.d(TAG, "Overlay dragged (moved=$moved px)")
                        }
                    }
                    isDragging = false
                    true
                }
            }
            false
        }
    }

    private fun onOverlayTapped() {
        // v6.1.0: If AI is speaking, tap STOPS the speech (don't read screen)
        if (tts?.isSpeaking == true) {
            Log.i(TAG, "Overlay tapped — stopping TTS")
            tts?.stop()
            Toast.makeText(context, "Stopped speaking", Toast.LENGTH_SHORT).show()
            return
        }

        val service = AgentAccessibilityService.getInstance()
        if (service == null) {
            // Check if the service is at least ENABLED in system settings (just not bound yet).
            // If enabled, give Android a moment and try again via coroutine rather than
            // immediately erroring out — this happens at app launch before onServiceConnected.
            val enabled = AgentAccessibilityService.isEnabled(context)
            val msg = if (enabled) {
                "Accessibility is enabled but service is still starting — try again in a moment."
            } else {
                "Accessibility not enabled"
            }
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            if (!enabled) {
                speak("Accessibility service is not enabled. Please enable it in Settings → Accessibility → AI Agent.")
            }
            return
        }

        // Visual feedback
        val btn = overlayView as? ImageButton
        btn?.let { button ->
            button.alpha = 0.5f
            Handler(Looper.getMainLooper()).postDelayed({
                button.alpha = 1.0f
            }, 500)
        }

        Toast.makeText(context, "Looking at your screen...", Toast.LENGTH_SHORT).show()

        scope.launch {
            try {
                // Keep button dimmed during processing
                val btn = overlayView as? ImageButton
                btn?.alpha = 0.3f

                // Step 1: Try screenshot + VLM first (vision-first approach)
                Toast.makeText(context, "Taking screenshot...", Toast.LENGTH_SHORT).show()
                val screenshotBase64 = takeScreenshotAndSend(service)
                
                if (screenshotBase64 != null && screenshotBase64.isNotEmpty()) {
                    // Screenshot worked! Use VLM
                    Toast.makeText(context, "AI vision analyzing...", Toast.LENGTH_SHORT).show()
                    val vlmDescription = callVLM(screenshotBase64, "Describe what's on this phone screen in 2-3 short bullet points. What app? What's visible?")
                    Log.i(TAG, "VLM description: $vlmDescription")
                    
                    if (vlmDescription != null && !vlmDescription.contains("error") && vlmDescription.isNotEmpty()) {
                        // Step 2: LLM makes it conversational
                        Toast.makeText(context, "Forming response...", Toast.LENGTH_SHORT).show()
                        btn?.alpha = 1.0f

                        val llmResponse = llmClient?.chat(
                            "The VLM analyzed the user's phone screen and said:\n\n$vlmDescription\n\nGive a brief, natural spoken response to 'what am I looking at?'. Maximum 2 sentences. Be conversational.",
                            "You are a helpful AI assistant. Give brief, natural spoken responses."
                        )
                        val finalResponse = llmResponse?.reply ?: vlmDescription.take(150)
                        speak(finalResponse)
                        sendToChat(finalResponse, false)
                        return@launch
                    }
                }
                
                // Screenshot failed or VLM failed — fallback to readScreen + LLM
                Log.i(TAG, "Screenshot/VLM failed, falling back to readScreen + LLM")
                Toast.makeText(context, "Reading screen text...", Toast.LENGTH_SHORT).show()
                val screenText = service.readScreen()
                
                btn?.alpha = 1.0f
                
                if (screenText.isEmpty() || screenText == "(screen is null)" || screenText == "(no text on screen)") {
                    // v6.0.3: More helpful error — distinguish "no text" from "screenshot broken"
                    val accessibilityRunning = AgentAccessibilityService.getInstance() != null
                    val msg = if (accessibilityRunning) {
                        "Accessibility is enabled, but I can't read text on this screen. It might be an image-only screen (game, video, image). Try opening a screen with text first."
                    } else {
                        "Accessibility service is not running. Please re-enable it in Settings → Accessibility → AI Agent."
                    }
                    speak(msg)
                    sendToChat(msg, false)
                } else {
                    // Use LLM to describe the text naturally
                    val llmResponse = llmClient?.chat(
                        "The user asked 'what am I looking at?'. Here's the text I found on their screen:\n\n$screenText\n\nGive a brief, natural description. Maximum 2 sentences.",
                        "You are a helpful AI assistant. Describe what's on screen based on the text. Be conversational."
                    )
                    val finalResponse = llmResponse?.reply ?: "I can see: ${screenText.take(150)}"
                    speak(finalResponse)
                    sendToChat(finalResponse, false)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error reading screen", e)
                speak("Sorry, I had an error reading the screen.")
            }
        }
    }

    /**
     * Direct VLM call — takes screenshot and asks VLM to describe it.
     * Vision-first approach: we always use VLM, not readScreen().
     */
    private suspend fun analyzeScreenWithVLMDirect(service: AgentAccessibilityService, prompt: String): String {
        Log.i(TAG, "VLM: Taking screenshot...")
        
        // Take screenshot with 10-second timeout
        val screenshotDeferred = kotlinx.coroutines.CompletableDeferred<String?>()
        service.captureScreen { base64 ->
            Log.i(TAG, "VLM: Screenshot callback received, base64 length: ${base64?.length ?: 0}")
            screenshotDeferred.complete(base64)
        }
        
        // Wait with timeout
        val screenshotBase64 = try {
            kotlinx.coroutines.withTimeoutOrNull(10000L) {
                screenshotDeferred.await()
            }
        } catch (e: Exception) {
            Log.e(TAG, "VLM: Screenshot timeout/error", e)
            null
        }

        if (screenshotBase64 == null || screenshotBase64.isEmpty()) {
            Log.w(TAG, "VLM: Screenshot failed, falling back to text")
            val text = service.readScreen()
            return if (text.isNotEmpty() && text.length > 10) {
                "Screen text: $text"
            } else {
                "I can't see the screen right now."
            }
        }

        Log.i(TAG, "VLM: Sending to VLM proxy (${screenshotBase64.length} chars)...")

        // Send to VLM via proxy
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val bodyStr = """{"model":"glm-4v","messages":[{"role":"user","content":[{"type":"text","text":"$prompt"},{"type":"image_url","image_url":{"url":"data:image/jpeg;base64,$screenshotBase64"}}]}]}"""
                val body = bodyStr.toRequestBody("application/json".toMediaType())

                Log.i(TAG, "VLM: POST to /api/llm/vision")
                val request = okhttp3.Request.Builder()
                    .url(AIProvider.getVisionUrl(context))
                    .header("Content-Type", "application/json")
                    .post(body)
                    .build()

                val response = vlmClient.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""
                Log.i(TAG, "VLM: Response code=${response.code}, body length=${responseBody.length}")

                if (!response.isSuccessful) {
                    Log.e(TAG, "VLM error ${response.code}: ${responseBody.take(300)}")
                    return@withContext "VLM error: ${response.code}. Response: ${responseBody.take(100)}"
                }

                val json = org.json.JSONObject(responseBody)
                val content = json
                    .optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")
                    ?: "No response from VLM"

                Log.i(TAG, "VLM: Got response: ${content.take(200)}...")
                content
            } catch (e: Exception) {
                Log.e(TAG, "VLM call failed", e)
                "VLM error: ${e.message}"
            }
        }
    }

    /**
     * Long-press handler — triggers voice input from any screen.
     * Opens a transparent VoiceInputActivity that handles speech recognition.
     */
    private fun onOverlayLongPressed() {
        Toast.makeText(context, "Listening... speak now", Toast.LENGTH_SHORT).show()

        try {
            val intent = android.content.Intent(context, com.ai.agent.VoiceInputActivity::class.java).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(com.ai.agent.VoiceInputActivity.EXTRA_LANG, "en-IN")
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start voice input", e)
            Toast.makeText(context, "Voice input error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Process a voice command using the FULL AGENT LOOP (not fire-and-forget).
     * Shows the command and result in the chat UI + speaks the result.
     */
    private suspend fun processCommandInternal(command: String) {
        Log.i(TAG, "Processing voice command: $command")
        speak("Got it.")

        // Show the user's voice command in the chat UI
        sendToChat(command, true)

        // Show "Processing..." in chat
        sendToChat("Processing your voice command...", false)

        try {
            val systemPrompt = """
                You are an AI agent controlling an Android phone. You help the user by executing actions on their device.

                You work in a LOOP. After you call tools, you will receive the RESULTS. Then decide the next step:
                - If task is COMPLETE → reply with just a message, NO tool_calls
                - If a step FAILED → try an alternative
                - If you need to VERIFY → call readScreen()

                Available tools:
                - readScreen() — returns all text on screen. USE THIS to verify actions.
                - tap(x, y) — taps at coordinates (screen is 1080x2400)
                - clickByText(text) — clicks a UI element by text
                - type(text) — types text into focused field
                - swipe(x1, y1, x2, y2) — swipes
                - scrollDown(), scrollUp()
                - pressBack(), pressHome(), pressEnter(), submitInput()
                - launchApp(package) — launches an app
                - listInstalledApps() — lists installed apps
                - wait(seconds)

                Common apps: YouTube=com.google.android.youtube, WhatsApp=com.whatsapp, Chrome=com.android.chrome, Instagram=com.instagram.android, Spotify=com.spotify.music

                Respond in JSON: {"reply": "short message", "tool_calls": [{"name": "...", "args": {...}}]}

                Rules:
                - After typing in a search box, call submitInput() then readScreen() to verify
                - If clickByText fails, readScreen() to see what's there, try alternative
                - If a step fails TWICE, STOP and report
                - Keep replies short (1-2 sentences)
                - Call up to 5-6 tools per response
                - When task is complete, use EMPTY tool_calls
            """.trimIndent()

            val conversation = StringBuilder(command)
            val toolCallHistory = mutableListOf<String>()
            val MAX_ITERATIONS = 15
            val MAX_REPEATED = 3

            var iteration = 0
            var finalReply = ""

            while (iteration < MAX_ITERATIONS) {
                iteration++
                val response = llmClient?.chat(conversation.toString(), systemPrompt) ?: break

                // No tool calls = task complete
                if (response.toolCalls.isEmpty()) {
                    finalReply = response.reply
                    break
                }

                // Infinite loop detection (action tools only, not readScreen/wait)
                val actionTools = response.toolCalls.filter { it.name != "readScreen" && it.name != "wait" }
                var stuck = false
                for (call in actionTools) {
                    val sig = "${call.name}(${call.args.entries.joinToString(",") { "${it.key}=${it.value}" }})"
                    toolCallHistory.add(sig)
                    if (toolCallHistory.count { it == sig } >= MAX_REPEATED) {
                        finalReply = "I'm stuck — I tried $sig $MAX_REPEATED times. ${response.reply}"
                        stuck = true
                        break
                    }
                }
                if (stuck) break

                // Execute tools
                val results = toolExecutor?.executeTools(response.toolCalls) ?: break

                // Build results for LLM
                val resultsForLLM = StringBuilder("\n\nTool results:\n")
                results.forEachIndexed { i, result ->
                    val call = response.toolCalls[i]
                    val argsStr = if (call.args.isNotEmpty()) {
                        call.args.entries.joinToString(", ") { "${it.key}=${it.value}" }
                    } else ""
                    resultsForLLM.append("${call.name}($argsStr): ${result.output}\n")
                }

                // Speak progress (first iteration only, to avoid too much talking)
                if (iteration == 1) {
                    speak(response.reply)
                }

                conversation.append(resultsForLLM.toString())
                conversation.append("\nDecide next step. If complete, reply with no tool_calls. If failed, try different approach.")

                // Trim conversation if too long
                if (conversation.length > 3000) {
                    val recent = conversation.substring(conversation.length - 2500)
                    conversation.clear()
                    conversation.append(command)
                    conversation.append("\n[Previous steps omitted. Recent:]\n")
                    conversation.append(recent)
                }

                kotlinx.coroutines.delay(300)
            }

            if (finalReply.isEmpty()) {
                finalReply = if (iteration >= MAX_ITERATIONS) {
                    "I've done $MAX_ITERATIONS steps. Task may need more work."
                } else {
                    "Done."
                }
            }

            Log.i(TAG, "Voice command complete: $finalReply")
            speak(finalReply)

            // Show the result in chat UI
            sendToChat(finalReply, false)

        } catch (e: Exception) {
            Log.e(TAG, "Error processing command", e)
            speak("Sorry, I had an error: ${e.message}")
        }
    }

    private fun speak(text: String) {
        tts?.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "overlay_msg")
    }

    /**
     * Step 1: Take a screenshot. Returns base64 or null.
     * NO text fallback — only returns the screenshot.
     */
    private suspend fun takeScreenshotAndSend(service: AgentAccessibilityService): String? {
        Log.i(TAG, "STEP 1: Taking screenshot...")
        val deferred = kotlinx.coroutines.CompletableDeferred<String?>()
        
        service.captureScreen { base64 ->
            Log.i(TAG, "STEP 1: Screenshot callback, length=${base64?.length ?: 0}")
            deferred.complete(base64)
        }
        
        // Wait with timeout
        val result = kotlinx.coroutines.withTimeoutOrNull(15000L) {
            deferred.await()
        }
        
        if (result == null) {
            Log.e(TAG, "STEP 1: Screenshot timed out after 15s")
        } else {
            Log.i(TAG, "STEP 1: Screenshot complete (${result.length} chars)")
        }
        
        return result
    }

    /**
     * Step 2: Call VLM with the screenshot. Returns description or null.
     */
    private suspend fun callVLM(screenshotBase64: String, prompt: String): String? {
        Log.i(TAG, "STEP 2: Calling VLM with ${screenshotBase64.length} chars...")
        
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val bodyStr = """{"model":"glm-4v","messages":[{"role":"user","content":[{"type":"text","text":"$prompt"},{"type":"image_url","image_url":{"url":"data:image/jpeg;base64,$screenshotBase64"}}]}]}"""
                val body = bodyStr.toRequestBody("application/json".toMediaType())

                Log.i(TAG, "STEP 2: POST to /api/llm/vision")
                val request = okhttp3.Request.Builder()
                    .url(AIProvider.getVisionUrl(context))
                    .header("Content-Type", "application/json")
                    .post(body)
                    .build()

                val response = vlmClient.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""
                Log.i(TAG, "STEP 2: VLM response code=${response.code}, length=${responseBody.length}")

                if (!response.isSuccessful) {
                    Log.e(TAG, "STEP 2: VLM error ${response.code}: ${responseBody.take(200)}")
                    return@withContext null
                }

                val json = org.json.JSONObject(responseBody)
                val content = json
                    .optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")
                    ?: null

                Log.i(TAG, "STEP 2: VLM content: ${content?.take(200) ?: "null"}")
                content
            } catch (e: Exception) {
                Log.e(TAG, "STEP 2: VLM call failed", e)
                null
            }
        }
    }

    /**
     * Send a message to the chat UI in MainActivity via broadcast.
     * Also saves to the database directly (in case the activity is paused).
     */
    private fun sendToChat(text: String, isUser: Boolean) {
        try {
            // Save to database directly (works even if activity is paused)
            val db = com.ai.agent.storage.AgentDatabase(context)
            if (isUser) {
                db.addConversation("user", text)
            } else {
                db.addConversation("ai", text)
            }

            // Send broadcast to update the UI if activity is visible
            val intent = android.content.Intent("com.ai.agent.SHOW_MESSAGE").apply {
                putExtra("text", text)
                putExtra("isUser", isUser)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                context.sendBroadcast(intent, null)
            } else {
                context.sendBroadcast(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send to chat", e)
        }
    }

    fun destroy() {
        hide()
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
