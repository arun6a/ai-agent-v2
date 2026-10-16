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
import com.ai.agent.llm.AIProvider
import com.ai.agent.ui.components.LocalCustomColors
import com.ai.agent.ui.theme.MonoFontFamily

// === SECTION 8: ADVANCED ===
@Composable
internal fun AdvancedConfigPage() {
    val context = LocalContext.current
    val custom = LocalCustomColors.current
    val provider = AIProvider.getCurrentProvider(context)
    val model = AIProvider.getModel(context)
    val endpoint = AIProvider.getEndpoint(context)

    ConfigSectionHeader("Advanced", "Endpoint + provider configuration")

    // Current provider info
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(custom.ink2, RoundedCornerShape(8.dp))
            .border(1.dp, custom.inkBorder, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Active Provider: ${provider.name}", color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text("Model: $model", color = custom.fg3, fontFamily = MonoFontFamily, fontSize = 10.sp)
            Text("Endpoint: ${endpoint.take(50)}...", color = custom.fg3, fontFamily = MonoFontFamily, fontSize = 9.sp, maxLines = 1)
        }
    }
    Spacer(Modifier.height(4.dp))

    // Legacy prompt mode selector
    Text("Prompt Mode (when Contextual is OFF)", color = custom.fg3, fontSize = 10.sp)
    val modes = listOf("smart" to "Smart (full, 11K)", "balanced" to "Balanced (1.5K)", "fast" to "Fast (750B)", "custom" to "Custom")
    var currentMode by remember { mutableStateOf(AIProvider.getPromptMode(context)) }
    modes.forEach { (mode, desc) ->
        Row(
            modifier = Modifier.fillMaxWidth()
                .background(if (currentMode == mode) custom.accent.copy(alpha = 0.08f) else custom.ink2, RoundedCornerShape(8.dp))
                .border(1.dp, if (currentMode == mode) custom.accent.copy(alpha = 0.4f) else custom.inkBorder, RoundedCornerShape(8.dp))
                .clickable { currentMode = mode; AIProvider.setPromptMode(context, mode) }
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (currentMode == mode) "●" else "○", color = if (currentMode == mode) custom.accent else custom.fg3, fontSize = 10.sp)
            Spacer(Modifier.width(8.dp))
            Text(desc, color = MaterialTheme.colorScheme.onSurface, fontSize = 11.sp)
        }
    }
    Spacer(Modifier.height(16.dp))
}
