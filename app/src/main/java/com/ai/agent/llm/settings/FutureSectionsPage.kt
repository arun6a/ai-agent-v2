package com.ai.agent.llm.settings

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

// === SECTION 5-7: AI CAPABILITY SECTIONS (Image / Video / Coding) ===
// v4.1.0: Replaced "Coming Soon" placeholders with working model selectors.
// Each section reads/writes its own SharedPreferences key so the user can
// pick which saved model handles that capability. The actual tools (generateImage,
// transcribeAudio, etc.) live in AICapabilityTools.kt.
@Composable
internal fun FutureSectionsPage() {
    val context = LocalContext.current

    ConfigSectionHeader("AI Capability Models", "Pick models for image, video, and code tasks")

    // Image Generation — models tagged IMAGE_GEN.
    CapabilitySection(
        context = context,
        icon = "🖼️",
        title = "Image Generation",
        description = "Used by the generateImage tool. Falls back to Pollinations.ai if no model is set.",
        capability = ModelCapability.IMAGE_GEN,
        prefKey = "image_gen_model_id",
    )

    Spacer(Modifier.height(8.dp))

    // Video Generation — models tagged VIDEO.
    CapabilitySection(
        context = context,
        icon = "🎬",
        title = "Video Generation",
        description = "Pick a model for future video-generation tasks.",
        capability = ModelCapability.VIDEO,
        prefKey = "video_model_id",
    )

    Spacer(Modifier.height(8.dp))

    // Coding Assistant — models tagged CODING.
    CapabilitySection(
        context = context,
        icon = "💻",
        title = "Coding Assistant",
        description = "Models good at writing, reviewing, and debugging code.",
        capability = ModelCapability.CODING,
        prefKey = "coding_model_id",
    )
}

/**
 * v4.1.0: A reusable section that shows models matching a capability and
 * lets the user pick one. Writes the selection to SharedPreferences under [prefKey].
 *
 * Mirrors the ModelSelectorRow pattern from VisionConfigPage.kt.
 */
@Composable
private fun CapabilitySection(
    context: Context,
    icon: String,
    title: String,
    description: String,
    capability: ModelCapability,
    prefKey: String,
) {
    val custom = LocalCustomColors.current
    val models = SavedModelsStore.getModelsByCapability(capability)
    val prefs = remember { context.getSharedPreferences("ai_agent_saved_models", Context.MODE_PRIVATE) }
    var selectedId by remember { mutableStateOf(prefs.getString(prefKey, "") ?: "") }

    // Section header strip (icon + title + description) — clickable to expand.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(custom.ink2, RoundedCornerShape(8.dp))
            .border(1.dp, custom.inkBorder, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(icon, fontSize = 16.sp)
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    description,
                    color = custom.fg3,
                    fontSize = 10.sp,
                )
            }
            if (selectedId.isNotEmpty()) {
                Text(
                    "● set",
                    color = custom.accent,
                    fontFamily = MonoFontFamily,
                    fontSize = 9.sp,
                )
            } else {
                Text(
                    "○ not set",
                    color = custom.fg3,
                    fontFamily = MonoFontFamily,
                    fontSize = 9.sp,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        // Inline model selector (reuses the shared ModelSelectorRow composable).
        ModelSelectorRow(
            label = title,
            models = models,
            selectedId = selectedId.ifEmpty { null },
            onSelect = { id ->
                selectedId = id
                prefs.edit().putString(prefKey, id).apply()
            },
        )
    }
}
