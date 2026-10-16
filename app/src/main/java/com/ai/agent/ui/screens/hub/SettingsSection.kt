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

// Hub section: Settings — prompt mode, browser, search, theme, about.

// ---------------------------------------------------------------------------
// Task 31 — Functional section: Settings (Prompt / Browser / Search / Toggles)
// ---------------------------------------------------------------------------

@Composable
internal fun SettingsSection(onOpenSystemPromptEditor: () -> Unit = {}) {
    val context = LocalContext.current
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme

    var promptMode by remember { mutableStateOf(AIProvider.getPromptMode(context)) }
    var browserMode by remember { mutableStateOf(AIProvider.getBrowserMode(context)) }
    var searchMethod by remember { mutableStateOf(AIProvider.getSearchMethod(context)) }
    var visionMode by remember { mutableStateOf(AIProvider.getVisionMode(context)) }
    // v3.5.0: alwaysLearn, dynamicPrompt, contextualPrompts moved to Brain Behavior page.
    // v2.0.7: Overlay toggle — persists via SharedPreferences("ai_agent_prefs","overlay_enabled")
    val prefs = remember { context.getSharedPreferences("ai_agent_prefs", android.content.Context.MODE_PRIVATE) }
    var overlayEnabled by remember { mutableStateOf(prefs.getBoolean("overlay_enabled", true)) }
    var overlayPermGranted by remember { mutableStateOf(android.provider.Settings.canDrawOverlays(context)) }
    var toast by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            SectionHeader(
                title = "Settings",
                subtitle = "Device settings · search · appearance",
            )
        }

        // v3.5.0: AI behavior toggles (prompt mode, browser mode, vision mode,
        // contextual prompts, always learn, auto-fallback, etc.) moved to
        // AI Settings → Brain Behavior. This page now only has device settings.

        item {
            SettingsGroup(title = "SEARCH METHOD") {
                SelectorRow(
                    label = "Search Method",
                    options = listOf("duckduckgo", "google", "bing", "google_app"),
                    selected = searchMethod,
                    onSelect = {
                        searchMethod = it
                        AIProvider.setSearchMethod(context, it)
                        toast = "Search method: $it"
                    },
                )
            }
        }
        // v3.5.0: VISION MODE + BEHAVIOR groups removed — moved to Brain Behavior page.
        // v2.0.7: Floating overlay toggle — lets user enable/disable the floating
        // button without uninstalling or revoking overlay permission.
        item {
            SettingsGroup(title = "FLOATING OVERLAY") {
                if (!overlayPermGranted) {
                    // Permission missing — show a "Grant overlay permission" row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(custom.accent.copy(alpha = 0.06f), RoundedCornerShape(8.dp))
                            .border(1.dp, custom.accent.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                            .clickable {
                                val intent = android.content.Intent(
                                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    android.net.Uri.parse("package:${context.packageName}"),
                                )
                                context.startActivity(intent)
                                toast = "Grant overlay permission in Settings"
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Warning,
                            contentDescription = null,
                            tint = custom.accent,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Grant overlay permission",
                                color = m3.onSurface,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = "Required for the floating button to appear",
                                color = custom.fg3,
                                fontSize = 11.sp,
                            )
                        }
                        Text(text = "Open →", color = custom.accent, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    }
                } else {
                    ToggleRow(
                        label = "Floating Overlay Button",
                        desc = "Show the floating Aira button on top of other apps",
                        checked = overlayEnabled,
                        onCheck = { enabled ->
                            overlayEnabled = enabled
                            prefs.edit().putBoolean("overlay_enabled", enabled).apply()
                            if (enabled) {
                                com.ai.agent.service.OverlayManager.setEnabled(true, context)
                                toast = "Overlay enabled — relaunch app to show"
                            } else {
                                com.ai.agent.service.OverlayManager.setEnabled(false, context)
                                toast = "Overlay disabled"
                            }
                        },
                    )
                }
            }
        }
        item {
            SettingsGroup(title = "APPEARANCE") {
                ThemeSelectorRow(
                    current = ThemeManager.mode,
                    onSelect = {
                        ThemeManager.setMode(context, it)
                        toast = "Theme: $it"
                    },
                )
            }
        }
        item {
            AboutCard()
        }
    }

    LaunchedEffect(toast) {
        toast?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            toast = null
        }
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    val custom = LocalCustomColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(custom.ink2, RoundedCornerShape(8.dp))
            .border(1.dp, custom.inkBorder, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            color = custom.fg3,
            fontFamily = MonoFontFamily,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
        )
        content()
    }
}

@Composable
private fun SelectorRow(label: String, options: List<String>, selected: String, onSelect: (String) -> Unit) {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = label,
            color = m3.onSurface,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(options) { opt ->
                val active = opt == selected
                Row(
                    modifier = Modifier
                        .background(
                            if (active) custom.accent.copy(alpha = 0.15f) else custom.surface2,
                            RoundedCornerShape(14.dp),
                        )
                        .border(
                            1.dp,
                            if (active) custom.accent.copy(alpha = 0.5f) else custom.border,
                            RoundedCornerShape(14.dp),
                        )
                        .clickable { onSelect(opt) }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (active) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(custom.accent, CircleShape),
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        text = opt,
                        color = if (active) custom.accent else custom.fg2,
                        fontFamily = MonoFontFamily,
                        fontSize = 11.sp,
                        fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, desc: String, checked: Boolean, onCheck: (Boolean) -> Unit) {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = m3.onSurface,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.size(2.dp))
            Text(
                text = desc,
                color = custom.fg2,
                fontSize = 10.sp,
                lineHeight = 13.sp,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheck,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = custom.accent,
                uncheckedThumbColor = custom.fg3,
                uncheckedTrackColor = custom.surface3,
            ),
        )
    }
}

/**
 * Task 41: Theme selector — System / Light / Dark.
 * Reads + writes [ThemeManager.mode] so the rest of the app recomposes
 * immediately when the user picks a different mode.
 */
@Composable
private fun ThemeSelectorRow(
    current: String,
    onSelect: (String) -> Unit,
) {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme
    val options = listOf("system", "light", "dark")

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "Theme",
            color = m3.onSurface,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = "Override the system theme — applies instantly.",
            color = custom.fg2,
            fontSize = 10.sp,
            lineHeight = 13.sp,
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(options) { opt ->
                val active = opt == current
                Row(
                    modifier = Modifier
                        .background(
                            if (active) custom.accent.copy(alpha = 0.15f) else custom.surface2,
                            RoundedCornerShape(14.dp),
                        )
                        .border(
                            1.dp,
                            if (active) custom.accent.copy(alpha = 0.5f) else custom.border,
                            RoundedCornerShape(14.dp),
                        )
                        .clickable { onSelect(opt) }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (active) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(custom.accent, CircleShape),
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        text = opt,
                        color = if (active) custom.accent else custom.fg2,
                        fontFamily = MonoFontFamily,
                        fontSize = 11.sp,
                        fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

@Composable
private fun AboutCard() {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(custom.ink2, RoundedCornerShape(8.dp))
            .border(1.dp, custom.inkBorder, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "ABOUT",
            color = custom.fg3,
            fontFamily = MonoFontFamily,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .background(custom.accent, RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text("A", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Aira",
                color = m3.onSurface,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        AboutRow(label = "subtitle", value = "Personal AI Assistant")
        AboutRow(label = "tagline", value = "Your intelligent companion for seamless device control and daily tasks.")
        AboutRow(label = "version", value = "2.0.9")
        AboutRow(label = "build", value = "9")
        AboutRow(label = "min sdk", value = "24 (Android 7.0)")
        AboutRow(label = "target sdk", value = "34 (Android 14)")
        AboutRow(label = "repo", value = "github.com/arun6a/ai-agent-v2")
    }
}

@Composable
private fun AboutRow(label: String, value: String) {
    val custom = LocalCustomColors.current
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            color = custom.fg3,
            fontFamily = MonoFontFamily,
            fontSize = 10.sp,
            modifier = Modifier.width(80.dp),
        )
        Text(
            text = value,
            color = custom.fg2,
            fontFamily = MonoFontFamily,
            fontSize = 10.sp,
        )
    }
}
