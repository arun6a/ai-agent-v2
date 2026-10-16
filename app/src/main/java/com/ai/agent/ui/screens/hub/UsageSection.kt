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

// Hub section: Usage — API call + token + cost tracking.

// ---------------------------------------------------------------------------
// v2.1.1: Usage Monitor section — port of v1 ApiUsageTracker display
// Shows: today's calls + tokens, session stats, current task stats,
// per-provider breakdown, and per-day history (last 7 days).
// ---------------------------------------------------------------------------

@Composable
internal fun UsageSection() {
    val context = LocalContext.current
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme

    // Refresh trigger — bump to re-read stats after each task
    var refreshKey by remember { mutableStateOf(0) }

    // Read all stats
    val todayStats = remember(refreshKey) { com.ai.agent.llm.ApiUsageTracker.getTodayStats(context) }
    val sessionStats = remember(refreshKey) { com.ai.agent.llm.ApiUsageTracker.getSessionStats() }
    val taskStats = remember(refreshKey) { com.ai.agent.llm.ApiUsageTracker.getTaskStats() }
    val fullReport = remember(refreshKey) { com.ai.agent.llm.ApiUsageTracker.getFullReport(context) }
    // v3.2.7: Check if a provider is actually configured before showing it.
    val isConfigured = remember { com.ai.agent.llm.AIProvider.isConfigured(context) }
    val currentProvider = remember { com.ai.agent.llm.AIProvider.getCurrentProvider(context) }
    val currentModel = remember { com.ai.agent.llm.AIProvider.getModel(context) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            SectionHeader(
                title = "Usage Monitor",
                subtitle = "API calls · tokens · cost tracking",
            )
        }

        // === Current provider card ===
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (isConfigured) custom.accent.copy(alpha = 0.06f) else custom.ink2, RoundedCornerShape(8.dp))
                    .border(1.dp, if (isConfigured) custom.accent.copy(alpha = 0.3f) else custom.inkBorder, RoundedCornerShape(8.dp))
                    .padding(12.dp),
            ) {
                Text(
                    text = "ACTIVE PROVIDER",
                    color = custom.fg3,
                    fontFamily = MonoFontFamily,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                if (isConfigured) {
                    Text(
                        text = currentProvider.name,
                        color = m3.onSurface,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "model: $currentModel",
                        color = custom.fg2,
                        fontFamily = MonoFontFamily,
                        fontSize = 11.sp,
                    )
                    Text(
                        text = "endpoint: ${currentProvider.defaultEndpoint.take(60)}...",
                        color = custom.fg3,
                        fontFamily = MonoFontFamily,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    // v3.2.7: No provider configured — show actionable guidance
                    // instead of the default "Z.AI Direct API" name (which is just
                    // a fallback and misleading when nothing is actually set up).
                    Text(
                        text = "Not configured",
                        color = m3.onSurface,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Open AI Settings → API Keys to add your API key, " +
                                "then pick a model in Model Catalog, then set it as " +
                                "Default in LLM Configuration.",
                        color = custom.fg3,
                        fontFamily = MonoFontFamily,
                        fontSize = 10.sp,
                    )
                }
            }
        }

        // === Today's stats (big cards) ===
        item {
            SectionHeader(title = "Today", subtitle = "Resets at midnight")
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatCard(
                    label = "Calls",
                    value = todayStats.first.toString(),
                    modifier = Modifier.weight(1f),
                )
                StatCard(
                    label = "Tokens",
                    value = todayStats.second.toString(),
                    modifier = Modifier.weight(1f),
                )
                // v3.3.11: real cost tracking (was a rough estimate before).
                StatCard(
                    label = "Cost",
                    value = com.ai.agent.llm.ApiUsageTracker.formatCost(
                        com.ai.agent.llm.ApiUsageTracker.getTodayCostMicro(context)
                    ),
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // === Session stats ===
        item {
            SectionHeader(title = "This Session", subtitle = "Since app launched")
        }
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(custom.ink2, RoundedCornerShape(8.dp))
                    .border(1.dp, custom.inkBorder, RoundedCornerShape(8.dp))
                    .padding(12.dp),
            ) {
                Text(
                    text = sessionStats,
                    color = custom.fg2,
                    fontFamily = MonoFontFamily,
                    fontSize = 12.sp,
                )
            }
        }

        // === Current task stats ===
        item {
            SectionHeader(title = "Current Task", subtitle = "Since last user message")
        }
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(custom.ink2, RoundedCornerShape(8.dp))
                    .border(1.dp, custom.inkBorder, RoundedCornerShape(8.dp))
                    .padding(12.dp),
            ) {
                Text(
                    text = taskStats,
                    color = custom.fg2,
                    fontFamily = MonoFontFamily,
                    fontSize = 12.sp,
                )
            }
        }

        // === Full report ===
        item {
            SectionHeader(title = "Full Report", subtitle = "All-time + per-provider")
        }
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(custom.ink2, RoundedCornerShape(8.dp))
                    .border(1.dp, custom.inkBorder, RoundedCornerShape(8.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                fullReport.split("\n").forEach { line ->
                    Text(
                        text = line,
                        color = if (line.startsWith("=== ")) custom.accent else custom.fg2,
                        fontFamily = MonoFontFamily,
                        fontSize = 11.sp,
                        fontWeight = if (line.startsWith("=== ")) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }

        // === Refresh button ===
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text(
                    text = "↻ Refresh",
                    color = custom.accent,
                    fontFamily = MonoFontFamily,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clickable { refreshKey++ }
                        .padding(8.dp),
                )
            }
        }

    }
}
