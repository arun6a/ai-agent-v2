package com.ai.agent.ui.screens

import android.util.Log
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.agent.llm.ContextualPromptBuilder
import com.ai.agent.ui.components.LocalCustomColors
import com.ai.agent.ui.theme.MonoFontFamily
import java.io.File

// ============================================================================
// SystemPromptEditorScreen — full-screen editor for Aira's system prompts.
// ----------------------------------------------------------------------------
// v2.0.9 Task 95. Mirrors v1's "Prompt mode" customizer.
//
// Modes (selected via the dropdown chip row at the top):
//
//   • custom    → edit /storage/emulated/0/Documents/ai-workspace/system_prompt_custom.txt
//                 If the file doesn't exist yet, seed it with the contents of the
//                 built-in "smart" prompt (assets/system_prompt_smart.txt) on
//                 first open.
//                 Saving writes the file + shows a toast with the new token count.
//                 "Restore Original" copies the smart prompt back over the file.
//
//   • smart     → read-only view of assets/system_prompt_smart.txt
//   • balanced  → read-only view of assets/system_prompt_balanced.txt
//   • fast      → read-only view of assets/system_prompt_fast.txt
//   • default   → read-only view of assets/system_prompt.txt
//
// Token estimate at the bottom uses DynamicPromptBuilder.estimateTokens(prompt)
// (~4 chars ≈ 1 token — matches the runtime cost tracker).
//
// Storage layout mirrors v1 exactly:
//   custom file:  /storage/emulated/0/Documents/ai-workspace/system_prompt_custom.txt
//   built-ins:    app/src/main/assets/system_prompt_*.txt  (frozen, read-only)
// ============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemPromptEditorScreen(
    initialMode: String = "smart", // v4.0.3: only smart + custom remain
    onBack: () -> Unit,
) {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme
    val context = LocalContext.current

    // ---- Mode state ----
    var mode by rememberSaveable { mutableStateOf(initialMode) }

    // ---- Text state (kept across mode switches so unsaved custom edits
    // aren't lost when the user peeks at a built-in and comes back) ----
    var customText by rememberSaveable { mutableStateOf("") }
    var builtinText by remember { mutableStateOf("") }
    var customLoaded by rememberSaveable { mutableStateOf(false) }
    var showRestoreDialog by remember { mutableStateOf(false) }

    val isCustom = mode == "custom"

    // ---- Load custom prompt on first open (seed from smart if missing) ----
    LaunchedEffect(Unit) {
        if (!customLoaded) {
            try {
                val dir = File("/storage/emulated/0/Documents/ai-workspace")
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, "system_prompt_custom.txt")
                if (!file.exists()) {
                    // Seed with smart prompt
                    val smartPrompt = context.assets
                        .open("system_prompt_smart.txt")
                        .bufferedReader()
                        .use { it.readText() }
                    file.writeText(smartPrompt)
                    Log.i(TAG, "Seeded custom prompt from system_prompt_smart.txt")
                }
                customText = file.readText()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load custom prompt", e)
                customText = ""
            }
            customLoaded = true
        }
    }

    // ---- Load built-in prompt whenever mode points to one ----
    LaunchedEffect(mode) {
        if (mode != "custom") {
            val assetName = "system_prompt_smart.txt"
            builtinText = try {
                context.assets.open(assetName).bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load built-in prompt: $assetName", e)
                "(Unable to read $assetName: ${e.message})"
            }
        }
    }

    // ---- Save (custom mode only) ----
    fun saveCustomPrompt() {
        try {
            val dir = File("/storage/emulated/0/Documents/ai-workspace")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "system_prompt_custom.txt")
            file.writeText(customText)
            val tokens = ContextualPromptBuilder.estimateTokens(customText)
            Toast.makeText(
                context,
                "Custom prompt saved (${customText.length} chars, ~$tokens tokens)",
                Toast.LENGTH_SHORT,
            ).show()
            onBack()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save custom prompt", e)
            Toast.makeText(
                context,
                "Save failed: ${e.message ?: e::class.simpleName}",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    // ---- Restore (custom mode only) ----
    fun restoreCustomPrompt() {
        try {
            val smartPrompt = context.assets
                .open("system_prompt_smart.txt")
                .bufferedReader()
                .use { it.readText() }
            val dir = File("/storage/emulated/0/Documents/ai-workspace")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "system_prompt_custom.txt")
            file.writeText(smartPrompt)
            customText = smartPrompt
            Toast.makeText(
                context,
                "Custom prompt restored to Smart default",
                Toast.LENGTH_SHORT,
            ).show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore custom prompt", e)
            Toast.makeText(
                context,
                "Restore failed: ${e.message ?: e::class.simpleName}",
                Toast.LENGTH_LONG,
            ).show()
        }
        showRestoreDialog = false
    }

    val displayText = if (isCustom) customText else builtinText
    val tokenEstimate = ContextualPromptBuilder.estimateTokens(displayText)

    Scaffold(
        containerColor = custom.canvas,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "System Prompt",
                        color = m3.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "Back",
                            tint = m3.onSurface,
                        )
                    }
                },
                actions = {
                    if (isCustom) {
                        IconButton(onClick = { saveCustomPrompt() }) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = "Save",
                                tint = custom.accent,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = m3.surface,
                    titleContentColor = m3.onSurface,
                ),
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---- Mode selector ----
            ModeChipRow(
                selected = mode,
                onSelect = { mode = it },
            )

            // ---- Mode hint ----
            val hint = when (mode) {
                "custom" -> "Editable. Your custom prompt."
                "smart" -> "Built-in Smart prompt (read-only). The active prompt."
                else -> ""
            }
            Text(
                text = hint,
                color = custom.fg2,
                fontSize = 11.sp,
                lineHeight = 14.sp,
            )

            // ---- Editor / viewer ----
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(custom.surface2, RoundedCornerShape(10.dp))
                    .border(1.dp, custom.border, RoundedCornerShape(10.dp)),
            ) {
                if (isCustom) {
                    OutlinedTextField(
                        value = customText,
                        onValueChange = { customText = it },
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                        textStyle = TextStyle(
                            color = m3.onSurface,
                            fontSize = 12.sp,
                            fontFamily = MonoFontFamily,
                            lineHeight = 18.sp,
                        ),
                        placeholder = {
                            Text(
                                text = "Write your custom system prompt here…",
                                color = custom.fg3,
                                fontSize = 12.sp,
                                fontFamily = MonoFontFamily,
                            )
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent,
                            cursorColor = custom.accent,
                        ),
                        // Long-form prompt — disable capitalization auto-correction.
                        // (KeyboardOptions omitted on purpose — Compose defaults are
                        // sensible for prose editing.)
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        Text(
                            text = builtinText,
                            color = m3.onSurface,
                            fontSize = 12.sp,
                            fontFamily = MonoFontFamily,
                            lineHeight = 18.sp,
                        )
                    }
                }
            }

            // ---- Token estimate ----
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${displayText.length} chars",
                    color = custom.fg3,
                    fontFamily = MonoFontFamily,
                    fontSize = 11.sp,
                )
                Text(
                    text = "~$tokenEstimate tokens",
                    color = if (tokenEstimate > 4000) custom.error else custom.accent,
                    fontFamily = MonoFontFamily,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                )
            }

            // ---- Restore button (custom only) ----
            if (isCustom) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(custom.surface2, RoundedCornerShape(10.dp))
                        .border(1.dp, custom.border, RoundedCornerShape(10.dp))
                        .clickable { showRestoreDialog = true }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = null,
                            tint = custom.fg2,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Restore Original",
                                color = m3.onSurface,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = "Replace this custom prompt with the built-in Smart default.",
                                color = custom.fg2,
                                fontSize = 11.sp,
                                lineHeight = 14.sp,
                            )
                        }
                    }
                }
            }
        }
    }

    // ---- Restore confirmation dialog ----
    if (showRestoreDialog) {
        AlertDialog(
            onDismissRequest = { showRestoreDialog = false },
            title = {
                Text(
                    text = "Restore to Smart default?",
                    color = m3.onSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            },
            text = {
                Text(
                    text = "This will overwrite your custom prompt with the built-in Smart prompt. Your edits will be lost.",
                    color = custom.fg2,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = { restoreCustomPrompt() }) {
                    Text(
                        text = "Restore",
                        color = custom.accent,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreDialog = false }) {
                    Text(
                        text = "Cancel",
                        color = custom.fg2,
                    )
                }
            },
            containerColor = m3.surface,
        )
    }
}

// ---------------------------------------------------------------------------
// ModeChipRow — horizontal scrollable chip row for picking which prompt to
// view / edit. Highlights the active mode with the accent color.
// ---------------------------------------------------------------------------

@Composable
private fun ModeChipRow(
    selected: String,
    onSelect: (String) -> Unit,
) {
    val custom = LocalCustomColors.current
    data class Mode(val id: String, val label: String)
    val modes = listOf(
        Mode("smart", "Smart"),
        Mode("custom", "Custom"),
    )
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(modes) { mode ->
            val active = mode.id == selected
            Box(
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
                    .clickable { onSelect(mode.id) }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Text(
                    text = mode.label,
                    color = if (active) custom.accent else custom.fg2,
                    fontFamily = MonoFontFamily,
                    fontSize = 12.sp,
                    fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                )
            }
        }
    }
}

private const val TAG = "SystemPromptEditor"
