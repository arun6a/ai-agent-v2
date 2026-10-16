package com.ai.agent.llm.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.agent.llm.SavedModelsStore
import com.ai.agent.ui.components.LocalCustomColors
import com.ai.agent.ui.theme.MonoFontFamily

/**
 * Shared composables extracted from ModelSettingsScreen — used by multiple
 * configuration pages (Llm, Vision, Audio, Advanced, SmartRouting) and the
 * main settings section list.
 */

@Composable
fun SectionCard(icon: String, title: String, subtitle: String, onClick: () -> Unit) {
    val custom = LocalCustomColors.current
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(custom.ink2, RoundedCornerShape(8.dp))
            .border(1.dp, custom.inkBorder, RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(icon, fontSize = 18.sp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Text(subtitle, color = custom.fg3, fontSize = 10.sp)
        }
        Text("▶", color = custom.fg3, fontSize = 12.sp)
    }
}

@Composable
fun ConfigSectionHeader(title: String, subtitle: String) {
    val custom = LocalCustomColors.current
    Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp)) {
        Text(title, color = custom.fg2, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(subtitle, color = custom.fg3, fontSize = 10.sp)
        Spacer(Modifier.height(4.dp))
        Divider(color = custom.inkBorder, thickness = 1.dp)
    }
}

@Composable
fun ModelSelectorRow(
    label: String,
    models: List<SavedModelsStore.SavedModel>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onTest: ((SavedModelsStore.SavedModel) -> Unit)? = null,
    testingId: String? = null,
) {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        if (models.isEmpty()) {
            Text("No models available. Add models with this capability from the catalog.",
                color = custom.fg3, fontSize = 11.sp, modifier = Modifier.padding(8.dp))
        } else {
            models.forEach { model ->
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .background(if (model.id == selectedId) custom.accent.copy(alpha = 0.08f) else custom.ink2, RoundedCornerShape(8.dp))
                        .border(1.dp, if (model.id == selectedId) custom.accent.copy(alpha = 0.4f) else custom.inkBorder, RoundedCornerShape(8.dp))
                        .clickable { onSelect(model.id) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (model.id == selectedId) "●" else "○",
                        color = if (model.id == selectedId) custom.accent else custom.fg3,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(model.modelName, color = m3.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                        Text("${model.providerName} · ${model.tagStr}", color = custom.fg3, fontFamily = MonoFontFamily, fontSize = 9.sp)
                    }
                    if (onTest != null) {
                        TextButton(
                            onClick = { onTest(model) },
                            enabled = testingId != model.id,
                        ) {
                            Text(if (testingId == model.id) "..." else "Test", fontSize = 10.sp)
                        }
                    }
                    Text("${model.contextStr} · ${model.priceStr}", color = custom.fg3, fontFamily = MonoFontFamily, fontSize = 8.sp)
                }
            }
        }
    }
}

@Composable
fun ToggleRow(label: String, desc: String, checked: Boolean, onCheck: (Boolean) -> Unit) {
    val custom = LocalCustomColors.current
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(custom.ink2, RoundedCornerShape(8.dp))
            .border(1.dp, custom.inkBorder, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            Text(desc, color = custom.fg3, fontSize = 10.sp)
        }
        Switch(checked = checked, onCheckedChange = onCheck,
            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = custom.accent))
    }
}
