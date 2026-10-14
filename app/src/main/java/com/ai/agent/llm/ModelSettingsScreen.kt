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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
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

/**
 * v3.2.0: ModelSettingsScreen — the new AI Settings page.
 *
 * 3 tabs:
 * 1. API Keys — add/edit/test/delete API keys per provider
 * 2. Saved Models — list of saved models with capability tags
 * 3. Model Catalog — browse 12,542 models from models.dev
 *
 * Below tabs: filtered config sections (LLM/Vision/Audio)
 */
@Composable
fun ModelSettingsScreen(
    onOpenProviderSettings: () -> Unit = {},
) {
    val context = LocalContext.current
    val custom = LocalCustomColors.current
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableStateOf(0) }  // 0=keys, 1=saved, 2=catalog
    var toast by remember { mutableStateOf<String?>(null) }

    // Load data on first composition
    LaunchedEffect(Unit) {
        ApiKeyStore.load(context)
        SavedModelsStore.load(context)
        ModelsDevRepository.load(context)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Tab bar
        TabRow(selectedTabIndex = selectedTab) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 },
                text = { Text("API Keys (${ApiKeyStore.getAllKeys().size})") })
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 },
                text = { Text("Saved Models (${SavedModelsStore.getAllModels().size})") })
            Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 },
                text = { Text("Model Catalog") })
        }

        when (selectedTab) {
            0 -> ApiKeysTab(scope = scope, onToast = { toast = it })
            1 -> SavedModelsTab(onToast = { toast = it })
            2 -> ModelCatalogTab(scope = scope, onToast = { toast = it })
        }
    }

    LaunchedEffect(toast) {
        toast?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            toast = null
        }
    }
}

// === TAB 1: API KEYS ===

@Composable
private fun ApiKeysTab(scope: kotlinx.coroutines.CoroutineScope, onToast: (String) -> Unit) {
    val context = LocalContext.current
    val custom = LocalCustomColors.current
    var showAddDialog by remember { mutableStateOf(false) }
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
                        keys = ApiKeyStore.getAllKeys()
                        onToast("Key deleted: ${key.providerName}")
                    },
                    onTest = {
                        onToast("Testing ${key.providerName}... (not implemented yet)")
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
                onToast("Key saved: ${newKey.providerName}")
            },
        )
    }
}

@Composable
private fun ApiKeyCard(key: ApiKeyStore.ApiKey, onDelete: () -> Unit, onTest: () -> Unit) {
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
        IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, contentDescription = "Delete", tint = custom.fg3, modifier = Modifier.size(16.dp)) }
    }
}

@Composable
private fun AddApiKeyDialog(onDismiss: () -> Unit, onSave: (ApiKeyStore.ApiKey) -> Unit) {
    val context = LocalContext.current
    val custom = LocalCustomColors.current
    var provider by remember { mutableStateOf("") }
    var providerName by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var alias by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }

    // Get providers from catalog
    val catalogProviders = remember { ModelsDevRepository.allProviders() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add API Key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Provider dropdown
                Text("Provider", fontSize = 12.sp, color = custom.fg3)
                OutlinedTextField(
                    value = providerName,
                    onValueChange = { providerName = it; provider = it.lowercase().replace(" ", "") },
                    placeholder = { Text("e.g. Z.AI, OpenAI, Custom") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
                )

                // API Key
                Text("API Key", fontSize = 12.sp, color = custom.fg3)
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    placeholder = { Text("Enter your API key") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showKey = !showKey }) {
                            Text(if (showKey) "🙈" else "👁", fontSize = 16.sp)
                        }
                    },
                    textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
                )

                // Alias
                Text("Alias (optional)", fontSize = 12.sp, color = custom.fg3)
                OutlinedTextField(
                    value = alias,
                    onValueChange = { alias = it },
                    placeholder = { Text("e.g. Work Account") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
                )

                // Base URL (for custom providers)
                Text("Base URL (optional, for custom providers)", fontSize = 12.sp, color = custom.fg3)
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    placeholder = { Text("https://api.example.com") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (apiKey.isNotEmpty() && providerName.isNotEmpty()) {
                        onSave(ApiKeyStore.ApiKey(
                            providerId = provider.lowercase().replace(" ", ""),
                            providerName = providerName,
                            apiKey = apiKey,
                            alias = alias,
                            baseUrl = baseUrl,
                        ))
                    }
                },
                enabled = apiKey.isNotEmpty() && providerName.isNotEmpty(),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// === TAB 2: SAVED MODELS ===

@Composable
private fun SavedModelsTab(onToast: (String) -> Unit) {
    val context = LocalContext.current
    val custom = LocalCustomColors.current
    var models by remember { mutableStateOf(SavedModelsStore.getAllModels()) }
    val defaultModel = remember { SavedModelsStore.getDefaultModel(context) }

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
                )
            }
        }
    }
}

@Composable
private fun SavedModelCard(
    model: SavedModelsStore.SavedModel,
    isDefault: Boolean,
    onSetDefault: () -> Unit,
    onDelete: () -> Unit,
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onSetDefault) { Text(if (isDefault) "Default ✓" else "Set Default", fontSize = 11.sp) }
            TextButton(onClick = onDelete) { Text("Remove", fontSize = 11.sp, color = custom.fg3) }
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

    val providers = remember { ModelsDevRepository.allProviders() }
    val capabilities = listOf(ModelCapability.VISION, ModelCapability.TOOLS, ModelCapability.REASONING, ModelCapability.FREE, ModelCapability.IMAGE_GEN)

    // Filter models
    val filteredModels = remember(searchQuery, selectedProvider, selectedCapability, models) {
        var result = if (searchQuery.isNotEmpty()) ModelsDevRepository.searchModels(searchQuery) else models
        if (selectedProvider != null) result = result.filter { it.providerId == selectedProvider }
        if (selectedCapability != null) result = result.filter { selectedCapability in it.capabilities }
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
                        Text("${ModelsDevRepository.allModels().size}", color = custom.fg3, fontSize = 10.sp)
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

        // Capability filter chips
        item {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
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
            }
        }

        // Model cards
        items(filteredModels, key = { "${it.providerId}_${it.id}" }) { model ->
            CatalogModelCard(
                model = model,
                isSaved = SavedModelsStore.isAlreadySaved(model.id, model.providerId),
                onAdd = {
                    val apiKeyId = ApiKeyStore.getKey(model.providerId)?.id
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
