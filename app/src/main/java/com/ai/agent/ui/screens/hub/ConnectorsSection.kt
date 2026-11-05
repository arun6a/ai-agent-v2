package com.ai.agent.ui.screens.hub

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.agent.mcp.McpManager
import com.ai.agent.mcp.McpSearchResult
import com.ai.agent.mcp.McpServerConfig
import com.ai.agent.ui.components.LocalCustomColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v4.0.80: ConnectorsSection — Hub page for MCP server management.
 *
 * Features:
 * 1. Search bar — searches official registry + community registries
 * 2. Search results — with [Connect] buttons for each server
 * 3. Connected servers — list with [Disconnect] buttons
 * 4. Add custom server — manual URL entry
 *
 * This is a top-level Hub section, not buried in Settings.
 */
@Composable
fun ConnectorsSection() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()

    var mcpEnabled by remember { mutableStateOf(com.ai.agent.llm.AIProvider.isMcpEnabled(context)) }
    var autoDiscover by remember { mutableStateOf(com.ai.agent.llm.AIProvider.isMcpAutoDiscoverEnabled(context)) }
    var autoConnect by remember { mutableStateOf(com.ai.agent.llm.AIProvider.isMcpAutoConnectEnabled(context)) }
    var consent by remember { mutableStateOf(com.ai.agent.llm.AIProvider.isMcpConsentEnabled(context)) }

    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<McpSearchResult>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf("") }

    var connectedServers by remember { mutableStateOf<List<McpServerConfig>>(emptyList()) }
    var showAddServer by remember { mutableStateOf(false) }

    // Load connected servers on first display
    LaunchedEffect(Unit) {
        McpManager.load(context)
        connectedServers = McpManager.getAllServers()
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // === Header ===
        item {
            SectionHeader(
                title = "Connectors",
                subtitle = "MCP servers — unlimited external tools",
            )
        }

        // === MCP Settings Toggles ===
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(custom.surface2, RoundedCornerShape(12.dp))
                    .border(1.dp, custom.border, RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Master toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Enable MCP", color = m3.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        Text("Connect to 47,000+ external MCP servers for unlimited tools.", color = custom.fg2, fontSize = 10.sp, lineHeight = 13.sp)
                    }
                    Switch(
                        checked = mcpEnabled,
                        onCheckedChange = {
                            mcpEnabled = it
                            com.ai.agent.llm.AIProvider.setMcpEnabled(context, it)
                        },
                        colors = androidx.compose.material3.SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = custom.accent,
                            uncheckedThumbColor = custom.fg3,
                            uncheckedTrackColor = custom.surface3,
                        ),
                    )
                }

                if (mcpEnabled) {
                    Spacer(Modifier.size(4.dp))
                    Text("Options", color = custom.fg2, fontSize = 10.sp, fontWeight = FontWeight.Medium)

                    // Auto-discover
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Auto-discover servers", color = m3.onSurface, fontSize = 11.sp)
                            Text("Brain searches for MCP servers when it needs a tool.", color = custom.fg2, fontSize = 9.sp)
                        }
                        Switch(
                            checked = autoDiscover,
                            onCheckedChange = { autoDiscover = it; com.ai.agent.llm.AIProvider.setMcpAutoDiscoverEnabled(context, it) },
                            colors = androidx.compose.material3.SwitchDefaults.colors(
                                checkedThumbColor = Color.White, checkedTrackColor = custom.accent,
                                uncheckedThumbColor = custom.fg3, uncheckedTrackColor = custom.surface3,
                            ),
                        )
                    }

                    // Auto-connect
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Auto-connect official servers", color = m3.onSurface, fontSize = 11.sp)
                            Text("Auto-connect to Google, Notion, Stripe. Community needs approval.", color = custom.fg2, fontSize = 9.sp)
                        }
                        Switch(
                            checked = autoConnect,
                            onCheckedChange = { autoConnect = it; com.ai.agent.llm.AIProvider.setMcpAutoConnectEnabled(context, it) },
                            colors = androidx.compose.material3.SwitchDefaults.colors(
                                checkedThumbColor = Color.White, checkedTrackColor = custom.accent,
                                uncheckedThumbColor = custom.fg3, uncheckedTrackColor = custom.surface3,
                            ),
                        )
                    }

                    // Consent
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Consent for sensitive tools", color = m3.onSurface, fontSize = 11.sp)
                            Text("Ask before calling MCP tools that send/delete/modify data.", color = custom.fg2, fontSize = 9.sp)
                        }
                        Switch(
                            checked = consent,
                            onCheckedChange = { consent = it; com.ai.agent.llm.AIProvider.setMcpConsentEnabled(context, it) },
                            colors = androidx.compose.material3.SwitchDefaults.colors(
                                checkedThumbColor = Color.White, checkedTrackColor = custom.accent,
                                uncheckedThumbColor = custom.fg3, uncheckedTrackColor = custom.surface3,
                            ),
                        )
                    }
                }
            }
        }

        // === Search Bar ===
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Search MCP servers...", fontSize = 12.sp) },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = m3.onSurface),
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = custom.accent,
                        unfocusedBorderColor = custom.border,
                    ),
                )
                Button(
                    onClick = {
                        if (searchQuery.isNotBlank()) {
                            isSearching = true
                            searchError = ""
                            scope.launch {
                                try {
                                    val results = withContext(Dispatchers.IO) {
                                        com.ai.agent.mcp.McpRegistryClient.searchAll(searchQuery.trim())
                                    }
                                    searchResults = results
                                    if (results.isEmpty()) {
                                        searchError = "No servers found for '$searchQuery'"
                                    }
                                } catch (e: Exception) {
                                    searchError = "Search failed: ${e.message}"
                                } finally {
                                    isSearching = false
                                }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = custom.accent),
                    enabled = !isSearching && searchQuery.isNotBlank(),
                ) {
                    if (isSearching) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = Color.White,
                        )
                    } else {
                        Text("Search", color = Color.White, fontSize = 11.sp)
                    }
                }
            }
        }

        // === Search Error ===
        if (searchError.isNotEmpty()) {
            item {
                Text(searchError, color = custom.fg2, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 4.dp))
            }
        }

        // === Search Results ===
        if (searchResults.isNotEmpty()) {
            item {
                Text("Search Results (${searchResults.size})",
                    color = custom.fg2, fontSize = 10.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 4.dp))
            }
            items(searchResults.take(15)) { result ->
                McpSearchResultCard(
                    result = result,
                    onConnect = {
                        scope.launch {
                            val config = McpServerConfig(
                                name = result.name.split("/").lastOrNull()?.take(20) ?: "mcp-server",
                                transport = "http",
                                url = result.url,
                                authType = if (result.isOfficial) "oauth" else "none",
                                description = result.description,
                                publisher = result.publisher,
                                isOfficial = result.isOfficial,
                                addedBy = "manual_search",
                            )
                            val success = withContext(Dispatchers.IO) {
                                McpManager.connect(context, config)
                            }
                            if (success) {
                                connectedServers = McpManager.getAllServers()
                            }
                        }
                    },
                )
            }
        }

        // === Connected Servers ===
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Connected Servers (${connectedServers.size})",
                    color = custom.fg2, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                Button(
                    onClick = { showAddServer = !showAddServer },
                    colors = ButtonDefaults.buttonColors(containerColor = custom.accent),
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.size(4.dp))
                    Text("Add", color = Color.White, fontSize = 10.sp)
                }
            }
        }

        if (connectedServers.isEmpty()) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Icons.Outlined.Hub, contentDescription = null, tint = custom.fg3, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("No servers connected", color = custom.fg2, fontSize = 12.sp)
                    Text("Search above or add a custom server", color = custom.fg3, fontSize = 10.sp)
                }
            }
        } else {
            items(connectedServers) { config ->
                McpServerCard(
                    config = config,
                    onDisconnect = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                McpManager.disconnect(context, config.name)
                            }
                            connectedServers = McpManager.getAllServers()
                        }
                    },
                    onConnect = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                McpManager.connect(context, config)
                            }
                            connectedServers = McpManager.getAllServers()
                        }
                    },
                    onRemove = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                McpManager.removeServer(context, config.name)
                            }
                            connectedServers = McpManager.getAllServers()
                        }
                    },
                )
            }
        }

        // === Add Custom Server Form ===
        if (showAddServer) {
            item {
                AddCustomServerForm(
                    onAdd = { name, url, auth, token ->
                        scope.launch {
                            val config = McpServerConfig(
                                name = name,
                                transport = "http",
                                url = url,
                                authType = auth,
                                bearerToken = token,
                                addedBy = "manual_url",
                            )
                            withContext(Dispatchers.IO) {
                                McpManager.connect(context, config)
                            }
                            connectedServers = McpManager.getAllServers()
                            showAddServer = false
                        }
                    },
                    onCancel = { showAddServer = false },
                )
            }
        }
    }
}

@Composable
private fun McpSearchResultCard(
    result: McpSearchResult,
    onConnect: () -> Unit,
) {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(custom.surface2, RoundedCornerShape(12.dp))
            .border(1.dp, custom.border, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (result.isOfficial) {
                    Text("⭐ ", fontSize = 12.sp)
                }
                Text(
                    result.name.take(40),
                    color = m3.onSurface,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            Text(
                result.description.take(80),
                color = custom.fg2,
                fontSize = 10.sp,
                lineHeight = 13.sp,
            )
            Text(
                "${result.transport} | ${result.source}",
                color = custom.fg3,
                fontSize = 9.sp,
            )
        }
        Spacer(Modifier.size(8.dp))
        Button(
            onClick = onConnect,
            colors = ButtonDefaults.buttonColors(containerColor = custom.accent),
            enabled = result.canAutoConnect,
        ) {
            Text(if (result.canAutoConnect) "Connect" else "View", color = Color.White, fontSize = 10.sp)
        }
    }
}

@Composable
private fun McpServerCard(
    config: McpServerConfig,
    onDisconnect: () -> Unit,
    onConnect: () -> Unit,
    onRemove: () -> Unit,
) {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(custom.surface2, RoundedCornerShape(12.dp))
            .border(1.dp, custom.border, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (config.connected) "🟢 " else "🔴 ", fontSize = 12.sp)
                Text(config.name, color = m3.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                if (config.isOfficial) Text(" ⭐", fontSize = 10.sp)
            }
            val toolCount = if (config.connected) config.tools.size else 0
            Text("$toolCount tools | ${config.transport} | ${config.authType}",
                color = custom.fg2, fontSize = 10.sp)
            if (config.url.isNotEmpty()) {
                Text(config.url.take(50), color = custom.fg3, fontSize = 9.sp)
            }
            if (config.lastError.isNotEmpty() && !config.connected) {
                Text("Error: ${config.lastError.take(60)}", color = Color(0xFFEF4444), fontSize = 9.sp)
            }
        }
        Spacer(Modifier.size(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Button(
                onClick = if (config.connected) onDisconnect else onConnect,
                colors = ButtonDefaults.buttonColors(containerColor = custom.surface3),
            ) {
                Text(if (config.connected) "Disconnect" else "Connect", color = m3.onSurface, fontSize = 10.sp)
            }
            Spacer(Modifier.size(4.dp))
            Text(
                "Remove",
                color = Color(0xFFEF4444),
                fontSize = 9.sp,
                modifier = Modifier.clickable { onRemove() }.padding(4.dp),
            )
        }
    }
}

@Composable
private fun AddCustomServerForm(
    onAdd: (name: String, url: String, auth: String, token: String) -> Unit,
    onCancel: () -> Unit,
) {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme

    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var auth by remember { mutableStateOf("none") }
    var token by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(custom.surface2, RoundedCornerShape(12.dp))
            .border(1.dp, custom.border, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Add Custom Server", color = m3.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Medium)

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Name", fontSize = 10.sp) },
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp),
        )
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("URL (https://...)", fontSize = 10.sp) },
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp),
        )

        // Auth type selector
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("none", "bearer", "oauth").forEach { authType ->
                val selected = auth == authType
                Row(
                    modifier = Modifier
                        .background(
                            if (selected) custom.accent.copy(alpha = 0.15f) else custom.surface3,
                            RoundedCornerShape(8.dp),
                        )
                        .border(
                            1.dp,
                            if (selected) custom.accent.copy(alpha = 0.5f) else custom.border,
                            RoundedCornerShape(8.dp),
                        )
                        .clickable { auth = authType }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(authType, color = if (selected) custom.accent else custom.fg2, fontSize = 10.sp)
                }
            }
        }

        if (auth == "bearer") {
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Bearer Token", fontSize = 10.sp) },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onCancel,
                colors = ButtonDefaults.buttonColors(containerColor = custom.surface3),
            ) {
                Text("Cancel", color = m3.onSurface, fontSize = 10.sp)
            }
            Button(
                onClick = { onAdd(name, url, auth, token) },
                colors = ButtonDefaults.buttonColors(containerColor = custom.accent),
                enabled = name.isNotBlank() && url.isNotBlank(),
            ) {
                Text("Add Server", color = Color.White, fontSize = 10.sp)
            }
        }
    }
}
