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

// Hub section: Tools — grouped view of ToolExecutor.getAvailableToolNames().

// ---------------------------------------------------------------------------
// Task 24 — Functional section: Tools (groups ToolExecutor.getAvailableToolNames)
// ---------------------------------------------------------------------------

private data class ToolCategory(
    val name: String,
    val icon: ImageVector,
    val tools: List<String>,
    val accent: Boolean = false,
)

private fun buildToolCategories(allTools: List<String>): List<ToolCategory> {
    val byPrefix: (String) -> Boolean = { prefix -> allTools.any { it.startsWith(prefix) } }
    val categories = mutableListOf<ToolCategory>()

    fun select(predicate: (String) -> Boolean): List<String> = allTools.filter(predicate)

    categories += ToolCategory("Screen", Icons.Outlined.Visibility, select {
        it in setOf(
            "readScreen", "readScreenStructured", "tap", "clickByText", "clickElement",
            "longClick", "longClickElement", "focusElement", "paste", "edit",
            "type", "swipe", "scrollDown", "scrollUp", "pressBack", "pressHome", "pressEnter", "submitInput",
            "lockScreen", "takeScreenshotToGallery",
        )
    })
    categories += ToolCategory("Apps", Icons.Outlined.Bolt, select {
        it in setOf(
            "launchApp", "listInstalledApps", "getAppInfo", "forceStopApp", "uninstallApp",
            "openDialer", "openContact", "openSettings", "shareText", "shareFile",
            "openActivity", "openDeepLink", "openAppWithData", "searchInApp",
            "listAppActivities", "openWhatsAppChat", "openYouTubeVideo", "openMapsLocation",
            "playSpotify", "openInstagramProfile", "openTelegramChat",
            "openGoogleSearch", "shareToApp",
        )
    })
    categories += ToolCategory("Vision", Icons.Outlined.Visibility, select {
        it in setOf("analyzeScreen", "findElement", "translateText")
    })
    categories += ToolCategory("Browser", Icons.Outlined.Public, select { it.startsWith("browser") || it in setOf("openInChrome", "searchInChrome") })
    categories += ToolCategory("Web", Icons.Outlined.Cloud, select {
        it in setOf("webSearch", "makeHttpRequest", "downloadFile", "openUrl", "fetchPageText")
    })
    // v4.0.39: New Search tools (headless + API-based)
    categories += ToolCategory("Search", Icons.Outlined.Search, select {
        it in setOf("headlessGoogleSearch", "headlessSearch", "braveSearch", "googleSearchApi", "smartSearch")
    })
    categories += ToolCategory("Device", Icons.Outlined.Build, select {
        it in setOf(
            "getBatteryLevel", "getCurrentTime", "getBluetoothState", "getNetworkInfo",
            "getDeviceInfo", "pingHost", "getVolume", "setVolume", "setBrightness",
            "toggleFlashlight", "toggleWifi",
        )
    })
    // v4.0.39: New Device Info tools
    categories += ToolCategory("Device Info", Icons.Outlined.Info, select {
        it in setOf(
            "getBrightness", "getFlashlightState", "getDeviceUptime",
            "getMemoryInfo", "getStorageInfo", "getBatteryHealth",
            "getScreenOrientation", "isCharging",
            "getRingtoneMode", "setRingtoneMode",
        )
    })
    // v4.0.39: Permission-based tools
    categories += ToolCategory("Permissions", Icons.Outlined.Lock, select {
        it in setOf("readSms", "vibrate", "getPhoneState", "wakeLock")
    })
    categories += ToolCategory("Memory", Icons.Outlined.Memory, select {
        it in setOf(
            "remember", "recall", "recallAll", "recallSimilar", "searchMemory",
            "saveToKnowledge", "recallKnowledge", "listKnowledge",
            "getClipboard", "setClipboard",
            "getConversationHistory", "getFullMemory",  // v4.0.39: on-demand context
        )
    })
    categories += ToolCategory("Location", Icons.Outlined.Public, select {
        it in setOf("getCurrentLocation", "openMaps")
    })
    categories += ToolCategory("Rules", Icons.Outlined.Rule, select {
        it in setOf("createRule", "listRules", "deleteRule", "modifyRule")
    })
    categories += ToolCategory("Contacts", Icons.Outlined.Verified, select {
        it in setOf(
            "readContacts", "searchContacts", "getCallLog", "openContact", "openDialer",
            "makePhoneCall", "callContact", "sendSMS", "sendEmail", "composeEmail",
            "getCalendarEvents", "createCalendarEvent", "setAlarm", "setTimer",
        )
    })
    categories += ToolCategory("Files", Icons.Outlined.Folder, select {
        it in setOf(
            "listFiles", "readFile", "writeFile", "copyFile", "moveFile", "deleteFile", "createDirectory",
            "listAttachments", "readAttachment", "sendAttachmentTo",
        )
    })
    categories += ToolCategory("Media", Icons.Outlined.GraphicEq, select {
        it in setOf("mediaPlayPause", "mediaNext", "mediaPrevious", "takePhoto")
    })
    categories += ToolCategory("Skills", Icons.Outlined.School, select {
        it in setOf("executeSkill", "listSkills", "createSkill", "deleteSkill", "getSkill")
    })
    categories += ToolCategory("Async", Icons.Outlined.Refresh, select {
        it in setOf("wait", "waitAndContinue", "runShellCommand", "actionChain")
    })
    // v4.0.39: Custom tools (created by AI via createCustomTool)
    categories += ToolCategory("Custom Tools", Icons.Outlined.Extension, select {
        it in setOf("createCustomTool", "listCustomTools", "deleteCustomTool")
    })
    // v4.0.39: Navigation + Wallpaper
    categories += ToolCategory("System", Icons.Outlined.Settings, select {
        it in setOf("navigateToAppScreen", "listAppScreens", "setWallpaper", "complete")
    })

    // Anything not bucketed lands in Misc.
    val bucketed = categories.flatMap { it.tools }.toSet()
    val misc = allTools.filter { it !in bucketed }
    if (misc.isNotEmpty()) {
        categories += ToolCategory("Misc", Icons.Outlined.MoreVert, misc)
    }

    // Accent the "terminal-like" categories — Apps + Device can drive the OS directly.
    return categories.mapIndexed { idx, c ->
        if (c.name == "Apps") c.copy(accent = true) else c
    }
}

@Composable
internal fun ToolsSection() {
    val context = LocalContext.current
    // v4.0.39: Include custom tools (created by AI via createCustomTool) in the list
    val allTools = remember {
        ToolExecutor.getAvailableToolNames() + com.ai.agent.tools.custom.CustomToolStore.getToolNames(context)
    }
    val categories = remember(allTools) { buildToolCategories(allTools) }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(2) }) {
            SectionHeader(
                title = "Tools",
                subtitle = "${allTools.size} tools · ${categories.size} categories",
            )
        }
        items(categories, key = { it.name }) { cat ->
            ToolCategoryCard(cat)
        }
    }
}

@Composable
private fun ToolCategoryCard(category: ToolCategory) {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme

    // v2.0.7: Expandable — tap card to toggle between collapsed (8 tools)
    // and expanded (all tools) views. Fixes "tools category not expanding".
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (category.accent) custom.accent.copy(alpha = 0.06f) else custom.ink2,
                RoundedCornerShape(8.dp),
            )
            .border(
                1.dp,
                if (category.accent) custom.accent.copy(alpha = 0.3f) else custom.inkBorder,
                RoundedCornerShape(8.dp),
            )
            .clickable { expanded = !expanded }
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .background(
                        if (category.accent) custom.accent else custom.surface3,
                        RoundedCornerShape(6.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    category.icon,
                    contentDescription = null,
                    tint = if (category.accent) Color.White else custom.fg3,
                    modifier = Modifier.size(12.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = category.name,
                color = m3.onSurface,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = category.tools.size.toString(),
                color = if (category.accent) custom.accent else custom.fg3,
                fontFamily = MonoFontFamily,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.width(8.dp))
            // v2.0.7: Expand/collapse indicator
            Text(
                text = if (expanded) "▼" else "▶",
                color = custom.fg3,
                fontSize = 10.sp,
            )
        }
        Spacer(Modifier.size(6.dp))
        HorizontalDivider(color = custom.inkBorder, thickness = 1.dp)
        Spacer(Modifier.size(6.dp))
        // v2.0.7: Show all tools when expanded, first 8 + "+N more" when collapsed.
        val shown = if (expanded) category.tools else category.tools.take(8)
        val more = category.tools.size - shown.size
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            shown.forEach { name ->
                Text(
                    text = "· $name",
                    color = custom.fg2,
                    fontFamily = MonoFontFamily,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (more > 0) {
                Text(
                    text = "+ $more more · tap to expand",
                    color = custom.fg3,
                    fontFamily = MonoFontFamily,
                    fontSize = 10.sp,
                )
            } else if (expanded && category.tools.size > 8) {
                Text(
                    text = "· all ${category.tools.size} shown · tap to collapse",
                    color = custom.fg3,
                    fontFamily = MonoFontFamily,
                    fontSize = 10.sp,
                )
            }
        }
    }
}
