package com.ai.agent.ui.screens.hub

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.delay
import java.io.File

/**
 * v3.7.2: Hub → Logs section.
 *
 * Shows the last 500 in-app log lines captured by [LogCapture] (the ring
 * buffer that mirrors system logcat). This is the user's only window into
 * what the brain is doing — they have no logcat access on Android 13+ MIUI.
 *
 * Features:
 *  - Live-updating list (auto-refresh every 1s), newest at the bottom.
 *  - Color-coded by level: info=gray, warn=yellow, error=red, debug=green.
 *  - Filter chips: All / Info / Warn / Error + free-text tag filter.
 *  - Clear button — empties the buffer.
 *  - Copy button — copies all visible logs to clipboard.
 *  - Export button — writes to Documents/ai-workspace/logs.txt + share sheet.
 *
 * Testing loop this enables:
 *   1. Send a message in chat.
 *   2. Open Hub → Logs.
 *   3. Tap Copy.
 *   4. Paste into the AI chat → diagnose in seconds.
 */
@Composable
internal fun LogsSection() {
    val custom = LocalCustomColors.current
    val context = LocalContext.current
    val listState = rememberLazyListState()

    var filterLevel by remember { mutableStateOf<String?>(null) }
    var filterTag by remember { mutableStateOf("") }
    var refreshKey by remember { mutableStateOf(0) }

    // Auto-refresh every 1 second so new logs appear live.
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            refreshKey++
        }
    }

    val allLogs = remember(refreshKey) { LogCapture.getAll() }
    val filteredLogs = allLogs.filter { line ->
        (filterLevel == null || line.level == filterLevel) &&
            (filterTag.isEmpty() || line.tag.contains(filterTag, ignoreCase = true))
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // ---- Top bar: title + action buttons ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Logs (${filteredLogs.size}/${allLogs.size})",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Clear button.
                IconButton(onClick = {
                    LogCapture.clear()
                    refreshKey++
                    Toast.makeText(context, "Logs cleared", Toast.LENGTH_SHORT).show()
                }) {
                    Icon(Icons.Outlined.Clear, contentDescription = "Clear")
                }
                // Copy button.
                IconButton(onClick = {
                    val text = LogCapture.getAllAsText()
                    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                    clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("logs", text))
                    Toast.makeText(context, "Copied ${allLogs.size} lines", Toast.LENGTH_SHORT).show()
                }) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy")
                }
                // Export button — write to file + open share sheet.
                IconButton(onClick = {
                    try {
                        val dir = File("/storage/emulated/0/Documents/ai-workspace")
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
                    Icon(Icons.Outlined.IosShare, contentDescription = "Export")
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // ---- Filter row ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = filterLevel == null,
                onClick = { filterLevel = null },
                label = { Text("All") },
            )
            FilterChip(
                selected = filterLevel == "I",
                onClick = { filterLevel = if (filterLevel == "I") null else "I" },
                label = { Text("Info") },
            )
            FilterChip(
                selected = filterLevel == "W",
                onClick = { filterLevel = if (filterLevel == "W") null else "W" },
                label = { Text("Warn") },
            )
            FilterChip(
                selected = filterLevel == "E",
                onClick = { filterLevel = if (filterLevel == "E") null else "E" },
                label = { Text("Error") },
            )
            OutlinedTextField(
                value = filterTag,
                onValueChange = { filterTag = it },
                placeholder = { Text("Filter by tag", fontSize = 12.sp) },
                modifier = Modifier.width(160.dp),
                singleLine = true,
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // ---- Log list ----
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(filteredLogs) { line ->
                val color = when (line.level) {
                    "E" -> Color(0xFFFF6B6B)  // red
                    "W" -> Color(0xFFFFD93D)  // yellow
                    "D" -> Color(0xFF6BCB77)  // green
                    else -> custom.fg2        // gray for info
                }
                Text(
                    text = LogCapture.formatLine(line),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = color,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // Auto-scroll to bottom when new logs arrive.
        LaunchedEffect(filteredLogs.size) {
            if (filteredLogs.isNotEmpty()) {
                listState.animateScrollToItem(filteredLogs.size - 1)
            }
        }
    }
}
