package com.ai.agent.llm.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.agent.llm.ApiKeyStore
import com.ai.agent.llm.ModelCapability
import com.ai.agent.llm.ModelsDevRepository
import com.ai.agent.llm.ProviderModelsStore
import com.ai.agent.llm.SavedModelsStore
import com.ai.agent.ui.components.LocalCustomColors
import com.ai.agent.ui.theme.MonoFontFamily
import kotlinx.coroutines.launch

// === TAB 3: MODEL CATALOG ===

@Composable
internal fun ModelCatalogTab(scope: kotlinx.coroutines.CoroutineScope, onToast: (String) -> Unit) {
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
        // v3.3.3: Removed the take(100) limit — LazyColumn virtualizes naturally
        // and only renders visible items. When the user searches, the result set
        // is already small. When no search, they should be able to scroll all models.
        result
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
                for (p in providers) {  // v3.3.3: show all providers (horizontal scroll handles overflow)
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
                    // v3.3.4: if no key has this exact model, fall back to ANY saved
                    // key (user can fix binding later via Saved Models → Manage).
                    // v3.3.5: resolve the ACTUAL model ID the provider's API expects.
                    // Gateway providers like Cline/OpenRouter prefix model IDs
                    // (e.g. "x-ai/grok-4.7" instead of "grok-4.7"). Sending the bare
                    // catalog ID → HTTP 400 "invalid model format".
                    val boundKey = keysWithModel.firstOrNull()
                        ?: ApiKeyStore.getKey(model.providerId)
                        ?: ApiKeyStore.getAllKeys().firstOrNull()
                    val apiKeyId = boundKey?.id
                    val actualModelId = if (boundKey != null) {
                        ProviderModelsStore.resolveActualModelId(context, boundKey.id, model.id)
                    } else model.id
                    SavedModelsStore.addModel(context, SavedModelsStore.SavedModel(
                        modelId = actualModelId,  // v3.3.5: provider-specific ID (e.g. "x-ai/grok-4.7")
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
                    onToast("Saved: ${model.name}" +
                        if (apiKeyId == null) " (no key bound — tap Manage to bind)"
                        else if (actualModelId != model.id) " (model ID: $actualModelId)"
                        else "")
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
