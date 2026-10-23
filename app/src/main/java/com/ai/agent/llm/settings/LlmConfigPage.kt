package com.ai.agent.llm.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.agent.llm.AIProvider
import com.ai.agent.llm.ModelCapability
import com.ai.agent.llm.SavedModelsStore
import com.ai.agent.ui.components.LocalCustomColors
import com.ai.agent.ui.theme.MonoFontFamily
import kotlinx.coroutines.launch

// === SECTION 2: LLM CONFIGURATION ===
@Composable
internal fun LlmConfigPage() {
    val context = LocalContext.current
    val custom = LocalCustomColors.current
    val scope = rememberCoroutineScope()
    val llmModels = SavedModelsStore.getModelsByCapabilities(ModelCapability.LLM_TAGS)
    val defaultModel = SavedModelsStore.getDefaultModel(context)
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
