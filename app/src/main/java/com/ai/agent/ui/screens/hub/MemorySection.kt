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

// Hub section: Memory — view / edit / add memory entries.

// ---------------------------------------------------------------------------
// Functional section: Memory (uses database.getAllMemory())
// ---------------------------------------------------------------------------

@Composable
internal fun MemorySection() {
    val context = LocalContext.current

    var memory by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var loaded by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var refreshKey by remember { mutableStateOf(0) }
    var toast by remember { mutableStateOf<String?>(null) }

    // Long-press → edit/delete dialog
    var editingEntry by remember { mutableStateOf<Pair<String, String>?>(null) }
    // Add-memory dialog
    var showAddDialog by remember { mutableStateOf(false) }
    // Clear-all confirmation dialog
    var showClearDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit, refreshKey) {
        memory = try {
            AgentDatabase(context).getAllMemory()
        } catch (e: Exception) {
            emptyMap()
        }
        loaded = true
    }

    val filteredEntries = remember(memory, searchQuery) {
        val q = searchQuery.trim().lowercase()
        if (q.isEmpty()) memory.entries.toList()
        else memory.entries.filter { (k, v) ->
            k.lowercase().contains(q) || v.lowercase().contains(q)
        }.toList()
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            SectionHeader(
                title = "Memory",
                subtitle = "${memory.size} keys · ${if (loaded) "ready" else "loading…"}" +
                    if (searchQuery.isNotBlank()) " · ${filteredEntries.size} match" else "",
            )
        }
        // Toolbar: search + Add + Clear all
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(modifier = Modifier.weight(1f)) {
                    MemorySearchBar(query = searchQuery, onQueryChange = { searchQuery = it })
                }
                MemoryActionButton(
                    text = "+ Add",
                    emphasized = true,
                    onClick = { showAddDialog = true },
                )
                MemoryActionButton(
                    text = "Clear all",
                    emphasized = false,
                    enabled = memory.isNotEmpty(),
                    onClick = { showClearDialog = true },
                )
            }
        }

        if (memory.isEmpty() && loaded) {
            item { PlaceholderCard(title = "No memories stored", detail = "Ask the agent to remember something — try \"remember my name is Arun\". Or tap + Add to create one manually.") }
        } else if (memory.isEmpty()) {
            item { LoadingCard() }
        } else if (filteredEntries.isEmpty()) {
            item { PlaceholderCard(title = "No matches", detail = "Nothing matches \"$searchQuery\".") }
        } else {
            items(filteredEntries, key = { it.key }) { entry ->
                MemoryRow(
                    key = entry.key,
                    value = entry.value,
                    onLongPress = { editingEntry = entry.key to entry.value },
                )
            }
        }
    }

    // ---- Long-press edit / delete dialog ----
    editingEntry?.let { (k, v) ->
        MemoryEditDialog(
            initialKey = k,
            initialValue = v,
            onDismiss = { editingEntry = null },
            onSave = { newKey, newValue ->
                try {
                    // If key changed, forget the old one and remember the new
                    if (newKey != k) AgentDatabase(context).forgetMemory(k)
                    AgentDatabase(context).remember(newKey, newValue)
                    refreshKey++
                    toast = "Saved: $newKey"
                } catch (e: Exception) {
                    toast = "Save failed: ${e.message}"
                }
                editingEntry = null
            },
            onDelete = {
                try {
                    AgentDatabase(context).forgetMemory(k)
                    refreshKey++
                    toast = "Deleted: $k"
                } catch (e: Exception) {
                    toast = "Delete failed: ${e.message}"
                }
                editingEntry = null
            },
        )
    }

    // ---- Add memory dialog ----
    if (showAddDialog) {
        MemoryAddDialog(
            onDismiss = { showAddDialog = false },
            onSave = { key, value ->
                if (key.isBlank()) {
                    toast = "Key cannot be blank"
                } else {
                    try {
                        AgentDatabase(context).remember(key.trim(), value)
                        refreshKey++
                        toast = "Memory saved: ${key.trim()}"
                    } catch (e: Exception) {
                        toast = "Save failed: ${e.message}"
                    }
                }
                showAddDialog = false
            },
        )
    }

    // ---- Clear all confirmation ----
    if (showClearDialog) {
        ConfirmDialog(
            title = "Clear all memory?",
            body = "This will permanently delete all ${memory.size} memory entries. This cannot be undone.",
            confirmLabel = "Clear all",
            onDismiss = { showClearDialog = false },
            onConfirm = {
                try {
                    AgentDatabase(context).clearMemory()
                    refreshKey++
                    toast = "All memory cleared"
                } catch (e: Exception) {
                    toast = "Clear failed: ${e.message}"
                }
                showClearDialog = false
            },
        )
    }

    LaunchedEffect(toast) {
        toast?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            toast = null
        }
    }
}

@Composable
private fun MemorySearchBar(query: String, onQueryChange: (String) -> Unit) {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(custom.surface2, RoundedCornerShape(8.dp))
            .border(1.dp, custom.border, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Search, contentDescription = null, tint = custom.fg3, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Box(modifier = Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(text = "Search memory…", color = custom.fg3, fontSize = 12.sp)
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(
                    color = m3.onSurface,
                    fontSize = 12.sp,
                ),
                cursorBrush = SolidColor(custom.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(20.dp)) {
                Icon(Icons.Outlined.Refresh, contentDescription = "Clear", tint = custom.fg3, modifier = Modifier.size(12.dp))
            }
        }
    }
}

@Composable
internal fun MemoryActionButton(
    text: String,
    emphasized: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val custom = LocalCustomColors.current
    Row(
        modifier = Modifier
            .background(
                when {
                    !enabled -> custom.surface3
                    emphasized -> custom.accent
                    else -> custom.surface2
                },
                RoundedCornerShape(8.dp),
            )
            .border(
                1.dp,
                when {
                    !enabled -> custom.border
                    emphasized -> custom.accent
                    else -> custom.border
                },
                RoundedCornerShape(8.dp),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            color = when {
                !enabled -> custom.fg3
                emphasized -> Color.White
                else -> custom.fg2
            },
            fontFamily = MonoFontFamily,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun MemoryRow(key: String, value: String, onLongPress: () -> Unit) {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(custom.ink2, RoundedCornerShape(8.dp))
            .border(1.dp, custom.inkBorder, RoundedCornerShape(8.dp))
            .combinedClickable(
                onClick = {},
                onLongClick = onLongPress,
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            Icons.Outlined.Memory,
            contentDescription = null,
            tint = custom.accent,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = key,
                color = custom.accent,
                fontFamily = MonoFontFamily,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.size(2.dp))
            Text(
                text = value,
                color = m3.onSurface,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.size(4.dp))
            Text(
                text = "${value.length} chars · long-press to edit",
                color = custom.fg3,
                fontFamily = MonoFontFamily,
                fontSize = 9.sp,
            )
        }
    }
}

@Composable
private fun MemoryEditDialog(
    initialKey: String,
    initialValue: String,
    onDismiss: () -> Unit,
    onSave: (key: String, value: String) -> Unit,
    onDelete: () -> Unit,
) {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme
    var key by remember { mutableStateOf(initialKey) }
    var value by remember { mutableStateOf(initialValue) }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = m3.surface,
        title = {
            Text(
                text = "Edit Memory",
                color = m3.onSurface,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("KEY", color = custom.fg3, fontFamily = MonoFontFamily, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                DialogTextField(value = key, onValueChange = { key = it }, placeholder = "memory_key")
                Spacer(Modifier.height(4.dp))
                Text("VALUE", color = custom.fg3, fontFamily = MonoFontFamily, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                DialogTextField(
                    value = value,
                    onValueChange = { value = it },
                    placeholder = "value",
                    singleLine = false,
                    minLines = 3,
                )
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.TextButton(onClick = onDelete) {
                    Text("Delete", color = custom.error, fontWeight = FontWeight.Medium)
                }
                androidx.compose.material3.TextButton(onClick = { onSave(key, value) }) {
                    Text("Save", color = custom.accent, fontWeight = FontWeight.SemiBold)
                }
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("Cancel", color = custom.fg2)
            }
        },
    )
}

@Composable
private fun MemoryAddDialog(
    onDismiss: () -> Unit,
    onSave: (key: String, value: String) -> Unit,
) {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme
    var key by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = m3.surface,
        title = {
            Text(
                text = "Add Memory",
                color = m3.onSurface,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("KEY", color = custom.fg3, fontFamily = MonoFontFamily, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                DialogTextField(value = key, onValueChange = { key = it }, placeholder = "e.g. user_name")
                Spacer(Modifier.height(4.dp))
                Text("VALUE", color = custom.fg3, fontFamily = MonoFontFamily, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                DialogTextField(
                    value = value,
                    onValueChange = { value = it },
                    placeholder = "e.g. Arun",
                    singleLine = false,
                    minLines = 2,
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { onSave(key, value) }) {
                Text("Save", color = custom.accent, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("Cancel", color = custom.fg2)
            }
        },
    )
}
