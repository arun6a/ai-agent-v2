package com.ai.agent.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.agent.consent.ConsentManager
import com.ai.agent.ui.components.LocalCustomColors
import com.ai.agent.ui.theme.MonoFontFamily
import kotlinx.coroutines.delay

/**
 * v4.0.4: ConsentDialogOverlay — shows when ConsentManager has a pending request.
 *
 * Polls ConsentManager.pendingRequest every 200ms (simple, no Flow needed).
 * When a request is pending, shows a full-screen overlay with:
 * - Tool name (what action the brain wants to take)
 * - Description (human-readable: "Send SMS", "Delete File", etc.)
 * - Details (the actual arguments: phone number, message, file path, etc.)
 * - [Approve] and [Cancel] buttons
 *
 * The overlay is semi-transparent (scrim) so the user can still see the chat behind it.
 * Tapping outside the dialog does NOT dismiss it — user must explicitly choose.
 */
@Composable
fun ConsentDialogOverlay() {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme

    // Simple polling — check every 200ms if there's a pending consent request
    var pendingRequest by remember { mutableStateOf(ConsentManager.pendingRequest) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            val current = ConsentManager.pendingRequest
            if (current != pendingRequest) {
                pendingRequest = current
            }
            delay(200)
        }
    }

    AnimatedVisibility(
        visible = pendingRequest != null,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        val request = pendingRequest ?: return@AnimatedVisibility

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .background(m3.surface, RoundedCornerShape(16.dp))
                    .border(1.dp, custom.border, RoundedCornerShape(16.dp))
                    .padding(24.dp),
            ) {
                // Warning icon + title
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "⚠️",
                        fontSize = 24.sp,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = "Consent Required",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = m3.onSurface,
                    )
                }

                Spacer(Modifier.padding(8.dp))

                // Description (what action)
                Text(
                    text = "Aira wants to:",
                    color = custom.fg2,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.padding(4.dp))
                Text(
                    text = request.description,
                    color = m3.onSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                )

                Spacer(Modifier.padding(12.dp))

                // Details (the actual arguments)
                Text(
                    text = "Details:",
                    color = custom.fg2,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.padding(4.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(custom.canvas, RoundedCornerShape(8.dp))
                        .padding(12.dp),
                ) {
                    Text(
                        text = request.details,
                        color = custom.fg2,
                        fontSize = 13.sp,
                        fontFamily = MonoFontFamily,
                    )
                }

                Spacer(Modifier.padding(20.dp))

                // Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    OutlinedButton(
                        onClick = { ConsentManager.resolve(false) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Cancel", color = custom.fg2)
                    }
                    Spacer(Modifier.width(12.dp))
                    Button(
                        onClick = { ConsentManager.resolve(true) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = custom.accent,
                        ),
                    ) {
                        Text("Approve", color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
