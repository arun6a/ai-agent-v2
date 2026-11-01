package com.ai.agent.ui.screens.hub

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.agent.terminal.TerminalConnection
import com.ai.agent.terminal.TerminalStatus
import com.ai.agent.terminal.TerminalStore
import com.ai.agent.terminal.TerminalType
import com.ai.agent.terminal.TerminalManager
import com.ai.agent.ui.components.LocalCustomColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v4.0.63: TerminalSection — Hub section for managing terminal connections.
 *
 * Shows:
 * - All configured terminals (AndroidIDE, Termux, Laptop, Cloud)
 * - Connection status (🟢 Connected / 🔴 Not connected)
 * - Capabilities (auto-detected after connect)
 * - Setup instructions for each terminal type
 * - Connect / Disconnect / Open Preview buttons
 *
 * Also shows setup instructions for adding new terminals.
 */
@Composable
fun TerminalSection(
    onOpenTerminal: () -> Unit = {},
) {
    val context = LocalContext.current
    val custom = LocalCustomColors.current
    val scope = rememberCoroutineScope()

    // Load terminals on first render
    var terminals by remember { mutableStateOf<List<TerminalConnection>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var selectedSetup by remember { mutableStateOf<TerminalType?>(null) }

    LaunchedEffect(Unit) {
        TerminalStore.load(context)
        terminals = TerminalStore.getAll()
    }

    fun refresh() {
        terminals = TerminalStore.getAll()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Header
        item {
            Text(
                "Terminal Connections",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = custom.fg2,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Connect Aira to any terminal — phone, tablet, laptop, or cloud.\n" +
                    "Aira auto-detects capabilities (pkg, node, java, git, etc.) after connecting.",
                fontSize = 13.sp,
                color = custom.fg3,
            )
        }

        // Existing connections
        if (terminals.isNotEmpty()) {
            items(terminals) { conn ->
                TerminalCard(
                    conn = conn,
                    onConnect = {
                        scope.launch {
                            loading = true
                            withContext(Dispatchers.IO) {
                                TerminalManager.connect(context, conn)
                            }
                            loading = false
                            refresh()
                        }
                    },
                    onDisconnect = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                TerminalManager.disconnect(context, conn)
                            }
                            refresh()
                        }
                    },
                    onOpenPreview = onOpenTerminal,
                    loading = loading,
                )
            }
        }

        // Add new terminal section
        item {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Add New Terminal",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = custom.fg2,
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Terminal type cards
        items(TerminalType.values().filter { it != TerminalType.CUSTOM }) { type ->
            TerminalTypeCard(
                type = type,
                onClick = { selectedSetup = type },
            )
        }

        // Setup instructions (expandable)
        selectedSetup?.let { type ->
            item {
                SetupInstructions(
                    type = type,
                    onDismiss = { selectedSetup = null },
                    onConnect = { host, port, token ->
                        scope.launch {
                            loading = true
                            val name = "${type.name.lowercase()}-${System.currentTimeMillis()}"
                            val conn = TerminalConnection(
                                name = name,
                                host = host,
                                port = port,
                                token = token,
                                type = type,
                            )
                            TerminalStore.add(conn, context)
                            withContext(Dispatchers.IO) {
                                TerminalManager.connect(context, conn)
                            }
                            loading = false
                            selectedSetup = null
                            refresh()
                        }
                    },
                )
            }
        }

        // Loading indicator
        if (loading) {
            item {
                Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = custom.accent)
                }
            }
        }
    }
}

@Composable
private fun TerminalCard(
    conn: TerminalConnection,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenPreview: () -> Unit,
    loading: Boolean,
) {
    val custom = LocalCustomColors.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = custom.surface2),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(conn.type.icon, fontSize = 24.sp)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(conn.name, fontWeight = FontWeight.SemiBold, color = custom.fg2)
                        Text("${conn.host}:${conn.port}", fontSize = 12.sp, color = custom.fg3, fontFamily = FontFamily.Monospace)
                    }
                }
                Text(conn.status.displayText, fontSize = 13.sp)
            }

            // Capabilities
            if (conn.capabilities.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text("Capabilities (${conn.capabilities.size}):", fontSize = 12.sp, color = custom.fg3)
                Text(
                    conn.capabilities.joinToString(", "),
                    fontSize = 12.sp,
                    color = custom.fg2,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }

            // Buttons
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (conn.status) {
                    TerminalStatus.CONNECTED -> {
                        OutlinedButton(onClick = onOpenPreview) { Text("Open Preview") }
                        OutlinedButton(onClick = onDisconnect) { Text("Disconnect") }
                    }
                    TerminalStatus.DISCONNECTED, TerminalStatus.ERROR -> {
                        Button(onClick = onConnect, enabled = !loading) { Text("Connect") }
                    }
                    TerminalStatus.TESTING -> {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun TerminalTypeCard(type: TerminalType, onClick: () -> Unit) {
    val custom = LocalCustomColors.current
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = custom.surface2),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(type.icon, fontSize = 24.sp)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(type.displayName, fontWeight = FontWeight.Medium, color = custom.fg2)
                Text(
                    when (type) {
                        TerminalType.ANDROIDIDE -> "On this phone — localhost"
                        TerminalType.TERMUX -> "On phone or tablet — localhost or WiFi"
                        TerminalType.LAPTOP -> "Linux/Mac/Windows — same WiFi"
                        TerminalType.CLOUD -> "Remote server — any network"
                        TerminalType.CUSTOM -> "Custom terminal"
                    },
                    fontSize = 12.sp,
                    color = custom.fg3,
                )
            }
            Icon(Icons.Outlined.ChevronRight, contentDescription = "Setup", tint = custom.fg3)
        }
    }
}

@Composable
private fun SetupInstructions(
    type: TerminalType,
    onDismiss: () -> Unit,
    onConnect: (host: String, port: Int, token: String) -> Unit,
) {
    val custom = LocalCustomColors.current
    var host by remember { mutableStateOf(when (type) {
        TerminalType.ANDROIDIDE -> "127.0.0.1"
        else -> "192.168.1.100"
    }) }
    var port by remember { mutableStateOf("8080") }
    var token by remember { mutableStateOf("aira") }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = custom.surface2),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Setup: ${type.displayName}", fontWeight = FontWeight.SemiBold, color = custom.fg2)
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "Close", tint = custom.fg3) }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Instructions per type
            val instructions = when (type) {
                TerminalType.ANDROIDIDE -> listOf(
                    "1. Open AndroidIDE-Rv2 on your phone",
                    "2. Run the server: node /sdcard/Aira/aira-server.js &",
                    "3. Tap Connect below (host=127.0.0.1)",
                )
                TerminalType.TERMUX -> listOf(
                    "1. Open Termux on phone/tablet",
                    "2. pkg install nodejs",
                    "3. node /sdcard/Aira/aira-server.js &",
                    "4. For WiFi access: edit HOST='0.0.0.0' in server file",
                    "5. Find IP: ifconfig wlan0",
                )
                TerminalType.LAPTOP -> listOf(
                    "1. Install Node.js on your laptop (nodejs.org)",
                    "2. Copy aira-server.js to your laptop",
                    "3. Run: node aira-server.js",
                    "4. Find laptop IP: ipconfig (Windows) or ifconfig (Mac/Linux)",
                    "5. Make sure same WiFi as phone",
                )
                TerminalType.CLOUD -> listOf(
                    "1. SSH into your server",
                    "2. Install Node.js",
                    "3. Run: node aira-server.js",
                    "4. Use server's public IP or domain",
                )
                TerminalType.CUSTOM -> listOf("Enter connection details below.")
            }
            instructions.forEach { line ->
                Text(line, fontSize = 13.sp, color = custom.fg2, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(vertical = 2.dp))
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Connection inputs
            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text("Host IP") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter { c -> c.isDigit() } },
                    label = { Text("Port") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("Token") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = { onConnect(host, port.toIntOrNull() ?: 8080, token) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Connect")
            }
        }
    }
}
