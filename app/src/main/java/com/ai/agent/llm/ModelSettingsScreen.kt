package com.ai.agent.llm

import android.widget.Toast
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.agent.llm.settings.AdvancedConfigPage
import com.ai.agent.llm.settings.ApiKeysTab
import com.ai.agent.llm.settings.AudioConfigPage
import com.ai.agent.llm.settings.BrainBehaviorPage
import com.ai.agent.llm.settings.FutureSectionsPage
import com.ai.agent.llm.settings.LlmConfigPage
import com.ai.agent.llm.settings.ModelCatalogTab
import com.ai.agent.llm.settings.SavedModelsTab
import com.ai.agent.llm.settings.SectionCard
import com.ai.agent.llm.settings.SmartRoutingPage
import com.ai.agent.llm.settings.VisionConfigPage
import com.ai.agent.ui.components.LocalCustomColors

/**
 * v3.2.2: ModelSettingsScreen — section-based navigation (no LazyColumn nesting).
 * Shows a list of sections. Click to open each section's content page.
 *
 * v3.3.14 (this file): refactored — tab/page implementations extracted into
 * com.ai.agent.llm.settings.* files. This file is now just the entry point +
 * the section list (top-level navigation). See settings/ for tab bodies.
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
                7 -> BrainBehaviorPage(onToast = { toast = it })  // v3.5.0: replaced Advanced with Brain Behavior
                8 -> SmartRoutingPage(onToast = { toast = it })  // v3.3.6
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
    6 -> "Coming Soon"; 7 -> "Brain Behavior"; 8 -> "Smart Routing"; else -> "AI Settings"
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
        item { SectionCard("🧠", "Brain Behavior", "How the AI thinks, plans, and learns") { onSectionClick(7) } }  // v3.5.0
        item { SectionCard("🧠", "Smart Routing", "Tier-based model selection + auto-fallback") { onSectionClick(8) } }  // v3.3.6
    }
}
