package com.ai.agent.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import com.ai.agent.ui.screens.OnboardingScreen
import com.ai.agent.ui.screens.ProviderSettingsScreen
import com.ai.agent.ui.screens.SplashScreen

// ============================================================================
// AppNavigation — root nav graph for the v2 app.
// ----------------------------------------------------------------------------
// Routes:
//   splash            → SplashScreen (cold start; auto-navigates after 1.5s)
//   onboarding        → OnboardingScreen (3-step first-run flow)
//   chat              → ChatScreen
//   browser           → BrowserScreen
//   hub               → HubScreen (default section: Skills)
//   hub/settings      → HubScreen (default section: Settings)
//   provider_settings → ProviderSettingsScreen (full-screen provider manager)
//
// The Splash route is the start destination. After ~1.5s the splash composable
// decides whether to send the user to onboarding or straight to chat.
// ============================================================================

@Composable
fun AppNavigation() {
    val custom = LocalCustomColors.current
    val nav: NavHostController = rememberNavController()

    val backstack by nav.currentBackStackEntryAsState()
    val currentRoute = backstack?.destination?.route

    // Bottom bar is shown only on the four primary tabs — not on splash /
    // onboarding / provider_settings (which are full-screen flows).
    val showBottomBar = currentRoute in setOf(
        Routes.CHAT, Routes.BROWSER, Routes.HUB, Routes.HUB_SETTINGS,
    )

    Scaffold(
        containerColor = custom.canvas,
        bottomBar = {
            if (showBottomBar) {
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
            }
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
                startDestination = Routes.SPLASH,
            ) {
                // ---- Splash ----
                composable(
                    route = Routes.SPLASH,
                    enterTransition = { fadeIn(tween(200)) },
                    exitTransition = { fadeOut(tween(200)) },
                ) {
                    SplashScreen(
                        onFinished = { goToOnboarding ->
                            if (goToOnboarding) {
                                nav.navigate(Routes.ONBOARDING) {
                                    popUpTo(Routes.SPLASH) { inclusive = true }
                                }
                            } else {
                                nav.navigate(Routes.CHAT) {
                                    popUpTo(Routes.SPLASH) { inclusive = true }
                                }
                            }
                        },
                    )
                }

                // ---- Onboarding ----
                composable(
                    route = Routes.ONBOARDING,
                    enterTransition = { fadeIn(tween(300)) + slideInVertically(tween(300)) { it / 4 } },
                    exitTransition = { fadeOut(tween(200)) },
                    popEnterTransition = { fadeIn(tween(300)) + slideInVertically(tween(300)) { -it / 4 } },
                    popExitTransition = { fadeOut(tween(200)) },
                ) {
                    OnboardingScreen(
                        onFinished = {
                            nav.navigate(Routes.CHAT) {
                                popUpTo(Routes.ONBOARDING) { inclusive = true }
                            }
                        },
                    )
                }

                // ---- Chat ----
                composable(
                    route = Routes.CHAT,
                    enterTransition = { fadeIn(tween(300)) + slideInVertically(tween(300)) { it / 4 } },
                    exitTransition = { fadeOut(tween(200)) },
                    popEnterTransition = { fadeIn(tween(300)) + slideInVertically(tween(300)) { -it / 4 } },
                    popExitTransition = { fadeOut(tween(200)) },
                ) {
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

                // ---- Browser ----
                composable(
                    route = Routes.BROWSER,
                    enterTransition = { fadeIn(tween(300)) + slideInVertically(tween(300)) { it / 4 } },
                    exitTransition = { fadeOut(tween(200)) },
                    popEnterTransition = { fadeIn(tween(300)) + slideInVertically(tween(300)) { -it / 4 } },
                    popExitTransition = { fadeOut(tween(200)) },
                ) {
                    BrowserScreen()
                }

                // ---- Hub ----
                composable(
                    route = Routes.HUB,
                    enterTransition = { fadeIn(tween(300)) + slideInVertically(tween(300)) { it / 4 } },
                    exitTransition = { fadeOut(tween(200)) },
                    popEnterTransition = { fadeIn(tween(300)) + slideInVertically(tween(300)) { -it / 4 } },
                    popExitTransition = { fadeOut(tween(200)) },
                ) {
                    HubScreen(
                        initialSection = HubSectionArg.SKILLS,
                        onOpenProviderSettings = { nav.navigate(Routes.PROVIDER_SETTINGS) },
                    )
                }

                composable(
                    route = Routes.HUB_SETTINGS,
                    enterTransition = { fadeIn(tween(300)) + slideInVertically(tween(300)) { it / 4 } },
                    exitTransition = { fadeOut(tween(200)) },
                    popEnterTransition = { fadeIn(tween(300)) + slideInVertically(tween(300)) { -it / 4 } },
                    popExitTransition = { fadeOut(tween(200)) },
                ) {
                    HubScreen(
                        initialSection = HubSectionArg.SETTINGS,
                        onOpenProviderSettings = { nav.navigate(Routes.PROVIDER_SETTINGS) },
                    )
                }

                // ---- Provider Settings ----
                composable(
                    route = Routes.PROVIDER_SETTINGS,
                    enterTransition = { slideInVertically(tween(300)) { it / 3 } + fadeIn(tween(300)) },
                    exitTransition = { slideOutVertically(tween(250)) { it / 3 } + fadeOut(tween(200)) },
                    popEnterTransition = { fadeIn(tween(300)) },
                    popExitTransition = { slideOutVertically(tween(250)) { -it / 3 } + fadeOut(tween(200)) },
                ) {
                    ProviderSettingsScreen(
                        onBack = { nav.popBackStack() },
                    )
                }
            }
        }
    }
}
