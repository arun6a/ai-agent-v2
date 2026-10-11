package com.ai.agent.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.agent.R
import com.ai.agent.llm.AIProvider
import com.ai.agent.ui.components.LocalCustomColors
import com.ai.agent.ui.theme.MonoFontFamily
import kotlinx.coroutines.delay

// ============================================================================
// SplashScreen — v2.0.6 cold-start splash (Aira rebrand).
// ----------------------------------------------------------------------------
// - Canvas-color background (matches Theme.AIAgentV2.Splash so there's no flash)
// - Aira logo (PNG, drawable/aira_logo.png) centered ~120dp
// - "Aira" title (semibold 28sp) + "Personal AI Assistant" subtitle (gray)
// - "v2.0.6 · build 6" mono caption below
// - Fade-in icon, then slide-up title (AnimatedVisibility)
// - After ~1500ms, navigates to chat (or onboarding if first-run)
// ============================================================================

@Composable
fun SplashScreen(
    onFinished: (goToOnboarding: Boolean) -> Unit,
) {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme
    val context = LocalContext.current

    // Entrance-animation flags.
    var showIcon by remember { mutableStateOf(false) }
    var showTitle by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // Stagger the icon fade-in, then the title slide-up.
        showIcon = true
        delay(150)
        showTitle = true
        delay(1500)

        // v2.0.3: Use a "has_completed_onboarding" SharedPreferences flag
        // to track true first-run. Previously we relied on `isConfigured()`
        // which returns true for the default "zai" proxy (no API key needed),
        // so onboarding NEVER triggered. Now it triggers once, until the user
        // finishes onboarding (regardless of provider choice).
        val prefs = context.getSharedPreferences("ai_agent_prefs", android.content.Context.MODE_PRIVATE)
        val hasCompletedOnboarding = prefs.getBoolean("has_completed_onboarding", false)
        val needsOnboarding = !hasCompletedOnboarding
        onFinished(needsOnboarding)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(custom.canvas),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // === Aira logo (PNG resource) ===
            val iconAlpha by animateFloatAsState(
                targetValue = if (showIcon) 1f else 0f,
                animationSpec = tween(durationMillis = 600, easing = LinearOutSlowInEasing),
                label = "splash-icon-fade",
            )
            Image(
                painter = androidx.compose.ui.res.painterResource(R.drawable.aira_logo),
                contentDescription = "Aira logo",
                modifier = Modifier
                    .size(120.dp)
                    .alpha(iconAlpha),
            )

            Spacer(Modifier.height(28.dp))

            // === Title — slides up + fades in ===
            AnimatedVisibility(
                visible = showTitle,
                enter = fadeIn(tween(500)) +
                    slideInVertically(
                        animationSpec = tween(600, easing = LinearOutSlowInEasing),
                        initialOffsetY = { it / 3 },
                    ),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Aira",
                        color = m3.onSurface,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Personal AI Assistant",
                        color = custom.fg2,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "v2.0.6 · build 6",
                        color = custom.fg3,
                        fontFamily = MonoFontFamily,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
