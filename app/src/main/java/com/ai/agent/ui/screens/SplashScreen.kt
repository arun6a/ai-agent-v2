package com.ai.agent.ui.screens

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.agent.ui.components.LocalCustomColors
import kotlinx.coroutines.delay

/**
 * v4.0.11: New splash/launch screen.
 *
 * Clean, minimal launch screen:
 *   [hexagon logo]
 *      Aira
 *   AI Runs Android
 *
 * Shows for 1.5s then auto-navigates to Chat (or Onboarding if first run).
 * The "AI Runs Android" tagline is the meaning of the AIRA acronym.
 */
@Composable
fun SplashScreen(
    onFinished: (goToOnboarding: Boolean) -> Unit,
) {
    val custom = LocalCustomColors.current
    val context = LocalContext.current

    val logoVisible = remember { mutableStateOf(false) }
    val textVisible = remember { mutableStateOf(false) }
    val taglineVisible = remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(200)
        logoVisible.value = true
        delay(300)
        textVisible.value = true
        delay(300)
        taglineVisible.value = true
        delay(700)

        val needsOnboarding = !com.ai.agent.llm.AIProvider.isConfigured(context) &&
            !com.ai.agent.accessibility.AgentAccessibilityService.isEnabled(context)

        onFinished(needsOnboarding)
    }

    val logoAlpha by animateFloatAsState(
        targetValue = if (logoVisible.value) 1f else 0f,
        animationSpec = tween(500, easing = LinearOutSlowInEasing),
        label = "logo_alpha",
    )
    val textAlpha by animateFloatAsState(
        targetValue = if (textVisible.value) 1f else 0f,
        animationSpec = tween(500, easing = LinearOutSlowInEasing),
        label = "text_alpha",
    )
    val taglineAlpha by animateFloatAsState(
        targetValue = if (taglineVisible.value) 1f else 0f,
        animationSpec = tween(500, easing = LinearOutSlowInEasing),
        label = "tagline_alpha",
    )

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
            // Logo — hexagon shape in accent color
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .alpha(logoAlpha)
                    .background(custom.accent, RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "⬡",
                    color = Color.White,
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            Spacer(Modifier.height(24.dp))

            // App name
            Text(
                text = "Aira",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                ),
                color = custom.fg2,
                modifier = Modifier.alpha(textAlpha),
            )

            Spacer(Modifier.height(4.dp))

            // Tagline — the meaning of AIRA
            Text(
                text = "AI Runs Android",
                style = MaterialTheme.typography.bodyMedium,
                color = custom.fg3,
                modifier = Modifier.alpha(taglineAlpha),
            )
        }
    }
}
