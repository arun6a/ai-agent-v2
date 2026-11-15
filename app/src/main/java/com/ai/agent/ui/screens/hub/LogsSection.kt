package com.ai.agent.ui.screens.hub

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.agent.ui.components.LocalCustomColors
import com.ai.agent.util.LogCapture
import com.ai.agent.util.LogCapture.LogLine
import kotlinx.coroutines.delay
import java.io.File

/**
 * v4.0.7: Professional Logs section — task-grouped, searchable, auto-scroll aware.
 *
 * Features:
 *  - Task grouping: logs grouped by task (collapsible headers)
 *  - Search: free-text search across all log messages
 *  - Auto-scroll pause: detects when user scrolls up, stops auto-scroll
 *  - Error/warning count badges
 *  - Copy last task / Copy all / Export
 *  - Filter by level (All / Info / Warn / Error) + tag filter
 */
@Composable
internal fun LogsSection() {
    val custom = LocalCustomColors.current
    val context = LocalContext.current
    val listState = rememberLazyListState()

    var filterLevel by remember { mutableStateOf<String?>(null) }
    var filterTag by remember { mutableStateOf("") }
    var searchText by remember { mutableStateOf("") }
    var refreshKey by remember { mutableStateOf(0) }
    var autoScroll by remember { mutableStateOf(true) }
    var expandedTasks by remember { mutableStateOf(setOf<Int>()) }

    // Auto-refresh every 1 second so new logs appear live.
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            refreshKey++
        }
    }

    // Detect when user scrolls up → pause auto-scroll
    LaunchedEffect(listState) {
        snapshotFlow {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItems = listState.layoutInfo.totalItemsCount
            lastVisible < totalItems - 2
        }.collect { scrolledUp ->
            if (scrolledUp) autoScroll = false
        }
    }

    val allLogs = remember(refreshKey) { LogCapture.getAll() }
    val errorCount = remember(refreshKey) { LogCapture.getErrorCount() }
    val warningCount = remember(refreshKey) { LogCapture.getWarningCount() }

    // Apply filters
    val filteredLogs = allLogs.filter { line ->
        (filterLevel == null || line.level == filterLevel) &&
            (filterTag.isEmpty() || line.tag.contains(filterTag, ignoreCase = true)) &&
            (searchText.isEmpty() || line.message.contains(searchText, ignoreCase = true) ||
                line.tag.contains(searchText, ignoreCase = true))
    }

    // Group logs by task (each "===== Agent loop started" starts a new group)
    val taskGroups = remember(filteredLogs) {
        val groups = mutableListOf<MutableList<LogLine>>()
        var currentGroup = mutableListOf<LogLine>()
        for (line in filteredLogs) {
            if (line.message.contains("===== Agent loop started")) {
                if (currentGroup.isNotEmpty()) groups.add(currentGroup)
                currentGroup = mutableListOf(line)
            } else {
                currentGroup.add(line)
            }
        }
        if (currentGroup.isNotEmpty()) groups.add(currentGroup)
        groups
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        // ---- Stats bar ----
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${filteredLogs.size} lines",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = custom.fg2,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (errorCount > 0) {
                    Row(
                        modifier = Modifier
                            .background(Color(0xFFFF6B6B).copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.Error, contentDescription = null, tint = Color(0xFFFF6B6B), modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(3.dp))
                        Text("$errorCount", color = Color(0xFFFF6B6B), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
                if (warningCount > 0) {
                    Row(
                        modifier = Modifier
                            .background(Color(0xFFFFD93D).copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.Warning, contentDescription = null, tint = Color(0xFFFFD93D), modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(3.dp))
                        Text("$warningCount", color = Color(0xFFFFD93D), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
                // Copy last task
                IconButton(onClick = {
                    val text = LogCapture.getLastTaskAsText()
                    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                    clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("last_task_logs", text))
                    Toast.makeText(context, "Copied last task (${text.lines().size} lines)", Toast.LENGTH_SHORT).show()
                }) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy last task", tint = custom.fg2, modifier = Modifier.size(18.dp))
                }
                // Copy all
                IconButton(onClick = {
                    val text = LogCapture.getAllAsText()
                    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                    clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("logs", text))
                    Toast.makeText(context, "Copied ${allLogs.size} lines", Toast.LENGTH_SHORT).show()
                }) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy all", tint = custom.fg2, modifier = Modifier.size(18.dp))
                }
                // Export
                IconButton(onClick = {
                    try {
                        val dir = File("/storage/emulated/0/Aira")
                        if (!dir.exists()) dir.mkdirs()
                        val file = File(dir, "logs.txt")
                        file.writeText(LogCapture.getAllAsText())
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "Aira logs")
                            putExtra(Intent.EXTRA_TEXT, LogCapture.getAllAsText().take(10000))
                            putExtra(Intent.EXTRA_STREAM, android.net.Uri.fromFile(file))
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "Share logs"))
                    } catch (e: Exception) {
                        Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }) {
                    Icon(Icons.Outlined.IosShare, contentDescription = "Export", tint = custom.fg2, modifier = Modifier.size(18.dp))
                }
                // Clear
                IconButton(onClick = {
                    LogCapture.clear()
                    refreshKey++
                    Toast.makeText(context, "Logs cleared", Toast.LENGTH_SHORT).show()
                }) {
                    Icon(Icons.Outlined.Clear, contentDescription = "Clear", tint = custom.fg2, modifier = Modifier.size(18.dp))
                }
            }
        }

        // ---- Search bar ----
        OutlinedTextField(
            value = searchText,
            onValueChange = { searchText = it },
            placeholder = { Text("Search logs...", fontSize = 13.sp) },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
        )

        // ---- Filter chips ----
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(selected = filterLevel == null, onClick = { filterLevel = null }, label = { Text("All", fontSize = 11.sp) })
            FilterChip(selected = filterLevel == "I", onClick = { filterLevel = if (filterLevel == "I") null else "I" }, label = { Text("Info", fontSize = 11.sp) })
            FilterChip(selected = filterLevel == "W", onClick = { filterLevel = if (filterLevel == "W") null else "W" }, label = { Text("Warn", fontSize = 11.sp) })
            FilterChip(selected = filterLevel == "E", onClick = { filterLevel = if (filterLevel == "E") null else "E" }, label = { Text("Error", fontSize = 11.sp) })
            OutlinedTextField(
                value = filterTag,
                onValueChange = { filterTag = it },
                placeholder = { Text("Tag", fontSize = 11.sp) },
                modifier = Modifier.width(120.dp),
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp),
            )
        }

        // ---- Log list (task-grouped) ----
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                if (searchText.isNotEmpty() || filterLevel != null || filterTag.isNotEmpty()) {
                    // When filtering, show flat list (no grouping)
                    items(filteredLogs) { line ->
                        LogLineRow(line, custom)
                    }
                } else {
                    // Grouped by task
                    taskGroups.forEachIndexed { groupIndex, group ->
                        val isExpanded = expandedTasks.contains(groupIndex)
                        val taskTitle = group.firstOrNull { it.message.contains("===== Agent loop started") }?.message
                            ?: "Other logs"
                        val hasError = group.any { it.level == "E" }
                        val hasWarning = group.any { it.level == "W" }

                        // Task header
                        item(key = "header_$groupIndex") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(custom.surface2, RoundedCornerShape(8.dp))
                                    .clickable {
                                        expandedTasks = if (isExpanded) {
                                            expandedTasks - groupIndex
                                        } else {
                                            expandedTasks + groupIndex
                                        }
                                    }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = if (isExpanded) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
                                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                                    tint = custom.fg2,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = taskTitle.take(60),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = custom.fg2,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1,
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "${group.size} lines",
                                    fontSize = 9.sp,
                                    color = custom.fg3,
                                )
                                if (hasError) {
                                    Spacer(Modifier.width(6.dp))
                                    Text("⚠️", fontSize = 10.sp)
                                }
                                if (hasWarning) {
                                    Spacer(Modifier.width(2.dp))
                                    Text("⚡", fontSize = 10.sp)
                                }
                            }
                        }

                        // Task logs (only when expanded)
                        if (isExpanded || groupIndex == taskGroups.lastIndex) {
                            items(group.drop(1)) { line ->
                                LogLineRow(line, custom)
                            }
                        }
                    }
                }
            }

            // Jump to bottom button (when auto-scroll is paused)
            if (!autoScroll) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(12.dp)
                        .background(custom.accent, RoundedCornerShape(20.dp))
                        .clickable {
                            autoScroll = true
                        }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text("↓ Latest", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }

    // Auto-scroll to bottom when new logs arrive (only if autoScroll is on)
    LaunchedEffect(filteredLogs.size, autoScroll) {
        if (autoScroll && filteredLogs.isNotEmpty()) {
            listState.animateScrollToItem(filteredLogs.size - 1)
        }
    }
}

@Composable
private fun LogLineRow(line: LogLine, custom: com.ai.agent.ui.components.CustomColors) {
    val color = when (line.level) {
        "E" -> Color(0xFFFF6B6B)
        "W" -> Color(0xFFFFD93D)
        "D" -> Color(0xFF6BCB77)
        else -> custom.fg2
    }
    Text(
        text = LogCapture.formatLine(line),
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        color = color,
        modifier = Modifier.fillMaxWidth(),
    )
}
