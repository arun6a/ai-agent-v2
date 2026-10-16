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

// Hub section: Apps — AppProfileManager integration (per-app AI access).

// ---------------------------------------------------------------------------
// v2.3.3: Apps section — replaces old Plugins. Uses AppProfileManager
// to detect installed apps and let user enable/disable AI access per-app.
// ---------------------------------------------------------------------------

@Composable
internal fun AppsSection() {
    val context = LocalContext.current
    val custom = LocalCustomColors.current

    var profiles by remember { mutableStateOf<List<com.ai.agent.llm.AppProfileManager.AppProfile>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        profiles = try {
            com.ai.agent.llm.AppProfileManager.refreshAndGet(context)
        } catch (e: Exception) {
            emptyList()
        }
        loaded = true
    }

    val installed = profiles.filter { it.installed }
    val notInstalled = profiles.filter { !it.installed }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            SectionHeader(
                title = "Apps",
                subtitle = "${installed.size} installed · ${installed.count { it.enabled }} AI-enabled",
            )
        }
        if (profiles.isEmpty() && !loaded) {
            item { LoadingCard() }
        }
        if (installed.isNotEmpty()) {
            item {
                Text(
                    text = "DETECTED ON DEVICE",
                    color = custom.fg3,
                    fontFamily = MonoFontFamily,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                )
            }
            items(installed, key = { it.packageName }) { profile ->
                AppCard(
                    profile = profile,
                    onToggle = { newValue ->
                        com.ai.agent.llm.AppProfileManager.setEnabled(context, profile.packageName, newValue)
                        profiles = profiles.map {
                            if (it.packageName == profile.packageName) it.copy(enabled = newValue) else it
                        }
                    },
                )
            }
        }
        if (notInstalled.isNotEmpty()) {
            item {
                Spacer(Modifier.size(8.dp))
                Text(
                    text = "NOT INSTALLED (tap to enable anyway)",
                    color = custom.fg3,
                    fontFamily = MonoFontFamily,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            items(notInstalled, key = { it.packageName }) { profile ->
                AppCard(
                    profile = profile,
                    onToggle = { newValue ->
                        com.ai.agent.llm.AppProfileManager.setEnabled(context, profile.packageName, newValue)
                        profiles = profiles.map {
                            if (it.packageName == profile.packageName) it.copy(enabled = newValue) else it
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun AppCard(profile: com.ai.agent.llm.AppProfileManager.AppProfile, onToggle: (Boolean) -> Unit) {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme
    val active = profile.installed && profile.enabled

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (active) custom.accent.copy(alpha = 0.06f) else custom.ink2,
                RoundedCornerShape(8.dp),
            )
            .border(
                1.dp,
                if (active) custom.accent.copy(alpha = 0.3f) else custom.inkBorder,
                RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(
                        if (active) custom.accent else custom.surface3,
                        RoundedCornerShape(8.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = profile.displayName.take(2).uppercase(),
                    color = if (active) Color.White else custom.fg3,
                    fontFamily = MonoFontFamily,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = profile.displayName,
                    color = m3.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = profile.category.name.lowercase() + if (profile.shortcutTool != null) " · has shortcut" else "",
                    color = custom.fg3,
                    fontFamily = MonoFontFamily,
                    fontSize = 10.sp,
                )
            }
            Switch(
                checked = profile.enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = custom.accent,
                    uncheckedThumbColor = custom.fg3,
                    uncheckedTrackColor = custom.surface3,
                ),
            )
        }
        if (profile.shortcutTool != null) {
            Spacer(Modifier.size(6.dp))
            Text(
                text = "→ ${profile.shortcutTool}",
                color = custom.fg2,
                fontFamily = MonoFontFamily,
                fontSize = 10.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (profile.tip != null) {
            Spacer(Modifier.size(4.dp))
            Text(
                text = profile.tip,
                color = custom.fg3,
                fontSize = 10.sp,
                lineHeight = 13.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!profile.installed) {
            Spacer(Modifier.size(4.dp))
            Text(
                text = "pkg: ${profile.packageName}",
                color = custom.fg3,
                fontFamily = MonoFontFamily,
                fontSize = 9.sp,
            )
        }
    }
}
