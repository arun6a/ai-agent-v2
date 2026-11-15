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

// Hub section: Knowledge — saved documents with similarity search.

// ---------------------------------------------------------------------------
// Task 26 — Functional section: Knowledge (getAllKnowledge + recallKnowledge)
// ---------------------------------------------------------------------------

@Composable
internal fun KnowledgeSection() {
    val context = LocalContext.current
    val custom = LocalCustomColors.current
    val scope = rememberCoroutineScope()

    var allDocs by remember { mutableStateOf<List<Triple<String, String, String>>>(emptyList()) }
    var searchResults by remember { mutableStateOf<List<Triple<String, String, Float>>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableStateOf(0) }
    var toast by remember { mutableStateOf<String?>(null) }
    var uploading by remember { mutableStateOf(false) }
    // Long-press → delete dialog
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    // Expanded card tracking — name of the card currently expanded
    var expandedName by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit, refreshKey) {
        allDocs = try {
            AgentDatabase(context).getAllKnowledge()
        } catch (e: Exception) {
            emptyList()
        }
        loaded = true
    }

    // Live search (debounced via simple remember guard).
    LaunchedEffect(query, allDocs) {
        searchResults = if (query.trim().length < 2 || allDocs.isEmpty()) {
            emptyList()
        } else {
            try {
                AgentDatabase(context).recallKnowledge(query.trim(), limit = 5)
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    // Task 39 — file upload launcher (any file type).
    val uploadLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        uploading = true
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val resolver = context.contentResolver
                var displayName: String? = null
                var size = 0L
                resolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (cursor.moveToFirst()) {
                        if (nameIdx >= 0) displayName = cursor.getString(nameIdx)
                        if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) size = cursor.getLong(sizeIdx)
                    }
                }
                val name = displayName?.takeIf { it.isNotBlank() }
                    ?: "doc-${System.currentTimeMillis()}"
                // Read content for text-like files; otherwise save filename as placeholder.
                val lower = name.lowercase()
                val isText = lower.endsWith(".txt") || lower.endsWith(".md") ||
                    lower.endsWith(".json") || lower.endsWith(".csv") ||
                    lower.endsWith(".xml") || lower.endsWith(".yaml") || lower.endsWith(".yml")
                val content = if (isText) {
                    resolver.openInputStream(uri)?.use { input ->
                        input.bufferedReader().readText()
                    } ?: "(could not read file)"
                } else {
                    // PDF / image / etc — save metadata as content placeholder.
                    val sizeStr = if (size > 0) formatFileSize(size) else "unknown size"
                    "(binary file: $name — $sizeStr. Use readFile tool to access the file path.)"
                }
                AgentDatabase(context).saveKnowledge(
                    name = name,
                    content = content,
                    source = "uploaded",
                )
                refreshKey++
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    toast = "Uploaded: $name"
                    uploading = false
                }
            } catch (e: Exception) {
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    toast = "Upload failed: ${e.message ?: e::class.simpleName}"
                    uploading = false
                }
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            SectionHeader(
                title = "Knowledge",
                subtitle = "${allDocs.size} documents · ${if (loaded) "ready" else "loading…"}",
            )
        }

        // Task 39 — Upload button row.
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MemoryActionButton(
                    text = if (uploading) "Uploading…" else "↑ Upload document",
                    emphasized = true,
                    enabled = !uploading,
                    onClick = { uploadLauncher.launch("*/*") },
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "txt · md · json · csv · pdf · images",
                    color = custom.fg3,
                    fontFamily = MonoFontFamily,
                    fontSize = 10.sp,
                )
            }
        }

        item { KnowledgeSearchBar(query = query, onQueryChange = { query = it }) }

        if (query.trim().length >= 2) {
            if (searchResults.isEmpty() && loaded) {
                item {
                    PlaceholderCard(
                        title = "No matches",
                        detail = "No knowledge entries match \"$query\". Try broader keywords.",
                    )
                }
            } else {
                item {
                    Text(
                        text = "TOP MATCHES",
                        color = custom.fg3,
                        fontFamily = MonoFontFamily,
                        fontSize = 10.sp,
                    )
                }
                items(searchResults.size) { i ->
                    val (name, content, score) = searchResults[i]
                    KnowledgeRow(
                        name = name,
                        content = content,
                        source = "similarity ${(score * 100).toInt()}%",
                        accent = true,
                        expanded = expandedName == name,
                        onToggleExpand = { expandedName = if (expandedName == name) null else name },
                        onLongPress = { pendingDelete = name },
                    )
                }
            }
        } else {
            if (allDocs.isEmpty() && loaded) {
                item {
                    PlaceholderCard(
                        title = "No knowledge yet",
                        detail = "Tap ↑ Upload document to add a text file (.txt / .md / .json / .csv). " +
                            "Or save knowledge via chat — try \"remember that my wifi password is hunter2\".",
                    )
                }
            } else if (allDocs.isEmpty()) {
                item { LoadingCard() }
            } else {
                item {
                    Text(
                        text = "ALL DOCUMENTS",
                        color = custom.fg3,
                        fontFamily = MonoFontFamily,
                        fontSize = 10.sp,
                    )
                }
                items(allDocs.size) { i ->
                    val (name, content, source) = allDocs[i]
                    KnowledgeRow(
                        name = name,
                        content = content,
                        source = source,
                        accent = false,
                        expanded = expandedName == name,
                        onToggleExpand = { expandedName = if (expandedName == name) null else name },
                        onLongPress = { pendingDelete = name },
                    )
                }
            }
        }
    }

    // Long-press → delete confirmation.
    pendingDelete?.let { name ->
        ConfirmDialog(
            title = "Delete \"$name\"?",
            body = "This will permanently remove the knowledge entry. This cannot be undone.",
            confirmLabel = "Delete",
            onDismiss = { pendingDelete = null },
            onConfirm = {
                try {
                    AgentDatabase(context).deleteKnowledge(name)
                    refreshKey++
                    toast = "Deleted: $name"
                } catch (e: Exception) {
                    toast = "Delete failed: ${e.message}"
                }
                pendingDelete = null
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

private fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "${bytes}B"
    bytes < 1024 * 1024 -> "${bytes / 1024}KB"
    else -> String.format("%.1fMB", bytes / (1024.0 * 1024.0))
}

@Composable
private fun KnowledgeSearchBar(query: String, onQueryChange: (String) -> Unit) {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(custom.surface2, RoundedCornerShape(8.dp))
            .border(1.dp, custom.border, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Search, contentDescription = null, tint = custom.fg3, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Box(modifier = Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(
                    text = "Search knowledge…",
                    color = custom.fg3,
                    fontSize = 13.sp,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(
                    color = m3.onSurface,
                    fontSize = 13.sp,
                ),
                cursorBrush = SolidColor(custom.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(20.dp)) {
                Icon(Icons.Outlined.Refresh, contentDescription = "Clear", tint = custom.fg3, modifier = Modifier.size(14.dp))
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun KnowledgeRow(
    name: String,
    content: String,
    source: String,
    accent: Boolean,
    expanded: Boolean = false,
    onToggleExpand: () -> Unit = {},
    onLongPress: () -> Unit = {},
) {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (accent) custom.accent.copy(alpha = 0.06f) else custom.ink2,
                RoundedCornerShape(8.dp),
            )
            .border(
                1.dp,
                if (accent) custom.accent.copy(alpha = 0.3f) else custom.inkBorder,
                RoundedCornerShape(8.dp),
            )
            .combinedClickable(
                onClick = onToggleExpand,
                onLongClick = onLongPress,
            )
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .animateContentSize(),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            Icons.Outlined.BookmarkBorder,
            contentDescription = null,
            tint = if (accent) custom.accent else custom.fg3,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = name,
                    color = if (accent) custom.accent else m3.onSurface,
                    fontFamily = MonoFontFamily,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                if (content.length > 100) {
                    Text(
                        text = if (expanded) "▼" else "▶",
                        color = custom.fg3,
                        fontFamily = MonoFontFamily,
                        fontSize = 10.sp,
                    )
                }
            }
            Spacer(Modifier.size(2.dp))
            Text(
                text = if (expanded) content else content.take(100) + if (content.length > 100) "…" else "",
                color = custom.fg2,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = if (expanded) TextOverflow.Visible else TextOverflow.Ellipsis,
            )
            Spacer(Modifier.size(4.dp))
            Text(
                text = "source: $source · ${content.length} chars" +
                    if (expanded) " · tap to collapse" else " · tap to expand",
                color = custom.fg3,
                fontFamily = MonoFontFamily,
                fontSize = 9.sp,
            )
        }
    }
}
