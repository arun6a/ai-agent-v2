package com.ai.agent.terminal

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * v4.0.63: TerminalConnection — data class + persistence for terminal connections.
 *
 * Represents a connection to a terminal server (aira-server.js) running on:
 * - The phone itself (localhost, via AndroidIDE-Rv2)
 * - Another device on the same WiFi (tablet, laptop)
 * - A remote server (cloud)
 *
 * Stores: name, host, port, token, type, capabilities, status.
 * Persisted to /sdcard/Aira/terminals.json so connections survive app restarts.
 */
data class TerminalConnection(
    val name: String,           // "phone-androidide", "laptop-linux", etc.
    val host: String,           // "127.0.0.1" or "192.168.1.100"
    val port: Int,              // 8080
    val token: String,          // "aira" or custom token
    val type: TerminalType,     // ANDROIDIDE, TERMUX, LAPTOP, CLOUD, CUSTOM
    var status: TerminalStatus = TerminalStatus.DISCONNECTED,
    var capabilities: List<String> = emptyList(),  // ["pkg","node","java","git",...]
) {
    /**
     * The full URL for HTTP requests to this terminal.
     */
    fun baseUrl(): String = "http://$host:$port/exec"

    /**
     * Whether this terminal has a specific capability (e.g., "node", "python3", "gradle").
     */
    fun hasCapability(tool: String): Boolean = capabilities.any { it.equals(tool, ignoreCase = true) }

    /**
     * Human-readable capabilities summary.
     */
    fun capabilitiesSummary(): String {
        if (capabilities.isEmpty()) return "Unknown (not tested yet)"
        return capabilities.joinToString(", ")
    }
}

enum class TerminalType(val displayName: String, val icon: String) {
    ANDROIDIDE("AndroidIDE-Rv2", "📱"),
    TERMUX("Termux", "📱"),
    LAPTOP("Laptop", "💻"),
    CLOUD("Cloud Server", "☁️"),
    CUSTOM("Custom", "🔌"),
}

enum class TerminalStatus(val displayText: String, val connected: Boolean) {
    CONNECTED("🟢 Connected", true),
    DISCONNECTED("🔴 Not connected", false),
    TESTING("🔄 Testing...", false),
    ERROR("⚠️ Error", false),
}

/**
 * Manages terminal connections + persistence.
 * Stored as JSON in /sdcard/Aira/terminals.json.
 */
object TerminalStore {
    private const val TAG = "TerminalStore"
    private const val FILE_NAME = "terminals.json"

    private var connections: MutableList<TerminalConnection> = mutableListOf()
    private var loaded = false

    /**
     * Load connections from disk. Called once on app startup.
     */
    fun load(context: Context) {
        if (loaded) return
        try {
            val file = File("${getBaseDir(context)}/$FILE_NAME")
            if (!file.exists()) {
                // First run — no saved connections
                loaded = true
                return
            }
            val json = file.readText()
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val conn = TerminalConnection(
                    name = obj.getString("name"),
                    host = obj.getString("host"),
                    port = obj.getInt("port"),
                    token = obj.getString("token"),
                    type = TerminalType.valueOf(obj.optString("type", "CUSTOM")),
                    status = TerminalStatus.DISCONNECTED,  // Always start disconnected
                    capabilities = parseCapabilities(obj.optJSONArray("capabilities")),
                )
                connections.add(conn)
            }
            Log.i(TAG, "Loaded ${connections.size} terminal connection(s) from disk")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load terminals", e)
        }
        loaded = true
    }

    /**
     * Save connections to disk.
     */
    fun save(context: Context) {
        try {
            val dir = File(getBaseDir(context))
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, FILE_NAME)
            val arr = JSONArray()
            for (conn in connections) {
                val obj = JSONObject()
                obj.put("name", conn.name)
                obj.put("host", conn.host)
                obj.put("port", conn.port)
                obj.put("token", conn.token)
                obj.put("type", conn.type.name)
                val capsArr = JSONArray()
                for (cap in conn.capabilities) capsArr.put(cap)
                obj.put("capabilities", capsArr)
                // Don't save status — always start disconnected
                arr.put(obj)
            }
            file.writeText(arr.toString())
            Log.d(TAG, "Saved ${connections.size} terminal connection(s)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save terminals", e)
        }
    }

    fun getAll(): List<TerminalConnection> = connections.toList()

    fun get(name: String): TerminalConnection? = connections.find { it.name == name }

    fun add(conn: TerminalConnection, context: Context): Boolean {
        // Don't add duplicate names
        if (connections.any { it.name == conn.name }) return false
        connections.add(conn)
        save(context)
        return true
    }

    fun remove(name: String, context: Context): Boolean {
        val removed = connections.removeAll { it.name == name }
        if (removed) save(context)
        return removed
    }

    fun update(conn: TerminalConnection, context: Context) {
        val idx = connections.indexOfFirst { it.name == conn.name }
        if (idx >= 0) {
            connections[idx] = conn
            save(context)
        }
    }

    fun getConnected(): List<TerminalConnection> = connections.filter { it.status == TerminalStatus.CONNECTED }

    private fun parseCapabilities(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val list = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            list.add(arr.getString(i))
        }
        return list
    }

    private fun getBaseDir(context: Context): String {
        return "/sdcard/Aira"
    }
}
