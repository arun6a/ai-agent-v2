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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.agent.llm.AIProvider
import com.ai.agent.llm.ModelCapability
import com.ai.agent.llm.SavedModelsStore
import com.ai.agent.ui.components.LocalCustomColors

// === SECTION 3: VISION CONFIGURATION ===
@Composable
internal fun VisionConfigPage() {
    val context = LocalContext.current
    val visionModels = SavedModelsStore.getModelsByCapability(ModelCapability.VISION)
    val visionModel = SavedModelsStore.getVisionModel(context)
    var visionMode by remember { mutableStateOf(AIProvider.getVisionMode(context)) }

    ConfigSectionHeader("Vision Configuration", "VLM model for screen reading fallback")
    ModelSelectorRow(
        label = "Vision Model",
        models = visionModels,
        selectedId = visionModel?.id,
        onSelect = { id ->
            SavedModelsStore.setVisionModel(context, id)
        },
    )
    Spacer(Modifier.height(4.dp))
    // Vision mode selector
    val modes = listOf("auto" to "Auto (a11y→ML Kit→VLM)", "mlkit" to "ML Kit OCR only", "vlm" to "VLM only")
    modes.forEach { (mode, desc) ->
        Row(
            modifier = Modifier.fillMaxWidth()
                .background(if (visionMode == mode) LocalCustomColors.current.accent.copy(alpha = 0.08f) else LocalCustomColors.current.ink2, RoundedCornerShape(8.dp))
                .border(1.dp, if (visionMode == mode) LocalCustomColors.current.accent.copy(alpha = 0.4f) else LocalCustomColors.current.inkBorder, RoundedCornerShape(8.dp))
                .clickable { visionMode = mode; AIProvider.setVisionMode(context, mode) }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (visionMode == mode) "●" else "○", color = if (visionMode == mode) LocalCustomColors.current.accent else LocalCustomColors.current.fg3, fontSize = 12.sp)
            Spacer(Modifier.width(8.dp))
            Text(desc, color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp)
        }
    }
}
