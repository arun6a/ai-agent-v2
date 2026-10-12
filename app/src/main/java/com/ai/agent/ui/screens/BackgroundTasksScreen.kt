package com.ai.agent.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.agent.llm.ApiUsageTracker
import com.ai.agent.service.AgentService
import com.ai.agent.service.TaskStopReceiver
import com.ai.agent.storage.AgentDatabase
import com.ai.agent.ui.components.LocalCustomColors
import com.ai.agent.ui.theme.MonoFontFamily
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackgroundTasksScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme
    var refreshKey by remember { mutableStateOf(0) }

    val taskRunning = remember(refreshKey) { AgentService.taskRunning }
    val stopRequested = remember(refreshKey) { TaskStopReceiver.stopRequested }
    val taskCalls = remember(refreshKey) { ApiUsageTracker.getTaskCalls() }

    // Load recent action log entries
    val recentActions = remember(refreshKey) {
        try {
            val db = AgentDatabase(context)
            // Use getRecentConversations as a proxy for recent activity
            db.getRecentConversations(20).reversed()
        } catch (e: Exception) {
            emptyList()
        }
    }

    Scaffold(
        containerColor = custom.canvas,
        topBar = {
            TopAppBar(
                title = { Text("Background Tasks", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = m3.onSurface)
                    }
                },
                actions = {
                    Text(
                        text = "↻",
                        fontSize = 18.sp,
                        modifier = Modifier.clickable { refreshKey++ }.padding(12.dp),
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = m3.surface),
            )
        },
    ) { inner ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // === Current running task ===
            if (taskRunning && !stopRequested) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(custom.accent.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                            .border(1.dp, custom.accent.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                            .padding(16.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("● ", color = custom.accent, fontSize = 20.sp)
                            Text(
                                "Task Running",
                                color = m3.onSurface,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.weight(1f))
                            Button(
                                onClick = {
                                    TaskStopReceiver.requestStop()
                                    refreshKey++
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                            ) {
                                Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Stop", fontSize = 13.sp)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "API calls this task: $taskCalls",
                            color = custom.fg2,
                            fontFamily = MonoFontFamily,
                            fontSize = 12.sp,
                        )
                        Text(
                            "Tap Stop to cancel the running task. The AI will finish the current step + save progress.",
                            color = custom.fg3,
                            fontSize = 11.sp,
                        )
                    }
                }
            } else if (stopRequested) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFF59E0B).copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                            .border(1.dp, Color(0xFFF59E0B).copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                            .padding(16.dp),
                    ) {
                        Text(
                            "⏹ Stopping...",
                            color = Color(0xFFF59E0B),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "Task is being stopped. Current step will finish, then the task will end.",
                            color = custom.fg2,
                            fontSize = 11.sp,
                        )
                    }
                }
            }

            // === Recent activity ===
            item {
                Text(
                    "Recent Activity",
                    color = m3.onSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            if (recentActions.isEmpty()) {
                item {
                    Text(
                        "No recent activity. Send a message in chat to start a task.",
                        color = custom.fg3,
                        fontSize = 13.sp,
                    )
                }
            } else {
                items(recentActions.size) { index ->
                    val (role, text) = recentActions[index]
                    val isUser = role == "user"
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(custom.ink2, RoundedCornerShape(8.dp))
                            .border(1.dp, custom.inkBorder, RoundedCornerShape(8.dp))
                            .padding(12.dp),
                    ) {
                        Text(
                            text = if (isUser) "👤" else "🤖",
                            fontSize = 14.sp,
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isUser) "You" else if (role == "ai_step") "AI Step" else "AI",
                                color = if (isUser) custom.accent else custom.fg2,
                                fontSize = 11.sp,
                                fontFamily = MonoFontFamily,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = text.take(150) + if (text.length > 150) "..." else "",
                                color = m3.onSurface,
                                fontSize = 12.sp,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}
