package com.ai.agent.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ============================================================================
// BottomNav — Material 3 NavigationBar with 3 items:
//   Chat  ·  Browse  ·  Aira Lab
//
// Settings tab was removed — Settings is available inside Aira Lab (the
// renamed Hub) as one of its 13 sections. Cleaner, less duplication.
// ============================================================================

/** Route identifiers used by AppNavigation. */
object Routes {
    const val SPLASH = "splash"
    const val ONBOARDING = "onboarding"
    const val CHAT = "chat"
    const val BROWSER = "browser"
    const val HUB = "hub"
    const val HUB_SETTINGS = "hub/settings"
    const val PROVIDER_SETTINGS = "provider_settings"
    const val BACKGROUND_TASKS = "background_tasks"
    const val SYSTEM_PROMPT_EDITOR = "system_prompt_editor"

    // Skill editor — argument pattern: "skill_editor/{skillName}/{isReadOnly}"
    // skillName = "__new__" for create mode (avoids nullable path segments).
    // isReadOnly = "1" or "0".
    const val SKILL_EDITOR = "skill_editor/{skillName}/{isReadOnly}"
    const val SKILL_EDITOR_NEW = "skill_editor/__new__/0"
    fun skillEditorRoute(skillName: String?, isReadOnly: Boolean): String {
        val name = if (skillName == null) "__new__" else skillName
        val ro = if (isReadOnly) "1" else "0"
        return "skill_editor/$name/$ro"
    }
}

private data class NavItem(
    val label: String,
    val icon: ImageVector,
    val route: String,
)

private val NAV_ITEMS = listOf(
    NavItem("Chat", Icons.Outlined.ChatBubbleOutline, Routes.CHAT),
    NavItem("Aira Lab", Icons.Outlined.Science, Routes.HUB),
)

@Composable
fun BottomNav(
    currentRoute: String?,
    onNavigate: (route: String) -> Unit,
) {
    val custom = LocalCustomColors.current

    // Aira Lab (Hub) highlights on both hub and hub/settings routes since
    // the Settings section lives inside Aira Lab now.
    fun isSelected(item: NavItem): Boolean {
        return when (item.route) {
            Routes.HUB -> currentRoute == Routes.HUB || currentRoute == Routes.HUB_SETTINGS
            else -> currentRoute == item.route
        }
    }

    NavigationBar(
        containerColor = custom.canvas,
        contentColor = custom.accent,
        tonalElevation = 0.dp,
    ) {
        NAV_ITEMS.forEach { item ->
            val selected = isSelected(item)
            NavigationBarItem(
                selected = selected,
                onClick = { onNavigate(item.route) },
                icon = {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = item.label,
                        tint = if (selected) custom.accent else custom.fg3,
                    )
                },
                label = {
                    Text(
                        text = item.label,
                        color = if (selected) custom.accent else custom.fg3,
                        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                        fontSize = 11.sp,
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    indicatorColor = custom.accent.copy(alpha = 0.10f),
                    selectedIconColor = custom.accent,
                    unselectedIconColor = custom.fg3,
                    selectedTextColor = custom.accent,
                    unselectedTextColor = custom.fg3,
                ),
            )
        }
    }
}
