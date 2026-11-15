package com.ai.agent.terminal

import android.content.Context
import android.util.Log
import com.ai.agent.tools.ToolExecutor

/**
 * v4.0.63: TerminalTools — AI-callable tools for terminal management.
 *
 * Tools:
 * 1. connectTerminal(name, host, port, token?) — connect to a terminal server
 * 2. listTerminals() — list all configured terminals + status + capabilities
 * 3. runOnTerminal(name, command, timeout?) — run a command on a specific terminal
 * 4. disconnectTerminal(name) — disconnect from a terminal
 *
 * These let the AI connect to AndroidIDE, Termux, laptop, or cloud terminals
 * and run commands on them — not just localhost.
 */
object TerminalTools {
    private const val TAG = "TerminalTools"

    fun execute(call: ToolExecutor.Companion, args: Map<String, Any>, context: Context): ToolExecutor.ToolResult {
        // This is called from ToolExecutor when a terminal tool is invoked.
        return ToolExecutor.ToolResult(false, "Use dispatch pattern")
    }

    // Static methods called by ToolExecutor
    object Handler {
        fun connectTerminal(args: Map<String, Any>, context: Context): ToolExecutor.ToolResult {
            val name = args["name"] as? String
                ?: return ToolExecutor.ToolResult(false, "missing name. Example: connectTerminal(name=\"phone-androidide\", host=\"127.0.0.1\", port=8080)")
            val host = args["host"] as? String
                ?: return ToolExecutor.ToolResult(false, "missing host. Example: host=\"127.0.0.1\" or \"192.168.1.100\"")
            val port = (args["port"] as? Number)?.toInt() ?: 8080
            val token = args["token"] as? String ?: "aira"
            val typeStr = (args["type"] as? String ?: "custom").uppercase()
            val type = when (typeStr) {
                "ANDROIDIDE", "ANDROID_IDE", "IDE" -> TerminalType.ANDROIDIDE
                "TERMUX" -> TerminalType.TERMUX
                "LAPTOP", "COMPUTER", "PC" -> TerminalType.LAPTOP
                "CLOUD", "REMOTE", "SERVER" -> TerminalType.CLOUD
                else -> TerminalType.CUSTOM
            }

            TerminalStore.load(context)
            val existing = TerminalStore.get(name)
            val conn = if (existing != null) {
                existing.copy(host = host, port = port, token = token, type = type)
            } else {
                TerminalConnection(name = name, host = host, port = port, token = token, type = type)
            }

            if (existing == null) {
                TerminalStore.add(conn, context)
            }

            // Connect: test + probe capabilities
            val updated = TerminalManager.connect(context, conn)

            return if (updated.status == TerminalStatus.CONNECTED) {
                ToolExecutor.ToolResult(true,
                    "✅ Connected to '$name' ($host:$port)\n" +
                    "Type: ${updated.type.displayName}\n" +
                    "Capabilities (${updated.capabilities.size} tools):\n" +
                    updated.capabilities.joinToString(", ") + "\n\n" +
                    "Use runOnTerminal(name=\"$name\", command=\"...\") to run commands."
                )
            } else {
                ToolExecutor.ToolResult(false,
                    "❌ Failed to connect to '$name' ($host:$port).\n" +
                    "Make sure aira-server.js is running:\n" +
                    "  1. Open the terminal app (AndroidIDE/Termux/laptop)\n" +
                    "  2. Run: node /sdcard/Aira/aira-server.js &\n" +
                    "  3. Make sure the host + port are correct\n" +
                    "  4. For network terminals, ensure same WiFi + server listens on 0.0.0.0"
                )
            }
        }

        fun listTerminals(args: Map<String, Any>, context: Context): ToolExecutor.ToolResult {
            TerminalStore.load(context)
            val terminals = TerminalStore.getAll()
            if (terminals.isEmpty()) {
                return ToolExecutor.ToolResult(true,
                    "No terminal connections configured.\n\n" +
                    "Set up a terminal:\n" +
                    "  1. Open Hub → Terminal section\n" +
                    "  2. Or use connectTerminal(name=\"phone-androidide\", host=\"127.0.0.1\", port=8080)\n" +
                    "\nTerminal types:\n" +
                    "  - AndroidIDE-Rv2 (on phone, localhost:8080)\n" +
                    "  - Termux (on phone/tablet, localhost or WiFi IP)\n" +
                    "  - Laptop (on same WiFi, e.g., 192.168.1.100:8080)\n" +
                    "  - Cloud (remote server)"
                )
            }
            val sb = StringBuilder("Terminal Connections (${terminals.size}):\n\n")
            for (conn in terminals) {
                sb.append("${conn.type.icon} ${conn.name}\n")
                sb.append("  Host: ${conn.host}:${conn.port}\n")
                sb.append("  Status: ${conn.status.displayText}\n")
                if (conn.capabilities.isNotEmpty()) {
                    sb.append("  Capabilities: ${conn.capabilities.joinToString(", ")}\n")
                }
                sb.append("\n")
            }
            return ToolExecutor.ToolResult(true, sb.toString())
        }

        fun runOnTerminal(args: Map<String, Any>, context: Context): ToolExecutor.ToolResult {
            TerminalStore.load(context)
            val name = args["name"] as? String
                ?: return ToolExecutor.ToolResult(false, "missing name. Example: runOnTerminal(name=\"phone-androidide\", command=\"ls /sdcard/\")")
            val command = args["command"] as? String
                ?: return ToolExecutor.ToolResult(false, "missing command")
            val timeout = (args["timeout"] as? Number)?.toInt() ?: 120

            val conn = TerminalStore.get(name)
            if (conn == null) {
                return ToolExecutor.ToolResult(false,
                    "Terminal '$name' not found. Call listTerminals() to see configured terminals, " +
                    "or connectTerminal(name=\"$name\", host=\"...\", port=8080) to add one."
                )
            }
            if (conn.status != TerminalStatus.CONNECTED) {
                // Try to reconnect
                val reconnected = TerminalManager.connect(context, conn)
                if (reconnected.status != TerminalStatus.CONNECTED) {
                    return ToolExecutor.ToolResult(false,
                        "Terminal '$name' is not connected. Make sure aira-server.js is running on $conn.host:$conn.port"
                    )
                }
            }

            val output = TerminalManager.runCommand(conn, command, timeout)
            return if (output != null) {
                ToolExecutor.ToolResult(true, "[$name] $command\n→ $output")
            } else {
                ToolExecutor.ToolResult(false, "[$name] Command failed: $command")
            }
        }

        fun disconnectTerminal(args: Map<String, Any>, context: Context): ToolExecutor.ToolResult {
            TerminalStore.load(context)
            val name = args["name"] as? String
                ?: return ToolExecutor.ToolResult(false, "missing name")
            val conn = TerminalStore.get(name)
                ?: return ToolExecutor.ToolResult(false, "Terminal '$name' not found")

            TerminalManager.disconnect(context, conn)
            return ToolExecutor.ToolResult(true, "Disconnected from '$name'.")
        }
    }
}
