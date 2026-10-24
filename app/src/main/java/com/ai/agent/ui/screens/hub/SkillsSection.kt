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

// Hub section: Skills — lists SkillManager skills with edit/delete.

// ---------------------------------------------------------------------------
// Functional section: Skills (uses SkillManager.getSkillCount())
// ---------------------------------------------------------------------------

@Composable
internal fun SkillsSection(
    onEditSkill: (skillName: String?, isReadOnly: Boolean) -> Unit,
) {
    val context = LocalContext.current
    val custom = LocalCustomColors.current

    var skills by remember { mutableStateOf<List<SkillManager.Skill>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var hiddenCount by remember { mutableStateOf(0) }  // v4.0.14: track hidden skills for restore button

    // Long-press menu state — when non-null, an AlertDialog is shown.
    var menuSkill by remember { mutableStateOf<SkillManager.Skill?>(null) }
    // Delete-confirmation dialog state.
    var deleteSkill by remember { mutableStateOf<SkillManager.Skill?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }

    fun reload() {
        val allSkills = try {
            SkillManager(context).loadSkills()
        } catch (e: Exception) {
            emptyList()
        }
        // Skills can be loaded from TWO sources:
        //   1. Built-in assets (assets/skills/*.md) — immutable, shipped with APK
        //   2. User storage (/storage/emulated/0/Documents/ai-workspace/skills/*.md)
        // SkillManager loads built-ins first, then user files — so a user file
        // with the same fileName as an asset creates a duplicate entry.
        //
        // Issue 51 (Edit + Delete on built-ins):
        //   - Edit on a built-in → SkillEditorScreen writes a user-owned override
        //     copy with the same fileName. associateBy { fileName } keeps the LAST
        //     occurrence, so the user override shadows the built-in (no duplicates).
        //   - Delete on a built-in → we write a marker file `.deleted_<fileName>`
        //     under /storage/.../skills/. Here we filter out any entry whose
        //     fileName matches such a marker (UNLESS a user override exists for
        //     that fileName — the override is still shown).
        val userDir = java.io.File("/storage/emulated/0/Documents/ai-workspace/skills")
        val userFileNames: Set<String> = if (userDir.exists()) {
            userDir.listFiles { f -> f.extension == "md" }
                ?.map { it.name }?.toSet() ?: emptySet()
        } else emptySet()
        val deletedMarkers: Set<String> = if (userDir.exists()) {
            userDir.listFiles { f -> f.name.startsWith(".deleted_") }
                ?.map { it.name.removePrefix(".deleted_") }?.toSet() ?: emptySet()
        } else emptySet()
        hiddenCount = deletedMarkers.size  // v4.0.14: update for restore button

        // associateBy keeps the LAST entry per fileName — since SkillManager loads
        // assets first then user files, user-owned overrides win on conflict.
        val deduped = allSkills.associateBy { it.fileName }.values
        skills = deduped.filter { skill ->
            // Show a skill if EITHER:
            //   - a user-owned file with this fileName exists (Edit override),
            //     OR
            //   - this fileName has NOT been marked deleted.
            skill.fileName in userFileNames || skill.fileName !in deletedMarkers
        }
        loaded = true
    }

    LaunchedEffect(Unit) { reload() }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            SectionHeader(
                title = "Skills",
                subtitle = "${skills.size} loaded · ${if (loaded) "ready" else "loading…"}",
            )
        }

        // ---- "New Skill" button (Create mode) ----
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(custom.accent.copy(alpha = 0.10f), RoundedCornerShape(8.dp))
                    .border(1.dp, custom.accent.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                    .clickable { onEditSkill(null, false) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Add, contentDescription = null, tint = custom.accent, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "New Skill",
                    color = custom.accent,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "create from scratch",
                    color = custom.fg3,
                    fontSize = 11.sp,
                )
            }
        }

        if (skills.isEmpty() && loaded) {
            item {
                PlaceholderCard(
                    title = "No skills loaded",
                    detail = "Skill assets failed to load — check assets/skills/ in the APK.",
                )
            }
        } else if (skills.isEmpty()) {
            item { LoadingCard() }
        } else {
            items(skills) { skill ->
                SkillRow(
                    skill = skill,
                    onLongPress = { menuSkill = skill },
                    onClick = {
                        // Tap = quick view (read-only for built-in, edit for user).
                        val ro = !isUserCreatedSkill(skill.fileName)
                        onEditSkill(skill.name, ro)
                    },
                )
            }
        }

        // v4.0.14: Restore hidden skills — OUTSIDE the if/else so it always shows.
        // (Previously inside else block — didn't show when all skills were hidden.)
        if (hiddenCount > 0) {
            item {
                TextButton(
                    onClick = {
                        val dir = java.io.File("/storage/emulated/0/Documents/ai-workspace/skills")
                        dir.listFiles { f -> f.name.startsWith(".deleted_") }?.forEach { it.delete() }
                        toast = "Restored $hiddenCount hidden skill(s)"
                        reload()
                    },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                ) {
                    Text(
                        "↻ Restore $hiddenCount hidden skill(s)",
                        color = custom.fg3,
                        fontSize = 11.sp,
                    )
                }
            }
        }
    }

    // ---- Long-press options dialog ----
    menuSkill?.let { skill ->
        val isUser = isUserCreatedSkill(skill.fileName)
        SkillOptionsDialog(
            skillName = skill.name,
            isUserCreated = isUser,
            onView = {
                menuSkill = null
                onEditSkill(skill.name, true)
            },
            onEdit = {
                menuSkill = null
                onEditSkill(skill.name, false)
            },
            onDelete = {
                menuSkill = null
                deleteSkill = skill
            },
            onDismiss = { menuSkill = null },
        )
    }

    // ---- Delete confirmation dialog ----
    deleteSkill?.let { skill ->
        val isUser = isUserCreatedSkill(skill.fileName)
        SkillDeleteConfirmDialog(
            skillName = skill.name,
            isUserCreated = isUser,
            onConfirm = {
                deleteOrHideSkill(skill)
                Toast.makeText(
                    context,
                    if (isUser) "Skill deleted: ${skill.name}"
                    else "Built-in skill deleted: ${skill.name}",
                    Toast.LENGTH_SHORT,
                ).show()
                deleteSkill = null
                reload()
            },
            onDismiss = { deleteSkill = null },
        )
    }

    // v4.0.14: Toast handler
    LaunchedEffect(toast) {
        toast?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            toast = null
        }
    }
}

/**
 * Delete / hide the underlying .md file for a skill.
 *
 * - User-created skill  → the .md file under /storage/.../skills/ is deleted.
 * - Built-in (asset) skill → a marker file `.deleted_<filename>` is written
 *   under /storage/.../skills/. On reload, SkillsSection filters out any
 *   asset whose fileName matches a marker. The original asset file in the
 *   APK remains untouched (so the user can restore it by deleting the marker).
 */
private fun deleteOrHideSkill(skill: SkillManager.Skill) {
    val dir = java.io.File("/storage/emulated/0/Documents/ai-workspace/skills")
    if (!dir.exists()) dir.mkdirs()
    val userFile = java.io.File(dir, skill.fileName)
    if (userFile.exists()) {
        // User-owned override copy exists → delete it.
        userFile.delete()
        return
    }
    // Built-in asset → write a .deleted_ marker so SkillsSection hides it.
    val marker = java.io.File(dir, ".deleted_${skill.fileName}")
    if (!marker.exists()) {
        try {
            marker.writeText("hidden\n")
        } catch (_: Exception) {
            // best-effort — marker write failure means the skill stays visible
        }
    }
}

@Composable
private fun SkillOptionsDialog(
    skillName: String,
    isUserCreated: Boolean,
    onView: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = m3.surface,
        title = {
            Column {
                Text(
                    text = skillName,
                    color = m3.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = if (isUserCreated) "User-created · editable" else "Built-in · edit creates a copy",
                    color = if (isUserCreated) custom.accent else custom.fg3,
                    fontFamily = MonoFontFamily,
                    fontSize = 10.sp,
                )
            }
        },
        text = {
            Column {
                DialogOptionRow(label = "View", onClick = onView)
                // Issue 51: Edit + Delete are now available for BOTH user-created
                // and built-in skills. For built-in skills, Edit opens the editor
                // in non-read-only mode — on save, a user-owned override copy
                // is written under /storage/.../skills/<filename>.md.
                DialogOptionRow(label = "Edit", onClick = onEdit)
                DialogOptionRow(
                    label = "Delete",
                    onClick = onDelete,
                    textColor = custom.error,
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("Cancel", color = custom.fg2, fontSize = 13.sp)
            }
        },
    )
}

@Composable
private fun SkillDeleteConfirmDialog(
    skillName: String,
    isUserCreated: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = m3.surface,
        title = {
            Text(
                text = "Delete skill?",
                color = m3.onSurface,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
            )
        },
        text = {
            Text(
                text = if (isUserCreated)
                    "Permanently delete \"$skillName\"? This cannot be undone."
                else
                    "This is a built-in skill. Deleting will remove it from the list " +
                        "(original file in assets remains). Continue?",
                color = custom.fg2,
                fontSize = 13.sp,
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onConfirm) {
                Text(
                    text = "Delete",
                    color = custom.error,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                )
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("Cancel", color = custom.fg2, fontSize = 13.sp)
            }
        },
    )
}

// DialogOptionRow now lives in SharedHubComponents.kt (shared across sections).

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SkillRow(
    skill: SkillManager.Skill,
    onLongPress: () -> Unit,
    onClick: () -> Unit,
) {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme
    val isUser = isUserCreatedSkill(skill.fileName)
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(custom.ink2, RoundedCornerShape(8.dp))
            .border(1.dp, custom.inkBorder, RoundedCornerShape(8.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptics.performHapticFeedback(
                        androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress
                    )
                    onLongPress()
                },
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(custom.accent.copy(alpha = 0.15f), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Bolt, contentDescription = null, tint = custom.accent, modifier = Modifier.size(14.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = skill.name,
                    color = m3.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.width(6.dp))
                // Source badge — emerald dot for user, gray "built-in" pill
                if (isUser) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(custom.accent, CircleShape),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "user",
                        color = custom.accent,
                        fontFamily = MonoFontFamily,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .background(custom.surface3, RoundedCornerShape(4.dp))
                            .border(1.dp, custom.border, RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    ) {
                        Text(
                            text = "built-in",
                            color = custom.fg3,
                            fontFamily = MonoFontFamily,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
            Text(
                text = skill.description,
                color = custom.fg2,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            )
            if (skill.triggers.isNotEmpty()) {
                Spacer(Modifier.size(4.dp))
                Text(
                    text = "triggers: ${skill.triggers.joinToString(", ") { "\"$it\"" }}",
                    color = custom.fg3,
                    fontFamily = MonoFontFamily,
                    fontSize = 10.sp,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = "v${skill.version}",
                color = custom.fg3,
                fontFamily = MonoFontFamily,
                fontSize = 10.sp,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "⋯",
                color = custom.fg3,
                fontSize = 12.sp,
            )
        }
    }
}
