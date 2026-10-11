package com.ai.agent.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.ai.agent.ui.components.BottomNav
import com.ai.agent.ui.components.LocalCustomColors
import com.ai.agent.ui.components.Routes
import com.ai.agent.ui.screens.BrowserScreen
import com.ai.agent.ui.screens.ChatScreen
import com.ai.agent.ui.screens.HubScreen
import com.ai.agent.ui.screens.HubSectionArg

// ============================================================================
// AppNavigation — root nav graph for the v2 app.
// ----------------------------------------------------------------------------
// Routes:
//   chat      → ChatScreen
//   browser   → BrowserScreen (placeholder)
//   hub       → HubScreen (default section: Skills)
//   hub/settings → HubScreen (default section: Settings)
//
// The Chat tab passes an onOpenHub callback that navigates to hub,
// so the top-bar hub button is wired end-to-end.
// ============================================================================

@Composable
fun AppNavigation() {
    val custom = LocalCustomColors.current
    val nav: NavHostController = rememberNavController()

    val backstack by nav.currentBackStackEntryAsState()
    val currentRoute = backstack?.destination?.route

    Scaffold(
        containerColor = custom.canvas,
        bottomBar = {
            BottomNav(
                currentRoute = currentRoute,
                onNavigate = { route ->
                    // Don't re-navigate to the same destination.
                    if (route != currentRoute) {
                        nav.navigate(route) {
                            // Pop back to the start destination to avoid a long stack.
                            popUpTo(nav.graph.startDestinationId) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                },
            )
        },
    ) { innerPadding: PaddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(custom.canvas)
                .padding(innerPadding),
        ) {
            NavHost(
                navController = nav,
                startDestination = Routes.CHAT,
            ) {
                composable(Routes.CHAT) {
                    ChatScreen(
                        onOpenHub = {
                            nav.navigate(Routes.HUB) {
                                popUpTo(nav.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                    )
                }
                composable(Routes.BROWSER) {
                    BrowserScreen()
                }
                composable(Routes.HUB) {
                    HubScreen(initialSection = HubSectionArg.SKILLS)
                }
                composable(Routes.HUB_SETTINGS) {
                    HubScreen(initialSection = HubSectionArg.SETTINGS)
                }
            }
        }
    }
}
