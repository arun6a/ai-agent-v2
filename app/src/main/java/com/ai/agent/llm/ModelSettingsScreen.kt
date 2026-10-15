package com.ai.agent.llm

import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.agent.ui.components.LocalCustomColors
import com.ai.agent.ui.theme.MonoFontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * v3.2.2: ModelSettingsScreen — section-based navigation (no LazyColumn nesting).
 * Shows a list of sections. Click to open each section's content page.
 */
@Composable
fun ModelSettingsScreen(
    onOpenProviderSettings: () -> Unit = {},
) {
    val context = LocalContext.current
    val custom = LocalCustomColors.current
    val scope = rememberCoroutineScope()
    var selectedSection by remember { mutableStateOf(-1) }
    var toast by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        ApiKeyStore.load(context)
        SavedModelsStore.load(context)
        ModelsDevRepository.load(context)
    }

    if (selectedSection == -1) {
        AiSettingsSectionList(onSectionClick = { selectedSection = it })
    } else {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { selectedSection = -1 }) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = custom.accent)
                }
                Text(getSectionTitle(selectedSection), color = custom.fg2, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            when (selectedSection) {
                0 -> ApiKeysTab(scope = scope, onToast = { toast = it })
                1 -> SavedModelsTab(onToast = { toast = it })
                2 -> ModelCatalogTab(scope = scope, onToast = { toast = it })
                3 -> LlmConfigPage()
                4 -> VisionConfigPage()
                5 -> AudioConfigPage()
                6 -> FutureSectionsPage()
                7 -> AdvancedConfigPage()
            }
        }
    }

    LaunchedEffect(toast) {
        toast?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            toast = null
        }
    }
}

private fun getSectionTitle(section: Int): String = when (section) {
    0 -> "API Keys"; 1 -> "Saved Models"; 2 -> "Model Catalog"
    3 -> "LLM Configuration"; 4 -> "Vision"; 5 -> "Audio"
    6 -> "Coming Soon"; 7 -> "Advanced"; else -> "AI Settings"
}

@Composable
private fun AiSettingsSectionList(onSectionClick: (Int) -> Unit) {
    val custom = LocalCustomColors.current
    val keyCount = ApiKeyStore.getAllKeys().size
    val modelCount = SavedModelsStore.getAllModels().size
    val catalogLoaded = ModelsDevRepository.isLoaded()

    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("AI Settings", color = custom.fg2, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
        item { Text("Manage models, API keys, and capabilities", color = custom.fg3, fontSize = 11.sp) }
        item { Spacer(Modifier.height(8.dp)) }
        item { SectionCard("🔑", "API Keys", "$keyCount key${if (keyCount != 1) "s" else ""} saved") { onSectionClick(0) } }
        item { SectionCard("🤖", "Saved Models", "$modelCount model${if (modelCount != 1) "s" else ""} saved") { onSectionClick(1) } }
        item { SectionCard("📦", "Model Catalog", if (catalogLoaded) "${ModelsDevRepository.allModels().size} models" else "Tap to load") { onSectionClick(2) } }
        item { Spacer(Modifier.height(8.dp)) }
        item { SectionCard("⚙️", "LLM Configuration", "Default agent model + prompts") { onSectionClick(3) } }
        item { SectionCard("👁️", "Vision", "Screen reading model + mode") { onSectionClick(4) } }
        item { SectionCard("🔊", "Audio", "TTS + voice input") { onSectionClick(5) } }
        item { SectionCard("🚀", "Coming Soon", "Image gen, video, coding") { onSectionClick(6) } }
        item { SectionCard("🔧", "Advanced", "Provider endpoint + prompt mode") { onSectionClick(7) } }
    }
}

@Composable
private fun SectionCard(icon: String, title: String, subtitle: String, onClick: () -> Unit) {
    val custom = LocalCustomColors.current
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(custom.ink2, RoundedCornerShape(8.dp))
            .border(1.dp, custom.inkBorder, RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(icon, fontSize = 18.sp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Text(subtitle, color = custom.fg3, fontSize = 10.sp)
        }
        Text("▶", color = custom.fg3, fontSize = 12.sp)
    }
}

// === TAB 1: API KEYS ===

@Composable
private fun ApiKeysTab(scope: kotlinx.coroutines.CoroutineScope, onToast: (String) -> Unit) {
    val context = LocalContext.current
    val custom = LocalCustomColors.current
    var showAddDialog by remember { mutableStateOf(false) }
    var editingKey by remember { mutableStateOf<ApiKeyStore.ApiKey?>(null) }
    var keys by remember { mutableStateOf(ApiKeyStore.getAllKeys()) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("API Keys", color = custom.fg2, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                IconButton(onClick = { showAddDialog = true }) {
                    Icon(Icons.Outlined.Add, contentDescription = "Add key", tint = custom.accent)
                }
            }
        }
        if (keys.isEmpty()) {
            item {
                Text("No API keys yet. Tap + to add one.", color = custom.fg3, fontSize = 12.sp)
            }
        } else {
            items(keys, key = { it.id }) { key ->
                ApiKeyCard(
                    key = key,
                    onDelete = {
                        ApiKeyStore.deleteKey(context, key.id)
                        // v3.3.0: drop this key's cached model list too.
                        ProviderModelsStore.invalidate(context, key.id)
                        keys = ApiKeyStore.getAllKeys()
                        onToast("Key deleted: ${key.providerName}")
                    },
                    onTest = {
                        scope.launch {
                            val result = testApiKeyConnection(key)
                            onToast(result)
                        }
                    },
                    onEdit = {
                        editingKey = key
                    },
                )
            }
        }
    }

    if (showAddDialog) {
        AddApiKeyDialog(
            onDismiss = { showAddDialog = false },
            onSave = { newKey ->
                ApiKeyStore.addKey(context, newKey)
                keys = ApiKeyStore.getAllKeys()
                showAddDialog = false
                onToast("Key saved: ${newKey.providerName} — fetching available models...")
                // v3.3.0: fetch this key's available models in the background.
                scope.launch {
                    val count = ProviderModelsStore.fetchAndCache(context, newKey).size
                    onToast("✅ ${newKey.providerName}: $count models available")
                }
            },
        )
    }

    editingKey?.let { key ->
        EditApiKeyDialog(
            original = key,
            onDismiss = { editingKey = null },
            onSave = { updated ->
                ApiKeyStore.updateKey(context, updated)
                keys = ApiKeyStore.getAllKeys()
                editingKey = null
                onToast("Key updated: ${updated.providerName} — refreshing models...")
                // v3.3.0: re-fetch on edit (URL or key may have changed) + invalidate old cache.
                scope.launch {
                    ProviderModelsStore.invalidate(context, key.id)
                    val count = ProviderModelsStore.fetchAndCache(context, updated).size
                    onToast("✅ ${updated.providerName}: $count models available")
                }
            },
        )
    }
}

@Composable
private fun ApiKeyCard(key: ApiKeyStore.ApiKey, onDelete: () -> Unit, onTest: () -> Unit, onEdit: () -> Unit) {
    val custom = LocalCustomColors.current
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(custom.ink2, RoundedCornerShape(8.dp))
            .border(1.dp, custom.inkBorder, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Key, contentDescription = null, tint = custom.accent, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(key.alias.ifEmpty { key.providerName }, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Text("${key.providerName} · ${if (key.apiKey.take(8).isNotEmpty()) key.apiKey.take(8) + "..." else "(empty)"}", color = custom.fg3, fontFamily = MonoFontFamily, fontSize = 10.sp)
            if (key.baseUrl.isNotEmpty()) {
                Text(key.baseUrl, color = custom.fg3, fontFamily = MonoFontFamily, fontSize = 9.sp, maxLines = 1)
            }
        }
        TextButton(onClick = onTest) { Text("Test", fontSize = 11.sp) }
        IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, contentDescription = "Edit", tint = custom.fg3, modifier = Modifier.size(16.dp)) }
        IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, contentDescription = "Delete", tint = custom.fg3, modifier = Modifier.size(16.dp)) }
    }
}

// v3.2.3: Add API Key dialog — with provider dropdown, auto-fill base URL, advanced options
@Composable
private fun AddApiKeyDialog(onDismiss: () -> Unit, onSave: (ApiKeyStore.ApiKey) -> Unit) {
    val context = LocalContext.current
    val custom = LocalCustomColors.current
    val scope = rememberCoroutineScope()

    // v3.3.1: selectedProvider is now a ProviderEndpoint (from our registry),
    // not a CatalogProvider (which had wrong/empty URLs). null = Custom provider.
    var selectedEndpoint by remember { mutableStateOf<ProviderEndpoints.ProviderEndpoint?>(null) }
    var isCustom by remember { mutableStateOf(false) }
    var providerName by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var alias by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }  // v3.3.1: editable, auto-replaced on provider switch
    var showKey by remember { mutableStateOf(false) }
    var showAdvanced by remember { mutableStateOf(false) }
    var chatEndpoint by remember { mutableStateOf("/v1/chat/completions") }
    var modelsEndpoint by remember { mutableStateOf("/v1/models") }
    var apiFormat by remember { mutableStateOf("Chat Completions (/chat/completions)") }
    var showProviderPicker by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }

    // v3.3.1: Build the provider picker list from ProviderEndpoints.ALL (the
    // canonical registry) + a "Custom" option. Grouped for the picker dialog.
    data class ProviderGroup(val name: String, val providers: List<ProviderEndpoints.ProviderEndpoint>)
    val popularProviders = remember {
        val byId = { id: String -> ProviderEndpoints.byId(id) }
        listOf(
            ProviderGroup("Recommended", listOfNotNull(
                byId("openrouter"), byId("zai"), byId("groq"),
            )),
            ProviderGroup("Popular", listOfNotNull(
                byId("openai"), byId("anthropic"), byId("gemini"),
                byId("together"), byId("mistral"), byId("deepinfra"),
                byId("fireworks"), byId("cerebras"),
            )),
            ProviderGroup("Free / Gateway", listOfNotNull(
                byId("apikeys_in"), byId("cline"),
            )),
        )
    }

    if (showProviderPicker) {
        AlertDialog(
            onDismissRequest = { showProviderPicker = false },
            containerColor = custom.canvas,
            title = { Text("Select Provider", color = custom.fg2, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                    for (group in popularProviders) {
                        item {
                            Text(group.name, color = custom.fg3, fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                        }
                        items(group.providers, key = { it.providerId }) { provider ->
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    selectedEndpoint = provider
                                    isCustom = false
                                    providerName = provider.displayName
                                    // v3.3.1: auto-replace the endpoint URL on provider switch.
                                    baseUrl = provider.chatEndpoint
                                    showProviderPicker = false
                                }.padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(provider.displayName,
                                        color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
                                    Text(provider.docsUrl,
                                        color = custom.fg3, fontFamily = MonoFontFamily, fontSize = 9.sp,
                                        maxLines = 1)
                                }
                            }
                        }
                    }
                    // Custom option
                    item {
                        Text("Custom", color = custom.fg3, fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                    }
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                isCustom = true
                                selectedEndpoint = null
                                providerName = "Custom"
                                baseUrl = ""
                                showProviderPicker = false
                                showAdvanced = true  // expand advanced for custom
                            }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Custom (any OpenAI-compatible API)",
                                color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showProviderPicker = false }) { Text("Cancel") } },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = custom.canvas,
        title = { Text("Add API Key", color = custom.fg2, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // Provider selector
                Text("Provider", fontSize = 11.sp, color = custom.fg3)
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { showProviderPicker = true }
                        .background(custom.ink2, RoundedCornerShape(8.dp))
                        .border(1.dp, custom.inkBorder, RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(providerName.ifEmpty { "Select provider" },
                        color = if (providerName.isNotEmpty()) MaterialTheme.colorScheme.onSurface else custom.fg3,
                        fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text("▼", color = custom.fg3, fontSize = 10.sp)
                }

                // v3.3.1: "Get API Key" button — opens the provider's official API key page in browser.
                if (selectedEndpoint != null) {
                    val apiKeyUrl = selectedEndpoint!!.apiKeyUrl
                    if (apiKeyUrl.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .background(custom.accent.copy(alpha = 0.06f), RoundedCornerShape(8.dp))
                                .border(1.dp, custom.accent.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                                .clickable {
                                    val intent = android.content.Intent(
                                        android.content.Intent.ACTION_VIEW,
                                        android.net.Uri.parse(apiKeyUrl)
                                    )
                                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    context.startActivity(intent)
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("📖", fontSize = 14.sp)
                            Spacer(Modifier.width(8.dp))
                            Text("Get API Key →",
                                color = custom.accent, fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Text("opens in browser", color = custom.fg3, fontSize = 9.sp)
                        }
                        // Also show the docs link as secondary.
                        TextButton(onClick = {
                            val intent = android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(selectedEndpoint!!.docsUrl)
                            )
                            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                        }) { Text("📚 Docs", fontSize = 10.sp, color = custom.fg3) }
                    }
                }

                // API Key
                Text("API Key", fontSize = 11.sp, color = custom.fg3)
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it; testResult = null },
                    placeholder = { Text("Enter your API key") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(onClick = { showKey = !showKey }) {
                            Text(if (showKey) "Hide" else "Show", fontSize = 10.sp)
                        }
                    },
                    textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
                )

                // Alias
                Text("Alias (optional)", fontSize = 11.sp, color = custom.fg3)
                OutlinedTextField(
                    value = alias,
                    onValueChange = { alias = it },
                    placeholder = { Text("e.g. Work Account") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
                )

                // v3.3.1: Endpoint URL — auto-filled from ProviderEndpoint.chatEndpoint
                // when the user picks a popular provider, but ALWAYS editable.
                // The user can change it if the URL is wrong, they're using a proxy,
                // a different region, or an enterprise gateway.
                Text("Endpoint URL${if (selectedEndpoint != null) " (auto-filled, editable)" else ""}",
                    fontSize = 11.sp, color = custom.fg3)
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it; testResult = null },
                    placeholder = { Text("https://api.example.com/v1/chat/completions") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
                )
                if (selectedEndpoint != null) {
                    Text("Default: ${selectedEndpoint!!.chatEndpoint} — edit if using a proxy/region override",
                        fontSize = 9.sp, color = custom.fg3)
                } else {
                    Text("Full URL including /v1/chat/completions path",
                        fontSize = 9.sp, color = custom.fg3)
                }

                // Advanced options (collapsible). Collapsed by default for popular providers,
                // expanded by default for custom (set when user picks Custom above).
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { showAdvanced = !showAdvanced }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Advanced Options", color = custom.fg2, fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text(if (showAdvanced) "▲" else "▼", color = custom.fg3, fontSize = 10.sp)
                }

                if (showAdvanced) {
                    // API Format
                    Text("API Format", fontSize = 11.sp, color = custom.fg3)
                    val formats = listOf("Chat Completions (/chat/completions)", "Anthropic Messages (/v1/messages)", "Responses (/responses)")
                    formats.forEach { fmt ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { apiFormat = fmt }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(if (apiFormat == fmt) "●" else "○",
                                color = if (apiFormat == fmt) custom.accent else custom.fg3, fontSize = 12.sp)
                            Spacer(Modifier.width(8.dp))
                            Text(fmt, color = MaterialTheme.colorScheme.onSurface, fontSize = 11.sp)
                        }
                    }

                    // Chat Endpoint (relative path)
                    Text("Chat Endpoint (relative path)", fontSize = 11.sp, color = custom.fg3)
                    OutlinedTextField(
                        value = chatEndpoint,
                        onValueChange = { chatEndpoint = it },
                        placeholder = { Text("/v1/chat/completions") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
                    )
                    Text("Default: /v1/chat/completions", fontSize = 9.sp, color = custom.fg3)

                    // Models Endpoint (relative path)
                    Text("Models Endpoint (relative path)", fontSize = 11.sp, color = custom.fg3)
                    OutlinedTextField(
                        value = modelsEndpoint,
                        onValueChange = { modelsEndpoint = it },
                        placeholder = { Text("/v1/models") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
                    )
                    Text("Default: /v1/models", fontSize = 9.sp, color = custom.fg3)
                }

                // Test result
                if (testResult != null) {
                    Text(testResult!!,
                        color = if (testResult!!.startsWith("✅")) custom.accent else custom.fg3,
                        fontSize = 11.sp, fontFamily = MonoFontFamily)
                }
                if (testing) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                }
            }
        },
        confirmButton = {
            Row {
                // Test button — uses the new authScheme-aware tester.
                TextButton(
                    onClick = {
                        testing = true
                        testResult = null
                        scope.launch {
                            // Build a temp ApiKey for testing — uses whatever's in baseUrl now.
                            val tempKey = ApiKeyStore.ApiKey(
                                id = "test",
                                providerId = selectedEndpoint?.providerId ?: "custom",
                                providerName = providerName.ifEmpty { "Test" },
                                apiKey = apiKey,
                                alias = alias,
                                baseUrl = baseUrl,
                            )
                            testResult = testApiKeyConnection(tempKey)
                            testing = false
                        }
                    },
                    enabled = apiKey.isNotEmpty() && baseUrl.isNotEmpty() && !testing,
                ) { Text("Test", fontSize = 11.sp) }

                // Save button
                TextButton(
                    onClick = {
                        if (apiKey.isNotEmpty() && providerName.isNotEmpty()) {
                            val providerId = selectedEndpoint?.providerId ?: "custom"
                            onSave(ApiKeyStore.ApiKey(
                                providerId = providerId,
                                providerName = providerName,
                                apiKey = apiKey,
                                alias = alias,
                                baseUrl = baseUrl,
                            ))
                        }
                    },
                    enabled = apiKey.isNotEmpty() && providerName.isNotEmpty(),
                ) { Text("Save", fontSize = 11.sp) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Test an API key by fetching models from the provider's /v1/models endpoint.
 */
private suspend fun testApiKey(baseUrl: String, apiKey: String, modelsEndpoint: String): String {
    return withContext(Dispatchers.IO) {
        try {
            val url = "${baseUrl.trimEnd('/')}$modelsEndpoint"
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            val request = okhttp3.Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $apiKey")
                .build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: ""
                val json = org.json.JSONObject(body)
                val modelsArr = json.optJSONArray("data") ?: org.json.JSONArray()
                "✅ Connected! ${modelsArr.length()} models available"
            } else {
                "❌ HTTP ${response.code}: ${response.message}"
            }
        } catch (e: Exception) {
            "❌ Error: ${e.message}"
        }
    }
}

/**
 * v3.2.7: Test a SAVED API key (from ApiKeyStore) — actually sends a real
 * chat completion request to the key's baseUrl + apiKey and reports
 * success/failure with the response. Works for the sandbox prefill key too.
 */
private suspend fun testApiKeyConnection(key: ApiKeyStore.ApiKey): String {
    return withContext(Dispatchers.IO) {
        try {
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            // v3.2.8: Sandbox proxy (/api/llm/proxy) is POST-only (chat completions).
            // v3.3.1: Look up the provider's authScheme via ProviderEndpoints.
            val isSandboxProxy = key.baseUrl.contains("/api/llm/proxy")
            val endpoint = ProviderEndpoints.byId(key.providerId)
            val authScheme = endpoint?.authScheme ?: ProviderEndpoints.AuthScheme.BEARER

            // Build the auth headers/query according to the provider's scheme.
            fun okhttp3.Request.Builder.applyAuth(): okhttp3.Request.Builder = this.apply {
                when (authScheme) {
                    ProviderEndpoints.AuthScheme.BEARER -> {
                        if (key.apiKey.isNotEmpty() && key.apiKey != "sandbox") {
                            header("Authorization", "Bearer ${key.apiKey}")
                        }
                    }
                    ProviderEndpoints.AuthScheme.X_API_KEY -> {
                        if (key.apiKey.isNotEmpty()) {
                            header("x-api-key", key.apiKey)
                            header("anthropic-version", "2023-06-01")
                        }
                    }
                    ProviderEndpoints.AuthScheme.QUERY_PARAM -> {
                        // Gemini uses ?key=<key>. If user's baseUrl doesn't have it, append it.
                        // (handled when constructing URL)
                    }
                    ProviderEndpoints.AuthScheme.NONE -> { /* no auth */ }
                }
            }

            val request = if (isSandboxProxy) {
                val body = org.json.JSONObject().apply {
                    put("model", "glm-4.6")
                    put("stream", false)
                    put("messages", org.json.JSONArray().apply {
                        put(org.json.JSONObject().apply {
                            put("role", "user")
                            put("content", "ping")
                        })
                    })
                }
                okhttp3.Request.Builder()
                    .url(key.baseUrl)
                    .header("Content-Type", "application/json")
                    .applyAuth()
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()
            } else {
                // v3.3.1: Use the registry's modelsEndpoint if available, else fall back.
                val url = endpoint?.modelsEndpoint ?: run {
                    var base = key.baseUrl.trimEnd('/')
                    base = base.replace(Regex("/chat/completions$"), "")
                    base = base.replace(Regex("/messages$"), "")
                    if (base.endsWith("/v1")) "${base}/models" else "${base}/v1/models"
                }
                // Append ?key=<key> for Gemini-style query-param auth.
                val finalUrl = if (authScheme == ProviderEndpoints.AuthScheme.QUERY_PARAM
                    && key.apiKey.isNotEmpty() && !url.contains("key=")) {
                    "$url${if (url.contains("?")) "&" else "?"}key=${key.apiKey}"
                } else url
                okhttp3.Request.Builder()
                    .url(finalUrl)
                    .applyAuth()
                    .build()
            }
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val respBody = response.body?.string() ?: ""
                if (isSandboxProxy) {
                    val reply = try {
                        org.json.JSONObject(respBody).optJSONArray("choices")
                            ?.optJSONObject(0)?.optJSONObject("message")
                            ?.optString("content", "") ?: ""
                    } catch (_: Exception) { "" }
                    val preview = if (reply.length > 40) reply.take(40) + "..." else reply
                    if (reply.isNotEmpty()) "✅ Connected! Reply: $preview"
                    else "✅ Connected (HTTP ${response.code})"
                } else {
                    val json = org.json.JSONObject(respBody)
                    val count = (json.optJSONArray("data") ?: json.optJSONArray("models"))?.length() ?: -1
                    if (count >= 0) "✅ Connected! $count models available"
                    else "✅ Connected (HTTP ${response.code})"
                }
            } else {
                "❌ HTTP ${response.code}: ${response.message}"
            }
        } catch (e: Exception) {
            "❌ ${e.javaClass.simpleName}: ${e.message ?: "unknown"}"
        }
    }
}

/**
 * v3.2.7: Test a SAVED model — resolves the endpoint + api_key using the same
 * resolution order as applyModelToProvider, then sends a tiny "ping" chat
 * completion. Reports success with the model's reply.
 */
private suspend fun testSavedModel(
    context: android.content.Context,
    model: SavedModelsStore.SavedModel,
): String {
    return withContext(Dispatchers.IO) {
        try {
            // Resolve the saved key the same way applyModelToProvider does.
            ApiKeyStore.load(context)
            val allKeys = ApiKeyStore.getAllKeys()
            val resolvedKey: ApiKeyStore.ApiKey? = when {
                !model.apiKeyId.isNullOrEmpty() ->
                    allKeys.find { it.id == model.apiKeyId }
                else -> allKeys.find { it.providerId == model.providerId }
            } ?: allKeys.find { it.providerId == "custom" }

            val endpoint = when {
                !resolvedKey?.baseUrl.isNullOrEmpty() -> resolvedKey!!.baseUrl
                model.apiUrl.isNotEmpty() -> model.apiUrl
                else -> AIProvider.PROVIDERS.find { it.id == model.providerId }?.defaultEndpoint ?: ""
            }
            val apiKey = resolvedKey?.apiKey ?: ""

            if (endpoint.isEmpty()) {
                return@withContext "❌ No endpoint resolved — add an API key first"
            }

            // Send a tiny chat completion request.
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            val body = org.json.JSONObject().apply {
                put("model", model.modelId)
                put("stream", false)
                put("messages", org.json.JSONArray().apply {
                    put(org.json.JSONObject().apply {
                        put("role", "user")
                        put("content", "ping")
                    })
                })
            }
            val request = okhttp3.Request.Builder()
                .url(endpoint)
                .header("Content-Type", "application/json")
                .apply {
                    if (apiKey.isNotEmpty() && apiKey != "sandbox") {
                        header("Authorization", "Bearer $apiKey")
                    }
                }
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val response = client.newCall(request).execute()
            val respBody = response.body?.string() ?: ""
            if (response.isSuccessful) {
                val json = org.json.JSONObject(respBody)
                val reply = json.optJSONArray("choices")?.optJSONObject(0)
                    ?.optJSONObject("message")?.optString("content", "") ?: ""
                val preview = if (reply.length > 40) reply.take(40) + "..." else reply
                "✅ ${model.modelName} replied: $preview"
            } else {
                "❌ HTTP ${response.code}: ${respBody.take(80)}"
            }
        } catch (e: Exception) {
            "❌ ${e.javaClass.simpleName}: ${e.message ?: "unknown"}"
        }
    }
}

/**
 * v3.2.7: EditApiKeyDialog — same fields as AddApiKeyDialog but pre-filled
 * with the existing key's values. Calls updateKey() on save.
 */
@Composable
private fun EditApiKeyDialog(
    original: ApiKeyStore.ApiKey,
    onDismiss: () -> Unit,
    onSave: (ApiKeyStore.ApiKey) -> Unit,
) {
    val context = LocalContext.current
    val custom = LocalCustomColors.current
    val scope = rememberCoroutineScope()

    var providerName by remember { mutableStateOf(original.providerName) }
    var apiKey by remember { mutableStateOf(original.apiKey) }
    var alias by remember { mutableStateOf(original.alias) }
    var baseUrl by remember { mutableStateOf(original.baseUrl) }
    var showKey by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }

    // v3.3.1: Look up the ProviderEndpoint for this key's providerId so we can
    // show the "Get API Key" + "Docs" buttons (only for popular providers).
    val endpoint = remember { ProviderEndpoints.byId(original.providerId) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = custom.canvas,
        title = { Text("Edit API Key", color = custom.fg2, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // v3.3.1: Show "Get API Key" + Docs buttons for popular providers.
                if (endpoint != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .background(custom.accent.copy(alpha = 0.06f), RoundedCornerShape(8.dp))
                            .border(1.dp, custom.accent.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                            .clickable {
                                val intent = android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse(endpoint.apiKeyUrl)
                                )
                                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(intent)
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("📖", fontSize = 14.sp)
                        Spacer(Modifier.width(8.dp))
                        Text("Get API Key →",
                            color = custom.accent, fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    }
                    TextButton(onClick = {
                        val intent = android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(endpoint.docsUrl)
                        )
                        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    }) { Text("📚 Docs", fontSize = 10.sp, color = custom.fg3) }
                }
                OutlinedTextField(
                    value = providerName, onValueChange = { providerName = it },
                    label = { Text("Provider Name", fontSize = 11.sp) },
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
                )
                OutlinedTextField(
                    value = alias, onValueChange = { alias = it },
                    label = { Text("Alias (optional)", fontSize = 11.sp) },
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it; testResult = null },
                    label = { Text("API Key", fontSize = 11.sp) },
                    singleLine = true,
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(onClick = { showKey = !showKey }) {
                            Text(if (showKey) "Hide" else "Show", fontSize = 10.sp)
                        }
                    },
                    textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
                )
                OutlinedTextField(
                    value = baseUrl, onValueChange = { baseUrl = it; testResult = null },
                    label = { Text("Endpoint URL", fontSize = 11.sp) },
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
                )
                if (endpoint != null) {
                    Text("Default for ${endpoint.displayName}: ${endpoint.chatEndpoint}",
                        fontSize = 9.sp, color = custom.fg3, fontFamily = MonoFontFamily)
                }
                if (testResult != null) {
                    Text(testResult!!,
                        color = if (testResult!!.startsWith("✅")) custom.accent else custom.fg3,
                        fontSize = 11.sp, fontFamily = MonoFontFamily)
                }
                if (testing) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(
                    onClick = {
                        testing = true
                        testResult = null
                        scope.launch {
                            val tempKey = original.copy(
                                apiKey = apiKey, baseUrl = baseUrl,
                                providerName = providerName, alias = alias,
                            )
                            testResult = testApiKeyConnection(tempKey)
                            testing = false
                        }
                    },
                    enabled = apiKey.isNotEmpty() && baseUrl.isNotEmpty() && !testing,
                ) { Text("Test", fontSize = 11.sp) }
                TextButton(
                    onClick = {
                        if (providerName.isNotEmpty()) {
                            onSave(original.copy(
                                providerName = providerName,
                                apiKey = apiKey,
                                alias = alias,
                                baseUrl = baseUrl,
                            ))
                        }
                    },
                    enabled = providerName.isNotEmpty(),
                ) { Text("Save", fontSize = 11.sp) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// === TAB 2: SAVED MODELS ===

@Composable
private fun SavedModelsTab(onToast: (String) -> Unit) {
    val context = LocalContext.current
    val custom = LocalCustomColors.current
    val scope = rememberCoroutineScope()
    var models by remember { mutableStateOf(SavedModelsStore.getAllModels()) }
    val defaultModel = remember { SavedModelsStore.getDefaultModel(context) }
    var testingId by remember { mutableStateOf<String?>(null) }
    var pickingKeyFor by remember { mutableStateOf<SavedModelsStore.SavedModel?>(null) }
    // Refresh keys once when the tab opens so the picker sees the latest list.
    LaunchedEffect(Unit) { ApiKeyStore.load(context) }
    val allKeys = remember { ApiKeyStore.getAllKeys() }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text("Saved Models", color = custom.fg2, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text("Configure model capability tags for different scenarios", color = custom.fg3, fontSize = 10.sp)
        }
        if (models.isEmpty()) {
            item { Text("No saved models. Browse the Model Catalog to add models.", color = custom.fg3, fontSize = 12.sp) }
        } else {
            items(models, key = { it.id }) { model ->
                SavedModelCard(
                    model = model,
                    isDefault = model.id == defaultModel?.id,
                    isTesting = testingId == model.id,
                    boundKey = allKeys.find { it.id == model.apiKeyId },
                    allKeys = allKeys,
                    onSetDefault = {
                        SavedModelsStore.setDefaultModel(context, model.id)
                        models = SavedModelsStore.getAllModels()
                        onToast("Default model: ${model.modelName}")
                    },
                    onDelete = {
                        SavedModelsStore.removeModel(context, model.id)
                        models = SavedModelsStore.getAllModels()
                        onToast("Removed: ${model.modelName}")
                    },
                    onTest = {
                        testingId = model.id
                        scope.launch {
                            val result = testSavedModel(context, model)
                            onToast(result)
                            testingId = null
                        }
                    },
                    onPickKey = { pickingKeyFor = model },
                )
            }
        }
    }

    // v3.2.11: Key picker dialog — opens when user taps "Key:" on a model card.
    pickingKeyFor?.let { model ->
        KeyPickerDialog(
            model = model,
            allKeys = allKeys,
            currentKeyId = model.apiKeyId,
            onDismiss = { pickingKeyFor = null },
            onPick = { keyId ->
                SavedModelsStore.updateModelApiKey(context, model.id, keyId)
                models = SavedModelsStore.getAllModels()
                pickingKeyFor = null
                val key = allKeys.find { it.id == keyId }
                onToast(if (key != null) "Bound to: ${key.alias.ifEmpty { key.providerName }}"
                        else "Key binding cleared — will auto-resolve")
            },
        )
    }
}

@Composable
private fun SavedModelCard(
    model: SavedModelsStore.SavedModel,
    isDefault: Boolean,
    isTesting: Boolean = false,
    boundKey: ApiKeyStore.ApiKey? = null,
    allKeys: List<ApiKeyStore.ApiKey> = emptyList(),
    onSetDefault: () -> Unit,
    onDelete: () -> Unit,
    onTest: () -> Unit,
    onPickKey: () -> Unit = {},
) {
    val custom = LocalCustomColors.current
    Column(
        modifier = Modifier.fillMaxWidth()
            .background(custom.ink2, RoundedCornerShape(8.dp))
            .border(1.dp, if (isDefault) custom.accent.copy(alpha = 0.5f) else custom.inkBorder, RoundedCornerShape(8.dp))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.SmartToy, contentDescription = null, tint = if (isDefault) custom.accent else custom.fg3, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(model.modelName, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text("${model.providerName} · ${model.modelId}", color = custom.fg3, fontFamily = MonoFontFamily, fontSize = 10.sp)
            }
            if (isDefault) {
                Text("●", color = custom.accent, fontSize = 10.sp)
            }
        }
        Spacer(Modifier.height(4.dp))
        // Capability tags
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (cap in model.capabilities) {
                Text(
                    text = cap.name,
                    color = custom.accent,
                    fontSize = 9.sp,
                    modifier = Modifier.background(custom.accent.copy(alpha = 0.1f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text("${model.contextStr} · ${model.priceStr}", color = custom.fg3, fontFamily = MonoFontFamily, fontSize = 9.sp)

        // v3.2.11: Key binding row — shows which API key this model is bound to.
        // Tap to open the KeyPickerDialog. If multiple keys exist for the same
        // provider, this is how the user picks the right one per model.
        Row(
            modifier = Modifier.fillMaxWidth()
                .padding(top = 4.dp)
                .clickable { onPickKey() }
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Key, contentDescription = null, tint = custom.fg3, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(6.dp))
            Text("Key:", color = custom.fg3, fontSize = 10.sp)
            Spacer(Modifier.width(6.dp))
            Column(modifier = Modifier.weight(1f)) {
                if (boundKey != null) {
                    Text(boundKey.alias.ifEmpty { boundKey.providerName },
                        color = MaterialTheme.colorScheme.onSurface, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Text("${boundKey.providerName} · ${boundKey.apiKey.take(8)}...",
                        color = custom.fg3, fontFamily = MonoFontFamily, fontSize = 9.sp, maxLines = 1)
                } else {
                    // No explicit binding — show what will resolve at chat time.
                    val fallback = allKeys.find { it.providerId == model.providerId }
                        ?: allKeys.find { it.providerId == "custom" }
                    Text("Auto-resolve",
                        color = custom.fg3, fontSize = 11.sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                    if (fallback != null) {
                        Text("Will use: ${fallback.alias.ifEmpty { fallback.providerName }}",
                            color = custom.fg3, fontFamily = MonoFontFamily, fontSize = 9.sp, maxLines = 1)
                    } else {
                        Text("No matching key — add one in API Keys",
                            color = custom.fg3, fontFamily = MonoFontFamily, fontSize = 9.sp)
                    }
                }
            }
            Text("Change ›", color = custom.accent, fontSize = 10.sp)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onSetDefault) { Text(if (isDefault) "Default ✓" else "Set Default", fontSize = 11.sp) }
            TextButton(onClick = onTest, enabled = !isTesting) {
                Text(if (isTesting) "Testing..." else "Test", fontSize = 11.sp)
            }
            TextButton(onClick = onDelete) { Text("Remove", fontSize = 11.sp, color = custom.fg3) }
        }
    }
}

/**
 * v3.2.11: KeyPickerDialog — lets the user bind a saved model to a specific
 * API key from ApiKeyStore. Includes an "Auto-resolve" option to clear the
 * binding (so applyModelToProvider's fallback chain runs at chat time).
 */
@Composable
private fun KeyPickerDialog(
    model: SavedModelsStore.SavedModel,
    allKeys: List<ApiKeyStore.ApiKey>,
    currentKeyId: String?,
    onDismiss: () -> Unit,
    onPick: (String?) -> Unit,
) {
    val custom = LocalCustomColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = custom.canvas,
        title = {
            Column {
                Text("Bind API Key", color = custom.fg2, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text("Which key should \"${model.modelName}\" use?",
                    color = custom.fg3, fontSize = 10.sp)
            }
        },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // Auto-resolve option (clears the binding).
                item {
                    KeyPickerRow(
                        label = "Auto-resolve",
                        subtitle = "Use the first key matching this model's provider, or the first custom key",
                        isSelected = currentKeyId == null,
                        onClick = { onPick(null) },
                    )
                }
                // All saved keys — matching provider first, then others.
                val matching = allKeys.filter { it.providerId == model.providerId || it.providerId == "custom" }
                val others = allKeys.filter { it.providerId != model.providerId && it.providerId != "custom" }
                if (matching.isNotEmpty()) {
                    item {
                        Text("Recommended (${matching.size})",
                            color = custom.fg3, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 4.dp, bottom = 2.dp))
                    }
                }
                items(matching, key = { it.id }) { key ->
                    KeyPickerRow(
                        label = key.alias.ifEmpty { key.providerName },
                        subtitle = "${key.providerName} · ${key.apiKey.take(8)}... · ${key.baseUrl.take(40)}",
                        isSelected = key.id == currentKeyId,
                        onClick = { onPick(key.id) },
                    )
                }
                if (others.isNotEmpty()) {
                    item {
                        Text("Other (${others.size})",
                            color = custom.fg3, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 6.dp, bottom = 2.dp))
                    }
                }
                items(others, key = { it.id }) { key ->
                    KeyPickerRow(
                        label = key.alias.ifEmpty { key.providerName },
                        subtitle = "${key.providerName} · ${key.apiKey.take(8)}... · ${key.baseUrl.take(40)}",
                        isSelected = key.id == currentKeyId,
                        onClick = { onPick(key.id) },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun KeyPickerRow(
    label: String,
    subtitle: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val custom = LocalCustomColors.current
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(if (isSelected) custom.accent.copy(alpha = 0.08f) else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(6.dp))
            .border(1.dp, if (isSelected) custom.accent.copy(alpha = 0.4f) else custom.inkBorder, RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (isSelected) "●" else "○",
            color = if (isSelected) custom.accent else custom.fg3, fontSize = 11.sp)
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = custom.fg3, fontFamily = MonoFontFamily, fontSize = 8.sp, maxLines = 1)
        }
    }
}

// === TAB 3: MODEL CATALOG ===

@Composable
private fun ModelCatalogTab(scope: kotlinx.coroutines.CoroutineScope, onToast: (String) -> Unit) {
    val context = LocalContext.current
    val custom = LocalCustomColors.current
    var searchQuery by remember { mutableStateOf("") }
    var selectedProvider by remember { mutableStateOf<String?>(null) }
    var selectedCapability by remember { mutableStateOf<ModelCapability?>(null) }
    var isLoaded by remember { mutableStateOf(ModelsDevRepository.isLoaded()) }
    var models by remember { mutableStateOf(ModelsDevRepository.allModels()) }
    // v3.3.0: "My Available Models" filter — only show models the user's saved
    // API keys can actually access (based on ProviderModelsStore cache).
    var onlyAvailable by remember { mutableStateOf(false) }
    // Bump to trigger recomposition after a fetch.
    var availableCacheKey by remember { mutableStateOf(0) }

    // Refresh models when loaded
    LaunchedEffect(isLoaded) {
        if (!isLoaded) {
            scope.launch {
                val success = ModelsDevRepository.load(context, forceRefresh = false)
                isLoaded = success
                models = ModelsDevRepository.allModels()
            }
        }
    }

    // v3.3.0: Initial fetch of per-key available models when the catalog opens.
    LaunchedEffect(Unit) {
        if (ApiKeyStore.getAllKeys().isNotEmpty()) {
            scope.launch {
                ProviderModelsStore.refreshAll(context)
                availableCacheKey++  // trigger recomposition
            }
        }
    }

    val providers = remember { ModelsDevRepository.allProviders() }
    val capabilities = listOf(ModelCapability.VISION, ModelCapability.TOOLS, ModelCapability.REASONING, ModelCapability.FREE, ModelCapability.IMAGE_GEN)

    // v3.3.0: Compute available-keys-per-model on demand (cheap — cached).
    // Reading ProviderModelsStore inside the filter block ensures the
    // availableCacheKey recomposition actually re-evaluates.
    val availableIds = remember(availableCacheKey, onlyAvailable) {
        if (onlyAvailable) ProviderModelsStore.getAllAvailableModelIds(context) else emptySet()
    }

    // Filter models
    val filteredModels = remember(searchQuery, selectedProvider, selectedCapability, models, availableCacheKey, onlyAvailable) {
        var result = if (searchQuery.isNotEmpty()) ModelsDevRepository.searchModels(searchQuery) else models
        if (selectedProvider != null) result = result.filter { it.providerId == selectedProvider }
        if (selectedCapability != null) result = result.filter { selectedCapability in it.capabilities }
        if (onlyAvailable) {
            result = result.filter { m ->
                // Loose match: catalog model ID matches any cached ID.
                availableIds.any { avail ->
                    val a = avail.lowercase(); val b = m.id.lowercase()
                    a == b || a.contains(b) || b.contains(a)
                }
            }
        }
        result.take(100)  // limit to 100 for performance
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Search bar
        item {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search models...") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, tint = custom.fg3) },
                trailingIcon = {
                    if (!isLoaded) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text("${filteredModels.size}/${ModelsDevRepository.allModels().size}", color = custom.fg3, fontSize = 10.sp)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
            )
        }

        // Provider filter chips
        item {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FilterChip(
                    selected = selectedProvider == null,
                    onClick = { selectedProvider = null },
                    label = { Text("All", fontSize = 10.sp) },
                )
                for (p in providers.take(20)) {
                    FilterChip(
                        selected = selectedProvider == p.id,
                        onClick = { selectedProvider = if (selectedProvider == p.id) null else p.id },
                        label = { Text(p.name, fontSize = 10.sp) },
                    )
                }
            }
        }

        // Capability filter chips + v3.3.0 "My Available Models" toggle + Refresh button.
        item {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = selectedCapability == null,
                    onClick = { selectedCapability = null },
                    label = { Text("All", fontSize = 10.sp) },
                )
                for (cap in capabilities) {
                    FilterChip(
                        selected = selectedCapability == cap,
                        onClick = { selectedCapability = if (selectedCapability == cap) null else cap },
                        label = { Text(cap.name, fontSize = 10.sp) },
                    )
                }
                Spacer(Modifier.width(8.dp))
                // v3.3.0: Available filter chip.
                FilterChip(
                    selected = onlyAvailable,
                    onClick = { onlyAvailable = !onlyAvailable },
                    label = { Text(if (onlyAvailable) "✓ Available" else "Available", fontSize = 10.sp) },
                )
                // v3.3.0: Manual refresh button.
                IconButton(onClick = {
                    scope.launch {
                        onToast("Refreshing available models for all keys...")
                        ProviderModelsStore.invalidateAll(context)
                        ProviderModelsStore.refreshAll(context)
                        availableCacheKey++
                        onToast("✅ Refreshed")
                    }
                }) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "Refresh", tint = custom.fg3, modifier = Modifier.size(16.dp))
                }
            }
        }

        // Model cards
        items(filteredModels, key = { "${it.providerId}_${it.id}" }) { model ->
            // v3.3.0: Compute which of the user's saved keys can access this model.
            val keysWithModel = remember(availableCacheKey) { ProviderModelsStore.keysWithModel(context, model.id) }
            CatalogModelCard(
                model = model,
                isSaved = SavedModelsStore.isAlreadySaved(model.id, model.providerId),
                availableKeys = keysWithModel,
                onAdd = {
                    // v3.3.0: prefer the first available key (if any) when saving —
                    // this auto-binds the model to a key that actually has access.
                    val apiKeyId = keysWithModel.firstOrNull()?.id
                        ?: ApiKeyStore.getKey(model.providerId)?.id
                    SavedModelsStore.addModel(context, SavedModelsStore.SavedModel(
                        modelId = model.id,
                        modelName = model.name,
                        providerId = model.providerId,
                        providerName = model.providerName,
                        apiKeyId = apiKeyId,
                        capabilities = model.capabilities,
                        contextLength = model.contextLength,
                        inputPrice = model.inputPrice,
                        outputPrice = model.outputPrice,
                        apiUrl = model.apiUrl,
                    ))
                    onToast("Saved: ${model.name}")
                },
            )
        }
    }
}

@Composable
private fun CatalogModelCard(
    model: ModelsDevRepository.CatalogModel,
    isSaved: Boolean,
    availableKeys: List<ApiKeyStore.ApiKey> = emptyList(),
    onAdd: () -> Unit,
) {
    val custom = LocalCustomColors.current
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(custom.ink2, RoundedCornerShape(8.dp))
            .border(1.dp, custom.inkBorder, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(model.name, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Text(model.providerName, color = custom.fg3, fontFamily = MonoFontFamily, fontSize = 10.sp)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (cap in model.capabilities) {
                    Text(
                        text = cap.name,
                        color = custom.accent,
                        fontSize = 8.sp,
                        modifier = Modifier.background(custom.accent.copy(alpha = 0.1f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text("${model.contextStr} · ${model.priceStr}", color = custom.fg3, fontFamily = MonoFontFamily, fontSize = 9.sp)
            // v3.3.0: "Available with" badge — shows which of the user's saved keys
            // can access this model. Skipped if no keys exist or none match.
            if (availableKeys.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("✅", fontSize = 9.sp)
                    Text(
                        availableKeys.joinToString(", ") { it.alias.ifEmpty { it.providerName } },
                        color = custom.accent,
                        fontSize = 9.sp,
                        fontFamily = MonoFontFamily,
                        modifier = Modifier.background(custom.accent.copy(alpha = 0.08f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
            }
        }
        if (isSaved) {
            Icon(Icons.Outlined.Check, contentDescription = "Saved", tint = custom.accent, modifier = Modifier.size(16.dp))
        } else {
            IconButton(onClick = onAdd) {
                Icon(Icons.Outlined.Add, contentDescription = "Add", tint = custom.accent, modifier = Modifier.size(16.dp))
            }
        }
    }
}

// ============================================================================
// v3.2.1: CONFIGURATION SECTIONS — show ONLY models matching capability tags
// ============================================================================

@Composable
private fun ConfigSectionHeader(title: String, subtitle: String) {
    val custom = LocalCustomColors.current
    Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp)) {
        Text(title, color = custom.fg2, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(subtitle, color = custom.fg3, fontSize = 10.sp)
        Spacer(Modifier.height(4.dp))
        Divider(color = custom.inkBorder, thickness = 1.dp)
    }
}

@Composable
private fun ModelSelectorRow(
    label: String,
    models: List<SavedModelsStore.SavedModel>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onTest: ((SavedModelsStore.SavedModel) -> Unit)? = null,
    testingId: String? = null,
) {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        if (models.isEmpty()) {
            Text("No models available. Add models with this capability from the catalog.",
                color = custom.fg3, fontSize = 11.sp, modifier = Modifier.padding(8.dp))
        } else {
            models.forEach { model ->
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .background(if (model.id == selectedId) custom.accent.copy(alpha = 0.08f) else custom.ink2, RoundedCornerShape(8.dp))
                        .border(1.dp, if (model.id == selectedId) custom.accent.copy(alpha = 0.4f) else custom.inkBorder, RoundedCornerShape(8.dp))
                        .clickable { onSelect(model.id) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (model.id == selectedId) "●" else "○",
                        color = if (model.id == selectedId) custom.accent else custom.fg3,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(model.modelName, color = m3.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                        Text("${model.providerName} · ${model.tagStr}", color = custom.fg3, fontFamily = MonoFontFamily, fontSize = 9.sp)
                    }
                    if (onTest != null) {
                        TextButton(
                            onClick = { onTest(model) },
                            enabled = testingId != model.id,
                        ) {
                            Text(if (testingId == model.id) "..." else "Test", fontSize = 10.sp)
                        }
                    }
                    Text("${model.contextStr} · ${model.priceStr}", color = custom.fg3, fontFamily = MonoFontFamily, fontSize = 8.sp)
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, desc: String, checked: Boolean, onCheck: (Boolean) -> Unit) {
    val custom = LocalCustomColors.current
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(custom.ink2, RoundedCornerShape(8.dp))
            .border(1.dp, custom.inkBorder, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            Text(desc, color = custom.fg3, fontSize = 10.sp)
        }
        Switch(checked = checked, onCheckedChange = onCheck,
            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = custom.accent))
    }
}

// === SECTION 2: LLM CONFIGURATION ===
@Composable
private fun LlmConfigPage() {
    val context = LocalContext.current
    val custom = LocalCustomColors.current
    val scope = rememberCoroutineScope()
    val llmModels = SavedModelsStore.getModelsByCapabilities(ModelCapability.LLM_TAGS)
    val defaultModel = SavedModelsStore.getDefaultModel(context)
    var promptMode by remember { mutableStateOf(AIProvider.getPromptMode(context)) }
    var contextualPrompts by remember { mutableStateOf(AIProvider.isContextualPromptsEnabled(context)) }
    // v3.2.6: read once at composition so the user can see whether their pick
    // has actually propagated to the active AIProvider config LLMClient reads.
    var activeProvider by remember { mutableStateOf(AIProvider.getProviderId(context)) }
    var activeModel by remember { mutableStateOf(AIProvider.getModel(context)) }
    var isConfigured by remember { mutableStateOf(AIProvider.isConfigured(context)) }
    var testingId by remember { mutableStateOf<String?>(null) }
    var lastTestResult by remember { mutableStateOf<String?>(null) }

    // v3.2.10: Capability check — does the selected default model support TOOLS?
    // If not, the agent loop won't be able to execute phone actions reliably.
    val supportsTools = defaultModel?.capabilities?.contains(ModelCapability.TOOLS) ?: true
    // Also check if it's at least tagged TEXT (the bare minimum for chat).
    val supportsText = defaultModel?.capabilities?.contains(ModelCapability.TEXT) ?: true

    ConfigSectionHeader("LLM Configuration", "Default agent model + prompt settings")

    // Status banner — tells the user whether the chat will work right now.
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(if (isConfigured) custom.accent.copy(alpha = 0.06f) else custom.ink2, RoundedCornerShape(8.dp))
            .border(1.dp, if (isConfigured) custom.accent.copy(alpha = 0.3f) else custom.inkBorder, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (isConfigured) "●" else "○",
            color = if (isConfigured) custom.accent else custom.fg3, fontSize = 12.sp)
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                if (isConfigured) "Ready to chat" else "Not configured",
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
            )
            Text(
                if (isConfigured) "Active: $activeProvider · $activeModel"
                else "Add an API key + select a model below to start chatting.",
                color = custom.fg3, fontFamily = MonoFontFamily, fontSize = 9.sp,
            )
        }
    }

    // v3.2.10: Capability warning — shown when the selected model lacks TOOLS or TEXT.
    if (defaultModel != null) {
        if (!supportsText) {
            // Critical: model can't even do basic chat in this agent format.
            Row(
                modifier = Modifier.fillMaxWidth()
                    .background(custom.ink2, RoundedCornerShape(8.dp))
                    .border(1.dp, custom.fg3, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("⚠️", fontSize = 14.sp)
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Model may not work for chat",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                    Text("'${defaultModel.modelName}' isn't tagged as a text/chat model. " +
                            "It may produce unexpected output or refuse to follow the agent format. " +
                            "Consider picking a different model.",
                        color = custom.fg3, fontSize = 9.sp)
                }
            }
            Spacer(Modifier.height(4.dp))
        } else if (!supportsTools) {
            // Warning: model can chat but can't reliably do tool calling.
            Row(
                modifier = Modifier.fillMaxWidth()
                    .background(custom.ink2, RoundedCornerShape(8.dp))
                    .border(1.dp, custom.fg3, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("ℹ️", fontSize = 14.sp)
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("No native tool-calling support",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                    Text("'${defaultModel.modelName}' isn't tagged as supporting function calling. " +
                            "It can still chat, but phone-control tasks (tap, launchApp, webSearch...) " +
                            "may not execute reliably. For full agent features, pick a model with the TOOLS tag.",
                        color = custom.fg3, fontSize = 9.sp)
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }

    // Last test result (if any).
    if (lastTestResult != null) {
        Text(lastTestResult!!,
            color = if (lastTestResult!!.startsWith("✅")) custom.accent else custom.fg3,
            fontSize = 10.sp, fontFamily = MonoFontFamily,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
    }
    Spacer(Modifier.height(6.dp))

    ModelSelectorRow(
        label = "Default Agent Model",
        models = llmModels,
        selectedId = defaultModel?.id,
        onSelect = { id ->
            SavedModelsStore.setDefaultModel(context, id)
            // Refresh the status banner — applyModelToProvider just ran.
            activeProvider = AIProvider.getProviderId(context)
            activeModel = AIProvider.getModel(context)
            isConfigured = AIProvider.isConfigured(context)
        },
        onTest = { model ->
            testingId = model.id
            lastTestResult = null
            scope.launch {
                val result = testSavedModel(context, model)
                lastTestResult = result
                testingId = null
            }
        },
        testingId = testingId,
    )
    Spacer(Modifier.height(4.dp))
    ToggleRow(
        label = "Contextual Prompts",
        desc = "Select relevant tool groups per task (85% token savings)",
        checked = contextualPrompts,
        onCheck = {
            contextualPrompts = it
            AIProvider.setContextualPromptsEnabled(context, it)
        },
    )
}

// === SECTION 3: VISION CONFIGURATION ===
@Composable
private fun VisionConfigPage() {
    val context = LocalContext.current
    val visionModels = SavedModelsStore.getModelsByCapability(ModelCapability.VISION)
    val visionModel = SavedModelsStore.getVisionModel(context)
    var visionMode by remember { mutableStateOf(AIProvider.getVisionMode(context)) }

    ConfigSectionHeader("Vision Configuration", "VLM model for screen reading fallback")
    ModelSelectorRow(
        label = "Vision Model",
        models = visionModels,
        selectedId = visionModel?.id,
        onSelect = { id ->
            SavedModelsStore.setVisionModel(context, id)
        },
    )
    Spacer(Modifier.height(4.dp))
    // Vision mode selector
    val modes = listOf("auto" to "Auto (a11y→ML Kit→VLM)", "mlkit" to "ML Kit OCR only", "vlm" to "VLM only")
    modes.forEach { (mode, desc) ->
        Row(
            modifier = Modifier.fillMaxWidth()
                .background(if (visionMode == mode) LocalCustomColors.current.accent.copy(alpha = 0.08f) else LocalCustomColors.current.ink2, RoundedCornerShape(8.dp))
                .border(1.dp, if (visionMode == mode) LocalCustomColors.current.accent.copy(alpha = 0.4f) else LocalCustomColors.current.inkBorder, RoundedCornerShape(8.dp))
                .clickable { visionMode = mode; AIProvider.setVisionMode(context, mode) }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (visionMode == mode) "●" else "○", color = if (visionMode == mode) LocalCustomColors.current.accent else LocalCustomColors.current.fg3, fontSize = 12.sp)
            Spacer(Modifier.width(8.dp))
            Text(desc, color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp)
        }
    }
}

// === SECTION 4: AUDIO CONFIGURATION ===
@Composable
private fun AudioConfigPage() {
    val context = LocalContext.current
    val audioModels = SavedModelsStore.getModelsByCapabilities(ModelCapability.AUDIO_TAGS)
    val audioModel = remember { SavedModelsStore.getAllModels().find { it.id == context.getSharedPreferences("ai_agent_saved_models", android.content.Context.MODE_PRIVATE).getString("audio_model_id", "") } }
    var ttsEnabled by remember { mutableStateOf(context.getSharedPreferences("ai_agent_prefs", android.content.Context.MODE_PRIVATE).getBoolean("tts_enabled", false)) }

    ConfigSectionHeader("Audio Configuration", "TTS + voice input settings")
    ModelSelectorRow(
        label = "Audio Model",
        models = audioModels,
        selectedId = audioModel?.id,
        onSelect = { id ->
            SavedModelsStore.setAudioModel(context, id)
        },
    )
    Spacer(Modifier.height(4.dp))
    ToggleRow(
        label = "Text-to-Speech (TTS)",
        desc = "AI speaks responses aloud",
        checked = ttsEnabled,
        onCheck = {
            ttsEnabled = it
            context.getSharedPreferences("ai_agent_prefs", android.content.Context.MODE_PRIVATE)
                .edit().putBoolean("tts_enabled", it).apply()
        },
    )
}

// === SECTION 5-7: FUTURE SECTIONS (Image/Video/Coding) ===
@Composable
private fun FutureSectionsPage() {
    val custom = LocalCustomColors.current
    ConfigSectionHeader("Coming Soon", "Future AI capabilities")
    
    val futures = listOf(
        "🖼️ Image Generation" to "Generate images from text descriptions",
        "🎬 Video Generation" to "Create video content from prompts",
        "💻 Coding Assistant" to "Write, review, and debug code",
    )
    futures.forEach { (title, desc) ->
        Row(
            modifier = Modifier.fillMaxWidth()
                .background(custom.ink2, RoundedCornerShape(8.dp))
                .border(1.dp, custom.inkBorder, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("Soon", color = custom.fg3, fontFamily = MonoFontFamily, fontSize = 9.sp)
        }
        Spacer(Modifier.height(4.dp))
    }
}

// === SECTION 8: ADVANCED ===
@Composable
private fun AdvancedConfigPage() {
    val context = LocalContext.current
    val custom = LocalCustomColors.current
    val provider = AIProvider.getCurrentProvider(context)
    val model = AIProvider.getModel(context)
    val endpoint = AIProvider.getEndpoint(context)

    ConfigSectionHeader("Advanced", "Endpoint + provider configuration")
    
    // Current provider info
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(custom.ink2, RoundedCornerShape(8.dp))
            .border(1.dp, custom.inkBorder, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Active Provider: ${provider.name}", color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text("Model: $model", color = custom.fg3, fontFamily = MonoFontFamily, fontSize = 10.sp)
            Text("Endpoint: ${endpoint.take(50)}...", color = custom.fg3, fontFamily = MonoFontFamily, fontSize = 9.sp, maxLines = 1)
        }
    }
    Spacer(Modifier.height(4.dp))
    
    // Legacy prompt mode selector
    Text("Prompt Mode (when Contextual is OFF)", color = custom.fg3, fontSize = 10.sp)
    val modes = listOf("smart" to "Smart (full, 11K)", "balanced" to "Balanced (1.5K)", "fast" to "Fast (750B)", "custom" to "Custom")
    var currentMode by remember { mutableStateOf(AIProvider.getPromptMode(context)) }
    modes.forEach { (mode, desc) ->
        Row(
            modifier = Modifier.fillMaxWidth()
                .background(if (currentMode == mode) custom.accent.copy(alpha = 0.08f) else custom.ink2, RoundedCornerShape(8.dp))
                .border(1.dp, if (currentMode == mode) custom.accent.copy(alpha = 0.4f) else custom.inkBorder, RoundedCornerShape(8.dp))
                .clickable { currentMode = mode; AIProvider.setPromptMode(context, mode) }
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (currentMode == mode) "●" else "○", color = if (currentMode == mode) custom.accent else custom.fg3, fontSize = 10.sp)
            Spacer(Modifier.width(8.dp))
            Text(desc, color = MaterialTheme.colorScheme.onSurface, fontSize = 11.sp)
        }
    }
    Spacer(Modifier.height(16.dp))
}
