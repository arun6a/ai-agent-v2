package com.ai.agent.service

import android.content.Context
import android.content.Intent
import android.util.Log
import com.ai.agent.Config
import com.ai.agent.accessibility.AgentAccessibilityService
import fi.iki.elonen.NanoHTTPD
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * AgentHttpServer — mini HTTP server inside the AI Agent app (v6.0.5).
 *
 * Lets sandbox AIs control the tablet screen remotely via HTTP.
 * Uses the accessibility service (which has permissions) to execute actions.
 *
 * Endpoints:
 *   GET /health              → "ok" + version + accessibility status
 *   GET /tap?x=540&y=1200    → tap at coordinates
 *   GET /swipe?x1=&y1=&x2=&y2= → swipe
 *   GET /type?text=hello      → type text into focused field
 *   GET /click?text=Search    → click element by text
 *   GET /screen               → read screen text (accessibility)
 *   GET /screenStructured     → read screen as JSON tree
 *   GET /screenshot           → base64 JPEG screenshot
 *   GET /pressBack            → press back
 *   GET /pressHome            → press home
 *   GET /pressEnter           → press enter
 *   GET /scrollDown           → scroll down
 *   GET /scrollUp             → scroll up
 *   GET /submitInput          → submit form/search
 *   GET /launchApp?package=   → launch an app
 */
class AgentHttpServer(
    private val context: Context,
    port: Int = 8080
) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "AgentHttpServer"
        private var instance: AgentHttpServer? = null

        fun start(context: Context) {
            if (instance != null) {
                Log.i(TAG, "Server already running on port ${instance?.listeningPort}")
                return
            }
            try {
                val server = AgentHttpServer(context, 8080)
                server.start(SOCKET_READ_TIMEOUT, false)
                instance = server
                Log.i(TAG, "✅ AgentHttpServer started on http://localhost:8080")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start HTTP server", e)
            }
        }

        fun stop() {
            instance?.stop()
            instance = null
            Log.i(TAG, "AgentHttpServer stopped")
        }
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val params = session.parms

        return try {
            val result = when (uri) {
                "/health" -> handleHealth()
                "/tap" -> handleTap(params)
                "/swipe" -> handleSwipe(params)
                "/type" -> handleType(params)
                "/click" -> handleClick(params)
                "/screen" -> handleScreen()
                "/screenStructured" -> handleScreenStructured()
                "/screenshot" -> handleScreenshot()
                "/pressBack" -> handleSimpleAction("pressBack") { it.pressBack() }
                "/pressHome" -> handleSimpleAction("pressHome") { it.pressHome() }
                "/pressEnter" -> handleSimpleAction("pressEnter") { it.pressEnter() }
                "/scrollDown" -> handleSimpleAction("scrollDown") { it.scrollDown() }
                "/scrollUp" -> handleSimpleAction("scrollUp") { it.scrollUp() }
                "/submitInput" -> handleSimpleAction("submitInput") { it.submitInput() }
                "/launchApp" -> handleLaunchApp(params)
                else -> "unknown endpoint: $uri"
            }
            newFixedLengthResponse(Response.Status.OK, "text/plain", result)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling $uri", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error: ${e.message}")
        }
    }

    private fun getService(): AgentAccessibilityService? {
        return AgentAccessibilityService.getInstance()
    }

    private fun handleHealth(): String {
        val service = getService()
        val a11yEnabled = AgentAccessibilityService.isEnabled(context)
        val a11yRunning = service != null
        return """{"status":"ok","version":"${Config.VERSION}","accessibility_enabled":$a11yEnabled,"accessibility_running":$a11yRunning}"""
    }

    private fun handleTap(params: Map<String, String>): String {
        val x = params["x"]?.toFloatOrNull() ?: return "missing x"
        val y = params["y"]?.toFloatOrNull() ?: return "missing y"
        val service = getService() ?: return "accessibility not running"
        val success = service.tap(x, y)
        Thread.sleep(500) // let the tap register
        return if (success) "tapped ($x, $y)" else "tap failed"
    }

    private fun handleSwipe(params: Map<String, String>): String {
        val x1 = params["x1"]?.toFloatOrNull() ?: return "missing x1"
        val y1 = params["y1"]?.toFloatOrNull() ?: return "missing y1"
        val x2 = params["x2"]?.toFloatOrNull() ?: return "missing x2"
        val y2 = params["y2"]?.toFloatOrNull() ?: return "missing y2"
        val service = getService() ?: return "accessibility not running"
        val success = service.swipe(x1, y1, x2, y2)
        return if (success) "swiped ($x1,$y1 → $x2,$y2)" else "swipe failed"
    }

    private fun handleType(params: Map<String, String>): String {
        val text = params["text"] ?: return "missing text"
        val service = getService() ?: return "accessibility not running"
        val success = service.type(text)
        Thread.sleep(400)
        return if (success) "typed: $text" else "type failed"
    }

    private fun handleClick(params: Map<String, String>): String {
        val text = params["text"] ?: return "missing text"
        val service = getService() ?: return "accessibility not running"
        val success = service.clickByText(text)
        Thread.sleep(500)
        return if (success) "clicked: $text" else "click failed: $text not found"
    }

    private fun handleScreen(): String {
        val service = getService() ?: return "accessibility not running"
        val text = service.readScreen()
        return text.ifEmpty { "(no text on screen)" }
    }

    private fun handleScreenStructured(): String {
        val service = getService() ?: return "{\"error\":\"accessibility not running\"}"
        return service.readScreenStructured()
    }

    private fun handleScreenshot(): String {
        val service = getService() ?: return "accessibility not running"
        val latch = CountDownLatch(1)
        var base64: String? = null
        service.captureScreen { b64 ->
            base64 = b64
            latch.countDown()
        }
        latch.await(5, TimeUnit.SECONDS)
        return base64 ?: "screenshot failed"
    }

    private fun handleSimpleAction(name: String, action: (AgentAccessibilityService) -> Boolean): String {
        val service = getService() ?: return "accessibility not running"
        val success = action(service)
        Thread.sleep(300)
        return if (success) "$name ok" else "$name failed"
    }

    private fun handleLaunchApp(params: Map<String, String>): String {
        val pkg = params["package"] ?: return "missing package"
        val service = getService()
        if (service != null) {
            val success = service.launchApp(pkg)
            Thread.sleep(2500)
            return if (success) "launched: $pkg" else "launch failed: $pkg"
        }
        // Fallback: use Intent directly
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                Thread.sleep(2500)
                "launched: $pkg (via intent)"
            } else {
                "app not found: $pkg"
            }
        } catch (e: Exception) {
            "launch error: ${e.message}"
        }
    }
}
