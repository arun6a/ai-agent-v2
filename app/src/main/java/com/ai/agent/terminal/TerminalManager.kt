package com.ai.agent.terminal

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList

/**
 * v4.0.63: TerminalManager — manages terminal connections + capability probing.
 * v4.0.64: Added live output broadcasting for TerminalPreviewScreen.
 *
 * Handles:
 * 1. Connecting to a terminal (HTTP test)
 * 2. Probing capabilities (command -v pkg node java ...)
 * 3. Running commands on a specific terminal
 * 4. Status tracking
 * 5. v4.0.64: Broadcasting command output to subscribers (live preview)
 *
 * Uses HTTP (not WebSocket) for simplicity. Same-WiFi security is just the token.
 */
object TerminalManager {
    private const val TAG = "TerminalManager"

    // v4.0.64: Live output broadcasting — TerminalPreviewScreen subscribes to this
    interface TerminalListener {
        fun onCommandRun(terminalName: String, command: String)
        fun onCommandResult(terminalName: String, output: String, success: Boolean)
    }

    private val listeners = CopyOnWriteArrayList<TerminalListener>()

    fun addListener(listener: TerminalListener) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: TerminalListener) {
        listeners.remove(listener)
    }

    private fun broadcastCommand(terminalName: String, command: String) {
        for (listener in listeners) {
            try { listener.onCommandRun(terminalName, command) } catch (_: Exception) {}
        }
    }

    private fun broadcastResult(terminalName: String, output: String, success: Boolean) {
        for (listener in listeners) {
            try { listener.onCommandResult(terminalName, output, success) } catch (_: Exception) {}
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Test connection to a terminal server.
     * Returns true if the server responded with a valid JSON.
     */
    fun testConnection(conn: TerminalConnection): Boolean {
        return try {
            val url = "${conn.baseUrl()}?token=${conn.token}&cmd=echo%20aira_connected"
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""
            response.isSuccessful && body.contains("aira_connected")
        } catch (e: Exception) {
            Log.w(TAG, "Connection test failed for ${conn.name}: ${e.message}")
            false
        }
    }

    /**
     * Probe terminal capabilities by running "command -v" for common tools.
     * Returns a list of available tool names.
     */
    fun probeCapabilities(conn: TerminalConnection): List<String> {
        val tools = listOf(
            "pkg", "node", "npm", "java", "javac", "gradle",
            "git", "grep", "find", "sed", "awk", "curl", "wget",
            "python3", "python", "pip",
            "am", "pm", "settings", "input", "dumpsys", "getprop",
            "ssh", "rsync", "tar", "gzip", "unzip",
            "vim", "nano", "make", "cmake", "gcc", "clang",
        )
        // Run: command -v tool1 tool2 tool3 ...
        val cmd = "command -v ${tools.joinToString(" ")} 2>/dev/null"
        val result = runCommand(conn, cmd) ?: return emptyList()

        // Parse: each line that has a path = tool is available
        val available = mutableListOf<String>()
        val lines = result.lines()
        for (i in tools.indices) {
            if (i < lines.size && lines[i].isNotBlank() && !lines[i].contains("not found")) {
                available.add(tools[i])
            }
        }
        Log.i(TAG, "Probed ${conn.name}: ${available.size}/${tools.size} tools available: $available")
        return available
    }

    /**
     * Run a command on a terminal server.
     * Returns the stdout output, or null on failure.
     */
    fun runCommand(conn: TerminalConnection, command: String, timeout: Int = 120): String? {
        // v4.0.64: Broadcast that a command is about to run (for live preview)
        broadcastCommand(conn.name, command)
        return try {
            val encodedCmd = URLEncoder.encode(command, "UTF-8")
            val timeoutParam = if (timeout != 120) "&timeout=$timeout" else ""
            val url = "${conn.baseUrl()}?token=${conn.token}&cmd=$encodedCmd$timeoutParam"
            Log.d(TAG, "Running on ${conn.name}: ${command.take(100)}")
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return null
            // Parse JSON response: {"success":true,"output":"...","exitCode":0,...}
            val json = org.json.JSONObject(body)
            val success = json.optBoolean("success", false)
            val output = if (success) {
                json.optString("output", "")
            } else {
                val err = json.optString("stderr", json.optString("error", "Unknown error"))
                "Error: $err\nOutput: ${json.optString("output", "")}"
            }
            // v4.0.64: Broadcast the result (for live preview)
            broadcastResult(conn.name, output, success)
            output
        } catch (e: Exception) {
            Log.e(TAG, "Run command failed on ${conn.name}: ${e.message}")
            broadcastResult(conn.name, "Error: ${e.message}", false)
            null
        }
    }

    /**
     * Connect to a terminal: test + probe + save capabilities + update status.
     */
    fun connect(context: Context, conn: TerminalConnection): TerminalConnection {
        conn.status = TerminalStatus.TESTING
        TerminalStore.update(conn, context)

        if (testConnection(conn)) {
            conn.status = TerminalStatus.CONNECTED
            conn.capabilities = probeCapabilities(conn)
            Log.i(TAG, "✅ Connected to ${conn.name}. Capabilities: ${conn.capabilities.size} tools")
        } else {
            conn.status = TerminalStatus.ERROR
            Log.w(TAG, "❌ Failed to connect to ${conn.name}")
        }
        TerminalStore.update(conn, context)
        return conn
    }

    /**
     * Disconnect from a terminal.
     */
    fun disconnect(context: Context, conn: TerminalConnection) {
        conn.status = TerminalStatus.DISCONNECTED
        TerminalStore.update(conn, context)
        Log.i(TAG, "Disconnected from ${conn.name}")
    }

    /**
     * Find the best terminal for a given capability.
     * Prefers: connected > has capability > phone (localhost) for phone tasks.
     */
    fun findBestFor(capability: String, preferLocal: Boolean = false): TerminalConnection? {
        val connected = TerminalStore.getConnected()
        if (connected.isEmpty()) return null

        // If preferLocal (e.g., for am/pm/input), return the localhost one first
        if (preferLocal) {
            val local = connected.find { it.host == "127.0.0.1" || it.host == "localhost" }
            if (local != null) return local
        }

        // Otherwise, find the first connected terminal with the capability
        return connected.find { it.hasCapability(capability) } ?: connected.first()
    }
}
