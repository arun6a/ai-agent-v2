package com.ai.agent.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.agent.ui.theme.MonoFontFamily

// ============================================================================
// HubNavItem — single side-nav row for the Hub screen.
// ----------------------------------------------------------------------------
// Layout (mirrors `03-hub-screen.html` nav-item):
//   icon · label · (count badge on the right)
// Active state: emerald tint background + 2dp emerald left border accent.
// Idle state: transparent background + hairline border off.
// ============================================================================

@Composable
fun HubNavItem(
    label: String,
    icon: ImageVector,
    count: String? = null,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme

    val bg = if (active) custom.accent.copy(alpha = 0.10f) else Color.Transparent
    val fg = if (active) m3.onSurface else custom.fg2

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Active left accent border
        Box(
            modifier = Modifier
                .size(width = 2.dp, height = 18.dp)
                .background(if (active) custom.accent else Color.Transparent),
        )
        Spacer(Modifier.width(8.dp))
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (active) custom.accent else custom.fg3,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            color = fg,
            fontSize = 12.sp,
            fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
        )
        Spacer(Modifier.weight(1f))
        if (count != null) {
            Text(
                text = count,
                color = custom.fg3,
                fontFamily = MonoFontFamily,
                fontSize = 11.sp,
            )
        }
    }
}
