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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
// v4.0.6: BottomNav import removed (bottom nav deleted)
import com.ai.agent.ui.components.LocalCustomColors
import com.ai.agent.ui.components.Routes
import com.ai.agent.ui.screens.BrowserScreen
import com.ai.agent.ui.screens.ChatScreen
import com.ai.agent.ui.screens.BackgroundTasksScreen
import com.ai.agent.ui.screens.HubScreen
import com.ai.agent.ui.screens.HubSectionArg
import com.ai.agent.ui.screens.OnboardingScreen
import com.ai.agent.ui.screens.ProviderSettingsScreen
import com.ai.agent.ui.screens.SkillEditorScreen
import com.ai.agent.ui.screens.SplashScreen
import com.ai.agent.ui.screens.SystemPromptEditorScreen

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

    // v2.4.0: Register navigation callback so BrowserController can trigger nav.navigate(Routes.BROWSER)
    // when AI calls browserOpen(). Must be done once after nav is created.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        com.ai.agent.browser.BrowserController.setNavigationCallback {
            nav.navigate(Routes.BROWSER) {
                popUpTo(nav.graph.startDestinationId) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    val backstack by nav.currentBackStackEntryAsState()
    val currentRoute = backstack?.destination?.route

    // v4.0.6: Bottom navigation bar REMOVED.
    // Hub (Aira Lab) is now accessed via the flask icon in ChatScreen header.
    // This gives more screen space + cleaner UX.

    Scaffold(
        containerColor = custom.canvas,
    ) { innerPadding: PaddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(custom.canvas)
                .padding(innerPadding),
        ) {
            NavHost(
                navController = nav,
                // v4.0.9: Splash screen removed — app opens directly to Chat.
                startDestination = Routes.CHAT,
            ) {
                // ---- Splash (removed in v4.0.9 — was a 1.5s delay before chat) ----
                // Kept as a no-op route for backward compat (deep links might reference it).
                composable(
                    route = Routes.SPLASH,
                ) {
                    // Immediately navigate to Chat.
                    LaunchedEffect(Unit) {
                        nav.navigate(Routes.CHAT) {
                            popUpTo(Routes.SPLASH) { inclusive = true }
                        }
                    }
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
                            // v2.0.3: Persist first-run completion so onboarding
                            // doesn't trigger again on next launch.
                            val ctx = nav.context.applicationContext
                            ctx.getSharedPreferences("ai_agent_prefs", android.content.Context.MODE_PRIVATE)
                                .edit()
                                .putBoolean("has_completed_onboarding", true)
                                .apply()
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
                ) { backStackEntry ->
                    // v2.2.0 Issue 113e — Hub → History passes a session id here.
                    // We observe it as a StateFlow so a new "Continue" tap triggers
                    // a recomposition + LaunchedEffect inside ChatScreen.
                    val pendingSessionId by backStackEntry.savedStateHandle
                        .getStateFlow<Long?>("session_id", null)
                        .collectAsState()

                    ChatScreen(
                        onOpenHub = {
                            nav.navigate(Routes.HUB) {
                                popUpTo(nav.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        onOpenBrowser = {
                            nav.navigate(Routes.BROWSER) {
                                popUpTo(nav.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        onOpenBackgroundTasks = {
                            nav.navigate(Routes.BACKGROUND_TASKS) {
                                launchSingleTop = true
                            }
                        },
                        pendingSessionId = pendingSessionId,
                        onSessionIdConsumed = {
                            backStackEntry.savedStateHandle.remove<Long>("session_id")
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
                    BrowserScreen(
                        onBackToChat = {
                            nav.navigate(Routes.CHAT) {
                                popUpTo(nav.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                    )
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
                        initialSection = null,  // v4.0.1: open Hub Home by default
                        onBackToChat = {
                            // v4.0.6: Back button in Hub navigates to Chat
                            nav.navigate(Routes.CHAT) {
                                popUpTo(nav.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        onOpenProviderSettings = { nav.navigate(Routes.PROVIDER_SETTINGS) },
                        onEditSkill = { skillName, isReadOnly ->
                            nav.navigate(Routes.skillEditorRoute(skillName, isReadOnly))
                        },
                        onOpenSystemPromptEditor = { nav.navigate(Routes.SYSTEM_PROMPT_EDITOR) },
                        onContinueSession = { sessionId ->
                            // v2.2.0 Issue 113e — hand the session id to the Chat
                            // destination via its savedStateHandle, then navigate.
                            try {
                                nav.getBackStackEntry(Routes.CHAT)
                                    .savedStateHandle["session_id"] = sessionId
                            } catch (_: IllegalArgumentException) {
                                // Chat not on the back-stack yet — fall back to
                                // setting it after navigation.
                            }
                            nav.navigate(Routes.CHAT) {
                                popUpTo(nav.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                            // If the chat entry didn't exist on the back-stack, set
                            // it now after navigating (the new top will be chat).
                            try {
                                nav.currentBackStackEntry?.savedStateHandle?.set("session_id", sessionId)
                            } catch (_: Exception) {}
                        },
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
                        onBackToChat = {
                            nav.navigate(Routes.CHAT) {
                                popUpTo(nav.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        onOpenProviderSettings = { nav.navigate(Routes.PROVIDER_SETTINGS) },
                        onEditSkill = { skillName, isReadOnly ->
                            nav.navigate(Routes.skillEditorRoute(skillName, isReadOnly))
                        },
                        onOpenSystemPromptEditor = { nav.navigate(Routes.SYSTEM_PROMPT_EDITOR) },
                        onContinueSession = { sessionId ->
                            try {
                                nav.getBackStackEntry(Routes.CHAT)
                                    .savedStateHandle["session_id"] = sessionId
                            } catch (_: IllegalArgumentException) {}
                            nav.navigate(Routes.CHAT) {
                                popUpTo(nav.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                            try {
                                nav.currentBackStackEntry?.savedStateHandle?.set("session_id", sessionId)
                            } catch (_: Exception) {}
                        },
                    )
                }

                // ---- Skill Editor ----
                // Route pattern: "skill_editor/{skillName}/{isReadOnly}"
                // skillName = "__new__" for create mode (avoids nullable path segments).
                composable(
                    route = Routes.SKILL_EDITOR,
                    arguments = listOf(
                        navArgument("skillName") { type = NavType.StringType },
                        navArgument("isReadOnly") { type = NavType.StringType },
                    ),
                    enterTransition = { slideInVertically(tween(300)) { it / 3 } + fadeIn(tween(300)) },
                    exitTransition = { slideOutVertically(tween(250)) { it / 3 } + fadeOut(tween(200)) },
                    popEnterTransition = { fadeIn(tween(300)) },
                    popExitTransition = { slideOutVertically(tween(250)) { -it / 3 } + fadeOut(tween(200)) },
                ) { backStackEntry ->
                    val rawName = backStackEntry.arguments?.getString("skillName") ?: "__new__"
                    val rawRo = backStackEntry.arguments?.getString("isReadOnly") == "1"
                    val skillName = if (rawName == "__new__") null else rawName
                    SkillEditorScreen(
                        skillName = skillName,
                        isReadOnly = rawRo,
                        onBack = { nav.popBackStack() },
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

                // ---- Background Tasks (v2.2.2) ----
                composable(
                    route = Routes.BACKGROUND_TASKS,
                    enterTransition = { slideInVertically(tween(300)) { it / 3 } + fadeIn(tween(300)) },
                    exitTransition = { slideOutVertically(tween(250)) { it / 3 } + fadeOut(tween(200)) },
                    popEnterTransition = { fadeIn(tween(300)) },
                    popExitTransition = { slideOutVertically(tween(250)) { -it / 3 } + fadeOut(tween(200)) },
                ) {
                    BackgroundTasksScreen(
                        onBack = { nav.popBackStack() },
                    )
                }

                // ---- System Prompt Editor (v2.0.9 Task 95) ----
                composable(
                    route = Routes.SYSTEM_PROMPT_EDITOR,
                    enterTransition = { slideInVertically(tween(300)) { it / 3 } + fadeIn(tween(300)) },
                    exitTransition = { slideOutVertically(tween(250)) { it / 3 } + fadeOut(tween(200)) },
                    popEnterTransition = { fadeIn(tween(300)) },
                    popExitTransition = { slideOutVertically(tween(250)) { -it / 3 } + fadeOut(tween(200)) },
                ) {
                    SystemPromptEditorScreen(
                        initialMode = "custom",
                        onBack = { nav.popBackStack() },
                    )
                }
            }
        }
    }
}
