package com.ai.agent.llm.settings

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.agent.llm.ApiKeyStore
import com.ai.agent.llm.BindingHealthStore
import com.ai.agent.llm.ProviderEndpoints
import com.ai.agent.llm.SavedModelsStore
import com.ai.agent.ui.components.LocalCustomColors
import com.ai.agent.ui.theme.MonoFontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

// === TAB 2: SAVED MODELS ===

@Composable
internal fun SavedModelsTab(onToast: (String) -> Unit) {
    val context = LocalContext.current
    val custom = LocalCustomColors.current
    val scope = rememberCoroutineScope()
    var models by remember { mutableStateOf(SavedModelsStore.getAllModels()) }
    val defaultModel = remember { SavedModelsStore.getDefaultModel(context) }
    var testingId by remember { mutableStateOf<String?>(null) }
    var pickingKeyFor by remember { mutableStateOf<SavedModelsStore.SavedModel?>(null) }
    // v3.3.2: bindings dialog state.
    var editingBindingsFor by remember { mutableStateOf<SavedModelsStore.SavedModel?>(null) }
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
                    onEditBindings = { editingBindingsFor = model },
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

    // v3.3.2: Bindings dialog — opens when user taps "Providers" on a model card.
    // v3.3.4: re-fetch the model from the models list on every recomposition
    // so the dialog reflects binding changes (toggle/remove/add) immediately.
    // Previously `editingBindingsFor` was a stale snapshot → changes didn't show.
    editingBindingsFor?.let { snapshot ->
        val freshModel = models.find { it.id == snapshot.id } ?: snapshot
        BindingsDialog(
            model = freshModel,
            allKeys = allKeys,
            onDismiss = { editingBindingsFor = null },
            onAddBinding = { apiKeyId ->
                // Add a new binding using an existing API key.
                val key = allKeys.find { it.id == apiKeyId } ?: return@BindingsDialog
                val binding = SavedModelsStore.ModelProviderBinding(
                    providerId = key.providerId,
                    providerName = key.providerName,
                    apiKeyId = key.id,
                )
                SavedModelsStore.addBinding(context, freshModel.id, binding)
                models = SavedModelsStore.getAllModels()
                onToast("Added binding: ${key.providerName}")
            },
            onRemoveBinding = { bindingId ->
                SavedModelsStore.removeBinding(context, freshModel.id, bindingId)
                models = SavedModelsStore.getAllModels()
                onToast("Removed binding")
            },
            onToggleBinding = { bindingId, enabled ->
                SavedModelsStore.updateBinding(context, freshModel.id, bindingId, enabled = enabled)
                models = SavedModelsStore.getAllModels()
            },
            onReorderBinding = { bindingId, direction ->
                SavedModelsStore.reorderBinding(context, freshModel.id, bindingId, direction)
                models = SavedModelsStore.getAllModels()
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
    onEditBindings: () -> Unit = {},
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
                        color = custom.fg3, fontSize = 11.sp, fontStyle = FontStyle.Italic)
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

        // v3.3.2: Providers (multi-binding) row — shows count of bound providers.
        // Tap to open the BindingsDialog where the user can add/remove/reorder/
        // toggle multiple provider bindings for the same model.
        Row(
            modifier = Modifier.fillMaxWidth()
                .padding(top = 2.dp)
                .clickable { onEditBindings() }
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("🔗", fontSize = 11.sp)
            Spacer(Modifier.width(6.dp))
            Text("Providers:", color = custom.fg3, fontSize = 10.sp)
            Spacer(Modifier.width(6.dp))
            Column(modifier = Modifier.weight(1f)) {
                val activeBindings = model.activeBindings()
                if (activeBindings.isNotEmpty()) {
                    Text("${activeBindings.size} bound",
                        color = MaterialTheme.colorScheme.onSurface, fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold)
                    Text(activeBindings.joinToString(", ") { b ->
                        val k = allKeys.find { it.id == b.apiKeyId }
                        val alias = k?.alias?.ifEmpty { null } ?: b.providerName
                        val health = if (!b.enabled) "✗" else if (BindingHealthStore.isHealthy(b.id)) "✓" else "⚠"
                        "$health $alias"
                    }, color = custom.fg3, fontFamily = MonoFontFamily, fontSize = 9.sp, maxLines = 2)
                } else {
                    Text("No bindings — add one",
                        color = custom.fg3, fontSize = 11.sp,
                        fontStyle = FontStyle.Italic)
                }
            }
            Text("Manage ›", color = custom.accent, fontSize = 10.sp)
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
            .background(if (isSelected) custom.accent.copy(alpha = 0.08f) else Color.Transparent, RoundedCornerShape(6.dp))
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

/**
 * v3.3.2: BindingsDialog — manage multiple provider bindings for a saved model.
 *
 * Shows the list of bindings (priority order) with health indicators, plus an
 * "Add Binding" button that opens a key picker. Each binding row has up/down
 * arrows for reordering, a toggle for enable/disable, and a remove button.
 *
 * This is the user-facing UI for the multi-provider routing feature.
 * e.g. GLM-4.6 bound to Z.AI + OpenRouter + Groq — pick one based on priority
 * + health, fall over to next on error.
 */
@Composable
private fun BindingsDialog(
    model: SavedModelsStore.SavedModel,
    allKeys: List<ApiKeyStore.ApiKey>,
    onDismiss: () -> Unit,
    onAddBinding: (apiKeyId: String) -> Unit,
    onRemoveBinding: (bindingId: String) -> Unit,
    onToggleBinding: (bindingId: String, enabled: Boolean) -> Unit,
    onReorderBinding: (bindingId: String, direction: SavedModelsStore.ReorderDirection) -> Unit,
) {
    val custom = LocalCustomColors.current
    var showAddPicker by remember { mutableStateOf(false) }
    // Refresh bindings from the model each recomposition.
    val bindings = remember(model) { model.activeBindings() }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = custom.canvas,
        title = {
            Column {
                Text("Provider Bindings", color = custom.fg2, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text("${model.modelName} — pick which providers/keys to use + their priority",
                    color = custom.fg3, fontSize = 10.sp)
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (bindings.isEmpty()) {
                    Text("No bindings yet. Tap '+ Add Binding' below to bind this model to an API key.",
                        color = custom.fg3, fontSize = 11.sp,
                        modifier = Modifier.padding(vertical = 8.dp))
                } else {
                    bindings.forEachIndexed { index, binding ->
                        val key = allKeys.find { it.id == binding.apiKeyId }
                        val isHealthy = BindingHealthStore.isHealthy(binding.id)
                        val health = BindingHealthStore.get(binding.id)
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .background(
                                    if (!binding.enabled) custom.ink2
                                    else if (!isHealthy) custom.ink2
                                    else custom.accent.copy(alpha = 0.06f),
                                    RoundedCornerShape(6.dp)
                                )
                                .border(1.dp,
                                    if (!binding.enabled) custom.inkBorder
                                    else if (!isHealthy) custom.fg3
                                    else custom.accent.copy(alpha = 0.3f),
                                    RoundedCornerShape(6.dp))
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Priority badge
                            Text("#${index + 1}", color = custom.fg3, fontSize = 10.sp,
                                fontWeight = FontWeight.Bold, modifier = Modifier.width(24.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    binding.providerName + if (key != null) " · ${key.alias.ifEmpty { "" }}" else "",
                                    color = MaterialTheme.colorScheme.onSurface, fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                if (key != null) {
                                    Text("${key.apiKey.take(8)}... · ${key.baseUrl.take(40)}",
                                        color = custom.fg3, fontFamily = MonoFontFamily, fontSize = 8.sp,
                                        maxLines = 1)
                                }
                                if (!binding.enabled) {
                                    Text("DISABLED", color = custom.fg3, fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold)
                                } else if (!isHealthy) {
                                    Text("UNHEALTHY (${health.consecutiveFailures} failures) — cooldown",
                                        color = custom.fg3, fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold)
                                } else if (health.totalCalls > 0) {
                                    Text("✓ ${health.totalSuccesses}/${health.totalCalls} · ${health.avgLatencyMs}ms avg",
                                        color = custom.accent, fontSize = 9.sp, fontFamily = MonoFontFamily)
                                }
                            }
                            // Reorder up/down
                            IconButton(
                                onClick = { onReorderBinding(binding.id, SavedModelsStore.ReorderDirection.UP) },
                                enabled = index > 0,
                                modifier = Modifier.size(20.dp),
                            ) { Text("▲", fontSize = 10.sp, color = custom.fg3) }
                            IconButton(
                                onClick = { onReorderBinding(binding.id, SavedModelsStore.ReorderDirection.DOWN) },
                                enabled = index < bindings.size - 1,
                                modifier = Modifier.size(20.dp),
                            ) { Text("▼", fontSize = 10.sp, color = custom.fg3) }
                            // Toggle enable/disable
                            Switch(
                                checked = binding.enabled,
                                onCheckedChange = { onToggleBinding(binding.id, it) },
                                modifier = Modifier.padding(horizontal = 4.dp),
                            )
                            // Remove
                            IconButton(onClick = { onRemoveBinding(binding.id) }, modifier = Modifier.size(20.dp)) {
                                Text("✕", fontSize = 11.sp, color = custom.fg3)
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                }

                // Add binding button
                TextButton(onClick = { showAddPicker = true }) {
                    Text("+ Add Binding", fontSize = 11.sp, color = custom.accent)
                }

                if (bindings.size > 1) {
                    Spacer(Modifier.height(4.dp))
                    Text("Routing: hybrid (priority → health → failover). Toggle auto-fallback in AI Settings → Advanced.",
                        color = custom.fg3, fontSize = 9.sp, fontFamily = MonoFontFamily)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )

    // Add binding picker — reuse the key list, but exclude already-bound keys.
    if (showAddPicker) {
        AlertDialog(
            onDismissRequest = { showAddPicker = false },
            containerColor = custom.canvas,
            title = { Text("Pick API Key to Bind", color = custom.fg2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                    val candidates = allKeys.filter { k -> bindings.none { it.apiKeyId == k.id } }
                    if (candidates.isEmpty()) {
                        item { Text("All saved keys are already bound. Add a new key first.",
                            color = custom.fg3, fontSize = 11.sp) }
                    } else {
                        items(candidates, key = { it.id }) { key ->
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    onAddBinding(key.id)
                                    showAddPicker = false
                                }.padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(key.alias.ifEmpty { key.providerName },
                                        color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp)
                                    Text("${key.providerName} · ${key.apiKey.take(8)}... · ${key.baseUrl.take(40)}",
                                        color = custom.fg3, fontFamily = MonoFontFamily, fontSize = 9.sp,
                                        maxLines = 1)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showAddPicker = false }) { Text("Cancel") } },
        )
    }
}

/**
 * v3.2.7: Test a SAVED model — resolves the endpoint + api_key using the same
 * resolution order as applyModelToProvider, then sends a tiny "ping" chat
 * completion. Reports success with the model's reply.
 *
 * Used by SavedModelsTab + LlmConfigPage → internal (cross-file).
 */
internal suspend fun testSavedModel(
    context: Context,
    model: SavedModelsStore.SavedModel,
): String {
    return withContext(Dispatchers.IO) {
        try {
            // v3.3.4: use the same resolveBinding() logic as applyModelToProvider
            // so the test reflects what chat will actually do. Previously this
            // used the old apiKeyId-only resolution → 404 for Gemini/Anthropic
            // + providers whose baseUrl is a base URL, not the chat completions URL.
            SavedModelsStore.load(context)
            ApiKeyStore.load(context)
            BindingHealthStore.load(context)
            val resolved = SavedModelsStore.resolveBinding(context, model, false)
            if (resolved == null) {
                return@withContext "❌ No binding/key resolved — add an API key first"
            }
            val (binding, key) = resolved
            val endpoint = SavedModelsStore.resolveEndpointForBinding(model, binding, key)
            if (endpoint.isEmpty()) {
                return@withContext "❌ No endpoint resolved for ${binding.providerName}"
            }
            // v3.3.5: resolve the actual model ID for this provider (Cline/OpenRouter prefix).
            val actualModelId = SavedModelsStore.resolveActualModelIdForBinding(context, model, binding)
            // v3.3.4: use the provider's auth scheme (BEARER / X_API_KEY / QUERY_PARAM / NONE).
            val endpointInfo = ProviderEndpoints.byId(binding.providerId)
            val authScheme = endpointInfo?.authScheme ?: ProviderEndpoints.AuthScheme.BEARER
            // v3.3.4: Gemini uses a different API shape — its test goes to /models, not chat.
            if (binding.providerId == "gemini") {
                val url = "${endpoint.trimEnd('/')}/models?key=${key.apiKey}"
                val req = okhttp3.Request.Builder().url(url).build()
                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                val resp = client.newCall(req).execute()
                if (resp.isSuccessful) {
                    val count = try {
                        org.json.JSONObject(resp.body?.string() ?: "")
                            .optJSONArray("models")?.length() ?: -1
                    } catch (_: Exception) { -1 }
                    if (count >= 0) "✅ Gemini connected! $count models available"
                    else "✅ Gemini connected"
                } else {
                    "❌ HTTP ${resp.code}: ${resp.message}"
                }
            } else {
                // Standard OpenAI-compatible chat completions test.
                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                val body = org.json.JSONObject().apply {
                    put("model", actualModelId)  // v3.3.5: provider-specific ID
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
                            ProviderEndpoints.AuthScheme.NONE -> { /* no auth */ }
                            ProviderEndpoints.AuthScheme.QUERY_PARAM -> { /* handled by URL */ }
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
                    if (reply.isNotEmpty()) "✅ ${model.modelName} replied: $preview"
                    else "✅ Connected (HTTP ${response.code})"
                } else {
                    "❌ HTTP ${response.code}: ${respBody.take(80)}"
                }
            }
        } catch (e: Exception) {
            "❌ ${e.javaClass.simpleName}: ${e.message ?: "unknown"}"
        }
    }
}
