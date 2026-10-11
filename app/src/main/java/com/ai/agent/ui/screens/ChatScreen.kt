package com.ai.agent.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.agent.ui.components.LocalCustomColors
import com.ai.agent.ui.theme.MonoFontFamily
import com.ai.agent.ui.theme.AIAgentTheme

// ============================================================================
// ChatScreen — v2 mobile placeholder
// ----------------------------------------------------------------------------
// Static visual layout only — no real chat logic yet. Mirrors the approved
// mockup (v2-design/02-chat-screen.html):
//   1. Top bar: hamburger / logo + "AI Agent v2" + status dot / overflow
//   2. Empty-state welcome message in the body
//   3. Bottom input bar: voice button · text field · send button
// Pluggable into a ViewModel later; for now uses local `remember` state.
// ============================================================================

@Composable
fun ChatScreen() {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme

    var input by remember { mutableStateOf("") }

    Scaffold(
        containerColor = custom.canvas,
        topBar = { ChatTopBar() },
        bottomBar = {
            ChatInputBar(
                text = input,
                onTextChange = { input = it },
                onSend = { input = "" },
                onVoice = { /* placeholder — wire to VoiceManager later */ },
            )
        },
    ) { inner ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
        ) {
            WelcomeEmptyState(modifier = Modifier.fillMaxSize())
        }
    }
}

// ---------------------------------------------------------------------------

@Composable
private fun ChatTopBar() {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme

    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Hex orb logo
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(custom.accent, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "A",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "AI Agent",
                    style = m3.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = m3.onSurface,
                )
                Text(
                    text = " v2",
                    style = m3.titleMedium.copy(
                        fontFamily = MonoFontFamily,
                        fontWeight = FontWeight.Medium,
                    ),
                    color = custom.accent,
                )
                Spacer(Modifier.width(6.dp))
                // Status dot — pulsing emerald (idle/ready)
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(custom.accent, CircleShape),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "ready",
                    style = TextStyle(
                        fontFamily = MonoFontFamily,
                        fontSize = 11.sp,
                    ),
                    color = custom.fg3,
                )
            }
        },
        actions = {
            IconButton(onClick = { /* placeholder */ }) {
                Icon(
                    imageVector = Icons.Outlined.Search,
                    contentDescription = "Search",
                    tint = custom.fg2,
                )
            }
            IconButton(onClick = { /* placeholder */ }) {
                Icon(
                    imageVector = Icons.Outlined.Hub,
                    contentDescription = "Hub",
                    tint = custom.fg2,
                )
            }
            IconButton(onClick = { /* placeholder */ }) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = "More",
                    tint = custom.fg2,
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = m3.surface,
            titleContentColor = m3.onSurface,
        ),
    )
}

// ---------------------------------------------------------------------------

@Composable
private fun WelcomeEmptyState(modifier: Modifier = Modifier) {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme

    Column(
        modifier = modifier.padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Orb avatar
        Box(
            modifier = Modifier
                .size(64.dp)
                .shadow(
                    elevation = 12.dp,
                    shape = RoundedCornerShape(20.dp),
                    clip = false,
                )
                .background(custom.accent, RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center,
        ) {
            // Hexagon glyph — same shape as the launcher foreground
            Text(
                text = "⬡",
                color = Color.White,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        Spacer(Modifier.height(20.dp))
        Text(
            text = "Hey, I'm your AI Agent",
            style = m3.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
            color = m3.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Ask anything, give a command, or tap the mic to talk.\n" +
                "I can run tools, control your phone, and remember what matters.",
            style = m3.bodyMedium,
            color = custom.fg2,
            textAlign = TextAlign.Center,
            lineHeight = 20.sp,
        )

        Spacer(Modifier.height(28.dp))

        // Quick suggestion chips
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SuggestionChip(text = "Play BLACKPINK on YouTube")
            SuggestionChip(text = "What's on my calendar today?")
            SuggestionChip(text = "Summarize my last WhatsApp chat")
        }
    }
}

@Composable
private fun SuggestionChip(text: String) {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme

    Row(
        modifier = Modifier
            .fillMaxWidth(0.85f)
            .background(custom.surface2, RoundedCornerShape(12.dp))
            .border(1.dp, custom.border, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(custom.accent, CircleShape),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            style = m3.bodyMedium,
            color = m3.onSurface,
        )
    }
}

// ---------------------------------------------------------------------------

@Composable
private fun ChatInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onVoice: () -> Unit,
) {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(m3.surface)
            .border(1.dp, custom.border)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Voice button
        IconButton(
            onClick = onVoice,
            modifier = Modifier
                .size(40.dp)
                .background(custom.surface2, CircleShape)
                .border(1.dp, custom.border, CircleShape),
        ) {
            Icon(
                imageVector = Icons.Outlined.Mic,
                contentDescription = "Voice",
                tint = custom.fg3,
            )
        }

        Spacer(Modifier.width(10.dp))

        // Text field — pill shape, surface2 background
        Box(
            modifier = Modifier
                .weight(1f)
                .height(40.dp)
                .background(custom.surface2, RoundedCornerShape(20.dp))
                .border(1.dp, custom.border, RoundedCornerShape(20.dp))
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (text.isEmpty()) {
                Text(
                    text = "Ask or command…",
                    style = m3.bodyMedium,
                    color = custom.fg3,
                )
            }
            BasicTextField(
                value = text,
                onValueChange = onTextChange,
                singleLine = true,
                textStyle = m3.bodyMedium.copy(color = m3.onSurface),
                cursorBrush = SolidColor(custom.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.width(8.dp))

        // Attach (paperclip) — hidden until needed but reserved for visual parity
        IconButton(
            onClick = { /* placeholder — file attachment later */ },
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.AttachFile,
                contentDescription = "Attach",
                tint = custom.fg3,
            )
        }

        Spacer(Modifier.width(4.dp))

        // Send button — emerald accent with soft glow shadow
        IconButton(
            onClick = onSend,
            enabled = text.isNotBlank(),
            modifier = Modifier
                .size(40.dp)
                .shadow(
                    elevation = if (text.isNotBlank()) 8.dp else 0.dp,
                    shape = CircleShape,
                    clip = false,
                )
                .background(
                    if (text.isNotBlank()) custom.accent else custom.surface3,
                    CircleShape,
                ),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = "Send",
                tint = if (text.isNotBlank()) Color.White else custom.fg3,
            )
        }
    }
}

// ---------------------------------------------------------------------------

@Preview(showBackground = true, name = "Chat — Light")
@Composable
private fun ChatScreenLightPreview() {
    AIAgentTheme(darkTheme = false) { ChatScreen() }
}

@Preview(showBackground = true, name = "Chat — Dark")
@Composable
private fun ChatScreenDarkPreview() {
    AIAgentTheme(darkTheme = true) { ChatScreen() }
}
