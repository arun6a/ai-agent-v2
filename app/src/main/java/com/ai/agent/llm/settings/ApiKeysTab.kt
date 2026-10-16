package com.ai.agent.llm.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.agent.llm.ApiKeyStore
import com.ai.agent.llm.ProviderEndpoints
import com.ai.agent.llm.ProviderModelsStore
import com.ai.agent.ui.components.LocalCustomColors
import com.ai.agent.ui.theme.MonoFontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

// === TAB 1: API KEYS ===

@Composable
internal fun ApiKeysTab(scope: kotlinx.coroutines.CoroutineScope, onToast: (String) -> Unit) {
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
                                    val intent = Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse(apiKeyUrl)
                                    )
                                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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
                            val intent = Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse(selectedEndpoint!!.docsUrl)
                            )
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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
                                val intent = Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse(endpoint.apiKeyUrl)
                                )
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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
                        val intent = Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse(endpoint.docsUrl)
                        )
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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
