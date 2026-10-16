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

// Hub section: Rules — automation rules from AgentDatabase.

// ---------------------------------------------------------------------------
// Task 23 — Functional section: Rules (uses AgentDatabase.getRules())
// ---------------------------------------------------------------------------

@Composable
internal fun RulesSection() {
    val custom = LocalCustomColors.current
    val context = LocalContext.current

    var rules by remember { mutableStateOf<List<AgentDatabase.Rule>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var lastToast by remember { mutableStateOf<String?>(null) }
    val actions = remember { AgentDatabase(context).getRecentActions(30) }

    LaunchedEffect(Unit) {
        rules = try {
            AgentDatabase(context).getRules(enabledOnly = false)
        } catch (e: Exception) {
            emptyList()
        }
        loaded = true
    }

    val activeCount = rules.count { it.enabled }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            SectionHeader(
                title = "Rules",
                subtitle = "$activeCount / ${rules.size} active · ${if (loaded) "ready" else "loading…"}",
            )
        }
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(custom.accent.copy(alpha = 0.10f), RoundedCornerShape(8.dp))
                    .border(1.dp, custom.accent.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                    .clickable {
                        lastToast = "New Rule editor coming soon — create rules via chat: \"create a rule…\""
                    }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Rule, contentDescription = null, tint = custom.accent, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "New Rule",
                    color = custom.accent,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "via chat →",
                    color = custom.fg3,
                    fontFamily = MonoFontFamily,
                    fontSize = 10.sp,
                )
            }
        }
        if (rules.isEmpty() && loaded) {
            item {
                PlaceholderCard(
                    title = "No rules yet",
                    detail = "Create rules via the chat screen — try \"create a rule that silences my phone at 10pm\".",
                )
            }
        } else if (rules.isEmpty()) {
            item { LoadingCard() }
        } else {
            items(rules, key = { it.id }) { rule ->
                RuleRow(
                    rule = rule,
                    onToggle = { newValue ->
                        try {
                            AgentDatabase(context).toggleRule(rule.id, newValue)
                            rules = rules.map { if (it.id == rule.id) it.copy(enabled = newValue) else it }
                        } catch (e: Exception) {
                            lastToast = "Toggle failed: ${e.message}"
                        }
                    },
                    onEdit = { newName, newAction ->
                        try {
                            AgentDatabase(context).updateRule(rule.id, name = newName, action = newAction)
                            rules = rules.map { if (it.id == rule.id) it.copy(name = newName, action = newAction) else it }
                            lastToast = "Rule updated: $newName"
                        } catch (e: Exception) {
                            lastToast = "Update failed: ${e.message}"
                        }
                    },
                    onDelete = {
                        try {
                            AgentDatabase(context).deleteRule(rule.id)
                            rules = rules.filterNot { it.id == rule.id }
                            lastToast = "Rule deleted: ${rule.name}"
                        } catch (e: Exception) {
                            lastToast = "Delete failed: ${e.message}"
                        }
                    },
                )
            }
        }

        // v3.0.5: Recent Activity (Rules History)
        item {
            Spacer(Modifier.size(16.dp))
            SectionHeader(
                title = "Recent Activity",
                subtitle = "Last rule fires + results",
            )
        }
        if (actions.isEmpty()) {
            item {
                Text(
                    text = "No rule activity yet",
                    color = custom.fg3,
                    fontSize = 12.sp,
                    fontFamily = MonoFontFamily,
                    modifier = Modifier.padding(16.dp),
                )
            }
        } else {
            items(actions, key = { it.id }) { entry ->
                val sdf = java.text.SimpleDateFormat("MMM dd HH:mm", java.util.Locale.getDefault())
                val timeStr = sdf.format(java.util.Date(entry.timestamp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(custom.ink2, RoundedCornerShape(8.dp))
                        .border(1.dp, custom.inkBorder, RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        text = timeStr,
                        color = custom.fg3,
                        fontFamily = MonoFontFamily,
                        fontSize = 10.sp,
                        modifier = Modifier.width(80.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = entry.text,
                        color = custom.fg2,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }

    LaunchedEffect(lastToast) {
        lastToast?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            lastToast = null
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RuleRow(
    rule: AgentDatabase.Rule,
    onToggle: (Boolean) -> Unit,
    onEdit: (String, String) -> Unit,
    onDelete: () -> Unit,
) {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme

    // v2.3.2: Long-press menu state
    var showMenu by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {},
                onLongClick = { showMenu = true },
            )
            .background(
                if (rule.enabled) custom.accent.copy(alpha = 0.06f) else custom.ink2,
                RoundedCornerShape(8.dp),
            )
            .border(
                1.dp,
                if (rule.enabled) custom.accent.copy(alpha = 0.3f) else custom.inkBorder,
                RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(
                        if (rule.enabled) custom.accent.copy(alpha = 0.15f) else custom.surface3,
                        RoundedCornerShape(8.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Rule,
                    contentDescription = null,
                    tint = if (rule.enabled) custom.accent else custom.fg3,
                    modifier = Modifier.size(14.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = rule.name,
                    color = if (rule.enabled) m3.onSurface else custom.fg2,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.size(2.dp))
                Text(
                    text = "trigger: ${rule.triggerType} · ${rule.triggerValue}",
                    color = custom.fg3,
                    fontFamily = MonoFontFamily,
                    fontSize = 10.sp,
                )
                Spacer(Modifier.size(2.dp))
                Text(
                    text = "→ ${rule.action}",
                    color = custom.fg2,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = rule.enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = custom.accent,
                    uncheckedThumbColor = custom.fg3,
                    uncheckedTrackColor = custom.surface3,
                ),
            )
        }

        // v2.3.2: Long-press hint
        Text(
            text = "long-press to edit/delete",
            color = custom.fg3,
            fontFamily = MonoFontFamily,
            fontSize = 9.sp,
            modifier = Modifier.padding(top = 4.dp, start = 38.dp),
        )
    }

    // v2.3.2: Long-press menu
    if (showMenu) {
        AlertDialog(
            onDismissRequest = { showMenu = false },
            title = { Text(rule.name) },
            text = { Text("trigger: ${rule.triggerType} · ${rule.triggerValue}\naction: ${rule.action}") },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        showMenu = false
                        showEditDialog = true
                    }) { Text("Edit", color = custom.accent) }
                    TextButton(onClick = {
                        showMenu = false
                        showDeleteDialog = true
                    }) { Text("Delete", color = Color(0xFFEF4444)) }
                }
            },
            dismissButton = {
                TextButton(onClick = { showMenu = false }) { Text("Cancel") }
            },
        )
    }

    // v2.3.2: Edit dialog
    if (showEditDialog) {
        var editName by remember { mutableStateOf(rule.name) }
        var editAction by remember { mutableStateOf(rule.action) }
        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            title = { Text("Edit Rule") },
            text = {
                Column {
                    Text("Name", color = custom.fg3, fontSize = 11.sp)
                    OutlinedTextField(
                        value = editName,
                        onValueChange = { editName = it },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    )
                    Text("Action", color = custom.fg3, fontSize = 11.sp)
                    OutlinedTextField(
                        value = editAction,
                        onValueChange = { editAction = it },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onEdit(editName, editAction)
                    showEditDialog = false
                }) { Text("Save", color = custom.accent) }
            },
            dismissButton = {
                TextButton(onClick = { showEditDialog = false }) { Text("Cancel") }
            },
        )
    }

    // v2.3.2: Delete confirmation
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Rule") },
            text = { Text("Are you sure you want to delete \"${rule.name}\"?") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete()
                    showDeleteDialog = false
                }) { Text("Delete", color = Color(0xFFEF4444)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            },
        )
    }
}
