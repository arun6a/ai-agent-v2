package com.ai.agent.ui.screens.hub

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn  // v4.0.77
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.ManageSearch
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Rule
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Button  // v4.0.77
import androidx.compose.material3.ButtonDefaults  // v4.0.77
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.ai.agent.llm.AIProvider
import com.ai.agent.llm.AgentThinkingSettings
import com.ai.agent.llm.ApiUsageTracker
import com.ai.agent.llm.AppProfileManager
import com.ai.agent.llm.AutoFallbackSettings
import com.ai.agent.llm.ModelSettingsScreen
import com.ai.agent.llm.SavedModelsStore
import com.ai.agent.llm.ApiKeyStore
import com.ai.agent.llm.BindingHealthStore
import com.ai.agent.llm.ModelCapability
import com.ai.agent.skills.SkillManager
import com.ai.agent.storage.AgentDatabase
import com.ai.agent.tools.ToolExecutor
import com.ai.agent.ui.components.HubNavItem
import com.ai.agent.ui.components.LocalCustomColors
import com.ai.agent.ui.components.StatCard
import com.ai.agent.ui.theme.MonoFontFamily
import com.ai.agent.ui.theme.ThemeManager
import com.ai.agent.ui.screens.ChatViewModel
import com.ai.agent.ui.screens.isUserCreatedSkill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Hub section: Settings — prompt mode, browser, search, theme, about.

// ---------------------------------------------------------------------------
// Task 31 — Functional section: Settings (Prompt / Browser / Search / Toggles)
// ---------------------------------------------------------------------------

@Composable
internal fun SettingsSection(onOpenSystemPromptEditor: () -> Unit = {}) {
    val context = LocalContext.current
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme

    // v4.0.3: promptMode removed — only smart prompt exists.
    var browserMode by remember { mutableStateOf(AIProvider.getBrowserMode(context)) }
    var searchMethod by remember { mutableStateOf(AIProvider.getSearchMethod(context)) }
    var visionMode by remember { mutableStateOf(AIProvider.getVisionMode(context)) }
    // v3.5.1: AI behavior toggles now live here (merged from BrainBehaviorPage + SmartRoutingPage)
    var reflection by remember { mutableStateOf(AgentThinkingSettings.isReflectionEnabled(context)) }
    var planning by remember { mutableStateOf(AgentThinkingSettings.isPlanningEnabled(context)) }
    var verification by remember { mutableStateOf(AgentThinkingSettings.isVerificationEnabled(context)) }
    var alwaysLearn by remember { mutableStateOf(AgentThinkingSettings.isAlwaysLearnEnabled(context)) }
    var knowledgeAutoRecall by remember { mutableStateOf(AgentThinkingSettings.isKnowledgeAutoRecallEnabled(context)) }
    var consentEnabled by remember { mutableStateOf(AgentThinkingSettings.isConsentEnabled(context)) }
    var contextualPrompts by remember { mutableStateOf(AIProvider.isContextualPromptsEnabled(context)) }
    var autoFallbackKeys by remember { mutableStateOf(AutoFallbackSettings.isEnabled(context)) }
    var autoFallbackModels by remember { mutableStateOf(AutoFallbackSettings.isAutoFallbackModelsEnabled(context)) }
    var autoPickCheapest by remember { mutableStateOf(AutoFallbackSettings.isAutoPickCheapestEnabled(context)) }
    // v3.5.1: model slots + health from SmartRoutingPage
    val allModels = remember { SavedModelsStore.getAllModels() }
    val defaultModel = remember { SavedModelsStore.getDefaultModel(context) }
    var fastModelId by remember { mutableStateOf(AutoFallbackSettings.getFastModelId(context)) }
    var advancedModelId by remember { mutableStateOf(AutoFallbackSettings.getAdvancedModelId(context)) }
    var healthRefresh by remember { mutableStateOf(0) }
    // v4.0.13: Overlay vars removed (overlay permission is in Permissions page now)
    var toast by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            SectionHeader(
                title = "Settings",
                subtitle = "All controls in one place",
            )
        }

        // v4.0.72: Removed THINKING section (Periodic Reflection, Task Planning,
        // Post-Action Verification, Contextual Prompts) — all always-on now.
        // Capability levels replace Contextual Prompts.
        // Reflection/Planning/Verification are core behavior (can't disable).

        // ===== LEARNING =====
        item {
            SettingsGroup(title = "LEARNING") {
                AiToggleRow("Always Learn",
                    "Saves successful workflows + failure lessons to memory for future tasks.",
                    alwaysLearn) { alwaysLearn = it; AgentThinkingSettings.setAlwaysLearnEnabled(context, it); toast = "Always learn: $it" }
                AiToggleRow("Knowledge Auto-Recall",
                    "Brain proactively searches saved docs when you ask questions. Turn OFF to save tokens.",
                    knowledgeAutoRecall) { knowledgeAutoRecall = it; AgentThinkingSettings.setKnowledgeAutoRecallEnabled(context, it); toast = "Knowledge auto-recall: $it" }
                AiToggleRow("Consent Flow",
                    "Asks for approval before sensitive actions (send SMS, delete file, call, etc.). Recommended ON.",
                    consentEnabled) { consentEnabled = it; AgentThinkingSettings.setConsentEnabled(context, it); toast = "Consent flow: $it" }
            }
        }

        // ===== AI BEHAVIOR (v4.0.75: Prompt architecture + presets) =====
        item {
            AiBehaviorSection(context = context, onToast = { toast = it })
        }

        // ===== PROACTIVE BEHAVIOR (v4.0.77) =====
        item {
            ProactiveBehaviorSection(context = context, onToast = { toast = it })
        }

        // ===== VISION =====
        item {
            SettingsGroup(title = "VISION") {
                SelectorRow(
                    label = "Vision Mode",
                    options = listOf("auto", "mlkit", "vlm"),
                    selected = visionMode,
                    onSelect = { visionMode = it; AIProvider.setVisionMode(context, it); toast = "Vision mode: $it" },
                )
            }
        }

        // ===== WEB (merged BROWSER + SEARCH METHOD) =====
        item {
            SettingsGroup(title = "WEB") {
                SelectorRow(
                    label = "Browser Mode",
                    options = listOf("auto", "in_app", "chrome"),
                    selected = browserMode,
                    onSelect = { browserMode = it; AIProvider.setBrowserMode(context, it); toast = "Browser mode: $it" },
                )
                SelectorRow(
                    label = "Search Method",
                    options = listOf("duckduckgo", "google", "bing", "google_app"),
                    selected = searchMethod,
                    onSelect = {
                        searchMethod = it
                        AIProvider.setSearchMethod(context, it)
                        toast = "Search method: $it"
                    },
                )
                // v4.0.32: Brave Search API key input
                BraveApiKeyRow(context) { toast = it }
            }
        }

        // ===== ROUTING & FAILOVER =====
        item {
            SettingsGroup(title = "ROUTING & FAILOVER") {
                AiToggleRow("Auto-Fallback Bindings",
                    "If one API key fails, try next key for same model.",
                    autoFallbackKeys) { autoFallbackKeys = it; AutoFallbackSettings.setEnabled(context, it); toast = "Auto-fallback bindings: $it" }
                AiToggleRow("Auto-Fallback Models",
                    "If all keys for current model fail, switch to different saved model.",
                    autoFallbackModels) { autoFallbackModels = it; AutoFallbackSettings.setAutoFallbackModelsEnabled(context, it); toast = "Auto-fallback models: $it" }
                AiToggleRow("Auto-Pick Cheapest",
                    "Pick cheapest healthy key instead of priority order. OFF = brain uses your priority order.",
                    autoPickCheapest) { autoPickCheapest = it; AutoFallbackSettings.setAutoPickCheapestEnabled(context, it); toast = "Auto-pick cheapest: $it" }
            }
        }

        // ===== MODEL SLOTS =====
        item {
            SettingsGroup(title = "MODEL SLOTS") {
                ModelSlotRow("Fast/Cheap (Tier 1)", allModels, fastModelId, defaultModel?.id) { id ->
                    fastModelId = id
                    AutoFallbackSettings.setFastModelId(context, id)
                    toast = "Fast/Cheap model set"
                }
                ModelSlotRow("Default (Tier 2)", allModels, defaultModel?.id, defaultModel?.id) { id ->
                    SavedModelsStore.setDefaultModel(context, id)
                    toast = "Default model set"
                }
                ModelSlotRow("Advanced (Tier 3)", allModels, advancedModelId, defaultModel?.id) { id ->
                    advancedModelId = id
                    AutoFallbackSettings.setAdvancedModelId(context, id)
                    toast = "Advanced model set"
                }
            }
        }

        // ===== BINDING HEALTH =====
        item {
            SettingsGroup(title = "BINDING HEALTH") {
                val allBindings = remember(healthRefresh, allModels) {
                    allModels.flatMap { model ->
                        model.activeBindings().map { binding ->
                            Triple(model, binding, BindingHealthStore.get(binding.id))
                        }
                    }
                }
                if (allBindings.isEmpty()) {
                    Text("No bindings yet. Add API keys + save models first.",
                        color = custom.fg3, fontSize = 10.sp)
                } else {
                    allBindings.forEach { (model, binding, health) ->
                        val key = ApiKeyStore.getAllKeys().find { it.id == binding.apiKeyId }
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(if (!binding.enabled) "✗" else if (health.isHealthy) "✓" else "⚠",
                                color = if (!binding.enabled) custom.fg3 else if (health.isHealthy) custom.accent else custom.fg3,
                                fontSize = 11.sp)
                            Spacer(Modifier.width(6.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("${model.modelName} → ${binding.providerName}",
                                    color = m3.onSurface, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                                if (health.totalCalls > 0) {
                                    Text("${health.totalSuccesses}/${health.totalCalls} success",
                                        color = custom.fg3, fontSize = 8.sp, fontFamily = MonoFontFamily)
                                } else if (!binding.enabled) {
                                    Text("DISABLED", color = custom.fg3, fontSize = 8.sp)
                                } else if (!health.isHealthy) {
                                    Text("UNHEALTHY (${health.consecutiveFailures} failures)",
                                        color = custom.fg3, fontSize = 8.sp)
                                } else {
                                    Text("No calls yet", color = custom.fg3, fontSize = 8.sp)
                                }
                            }
                        }
                    }
                    TextButton(onClick = {
                        BindingHealthStore.resetAll(context); healthRefresh++
                        toast = "All health records cleared"
                    }) { Text("Reset All Health", fontSize = 9.sp, color = custom.fg3) }
                }
            }
        }

        // ===== APPEARANCE =====
        item {
            SettingsGroup(title = "APPEARANCE") {
                ThemeSelectorRow(
                    current = ThemeManager.mode,
                    onSelect = {
                        ThemeManager.setMode(context, it)
                        toast = "Theme: $it"
                    },
                )
            }
        }

        // ===== RESET DEFAULTS (at very bottom) =====
        item {
            TextButton(onClick = {
                AgentThinkingSettings.resetToDefaults(context)
                AutoFallbackSettings.setEnabled(context, true)
                AutoFallbackSettings.setAutoFallbackModelsEnabled(context, true)
                AutoFallbackSettings.setAutoPickCheapestEnabled(context, false)
                AIProvider.setContextualPromptsEnabled(context, true)
                AIProvider.setVisionMode(context, "auto")
                AIProvider.setBrowserMode(context, "auto")
                reflection = true; planning = true; verification = true
                alwaysLearn = true; knowledgeAutoRecall = true; consentEnabled = true
                contextualPrompts = true; visionMode = "auto"
                autoFallbackKeys = true; autoFallbackModels = true; autoPickCheapest = false
                browserMode = "auto"
                toast = "Reset to recommended defaults ✓"
            }, modifier = Modifier.fillMaxWidth()) {
                Text("Reset to recommended defaults", color = custom.accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
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

@Composable
private fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    val custom = LocalCustomColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(custom.ink2, RoundedCornerShape(8.dp))
            .border(1.dp, custom.inkBorder, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            color = custom.fg3,
            fontFamily = MonoFontFamily,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
        )
        content()
    }
}

@Composable
private fun SelectorRow(label: String, options: List<String>, selected: String, onSelect: (String) -> Unit) {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = label,
            color = m3.onSurface,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(options) { opt ->
                val active = opt == selected
                Row(
                    modifier = Modifier
                        .background(
                            if (active) custom.accent.copy(alpha = 0.15f) else custom.surface2,
                            RoundedCornerShape(14.dp),
                        )
                        .border(
                            1.dp,
                            if (active) custom.accent.copy(alpha = 0.5f) else custom.border,
                            RoundedCornerShape(14.dp),
                        )
                        .clickable { onSelect(opt) }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (active) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(custom.accent, CircleShape),
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        text = opt,
                        color = if (active) custom.accent else custom.fg2,
                        fontFamily = MonoFontFamily,
                        fontSize = 11.sp,
                        fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, desc: String, checked: Boolean, onCheck: (Boolean) -> Unit) {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = m3.onSurface,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.size(2.dp))
            Text(
                text = desc,
                color = custom.fg2,
                fontSize = 10.sp,
                lineHeight = 13.sp,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheck,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = custom.accent,
                uncheckedThumbColor = custom.fg3,
                uncheckedTrackColor = custom.surface3,
            ),
        )
    }
}

/**
 * Task 41: Theme selector — System / Light / Dark.
 * Reads + writes [ThemeManager.mode] so the rest of the app recomposes
 * immediately when the user picks a different mode.
 */
@Composable
private fun ThemeSelectorRow(
    current: String,
    onSelect: (String) -> Unit,
) {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme
    val options = listOf("system", "light", "dark")

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "Theme",
            color = m3.onSurface,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = "Override the system theme — applies instantly.",
            color = custom.fg2,
            fontSize = 10.sp,
            lineHeight = 13.sp,
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(options) { opt ->
                val active = opt == current
                Row(
                    modifier = Modifier
                        .background(
                            if (active) custom.accent.copy(alpha = 0.15f) else custom.surface2,
                            RoundedCornerShape(14.dp),
                        )
                        .border(
                            1.dp,
                            if (active) custom.accent.copy(alpha = 0.5f) else custom.border,
                            RoundedCornerShape(14.dp),
                        )
                        .clickable { onSelect(opt) }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (active) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(custom.accent, CircleShape),
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        text = opt,
                        color = if (active) custom.accent else custom.fg2,
                        fontFamily = MonoFontFamily,
                        fontSize = 11.sp,
                        fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

@Composable
private fun AboutCard() {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(custom.ink2, RoundedCornerShape(8.dp))
            .border(1.dp, custom.inkBorder, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "ABOUT",
            color = custom.fg3,
            fontFamily = MonoFontFamily,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .background(custom.accent, RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text("A", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Aira",
                color = m3.onSurface,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        AboutRow(label = "subtitle", value = "Personal AI Assistant")
        AboutRow(label = "tagline", value = "Your intelligent companion for seamless device control and daily tasks.")
        AboutRow(label = "version", value = "2.0.9")
        AboutRow(label = "build", value = "9")
        AboutRow(label = "min sdk", value = "24 (Android 7.0)")
        AboutRow(label = "target sdk", value = "34 (Android 14)")
        AboutRow(label = "repo", value = "github.com/arun6a/ai-agent-v2")
    }
}

@Composable
private fun AboutRow(label: String, value: String) {
    val custom = LocalCustomColors.current
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            color = custom.fg3,
            fontFamily = MonoFontFamily,
            fontSize = 10.sp,
            modifier = Modifier.width(80.dp),
        )
        Text(
            text = value,
            color = custom.fg2,
            fontFamily = MonoFontFamily,
            fontSize = 10.sp,
        )
    }
}

// v3.5.1: AI toggle row — title + one-line description + Switch
@Composable
private fun AiToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheck: (Boolean) -> Unit,
) {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = m3.onSurface, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(description, color = custom.fg3, fontSize = 9.sp, lineHeight = 12.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheck,
            colors = SwitchDefaults.colors(
                checkedThumbColor = androidx.compose.ui.graphics.Color.White,
                checkedTrackColor = custom.accent,
            ),
        )
    }
}

// v3.5.1: Model slot row — shows a label + a model picker (radio list of saved models)
@Composable
private fun ModelSlotRow(
    label: String,
    models: List<SavedModelsStore.SavedModel>,
    selectedId: String?,
    defaultId: String?,
    onSelect: (String) -> Unit,
) {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, color = m3.onSurface, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        if (models.isEmpty()) {
            Text("No saved models. Add from Model Catalog first.",
                color = custom.fg3, fontSize = 9.sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
        } else {
            models.take(5).forEach { model ->
                val isSelected = model.id == selectedId
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clickable { onSelect(model.id) }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(if (isSelected) "●" else "○",
                        color = if (isSelected) custom.accent else custom.fg3, fontSize = 10.sp)
                    Spacer(Modifier.width(6.dp))
                    Text(model.modelName,
                        color = if (isSelected) m3.onSurface else custom.fg3,
                        fontSize = 10.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal)
                    if (model.id == defaultId) {
                        Text(" DEFAULT", color = custom.accent, fontSize = 7.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/**
 * v4.0.32: Search API Keys row — lets the user add Brave + Google Custom Search API keys.
 * When keys are set, the AI can use braveSearch() and googleSearchApi() for reliable,
 * CAPTCHA-free search results (no scraping).
 */
@Composable
private fun BraveApiKeyRow(context: android.content.Context, onToast: (String) -> Unit) {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme
    var showKeys by remember { mutableStateOf(false) }
    var braveKey by remember { mutableStateOf(com.ai.agent.llm.AIProvider.getBraveSearchApiKey(context)) }
    var googleKey by remember { mutableStateOf(com.ai.agent.llm.AIProvider.getGoogleSearchApiKey(context)) }
    var googleCxId by remember { mutableStateOf(com.ai.agent.llm.AIProvider.getGoogleSearchEngineId(context)) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Header row with toggle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showKeys = !showKeys }
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    "Search API Keys",
                    color = m3.onSurface,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    "Brave (2000/mo free) + Google CSE (100/day free) — enables reliable search",
                    color = m3.onSurface.copy(alpha = 0.5f),
                    fontSize = 10.sp,
                )
            }
            Text(
                if (showKeys) "▲" else "▼",
                color = m3.onSurface.copy(alpha = 0.5f),
                fontSize = 12.sp,
            )
        }

        // Status badges
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val braveSet = braveKey.isNotEmpty()
            val googleSet = googleKey.isNotEmpty() && googleCxId.isNotEmpty()
            StatusBadge("Brave ${if (braveSet) "✓" else "✗"}", braveSet, custom)
            StatusBadge("Google ${if (googleSet) "✓" else "✗"}", googleSet, custom)
        }

        if (showKeys) {
            // Brave API Key
            Text("Brave Search API Key", color = m3.onSurface, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            Text("Get free key (2000 queries/month): api.search.brave.com",
                color = m3.onSurface.copy(alpha = 0.4f), fontSize = 9.sp)
            OutlinedTextField(
                value = braveKey,
                onValueChange = {
                    braveKey = it
                    com.ai.agent.llm.AIProvider.setBraveSearchApiKey(context, it)
                },
                label = { Text("Brave API Key") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Google API Key
            Text("Google Custom Search API Key", color = m3.onSurface, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            Text("Get key: developers.google.com/custom-search/v1/overview",
                color = m3.onSurface.copy(alpha = 0.4f), fontSize = 9.sp)
            OutlinedTextField(
                value = googleKey,
                onValueChange = {
                    googleKey = it
                    com.ai.agent.llm.AIProvider.setGoogleSearchApiKey(context, it)
                },
                label = { Text("Google API Key") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Google Engine ID (cx)
            Text("Google Search Engine ID (cx)", color = m3.onSurface, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            Text("Create engine: programmablesearchengine.google.com",
                color = m3.onSurface.copy(alpha = 0.4f), fontSize = 9.sp)
            OutlinedTextField(
                value = googleCxId,
                onValueChange = {
                    googleCxId = it
                    com.ai.agent.llm.AIProvider.setGoogleSearchEngineId(context, it)
                },
                label = { Text("Engine ID (cx)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.TextButton(onClick = {
                    braveKey = ""
                    googleKey = ""
                    googleCxId = ""
                    com.ai.agent.llm.AIProvider.setBraveSearchApiKey(context, "")
                    com.ai.agent.llm.AIProvider.setGoogleSearchApiKey(context, "")
                    com.ai.agent.llm.AIProvider.setGoogleSearchEngineId(context, "")
                    onToast("Search API keys cleared")
                }) {
                    Text("Clear All", color = m3.error)
                }
                androidx.compose.material3.TextButton(onClick = {
                    onToast("Keys saved. AI can now use braveSearch() + googleSearchApi()")
                }) {
                    Text("Done", color = custom.accent)
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(text: String, active: Boolean, custom: com.ai.agent.ui.components.CustomColors) {
    Text(
        text = text,
        color = if (active) custom.accent else custom.surface2,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .background(
                if (active) custom.accent.copy(alpha = 0.1f) else custom.surface2.copy(alpha = 0.3f),
                androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

// ============================================================================
// v4.0.75: AI BEHAVIOR SECTION — Prompt architecture + presets
// ============================================================================
// 3 Presets (Fast / Balanced / Smart) + Advanced toggles for power users.
// Controls: on-demand tools, thinking mode, intent detection, memory/skills
// injection, conversation compression, max iterations.
// ============================================================================

@Composable
private fun AiBehaviorSection(context: android.content.Context, onToast: (String) -> Unit) {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme
    var currentPreset by remember { mutableStateOf(AIProvider.getPreset(context)) }
    var showAdvanced by remember { mutableStateOf(currentPreset == AIProvider.PRESET_ADVANCED) }

    // Flag states (read from AIProvider)
    var capabilityLevels by remember { mutableStateOf(AIProvider.isCapabilityLevelsEnabled(context)) }
    var combinedApproach by remember { mutableStateOf(AIProvider.isCombinedApproachEnabled(context)) }
    var onDemand by remember { mutableStateOf(AIProvider.isOnDemandEnabled(context)) }
    var patternDetection by remember { mutableStateOf(AIProvider.isPatternDetectionEnabled(context)) }
    var llmIntent by remember { mutableStateOf(AIProvider.isLlmIntentEnabled(context)) }
    var memoryInject by remember { mutableStateOf(AIProvider.isMemoryInjectEnabled(context)) }
    var skillsInject by remember { mutableStateOf(AIProvider.isSkillsInjectEnabled(context)) }
    var autoExpand by remember { mutableStateOf(AIProvider.isAutoExpandEnabled(context)) }
    var failureRules by remember { mutableStateOf(AIProvider.isFailureRulesEnabled(context)) }
    var thinkingMode by remember { mutableStateOf(AIProvider.isThinkingEnabled(context)) }
    var todoPlanning by remember { mutableStateOf(AIProvider.isTodoPlanningEnabled(context)) }
    var convCompression by remember { mutableStateOf(AIProvider.isConvCompressionEnabled(context)) }
    var maxIters by remember { mutableStateOf(AIProvider.getMaxIterations(context)) }

    SettingsGroup(title = "AI BEHAVIOR") {
        // === Presets ===
        Text(
            text = "Preset",
            color = m3.onSurface,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.size(4.dp))
        val presets = listOf(
            Triple(AIProvider.PRESET_FAST, "Fast", "Minimal reasoning, all tools upfront. Cheapest."),
            Triple(AIProvider.PRESET_BALANCED, "Balanced", "Reasoning + smart classification. Recommended."),
            Triple(AIProvider.PRESET_SMART, "Smart", "On-demand everything. Max token savings."),
        )
        presets.forEach { (id, name, desc) ->
            val active = currentPreset == id
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (active) custom.accent.copy(alpha = 0.15f) else custom.surface2,
                        RoundedCornerShape(12.dp),
                    )
                    .border(
                        1.dp,
                        if (active) custom.accent.copy(alpha = 0.5f) else custom.border,
                        RoundedCornerShape(12.dp),
                    )
                    .clickable {
                        currentPreset = id
                        AIProvider.applyPreset(context, id)
                        // Refresh all flag states
                        capabilityLevels = AIProvider.isCapabilityLevelsEnabled(context)
                        combinedApproach = AIProvider.isCombinedApproachEnabled(context)
                        onDemand = AIProvider.isOnDemandEnabled(context)
                        patternDetection = AIProvider.isPatternDetectionEnabled(context)
                        llmIntent = AIProvider.isLlmIntentEnabled(context)
                        memoryInject = AIProvider.isMemoryInjectEnabled(context)
                        skillsInject = AIProvider.isSkillsInjectEnabled(context)
                        autoExpand = AIProvider.isAutoExpandEnabled(context)
                        failureRules = AIProvider.isFailureRulesEnabled(context)
                        thinkingMode = AIProvider.isThinkingEnabled(context)
                        todoPlanning = AIProvider.isTodoPlanningEnabled(context)
                        convCompression = AIProvider.isConvCompressionEnabled(context)
                        showAdvanced = false
                        onToast("Preset: $name")
                    }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = name, color = m3.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    Text(text = desc, color = custom.fg2, fontSize = 10.sp, lineHeight = 13.sp)
                }
                if (active) {
                    Text("✓", color = custom.accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.size(4.dp))
        }

        // === Advanced toggle ===
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showAdvanced = !showAdvanced; if (showAdvanced) currentPreset = AIProvider.PRESET_ADVANCED }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (showAdvanced) "▼ Advanced" else "▶ Advanced",
                color = custom.accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
        }

        // === Advanced toggles ===
        if (showAdvanced) {
            Spacer(Modifier.size(8.dp))
            Text(
                text = "Prompt Architecture",
                color = custom.fg2,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.size(4.dp))
            AiToggleRow("Capability Levels", "Classify message complexity (Level 1/2/3) for prompt + tool budget.", capabilityLevels) {
                capabilityLevels = it; AIProvider.setCapabilityLevelsEnabled(context, it); onToast("Capability levels: $it")
            }
            AiToggleRow("Combined Approach", "Merge levels (budget) + contextual groups (relevance) for best tool set.", combinedApproach) {
                combinedApproach = it; AIProvider.setCombinedApproachEnabled(context, it); onToast("Combined approach: $it")
            }
            AiToggleRow("On-Demand Tools", "Send only ~35 tools upfront. Model searches for the rest. Saves 60-80% tokens.", onDemand) {
                onDemand = it; AIProvider.setOnDemandEnabled(context, it); onToast("On-demand tools: $it")
            }

            Spacer(Modifier.size(8.dp))
            Text("Intent Detection", color = custom.fg2, fontSize = 10.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.size(4.dp))
            AiToggleRow("Pattern Detection", "Detect 'difference between', 'how does', 'don't' → no tools (saves ~2400 tokens).", patternDetection) {
                patternDetection = it; AIProvider.setPatternDetectionEnabled(context, it); onToast("Pattern detection: $it")
            }
            AiToggleRow("LLM Intent Classifier", "~100-token call to classify ambiguous messages as ACTION/QUESTION/COMPARISON/PROHIBITION.", llmIntent) {
                llmIntent = it; AIProvider.setLlmIntentEnabled(context, it); onToast("LLM intent: $it")
            }

            Spacer(Modifier.size(8.dp))
            Text("Context Injection", color = custom.fg2, fontSize = 10.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.size(4.dp))
            AiToggleRow("Auto-Inject Memory", "Inject top 10 memories via FTS5 into every prompt.", memoryInject) {
                memoryInject = it; AIProvider.setMemoryInjectEnabled(context, it); onToast("Memory inject: $it")
            }
            AiToggleRow("Auto-Inject Skills", "Inject full skill bodies by keyword match.", skillsInject) {
                skillsInject = it; AIProvider.setSkillsInjectEnabled(context, it); onToast("Skills inject: $it")
            }
            AiToggleRow("Auto-Expand Tools", "Auto-add tool's group when model calls a missing tool.", autoExpand) {
                autoExpand = it; AIProvider.setAutoExpandEnabled(context, it); onToast("Auto-expand: $it")
            }
            AiToggleRow("Failure Rules", "Inject retry/failure guidance contextually (iteration 2/3/4).", failureRules) {
                failureRules = it; AIProvider.setFailureRulesEnabled(context, it); onToast("Failure rules: $it")
            }

            Spacer(Modifier.size(8.dp))
            Text("Reasoning & Planning", color = custom.fg2, fontSize = 10.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.size(4.dp))
            AiToggleRow("Thinking Mode", "Enable chain-of-thought reasoning before responding.", thinkingMode) {
                thinkingMode = it; AIProvider.setThinkingEnabled(context, it); onToast("Thinking mode: $it")
            }
            AiToggleRow("Todo Planning", "Enforce todoWrite for complex tasks (3+ steps). Like a real agent.", todoPlanning) {
                todoPlanning = it; AIProvider.setTodoPlanningEnabled(context, it); onToast("Todo planning: $it")
            }
            AiToggleRow("Conversation Compression", "Summarize old iterations to cap token cost on long tasks.", convCompression) {
                convCompression = it; AIProvider.setConvCompressionEnabled(context, it); onToast("Conv compression: $it")
            }

            Spacer(Modifier.size(8.dp))
            Text("Limits", color = custom.fg2, fontSize = 10.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.size(4.dp))
            SelectorRow(
                label = "Max iterations per task",
                options = listOf("5", "10", "20", "40", "80", "120"),
                selected = maxIters.toString(),
                onSelect = {
                    maxIters = it.toInt()
                    AIProvider.setMaxIterations(context, it.toInt())
                    onToast("Max iterations: $it")
                },
            )
        }
    }
}

// ============================================================================
// v4.0.77: PROACTIVE BEHAVIOR SECTION — Aira suggests actions based on patterns
// ============================================================================
// Master toggle, quiet hours, auto-execute, personality prompt textarea.
// ============================================================================

@Composable
private fun ProactiveBehaviorSection(context: android.content.Context, onToast: (String) -> Unit) {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme

    var enabled by remember { mutableStateOf(com.ai.agent.proactive.ProactiveSettings.isEnabled(context)) }
    var autoExecute by remember { mutableStateOf(com.ai.agent.proactive.ProactiveSettings.isAutoExecuteEnabled(context)) }
    var quietStart by remember { mutableStateOf(com.ai.agent.proactive.ProactiveSettings.getQuietStart(context)) }
    var quietEnd by remember { mutableStateOf(com.ai.agent.proactive.ProactiveSettings.getQuietEnd(context)) }
    var tickInterval by remember { mutableStateOf(com.ai.agent.proactive.ProactiveSettings.getTickIntervalMin(context)) }
    var personality by remember { mutableStateOf(com.ai.agent.proactive.ProactiveSettings.getPersonality(context)) }
    var showPersonality by remember { mutableStateOf(false) }

    SettingsGroup(title = "PROACTIVE BEHAVIOR") {
        AiToggleRow(
            "Enable Proactive Suggestions",
            "Aira observes your patterns + suggests actions based on context (time, battery, location).",
            enabled
        ) {
            enabled = it
            com.ai.agent.proactive.ProactiveSettings.setEnabled(context, it)
            if (it) {
                com.ai.agent.proactive.ProactiveScheduler.schedule(context)
                onToast("Proactive enabled — tick every $tickInterval min")
            } else {
                com.ai.agent.proactive.ProactiveScheduler.cancel(context)
                onToast("Proactive disabled")
            }
        }

        if (enabled) {
            Spacer(Modifier.size(8.dp))

            // Quiet hours
            Text("Quiet Hours", color = custom.fg2, fontSize = 10.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.size(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("No suggestions from", color = m3.onSurface, fontSize = 11.sp)
                SelectorRow(
                    label = "Start",
                    options = listOf("20:00", "21:00", "22:00", "23:00", "00:00"),
                    selected = quietStart,
                    onSelect = { quietStart = it; com.ai.agent.proactive.ProactiveSettings.setQuietHours(context, it, quietEnd) },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("until", color = m3.onSurface, fontSize = 11.sp)
                SelectorRow(
                    label = "End",
                    options = listOf("05:00", "06:00", "07:00", "08:00", "09:00"),
                    selected = quietEnd,
                    onSelect = { quietEnd = it; com.ai.agent.proactive.ProactiveSettings.setQuietHours(context, quietStart, it) },
                )
            }

            Spacer(Modifier.size(8.dp))

            // Tick interval
            SelectorRow(
                label = "Check interval (minutes)",
                options = listOf("1", "5", "10", "15", "30"),
                selected = tickInterval.toString(),
                onSelect = {
                    tickInterval = it.toInt()
                    com.ai.agent.proactive.ProactiveSettings.setTickIntervalMin(context, it.toInt())
                    com.ai.agent.proactive.ProactiveScheduler.schedule(context)
                    onToast("Tick interval: $it min")
                },
            )

            Spacer(Modifier.size(8.dp))

            AiToggleRow(
                "Auto-execute trusted patterns",
                "Automatically run actions the AI is confident about (e.g. 'play your evening playlist'). OFF = suggest only.",
                autoExecute
            ) {
                autoExecute = it
                com.ai.agent.proactive.ProactiveSettings.setAutoExecute(context, it)
                onToast("Auto-execute: $it")
            }

            Spacer(Modifier.size(8.dp))

            // Personality prompt
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showPersonality = !showPersonality }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (showPersonality) "▼ Proactive Personality" else "▶ Proactive Personality",
                    color = custom.accent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            if (showPersonality) {
                Text(
                    "Describe what Aira should proactively do for you in natural language.",
                    color = custom.fg2,
                    fontSize = 10.sp,
                )
                Spacer(Modifier.size(4.dp))
                OutlinedTextField(
                    value = personality,
                    onValueChange = { personality = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 100.dp),
                    placeholder = { Text("Example: Remind me to call Mom every Sunday 7pm. If battery < 30%, suggest power saving. If I open YouTube after 10pm, suggest I sleep.") },
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, color = m3.onSurface),
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = custom.accent,
                        unfocusedBorderColor = custom.border,
                    ),
                )
                Spacer(Modifier.size(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Button(
                        onClick = {
                            com.ai.agent.proactive.ProactiveSettings.setPersonality(context, personality)
                            onToast("Personality saved (${personality.length} chars)")
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = custom.accent),
                    ) {
                        Text("Save", color = Color.White, fontSize = 11.sp)
                    }
                }
            }

            Spacer(Modifier.size(8.dp))

            // Clear patterns
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        com.ai.agent.proactive.ProactiveObserver.clearAll(context)
                        onToast("All patterns cleared")
                    }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Clear all learned patterns",
                    color = androidx.compose.ui.graphics.Color(0xFFEF4444),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}
