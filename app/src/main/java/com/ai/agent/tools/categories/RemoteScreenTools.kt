package com.ai.agent.tools.categories

import android.content.Context
import android.util.Log
import com.ai.agent.llm.LLMClient
import com.ai.agent.terminal.TerminalManager
import com.ai.agent.terminal.TerminalStatus
import com.ai.agent.terminal.TerminalStore
import com.ai.agent.tools.ToolExecutor

/**
 * v4.0.76: Remote Screen Control — control a laptop/PC screen via pyautogui.
 *
 * Tools:
 * 1. remoteScreenshot(name?) — take a screenshot on the remote machine
 * 2. remoteClick(x, y, name?) — click at (x, y) on the remote screen
 * 3. remoteType(text, name?) — type text on the remote machine
 * 4. remoteKey(key, name?) — press a key on the remote machine
 *
 * Prerequisites:
 * - A terminal must be connected to the remote machine (via connectTerminal)
 * - Python 3 + pyautogui must be installed on the remote machine
 *   (install: pip3 install pyautogui)
 *
 * Usage:
 *   Model calls connectTerminal(name="laptop", host="192.168.1.100", port=8080)
 *   Then calls remoteClick(x=500, y=300, name="laptop")
 *   → This runs: python3 -c "import pyautogui; pyautogui.click(500, 300)"
 *     on the laptop via the terminal connection
 *
 * The remote machine must have aira-server.js running (same as runOnTerminal).
 */
object RemoteScreenTools {
    private const val TAG = "RemoteScreenTools"

    suspend fun execute(
        call: LLMClient.ToolCall,
        context: Context,
    ): ToolExecutor.ToolResult {
        return when (call.name) {
            "remoteScreenshot" -> remoteScreenshot(call.args, context)
            "remoteClick" -> remoteClick(call.args, context)
            "remoteType" -> remoteType(call.args, context)
            "remoteKey" -> remoteKey(call.args, context)
            else -> ToolExecutor.ToolResult(false, "Unknown remote screen tool: ${call.name}")
        }
    }

    /**
     * Find the first connected terminal. If a name is specified, use that one.
     */
    private fun findTerminal(name: String?, context: Context): com.ai.agent.terminal.TerminalConnection? {
        TerminalStore.load(context)
        val terminals = TerminalStore.getAll()

        // If name specified, find that one
        if (name != null) {
            val conn = terminals.find { it.name == name }
            if (conn != null) return conn
            return null
        }

        // Otherwise find first CONNECTED terminal
        val connected = terminals.filter { it.status == TerminalStatus.CONNECTED }
        if (connected.isNotEmpty()) return connected.first()

        // Try to auto-connect any terminal
        for (conn in terminals) {
            val reconnected = TerminalManager.connect(context, conn)
            if (reconnected.status == TerminalStatus.CONNECTED) return reconnected
        }

        return null
    }

    private fun remoteScreenshot(args: Map<String, Any>, context: Context): ToolExecutor.ToolResult {
        val name = args["name"] as? String
        val path = args["path"] as? String ?: "/tmp/aira_screenshot.png"

        val conn = findTerminal(name, context)
            ?: return ToolExecutor.ToolResult(false,
                "No connected terminal found. Call connectTerminal(name=\"laptop\", host=\"...\", port=8080) first.")

        val cmd = "python3 -c \"import pyautogui; pyautogui.screenshot('$path'); print('OK: screenshot saved to $path')\""
        val output = TerminalManager.runCommand(conn, cmd, 30)

        return if (output != null && output.contains("OK")) {
            Log.i(TAG, "Remote screenshot saved to $path on ${conn.name}")
            ToolExecutor.ToolResult(true,
                "Screenshot saved to $path on ${conn.name}.\n" +
                "To analyze it: use runOnTerminal to base64 encode it, or use VLM tools.\n" +
                "Example: runOnTerminal(name=\"${conn.name}\", command=\"base64 $path | head -c 100\")")
        } else {
            ToolExecutor.ToolResult(false,
                "Screenshot failed on ${conn.name}.\n" +
                "Make sure pyautogui is installed: pip3 install pyautogui\n" +
                "Output: ${output ?: "(no output)"}")
        }
    }

    private fun remoteClick(args: Map<String, Any>, context: Context): ToolExecutor.ToolResult {
        val name = args["name"] as? String
        val x = (args["x"] as? Number)?.toInt()
            ?: return ToolExecutor.ToolResult(false, "missing x. Example: remoteClick(x=500, y=300)")
        val y = (args["y"] as? Number)?.toInt()
            ?: return ToolExecutor.ToolResult(false, "missing y. Example: remoteClick(x=500, y=300)")

        val conn = findTerminal(name, context)
            ?: return ToolExecutor.ToolResult(false,
                "No connected terminal found. Call connectTerminal first.")

        val cmd = "python3 -c \"import pyautogui; pyautogui.click($x, $y); print('OK: clicked at ($x, $y)')\""
        val output = TerminalManager.runCommand(conn, cmd, 15)

        return if (output != null && output.contains("OK")) {
            Log.i(TAG, "Remote click at ($x, $y) on ${conn.name}")
            ToolExecutor.ToolResult(true, "Clicked at ($x, $y) on ${conn.name}.")
        } else {
            ToolExecutor.ToolResult(false, "Click failed on ${conn.name}. Output: ${output ?: "(none)"}")
        }
    }

    private fun remoteType(args: Map<String, Any>, context: Context): ToolExecutor.ToolResult {
        val name = args["name"] as? String
        val text = args["text"] as? String
            ?: return ToolExecutor.ToolResult(false, "missing text. Example: remoteType(text=\"hello\")")

        val conn = findTerminal(name, context)
            ?: return ToolExecutor.ToolResult(false,
                "No connected terminal found. Call connectTerminal first.")

        // Escape single quotes in text for the Python command
        val escapedText = text.replace("'", "\\'")
        val cmd = "python3 -c \"import pyautogui; pyautogui.typewrite('$escapedText'); print('OK: typed ${escapedText.length} chars')\""
        val output = TerminalManager.runCommand(conn, cmd, 15)

        return if (output != null && output.contains("OK")) {
            Log.i(TAG, "Remote typed '$text' on ${conn.name}")
            ToolExecutor.ToolResult(true, "Typed '$text' on ${conn.name}.")
        } else {
            ToolExecutor.ToolResult(false, "Type failed on ${conn.name}. Output: ${output ?: "(none)"}")
        }
    }

    private fun remoteKey(args: Map<String, Any>, context: Context): ToolExecutor.ToolResult {
        val name = args["name"] as? String
        val key = args["key"] as? String
            ?: return ToolExecutor.ToolResult(false, "missing key. Example: remoteKey(key=\"enter\")")

        val conn = findTerminal(name, context)
            ?: return ToolExecutor.ToolResult(false,
                "No connected terminal found. Call connectTerminal first.")

        // Validate key name (pyautogui key names: enter, escape, tab, space, backspace, etc.)
        val validKeys = setOf("enter", "return", "esc", "escape", "tab", "space", "backspace",
            "delete", "up", "down", "left", "right", "home", "end", "pageup", "pagedown",
            "f1", "f2", "f3", "f4", "f5", "f6", "f7", "f8", "f9", "f10", "f11", "f12",
            "ctrl", "alt", "shift", "cmd", "win", "capslock", "numlock", "scrolllock",
            "insert", "printscreen", "pause")
        val keyLower = key.lowercase()
        if (keyLower !in validKeys && keyLower.length > 1) {
            return ToolExecutor.ToolResult(false,
                "Invalid key '$key'. Valid keys: ${validKeys.joinToString(", ")}\n" +
                "For single characters (a, b, 1, etc.), pass the character directly.")
        }

        val pyautoguiKey = when (keyLower) {
            "enter", "return" -> "enter"
            "esc" -> "escape"
            "ctrl" -> "ctrl"
            "alt" -> "alt"
            "shift" -> "shift"
            "win", "cmd" -> "win"
            else -> keyLower
        }

        val cmd = "python3 -c \"import pyautogui; pyautogui.press('$pyautoguiKey'); print('OK: pressed $pyautoguiKey')\""
        val output = TerminalManager.runCommand(conn, cmd, 15)

        return if (output != null && output.contains("OK")) {
            Log.i(TAG, "Remote key press '$key' on ${conn.name}")
            ToolExecutor.ToolResult(true, "Pressed '$key' on ${conn.name}.")
        } else {
            ToolExecutor.ToolResult(false, "Key press failed on ${conn.name}. Output: ${output ?: "(none)"}")
        }
    }
}
