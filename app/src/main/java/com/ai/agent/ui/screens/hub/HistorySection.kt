package com.ai.agent.ui.screens.hub

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.ManageSearch
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Rule
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.ai.agent.llm.AIProvider
import com.ai.agent.llm.ApiUsageTracker
import com.ai.agent.llm.AppProfileManager
import com.ai.agent.llm.ModelSettingsScreen
import com.ai.agent.skills.SkillManager
import com.ai.agent.storage.AgentDatabase
import com.ai.agent.tools.ToolExecutor
import com.ai.agent.ui.components.HubNavItem
import com.ai.agent.ui.components.LocalCustomColors
import com.ai.agent.ui.components.StatCard
import com.ai.agent.ui.theme.MonoFontFamily
import com.ai.agent.ui.theme.ThemeManager
import com.ai.agent.ui.screens.ChatViewModel
import com.ai.agent.ui.screens.isUserCreatedSkill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Hub section: History — chat session list with continue/delete.

// ---------------------------------------------------------------------------
// Task 27 — Functional section: History
// v2.2.0 Issue 113d — now shows chat SESSIONS (not flat messages). Each row
// shows the session title (first user message), last active time, and message
// count. Tap → continue session on chat screen. Long-press → Delete option.
// ---------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HistorySection(
    onContinueSession: (Long) -> Unit = {},
) {
    val context = LocalContext.current
    val custom = LocalCustomColors.current

    var sessions by remember { mutableStateOf<List<AgentDatabase.ChatSession>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    // Per-session message count cache — populated on the same LaunchedEffect.
    var messageCounts by remember { mutableStateOf<Map<Long, Int>>(emptyMap()) }
    // Per-session first-user-message preview cache (used as the title if the
    // session title is just "New Chat" / "Default").
    var previews by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }
    // Delete-confirmation dialog target.
    var deleteTarget by remember { mutableStateOf<AgentDatabase.ChatSession?>(null) }

    fun reload() {
        val all = try {
            AgentDatabase(context).getChatSessions()
        } catch (e: Exception) {
            emptyList()
        }
        sessions = all
        // Fetch message count + first user message for each session in a single pass.
        val counts = mutableMapOf<Long, Int>()
        val previewsMap = mutableMapOf<Long, String>()
        try {
            val db = AgentDatabase(context)
            for (s in all) {
                counts[s.id] = db.getSessionMessageCount(s.id)
                val firstUser = db.getConversationsForSession(s.id).firstOrNull { it.first == "user" }
                previewsMap[s.id] = firstUser?.second?.take(80) ?: ""
            }
        } catch (_: Exception) {}
        messageCounts = counts
        previews = previewsMap
        loaded = true
    }

    LaunchedEffect(Unit) { reload() }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            SectionHeader(
                title = "Chat History",
                subtitle = "${sessions.size} sessions · ${if (loaded) "ready" else "loading…"}",
            )
        }
        if (sessions.isEmpty() && loaded) {
            item {
                PlaceholderCard(
                    title = "No chats yet",
                    detail = "Send a message on the Chat tab — your conversations will be grouped into sessions you can resume later.",
                )
            }
        } else if (sessions.isEmpty()) {
            item { LoadingCard() }
        } else {
            items(sessions, key = { it.id }) { session ->
                val count = messageCounts[session.id] ?: 0
                val preview = previews[session.id] ?: ""
                SessionRow(
                    session = session,
                    messageCount = count,
                    preview = preview,
                    onClick = { onContinueSession(session.id) },
                    onLongPress = { deleteTarget = session },
                )
            }
        }
    }

    // Delete-confirmation dialog.
    if (deleteTarget != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete chat?", color = custom.fg2) },
            text = {
                Text(
                    "This will permanently remove this chat and all its messages. This cannot be undone.",
                    color = custom.fg3,
                    fontSize = 12.sp,
                )
            },
            confirmButton = {
                Text(
                    text = "Delete",
                    color = custom.error,
                    modifier = Modifier.clickable {
                        val target = deleteTarget
                        if (target != null) {
                            try {
                                AgentDatabase(context).deleteChatSession(target.id)
                            } catch (_: Exception) {}
                            deleteTarget = null
                            reload()
                        }
                    },
                )
            },
            dismissButton = {
                Text(
                    text = "Cancel",
                    color = custom.fg3,
                    modifier = Modifier.clickable { deleteTarget = null },
                )
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(
    session: AgentDatabase.ChatSession,
    messageCount: Int,
    preview: String,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme

    val title = when {
        preview.isNotBlank() -> preview
        session.title != "New Chat" && session.title != "Default" -> session.title
        else -> "New Chat"
    }
    val timeAgo = formatTimeAgo(session.lastActive)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(custom.surface2, RoundedCornerShape(12.dp))
            .border(1.dp, custom.border, RoundedCornerShape(12.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongPress,
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Accent dot
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(custom.accent, CircleShape),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = title,
                color = m3.onSurface,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "$messageCount msgs",
                color = custom.fg3,
                fontFamily = MonoFontFamily,
                fontSize = 10.sp,
            )
        }
        Spacer(Modifier.size(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Last active $timeAgo",
                color = custom.fg3,
                fontFamily = MonoFontFamily,
                fontSize = 10.sp,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            Text(
                text = "Tap to continue →",
                color = custom.accent,
                fontFamily = MonoFontFamily,
                fontSize = 10.sp,
            )
        }
    }
}

private fun formatTimeAgo(timestamp: Long): String {
    if (timestamp <= 0L) return "unknown"
    val delta = System.currentTimeMillis() - timestamp
    return when {
        delta < 60_000L -> "just now"
        delta < 3_600_000L -> "${delta / 60_000L}m ago"
        delta < 86_400_000L -> "${delta / 3_600_000L}h ago"
        delta < 604_800_000L -> "${delta / 86_400_000L}d ago"
        else -> {
            val sdf = java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.getDefault())
            sdf.format(java.util.Date(timestamp))
        }
    }
}
