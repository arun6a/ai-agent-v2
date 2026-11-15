package com.ai.agent.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.agent.terminal.TerminalManager
import com.ai.agent.terminal.TerminalStatus
import com.ai.agent.terminal.TerminalStore
import com.ai.agent.ui.components.LocalCustomColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v4.0.63: TerminalPreviewScreen — live terminal preview.
 *
 * Shows real-time output from a connected terminal + lets the user type commands.
 * Uses HTTP polling (not WebSocket yet — simpler + works with current server).
 *
 * The user can:
 * - See command output (from AI's runOnTerminal calls + manual commands)
 * - Type commands manually
 * - See connection status
 */
@Composable
fun TerminalPreviewScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val custom = LocalCustomColors.current
    val scope = rememberCoroutineScope()

    var outputLines by remember { mutableStateOf(listOf<String>()) }
    var input by remember { mutableStateOf("") }
    var connectedTerminal by remember { mutableStateOf<String?>(null) }

    val listState = rememberLazyListState()

    // v4.0.64: Subscribe to TerminalManager broadcasts for LIVE output
    // v4.0.70: Fixed — callbacks fire on background thread, must post to main thread
    DisposableEffect(Unit) {
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        val listener = object : TerminalManager.TerminalListener {
            override fun onCommandRun(terminalName: String, command: String) {
                mainHandler.post {
                    outputLines = outputLines + "[$terminalName] \$ $command"
                }
            }
            override fun onCommandResult(terminalName: String, output: String, success: Boolean) {
                mainHandler.post {
                    val prefix = if (success) "" else "⚠️ "
                    output.split("\n").forEach { line ->
                        outputLines = outputLines + "$prefix$line"
                    }
                    outputLines = outputLines + ""  // blank line for readability
                }
            }
        }
        TerminalManager.addListener(listener)
        onDispose { TerminalManager.removeListener(listener) }
    }

    // Load terminals + find the connected one
    LaunchedEffect(Unit) {
        TerminalStore.load(context)
        val connected = TerminalStore.getConnected().firstOrNull()
        connectedTerminal = connected?.name
        if (connected != null) {
            outputLines = outputLines + "✅ Connected to: ${connected.name} (${connected.host}:${connected.port})"
            outputLines = outputLines + "Capabilities: ${connected.capabilities.joinToString(", ")}"
            outputLines = outputLines + ""
        } else {
            outputLines = outputLines + "⚠️ No terminal connected. Go to Hub → Terminal to connect."
        }
    }

    // Auto-scroll to bottom when new output arrives
    LaunchedEffect(outputLines.size) {
        if (outputLines.isNotEmpty()) {
            listState.animateScrollToItem(outputLines.size - 1)
        }
    }

    Scaffold(
        topBar = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Outlined.ArrowBack, "Back", tint = custom.fg2)
                }
                Text(
                    "Terminal Preview",
                    fontSize = 18.sp,
                    color = custom.fg2,
                    modifier = Modifier.weight(1f),
                )
                if (connectedTerminal != null) {
                    Text("🟢 $connectedTerminal", fontSize = 12.sp, color = custom.accent)
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).background(Color(0xFF1E1E1E)),
        ) {
            // Output area
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(8.dp),
                state = listState,
            ) {
                items(outputLines) { line ->
                    Text(
                        text = line,
                        color = Color(0xFF00FF41),  // Terminal green
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                    )
                }
            }

            // Input bar
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp).background(Color(0xFF2D2D2D), RoundedCornerShape(8.dp)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("$ ", color = Color(0xFF00FF41), fontFamily = FontFamily.Monospace, modifier = Modifier.padding(start = 8.dp))
                TextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Type command...", color = Color.Gray, fontSize = 13.sp) },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedTextColor = Color(0xFF00FF41),
                        unfocusedTextColor = Color(0xFF00FF41),
                        cursorColor = Color(0xFF00FF41),
                    ),
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                    singleLine = true,
                )
                IconButton(onClick = {
                    if (input.isNotBlank() && connectedTerminal != null) {
                        val cmd = input
                        val termName = connectedTerminal!!
                        input = ""
                        outputLines = outputLines + "\$ $cmd"
                        scope.launch {
                            val conn = TerminalStore.get(termName)
                            if (conn != null) {
                                val result = withContext(Dispatchers.IO) {
                                    TerminalManager.runCommand(conn, cmd)
                                }
                                outputLines = outputLines + (result ?: "Error: command failed")
                            }
                        }
                    }
                }) {
                    Icon(Icons.Outlined.Send, "Run", tint = Color(0xFF00FF41))
                }
            }
        }
    }
}
