package com.ai.agent.llm.settings

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ai.agent.llm.ModelCapability
import com.ai.agent.llm.SavedModelsStore

// === SECTION 4: AUDIO CONFIGURATION ===
@Composable
internal fun AudioConfigPage() {
    val context = LocalContext.current
    val audioModels = SavedModelsStore.getModelsByCapabilities(ModelCapability.AUDIO_TAGS)
    val audioModel = remember { SavedModelsStore.getAllModels().find { it.id == context.getSharedPreferences("ai_agent_saved_models", Context.MODE_PRIVATE).getString("audio_model_id", "") } }
    var ttsEnabled by remember { mutableStateOf(context.getSharedPreferences("ai_agent_prefs", Context.MODE_PRIVATE).getBoolean("tts_enabled", false)) }

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
            context.getSharedPreferences("ai_agent_prefs", Context.MODE_PRIVATE)
                .edit().putBoolean("tts_enabled", it).apply()
        },
    )
}
