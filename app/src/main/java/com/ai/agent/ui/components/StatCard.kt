package com.ai.agent.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.agent.ui.theme.MonoFontFamily

// ============================================================================
// StatCard — small stat box used in the Hub footer / sections.
// Renders a label, big mono value, and optional sublabel (mono small).
// ============================================================================

@Composable
fun StatCard(
    label: String,
    value: String,
    sublabel: String? = null,
    valueColor: Color? = null,
    modifier: Modifier = Modifier,
) {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(custom.ink2, RoundedCornerShape(8.dp))
            .border(1.dp, custom.inkBorder, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(
            text = label.uppercase(),
            color = custom.fg3,
            fontFamily = MonoFontFamily,
            fontSize = 10.sp,
        )
        Spacer(Modifier.size(2.dp))
        Text(
            text = value,
            color = valueColor ?: m3.onSurface,
            fontFamily = MonoFontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
        )
        if (sublabel != null) {
            Spacer(Modifier.size(2.dp))
            Text(
                text = sublabel,
                color = custom.fg3,
                fontFamily = MonoFontFamily,
                fontSize = 10.sp,
            )
        }
    }
}
