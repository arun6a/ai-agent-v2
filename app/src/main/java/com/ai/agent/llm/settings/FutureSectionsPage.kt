package com.ai.agent.llm.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import com.ai.agent.llm.ModelCapability
import com.ai.agent.llm.SavedModelsStore
import com.ai.agent.ui.components.LocalCustomColors
import com.ai.agent.ui.theme.MonoFontFamily

// === SECTION 5-7: AI CAPABILITY CONFIGURATION (Image/Video/Coding) ===
// v4.1.2: Activated — replaces "Coming Soon" with working model selectors.
@Composable
internal fun FutureSectionsPage() {
    val context = LocalContext.current
    val custom = LocalCustomColors.current
    ConfigSectionHeader("AI Capabilities", "Configure models for image, video & code generation")

    // === Image Generation ===
    val imageGenModels = remember { SavedModelsStore.getModelsByCapability(ModelCapability.IMAGE_GEN) }
    val prefs = context.getSharedPreferences("ai_agent_saved_models", android.content.Context.MODE_PRIVATE)
    var selectedImageGenId by remember { mutableStateOf(prefs.getString("image_gen_model_id", "") ?: "") }

    ConfigSubHeader("🖼️ Image Generation", "Model for generateImage() tool")
    if (imageGenModels.isEmpty()) {
        ConfigInfoBox("No IMAGE_GEN model saved. Aira will use free Pollinations.ai API for image generation. Add a model with IMAGE_GEN capability in the Model Catalog.")
    } else {
        ModelPickerRow(
            label = "Image Generation Model",
            models = imageGenModels,
            selectedId = selectedImageGenId,
            onSelect = { id ->
                selectedImageGenId = id
                prefs.edit().putString("image_gen_model_id", id).apply()
            },
        )
        if (selectedImageGenId.isEmpty()) {
            ConfigInfoBox("No model selected — using Pollinations.ai (free, no API key needed)")
        }
    }

    Spacer(Modifier.height(12.dp))

    // === Video Generation ===
    val videoModels = remember { SavedModelsStore.getModelsByCapability(ModelCapability.VIDEO) }
    var selectedVideoId by remember { mutableStateOf(prefs.getString("video_model_id", "") ?: "") }

    ConfigSubHeader("🎬 Video Generation", "Model for future generateVideo() tool")
    if (videoModels.isEmpty()) {
        ConfigInfoBox("No VIDEO model saved. Video generation is not yet available. Add a model with VIDEO capability when providers support it.")
    } else {
        ModelPickerRow(
            label = "Video Generation Model",
            models = videoModels,
            selectedId = selectedVideoId,
            onSelect = { id ->
                selectedVideoId = id
                prefs.edit().putString("video_model_id", id).apply()
            },
        )
    }

    Spacer(Modifier.height(12.dp))

    // === Coding Assistant ===
    val codingModels = remember { SavedModelsStore.getModelsByCapability(ModelCapability.CODING) }
    var selectedCodingId by remember { mutableStateOf(prefs.getString("coding_model_id", "") ?: "") }

    ConfigSubHeader("💻 Coding Assistant", "Model optimized for code generation")
    if (codingModels.isEmpty()) {
        ConfigInfoBox("No CODING model saved. The default chat model will be used for code tasks. Add a model with CODING capability for better code generation.")
    } else {
        ModelPickerRow(
            label = "Coding Model",
            models = codingModels,
            selectedId = selectedCodingId,
            onSelect = { id ->
                selectedCodingId = id
                prefs.edit().putString("coding_model_id", id).apply()
            },
        )
    }

    Spacer(Modifier.height(8.dp))

    // Info footer
    ConfigInfoBox("💡 The generateImage tool automatically uses the selected IMAGE_GEN model. If no model is selected, it falls back to Pollinations.ai (free, no API key). The AI can also search the web for free image generation APIs if needed.")
}

@Composable
private fun ConfigSubHeader(title: String, subtitle: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Text(title, color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Text(subtitle, color = LocalCustomColors.current.fg3, fontSize = 10.sp)
    }
}

@Composable
private fun ConfigInfoBox(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(LocalCustomColors.current.ink2, RoundedCornerShape(8.dp))
            .border(1.dp, LocalCustomColors.current.inkBorder, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(text, color = LocalCustomColors.current.fg3, fontSize = 10.sp, lineHeight = 14.sp)
    }
}

@Composable
private fun ModelPickerRow(
    label: String,
    models: List<SavedModelsStore.SavedModel>,
    selectedId: String,
    onSelect: (String) -> Unit,
) {
    val custom = LocalCustomColors.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, color = custom.fg3, fontSize = 10.sp, modifier = Modifier.padding(bottom = 4.dp))
        models.forEach { model ->
            val isSelected = model.id == selectedId
            Row(
                modifier = Modifier.fillMaxWidth()
                    .background(if (isSelected) custom.accent.copy(alpha = 0.08f) else custom.ink2, RoundedCornerShape(8.dp))
                    .border(1.dp, if (isSelected) custom.accent.copy(alpha = 0.4f) else custom.inkBorder, RoundedCornerShape(8.dp))
                    .clickable { onSelect(model.id) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (isSelected) "●" else "○", color = if (isSelected) custom.accent else custom.fg3, fontSize = 12.sp)
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(model.modelName, color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    Text("${model.providerName} · ${model.tagStr}", color = custom.fg3, fontSize = 9.sp)
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}
