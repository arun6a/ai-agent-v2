package com.ai.agent.llm.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.agent.ui.components.LocalCustomColors
import com.ai.agent.ui.theme.MonoFontFamily

// === SECTION 5-7: FUTURE SECTIONS (Image/Video/Coding) ===
@Composable
internal fun FutureSectionsPage() {
    val custom = LocalCustomColors.current
    ConfigSectionHeader("Coming Soon", "Future AI capabilities")

    val futures = listOf(
        "🖼️ Image Generation" to "Generate images from text descriptions",
        "🎬 Video Generation" to "Create video content from prompts",
        "💻 Coding Assistant" to "Write, review, and debug code",
    )
    futures.forEach { (title, desc) ->
        Row(
            modifier = Modifier.fillMaxWidth()
                .background(custom.ink2, RoundedCornerShape(8.dp))
                .border(1.dp, custom.inkBorder, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("Soon", color = custom.fg3, fontFamily = MonoFontFamily, fontSize = 9.sp)
        }
        Spacer(Modifier.height(4.dp))
    }
}
