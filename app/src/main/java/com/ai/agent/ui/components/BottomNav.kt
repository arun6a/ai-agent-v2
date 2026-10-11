package com.ai.agent.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Settings
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
// BottomNav — Material 3 NavigationBar with 4 items:
//   Chat  ·  Browse  ·  Hub  ·  Settings
//
// "Settings" routes to the Hub screen with the Settings section pre-selected
// (same destination, different starting section) — the Hub screen handles it.
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
}

private data class NavItem(
    val label: String,
    val icon: ImageVector,
    val route: String,
)

private val NAV_ITEMS = listOf(
    NavItem("Chat", Icons.Outlined.ChatBubbleOutline, Routes.CHAT),
    NavItem("Browse", Icons.Outlined.Public, Routes.BROWSER),
    NavItem("Hub", Icons.Outlined.Hub, Routes.HUB),
    NavItem("Settings", Icons.Outlined.Settings, Routes.HUB_SETTINGS),
)

@Composable
fun BottomNav(
    currentRoute: String?,
    onNavigate: (route: String) -> Unit,
) {
    val custom = LocalCustomColors.current

    // Treat hub and hub/settings as the same nav target so both highlight "Hub".
    // The Settings tab highlights itself when currentRoute == hub/settings.
    fun isSelected(item: NavItem): Boolean {
        return when (item.route) {
            Routes.HUB -> currentRoute == Routes.HUB
            Routes.HUB_SETTINGS -> currentRoute == Routes.HUB_SETTINGS
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
