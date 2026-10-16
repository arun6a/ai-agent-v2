package com.ai.agent.llm.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.agent.llm.ApiKeyStore
import com.ai.agent.llm.AutoFallbackSettings
import com.ai.agent.llm.BindingHealthStore
import com.ai.agent.llm.SavedModelsStore
import com.ai.agent.ui.components.LocalCustomColors
import com.ai.agent.ui.theme.MonoFontFamily

// === SECTION 8: SMART ROUTING (v3.3.6) ===
@Composable
internal fun SmartRoutingPage(onToast: (String) -> Unit) {
    val context = LocalContext.current
    val custom = LocalCustomColors.current

    // Load stores.
    LaunchedEffect(Unit) {
        SavedModelsStore.load(context)
        ApiKeyStore.load(context)
        BindingHealthStore.load(context)
    }

    var fastModelId by remember { mutableStateOf(AutoFallbackSettings.getFastModelId(context)) }
    var advancedModelId by remember { mutableStateOf(AutoFallbackSettings.getAdvancedModelId(context)) }
    var autoFallbackKeys by remember { mutableStateOf(AutoFallbackSettings.isEnabled(context)) }
    var autoFallbackModels by remember { mutableStateOf(AutoFallbackSettings.isAutoFallbackModelsEnabled(context)) }
    var autoPickCheapest by remember { mutableStateOf(AutoFallbackSettings.isAutoPickCheapestEnabled(context)) }
    // Bump to refresh health display.
    var healthRefresh by remember { mutableStateOf(0) }

    val defaultModel = remember { SavedModelsStore.getDefaultModel(context) }
    val allModels = remember { SavedModelsStore.getAllModels() }

    // v3.3.7: wrap in verticalScroll so the page scrolls when content overflows.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ConfigSectionHeader("Smart Routing", "Tier-based model selection + auto-fallback")

    // === Tier-based model selection ===
    Text("Task-Based Model Selection", color = custom.fg2, fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
    Text("The app classifies each message into a tier and picks the right model:",
        color = custom.fg3, fontSize = 9.sp)
    Spacer(Modifier.height(4.dp))

    // Tier 1: Fast/Cheap
    SmartRoutingModelSlot(
        label = "Fast/Cheap (Tier 1 — greetings)",
        description = "Used for 'hi', simple questions. Should be fast + cheap.",
        models = allModels,
        selectedId = fastModelId,
        defaultModel = defaultModel,
        tier = 1,
        onSelect = { id ->
            fastModelId = id
            AutoFallbackSettings.setFastModelId(context, id)
            onToast("Fast/Cheap model set")
        },
    )
    Spacer(Modifier.height(6.dp))

    // Tier 2: Default (read-only — set via LLM Configuration)
    SmartRoutingModelSlot(
        label = "Default (Tier 2 — matched tools)",
        description = "Used for tool-matched tasks (tap, launchApp, etc.). Set via LLM Configuration.",
        models = allModels,
        selectedId = defaultModel?.id,
        defaultModel = defaultModel,
        tier = 2,
        onSelect = { id ->
            SavedModelsStore.setDefaultModel(context, id)
            onToast("Default model set")
        },
    )
    Spacer(Modifier.height(6.dp))

    // Tier 3: Advanced
    SmartRoutingModelSlot(
        label = "Advanced (Tier 3 — complex tasks)",
        description = "Used for complex/unknown tasks that need full prompt. Should be smart.",
        models = allModels,
        selectedId = advancedModelId,
        defaultModel = defaultModel,
        tier = 3,
        onSelect = { id ->
            advancedModelId = id
            AutoFallbackSettings.setAdvancedModelId(context, id)
            onToast("Advanced model set")
        },
    )
    Spacer(Modifier.height(12.dp))

    // === Auto-Fallback toggles ===
    Text("Auto-Fallback", color = custom.fg2, fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
    Spacer(Modifier.height(4.dp))

    // Auto-Fallback Keys (binding-level)
    SmartRoutingToggle(
        label = "Auto-Fallback Bindings",
        desc = "When the active provider/key fails (429/402/401/5xx), try the next binding for the same model.",
        checked = autoFallbackKeys,
        onCheck = {
            autoFallbackKeys = it
            AutoFallbackSettings.setEnabled(context, it)
            onToast("Auto-fallback bindings: ${if (it) "ON" else "OFF"}")
        },
    )
    Spacer(Modifier.height(4.dp))

    // Auto-Fallback Models (cross-model)
    SmartRoutingToggle(
        label = "Auto-Fallback Models (cross-model)",
        desc = "When ALL bindings of the picked model fail, try a different model. e.g. GLM-4.6 fails → try Grok → try Gemini Flash.",
        checked = autoFallbackModels,
        onCheck = {
            autoFallbackModels = it
            AutoFallbackSettings.setAutoFallbackModelsEnabled(context, it)
            onToast("Auto-fallback models: ${if (it) "ON" else "OFF"}")
        },
    )
    Spacer(Modifier.height(4.dp))

    // Auto-Pick Cheapest
    SmartRoutingToggle(
        label = "Auto-Pick Cheapest Binding",
        desc = "Instead of priority order, pick the cheapest healthy binding (lowest price).",
        checked = autoPickCheapest,
        onCheck = {
            autoPickCheapest = it
            AutoFallbackSettings.setAutoPickCheapestEnabled(context, it)
            onToast("Auto-pick cheapest: ${if (it) "ON" else "OFF"}")
        },
    )
    Spacer(Modifier.height(12.dp))

    // === Health Dashboard ===
    Text("Binding Health", color = custom.fg2, fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
    Spacer(Modifier.height(4.dp))

    // Show health for ALL bindings across ALL saved models.
    val allBindings = remember(healthRefresh, allModels) {
        allModels.flatMap { model ->
            model.activeBindings().map { binding ->
                Triple(model, binding, BindingHealthStore.get(binding.id))
            }
        }
    }

    if (allBindings.isEmpty()) {
        Text("No bindings yet. Add API keys + save models first.",
            color = custom.fg3, fontSize = 10.sp, modifier = Modifier.padding(8.dp))
    } else {
        allBindings.forEach { (model, binding, health) ->
            val key = remember(binding.apiKeyId) { ApiKeyStore.getAllKeys().find { it.id == binding.apiKeyId } }
            Row(
                modifier = Modifier.fillMaxWidth()
                    .background(
                        if (!binding.enabled) custom.ink2
                        else if (!health.isHealthy) custom.ink2
                        else custom.accent.copy(alpha = 0.06f),
                        RoundedCornerShape(6.dp)
                    )
                    .border(1.dp,
                        if (!binding.enabled) custom.inkBorder
                        else if (!health.isHealthy) custom.fg3
                        else custom.accent.copy(alpha = 0.3f),
                        RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (!binding.enabled) "✗" else if (health.isHealthy) "✓" else "⚠",
                    color = if (!binding.enabled) custom.fg3
                    else if (health.isHealthy) custom.accent
                    else custom.fg3,
                    fontSize = 12.sp)
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("${model.modelName} → ${binding.providerName}",
                        color = MaterialTheme.colorScheme.onSurface, fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold)
                    if (key != null) {
                        Text("${key.alias.ifEmpty { key.providerName }} · ${key.apiKey.take(8)}...",
                            color = custom.fg3, fontFamily = MonoFontFamily, fontSize = 8.sp,
                            maxLines = 1)
                    }
                    if (!binding.enabled) {
                        Text("DISABLED", color = custom.fg3, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                    } else if (!health.isHealthy) {
                        Text("UNHEALTHY (${health.consecutiveFailures} failures) — cooldown",
                            color = custom.fg3, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                    } else if (health.totalCalls > 0) {
                        Text("${health.totalSuccesses}/${health.totalCalls} success · ${health.avgLatencyMs}ms avg",
                            color = custom.accent, fontSize = 8.sp, fontFamily = MonoFontFamily)
                    } else {
                        Text("No calls yet", color = custom.fg3, fontSize = 8.sp)
                    }
                }
                if (!binding.enabled || !health.isHealthy) {
                    TextButton(onClick = {
                        if (!binding.enabled) {
                            SavedModelsStore.updateBinding(context, model.id, binding.id, enabled = true)
                        } else {
                            BindingHealthStore.reset(binding.id, context)
                        }
                        healthRefresh++
                        onToast("Reset ${binding.providerName}")
                    }) { Text("Reset", fontSize = 9.sp) }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }

    Spacer(Modifier.height(8.dp))
    TextButton(onClick = {
        BindingHealthStore.resetAll(context)
        healthRefresh++
        onToast("All health records cleared")
    }) { Text("Reset All Health", fontSize = 10.sp, color = custom.fg3) }

        Spacer(Modifier.height(16.dp))
    }  // end Column
}

@Composable
private fun SmartRoutingModelSlot(
    label: String,
    description: String,
    models: List<SavedModelsStore.SavedModel>,
    selectedId: String?,
    defaultModel: SavedModelsStore.SavedModel?,
    tier: Int,
    onSelect: (String) -> Unit,
) {
    val custom = LocalCustomColors.current
    Column(
        modifier = Modifier.fillMaxWidth()
            .background(custom.ink2, RoundedCornerShape(8.dp))
            .border(1.dp, custom.inkBorder, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold)
        Text(description, color = custom.fg3, fontSize = 9.sp)
        Spacer(Modifier.height(4.dp))
        if (models.isEmpty()) {
            Text("No saved models. Add models from the catalog first.",
                color = custom.fg3, fontSize = 9.sp, fontStyle = FontStyle.Italic)
        } else {
            // "None" option (use Default for this tier).
            Row(
                modifier = Modifier.fillMaxWidth()
                    .background(
                        if (selectedId == null && tier != 2) custom.accent.copy(alpha = 0.08f) else Color.Transparent,
                        RoundedCornerShape(4.dp)
                    )
                    .clickable { if (tier != 2) onSelect("") }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (selectedId == null && tier != 2) "●" else "○",
                    color = if (selectedId == null && tier != 2) custom.accent else custom.fg3, fontSize = 10.sp)
                Spacer(Modifier.width(6.dp))
                Text(if (tier == 2) "Default (set via LLM Configuration)" else "None (use Default)",
                    color = custom.fg3, fontSize = 10.sp)
            }
            // Model options.
            models.forEach { model ->
                val isSelected = model.id == selectedId
                val isHealthy = remember(isSelected) {
                    model.activeBindings().any { it.enabled && BindingHealthStore.isHealthy(it.id) }
                }
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .background(
                            if (isSelected) custom.accent.copy(alpha = 0.08f) else Color.Transparent,
                            RoundedCornerShape(4.dp)
                        )
                        .clickable { onSelect(model.id) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(if (isSelected) "●" else "○",
                        color = if (isSelected) custom.accent else custom.fg3, fontSize = 10.sp)
                    Spacer(Modifier.width(6.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(model.modelName,
                            color = MaterialTheme.colorScheme.onSurface, fontSize = 10.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal)
                        Text("${model.providerName} · ${model.modelId.take(30)}",
                            color = custom.fg3, fontFamily = MonoFontFamily, fontSize = 8.sp,
                            maxLines = 1)
                    }
                    if (model.id == defaultModel?.id) {
                        Text("DEFAULT", color = custom.accent, fontSize = 7.sp,
                            fontWeight = FontWeight.Bold)
                    }
                    Text(if (isHealthy) "✓" else "⚠",
                        color = if (isHealthy) custom.accent else custom.fg3, fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
private fun SmartRoutingToggle(
    label: String,
    desc: String,
    checked: Boolean,
    onCheck: (Boolean) -> Unit,
) {
    val custom = LocalCustomColors.current
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(custom.ink2, RoundedCornerShape(8.dp))
            .border(1.dp, custom.inkBorder, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold)
            Text(desc, color = custom.fg3, fontSize = 9.sp)
        }
        Switch(checked = checked, onCheckedChange = onCheck,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = custom.accent,
            ))
    }
}
