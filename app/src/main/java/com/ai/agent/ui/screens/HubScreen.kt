package com.ai.agent.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.ai.agent.llm.AIProvider
import com.ai.agent.llm.ApiUsageTracker
import com.ai.agent.skills.SkillManager
import com.ai.agent.storage.AgentDatabase
import com.ai.agent.tools.ToolExecutor
import com.ai.agent.ui.components.HubNavItem
import com.ai.agent.ui.components.LocalCustomColors
import com.ai.agent.ui.components.StatCard
import com.ai.agent.ui.theme.MonoFontFamily
import com.ai.agent.ui.theme.ThemeManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ============================================================================
// HubScreen — tablet/desktop-style dashboard with side nav + content area.
// ----------------------------------------------------------------------------
// Mirrors `design/03-hub-screen.html`:
//   - Side nav 200dp wide: 13 sections + count badges
//   - Content area: 13 functional sections (Skills, Rules, Tools, Plugins,
//     Memory, Knowledge, History, Files, LLM, Vision, Audio, Permissions,
//     Settings) — all backed by real data from the frozen backend
//   - Status footer: agent ready · API calls today · success rate
// ============================================================================

// v3.1.0: Reorganized Hub sections for better UX
// - History moved to FIRST (most used)
// - Rules renamed to "Automation Rules"
// - Apps section REMOVED (useless — AppProfileManager does this)
// - LLM + Vision + Audio merged into "AI Settings"
// - Order optimized for mobile usage
enum class HubSection(val label: String, val icon: ImageVector, val count: String? = null) {
    HISTORY("History", Icons.Outlined.History, "487"),
    RULES("Automation", Icons.Outlined.Rule, "36"),
    SKILLS("Skills", Icons.Outlined.Psychology, "20"),
    TOOLS("Tools", Icons.Outlined.Build, "100+"),
    MEMORY("Memory", Icons.Outlined.Memory, "142"),
    KNOWLEDGE("Knowledge", Icons.Outlined.BookmarkBorder, "23"),
    FILES("Files", Icons.Outlined.Folder, "12"),
    AI_SETTINGS("AI Settings", Icons.Outlined.SmartToy),
    USAGE("Usage", Icons.Outlined.BarChart, "live"),
    PERMISSIONS("Permissions", Icons.Outlined.Verified, "14"),
    SETTINGS("Settings", Icons.Outlined.Settings),
}

@Composable
fun HubScreen(
    initialSection: HubSectionArg = HubSectionArg.SKILLS,
    onBackToChat: () -> Unit = {},
    onOpenProviderSettings: () -> Unit = {},
    onEditSkill: (skillName: String?, isReadOnly: Boolean) -> Unit = { _, _ -> },
    onOpenSystemPromptEditor: () -> Unit = {},
    /** v2.2.0 Issue 113d — Tap a chat session in History → continue it on the chat screen. */
    onContinueSession: (Long) -> Unit = {},
) {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme
    val context = LocalContext.current

    var selected by remember { mutableStateOf(initialSection.toHubSection()) }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(custom.canvas),
    ) {
        // ---- Side nav (200dp) ----
        Column(
            modifier = Modifier
                .width(200.dp)
                .fillMaxHeight()
                .background(custom.canvas)
                .border(width = 1.dp, color = custom.border)
                .padding(vertical = 8.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .background(custom.accent, RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("A", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                }
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        text = "Aira Lab",
                        color = m3.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                    )
                    Text(
                        text = "Personal Assistant",
                        color = custom.fg3,
                        fontSize = 10.sp,
                    )
                }
            }
            Text(
                text = "SECTIONS · 13",
                color = custom.fg3,
                fontFamily = MonoFontFamily,
                fontSize = 10.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            HorizontalDivider(color = custom.border, thickness = 1.dp)

            // Nav items — scrollable if viewport is short
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                HubSection.entries.forEach { section ->
                    HubNavItem(
                        label = section.label,
                        icon = section.icon,
                        count = section.count,
                        active = section == selected,
                        onClick = { selected = section },
                    )
                }
            }
        }

        // ---- Content area ----
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                when (selected) {
                    HubSection.HISTORY -> HistorySection(onContinueSession = onContinueSession)
                    HubSection.RULES -> RulesSection()
                    HubSection.SKILLS -> SkillsSection(onEditSkill = onEditSkill)
                    HubSection.TOOLS -> ToolsSection()
                    HubSection.MEMORY -> MemorySection()
                    HubSection.KNOWLEDGE -> KnowledgeSection()
                    HubSection.FILES -> FilesSection()
                    HubSection.AI_SETTINGS -> AiSettingsSection(onOpenProviderSettings = onOpenProviderSettings)
                    HubSection.USAGE -> UsageSection()
                    HubSection.PERMISSIONS -> PermissionsSection()
                    HubSection.SETTINGS -> SettingsSection(onOpenSystemPromptEditor = onOpenSystemPromptEditor)
                }
            }

            HubFooter()
        }
    }
}

// ---------------------------------------------------------------------------
// Argument wrapper so navigation can pass an initial section (e.g. Settings →
// open the Hub directly on the Settings section).
// ---------------------------------------------------------------------------

enum class HubSectionArg { HISTORY, RULES, SKILLS, TOOLS, MEMORY, KNOWLEDGE, FILES, AI_SETTINGS, USAGE, PERMISSIONS, SETTINGS
    ;
    fun toHubSection(): HubSection = when (this) {
        HubSectionArg.HISTORY -> HubSection.HISTORY
        HubSectionArg.RULES -> HubSection.RULES
        HubSectionArg.SKILLS -> HubSection.SKILLS
        HubSectionArg.TOOLS -> HubSection.TOOLS
        HubSectionArg.MEMORY -> HubSection.MEMORY
        HubSectionArg.KNOWLEDGE -> HubSection.KNOWLEDGE
        HubSectionArg.FILES -> HubSection.FILES
        HubSectionArg.AI_SETTINGS -> HubSection.AI_SETTINGS
        HubSectionArg.USAGE -> HubSection.USAGE
        HubSectionArg.PERMISSIONS -> HubSection.PERMISSIONS
        HubSectionArg.SETTINGS -> HubSection.SETTINGS
    }
}

// ---------------------------------------------------------------------------
// Functional section: Skills (uses SkillManager.getSkillCount())
// ---------------------------------------------------------------------------

@Composable
private fun SkillsSection(
    onEditSkill: (skillName: String?, isReadOnly: Boolean) -> Unit,
) {
    val context = LocalContext.current
    val custom = LocalCustomColors.current

    var skills by remember { mutableStateOf<List<SkillManager.Skill>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }

    // Long-press menu state — when non-null, an AlertDialog is shown.
    var menuSkill by remember { mutableStateOf<SkillManager.Skill?>(null) }
    // Delete-confirmation dialog state.
    var deleteSkill by remember { mutableStateOf<SkillManager.Skill?>(null) }

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
                    else "Built-in skill hidden: ${skill.name}",
                    Toast.LENGTH_SHORT,
                ).show()
                deleteSkill = null
                reload()
            },
            onDismiss = { deleteSkill = null },
        )
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
                text = if (isUserCreated) "Delete skill?" else "Hide built-in skill?",
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
                    "This is a built-in skill. Deleting will hide it from the list " +
                        "(original file in assets remains). Continue?",
                color = custom.fg2,
                fontSize = 13.sp,
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onConfirm) {
                Text(
                    text = if (isUserCreated) "Delete" else "Hide",
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

@Composable
private fun DialogOptionRow(label: String, onClick: () -> Unit, textColor: Color? = null) {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
    ) {
        Text(
            text = label,
            color = textColor ?: m3.onSurface,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

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

// ---------------------------------------------------------------------------
// Task 23 — Functional section: Rules (uses AgentDatabase.getRules())
// ---------------------------------------------------------------------------

@Composable
private fun RulesSection() {
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
            "readScreen", "readScreenStructured", "tap", "clickByText", "type", "swipe",
            "scrollDown", "scrollUp", "pressBack", "pressHome", "pressEnter", "submitInput",
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
    categories += ToolCategory("Device", Icons.Outlined.Build, select {
        it in setOf(
            "getBatteryLevel", "getCurrentTime", "getBluetoothState", "getNetworkInfo",
            "getDeviceInfo", "pingHost", "getVolume", "setVolume", "setBrightness",
            "toggleFlashlight", "toggleWifi",
        )
    })
    categories += ToolCategory("Memory", Icons.Outlined.Memory, select {
        it in setOf(
            "remember", "recall", "recallAll", "recallSimilar", "searchMemory",
            "saveToKnowledge", "recallKnowledge", "listKnowledge",
            "getClipboard", "setClipboard",
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
        it in setOf("wait", "waitAndContinue")
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
private fun ToolsSection() {
    val context = LocalContext.current
    val allTools = remember { ToolExecutor.getAvailableToolNames() }
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

// ---------------------------------------------------------------------------
// v2.3.3: Apps section — replaces old Plugins. Uses AppProfileManager
// to detect installed apps and let user enable/disable AI access per-app.
// ---------------------------------------------------------------------------

@Composable
private fun AppsSection() {
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

// ---------------------------------------------------------------------------
// Task 26 — Functional section: Knowledge (getAllKnowledge + recallKnowledge)
// ---------------------------------------------------------------------------

@Composable
private fun KnowledgeSection() {
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

// ---------------------------------------------------------------------------
// Task 27 — Functional section: History
// v2.2.0 Issue 113d — now shows chat SESSIONS (not flat messages). Each row
// shows the session title (first user message), last active time, and message
// count. Tap → continue session on chat screen. Long-press → Delete option.
// ---------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistorySection(
    onContinueSession: (Long) -> Unit = {},
) {
    val context = LocalContext.current
    val custom = LocalCustomColors.current

    var sessions by remember { mutableStateOf<List<AgentDatabase.ChatSession>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    // Per-session message count cache — populated on the same LaunchedEffect.
    var messageCounts by remember { mutableStateOf<Map<Long, Int>>(emptyMap()) }
    // Per-session first-user-message preview cache (used as the title if the
    // session title is just "New Chat" / "Default").
    var previews by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }
    // Delete-confirmation dialog target.
    var deleteTarget by remember { mutableStateOf<AgentDatabase.ChatSession?>(null) }

    fun reload() {
        val all = try {
            AgentDatabase(context).getChatSessions()
        } catch (e: Exception) {
            emptyList()
        }
        sessions = all
        // Fetch message count + first user message for each session in a single pass.
        val counts = mutableMapOf<Long, Int>()
        val previewsMap = mutableMapOf<Long, String>()
        try {
            val db = AgentDatabase(context)
            for (s in all) {
                counts[s.id] = db.getSessionMessageCount(s.id)
                val firstUser = db.getConversationsForSession(s.id).firstOrNull { it.first == "user" }
                previewsMap[s.id] = firstUser?.second?.take(80) ?: ""
            }
        } catch (_: Exception) {}
        messageCounts = counts
        previews = previewsMap
        loaded = true
    }

    LaunchedEffect(Unit) { reload() }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            SectionHeader(
                title = "Chat History",
                subtitle = "${sessions.size} sessions · ${if (loaded) "ready" else "loading…"}",
            )
        }
        if (sessions.isEmpty() && loaded) {
            item {
                PlaceholderCard(
                    title = "No chats yet",
                    detail = "Send a message on the Chat tab — your conversations will be grouped into sessions you can resume later.",
                )
            }
        } else if (sessions.isEmpty()) {
            item { LoadingCard() }
        } else {
            items(sessions, key = { it.id }) { session ->
                val count = messageCounts[session.id] ?: 0
                val preview = previews[session.id] ?: ""
                SessionRow(
                    session = session,
                    messageCount = count,
                    preview = preview,
                    onClick = { onContinueSession(session.id) },
                    onLongPress = { deleteTarget = session },
                )
            }
        }
    }

    // Delete-confirmation dialog.
    if (deleteTarget != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete chat?", color = custom.fg2) },
            text = {
                Text(
                    "This will permanently remove this chat and all its messages. This cannot be undone.",
                    color = custom.fg3,
                    fontSize = 12.sp,
                )
            },
            confirmButton = {
                Text(
                    text = "Delete",
                    color = custom.error,
                    modifier = Modifier.clickable {
                        val target = deleteTarget
                        if (target != null) {
                            try {
                                AgentDatabase(context).deleteChatSession(target.id)
                            } catch (_: Exception) {}
                            deleteTarget = null
                            reload()
                        }
                    },
                )
            },
            dismissButton = {
                Text(
                    text = "Cancel",
                    color = custom.fg3,
                    modifier = Modifier.clickable { deleteTarget = null },
                )
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(
    session: AgentDatabase.ChatSession,
    messageCount: Int,
    preview: String,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme

    val title = when {
        preview.isNotBlank() -> preview
        session.title != "New Chat" && session.title != "Default" -> session.title
        else -> "New Chat"
    }
    val timeAgo = formatTimeAgo(session.lastActive)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(custom.surface2, RoundedCornerShape(12.dp))
            .border(1.dp, custom.border, RoundedCornerShape(12.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongPress,
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Accent dot
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(custom.accent, CircleShape),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = title,
                color = m3.onSurface,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "$messageCount msgs",
                color = custom.fg3,
                fontFamily = MonoFontFamily,
                fontSize = 10.sp,
            )
        }
        Spacer(Modifier.size(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Last active $timeAgo",
                color = custom.fg3,
                fontFamily = MonoFontFamily,
                fontSize = 10.sp,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            Text(
                text = "Tap to continue →",
                color = custom.accent,
                fontFamily = MonoFontFamily,
                fontSize = 10.sp,
            )
        }
    }
}

private fun formatTimeAgo(timestamp: Long): String {
    if (timestamp <= 0L) return "unknown"
    val delta = System.currentTimeMillis() - timestamp
    return when {
        delta < 60_000L -> "just now"
        delta < 3_600_000L -> "${delta / 60_000L}m ago"
        delta < 86_400_000L -> "${delta / 3_600_000L}h ago"
        delta < 604_800_000L -> "${delta / 86_400_000L}d ago"
        else -> {
            val sdf = java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.getDefault())
            sdf.format(java.util.Date(timestamp))
        }
    }
}

// ---------------------------------------------------------------------------
// Task 28 — Functional section: Files (list attachments dir)
// ---------------------------------------------------------------------------

@Composable
private fun FilesSection() {
    val context = LocalContext.current
    val custom = LocalCustomColors.current

    data class FileEntry(val name: String, val size: Long, val lastModified: Long)
    var files by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        files = try {
            val dir = File(ChatViewModel.ATTACHMENTS_DIR)
            if (!dir.exists() || !dir.isDirectory) {
                emptyList()
            } else {
                dir.listFiles()
                    ?.filter { it.isFile }
                    ?.sortedByDescending { it.lastModified() }
                    ?.map { FileEntry(it.name, it.length(), it.lastModified()) }
                    ?: emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
        loaded = true
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            SectionHeader(
                title = "Files",
                subtitle = "${files.size} attachments · ${ChatViewModel.ATTACHMENTS_DIR}",
            )
        }
        if (files.isEmpty() && loaded) {
            item {
                PlaceholderCard(
                    title = "No attachments yet",
                    detail = "Tap the paperclip on the chat bar to attach a file. It will be copied to " +
                        "${ChatViewModel.ATTACHMENTS_DIR} and shown here.",
                )
            }
        } else if (files.isEmpty()) {
            item { LoadingCard() }
        } else {
            items(files, key = { it.name }) { file ->
                FileRow(
                    name = file.name,
                    size = file.size,
                    lastModified = file.lastModified,
                    onAttach = {
                        toast = "${file.name} staged for chat — go to Chat tab to send"
                    },
                )
            }
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
private fun FileRow(name: String, size: Long, lastModified: Long, onAttach: () -> Unit) {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme
    val df = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(custom.ink2, RoundedCornerShape(8.dp))
            .border(1.dp, custom.inkBorder, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(custom.surface3, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                iconForFile(name),
                contentDescription = null,
                tint = custom.accent,
                modifier = Modifier.size(14.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                color = m3.onSurface,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.size(2.dp))
            Text(
                text = "${formatBytes(size)} · ${df.format(Date(lastModified))}",
                color = custom.fg3,
                fontFamily = MonoFontFamily,
                fontSize = 10.sp,
            )
        }
        IconButton(onClick = onAttach, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Outlined.AttachFile, contentDescription = "Attach to chat", tint = custom.fg3, modifier = Modifier.size(14.dp))
        }
    }
}

private fun iconForFile(name: String): ImageVector {
    val n = name.lowercase()
    return when {
        n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg") ||
            n.endsWith(".gif") || n.endsWith(".webp") -> Icons.Outlined.Image
        n.endsWith(".pdf") -> Icons.Outlined.PictureAsPdf
        n.endsWith(".txt") || n.endsWith(".md") || n.endsWith(".json") ||
            n.endsWith(".csv") -> Icons.Outlined.Description
        n.endsWith(".mp3") || n.endsWith(".wav") || n.endsWith(".m4a") -> Icons.Outlined.GraphicEq
        else -> Icons.Outlined.InsertDriveFile
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "${bytes}B"
    bytes < 1024 * 1024 -> "${bytes / 1024}KB"
    else -> String.format("%.1fMB", bytes / (1024.0 * 1024.0))
}

// ---------------------------------------------------------------------------
// Task 29 — Functional section: Vision (3-tier fallback diagram + vision models)
// ---------------------------------------------------------------------------

private fun isVisionCapable(modelId: String): Boolean {
    val m = modelId.lowercase()
    return m.contains("vision") || m.contains("-v") || m.contains("vlm") ||
        m.contains("pixtral") || m.contains("gpt-4-vision") || m.contains("vl-") ||
        m.contains("glm-4.5v") || m.contains("glm-4.1v") || m.contains("glm-5v") ||
        m.contains("llama-3.2") && m.contains("vision") || m.contains("gemma-4-31b")
}

// v3.2.0: AiSettingsSection now uses the new ModelSettingsScreen with 3 tabs
@Composable
private fun AiSettingsSection(onOpenProviderSettings: () -> Unit = {}) {
    com.ai.agent.llm.ModelSettingsScreen(onOpenProviderSettings = onOpenProviderSettings)
}

// Helper for MaterialTheme onSurface color (avoid import issues)
@Composable
private fun m3_onSurface(): Color = MaterialTheme.colorScheme.onSurface

@Composable
private fun VisionSection() {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme
    val providers = remember { AIProvider.PROVIDERS }

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .padding(0.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            SectionHeader(
                title = "Vision",
                subtitle = "3-tier screen-understanding pipeline",
            )
        }
        // 3-tier fallback diagram
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(custom.ink2, RoundedCornerShape(8.dp))
                    .border(1.dp, custom.inkBorder, RoundedCornerShape(8.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                VisionTierRow(idx = 1, name = "Accessibility Service", desc = "Free, instant, no API. Reads on-screen text directly from the OS.", hits = "—", accent = true)
                VisionTierRow(idx = 2, name = "ML Kit OCR (on-device)", desc = "Falls back to on-device OCR via ML Kit text-recognition — works offline.", hits = "—")
                VisionTierRow(idx = 3, name = "VLM API (cloud)", desc = "Final fallback — sends screenshot to a vision LLM (GLM-5V / Gemma Vision).", hits = "—")
            }
        }
        // Stats row
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatBox(label = "TIER 1 HITS", value = "—", modifier = Modifier.weight(1f))
                StatBox(label = "TIER 2 HITS", value = "—", modifier = Modifier.weight(1f))
                StatBox(label = "TIER 3 CALLS", value = "—", modifier = Modifier.weight(1f))
            }
        }
        item {
            Text(
                text = "VISION-CAPABLE MODELS",
                color = custom.fg3,
                fontFamily = MonoFontFamily,
                fontSize = 10.sp,
            )
        }
        items(providers, key = { it.id }) { provider ->
            val visionModels = provider.models.filter { isVisionCapable(it.id) }
            if (visionModels.isNotEmpty()) {
                VisionProviderRow(providerName = provider.name, models = visionModels.map { it.name })
            }
        }
    }
}

@Composable
private fun VisionTierRow(idx: Int, name: String, desc: String, hits: String, accent: Boolean = false) {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .background(
                    if (accent) custom.accent else custom.surface3,
                    RoundedCornerShape(6.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = idx.toString(),
                color = if (accent) Color.White else custom.fg3,
                fontFamily = MonoFontFamily,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                color = m3.onSurface,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = desc,
                color = custom.fg2,
                fontSize = 10.sp,
                lineHeight = 13.sp,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = hits,
            color = custom.fg3,
            fontFamily = MonoFontFamily,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun VisionProviderRow(providerName: String, models: List<String>) {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(custom.ink2, RoundedCornerShape(8.dp))
            .border(1.dp, custom.inkBorder, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Visibility, contentDescription = null, tint = custom.accent, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = providerName,
                color = m3.onSurface,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = models.joinToString(", "),
                color = custom.fg3,
                fontFamily = MonoFontFamily,
                fontSize = 10.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = models.size.toString(),
            color = custom.accent,
            fontFamily = MonoFontFamily,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun StatBox(label: String, value: String, modifier: Modifier = Modifier) {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .background(custom.ink2, RoundedCornerShape(8.dp))
            .border(1.dp, custom.inkBorder, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            color = custom.fg3,
            fontFamily = MonoFontFamily,
            fontSize = 9.sp,
        )
        Spacer(Modifier.size(2.dp))
        Text(
            text = value,
            color = m3.onSurface,
            fontFamily = MonoFontFamily,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

// ---------------------------------------------------------------------------
// Task 30 — Functional section: Audio (voice input + TTS info)
// ---------------------------------------------------------------------------

@Composable
private fun AudioSection() {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            SectionHeader(
                title = "Audio",
                subtitle = "Voice input + speech output",
            )
        }
        // Voice input (Google Voice Intent) — active
        item {
            AudioCard(
                icon = Icons.Outlined.Mic,
                title = "Voice Input",
                method = "Google Voice Intent",
                status = "ACTIVE",
                desc = "Uses Android's built-in RecognizerIntent — works on every device with Google services. Tap the mic in the chat bar to dictate.",
                active = true,
            )
        }
        // TTS engine (Android TextToSpeech)
        item {
            AudioCard(
                icon = Icons.Outlined.VolumeUp,
                title = "Speech Output (TTS)",
                method = "Android TextToSpeech",
                status = "ACTIVE",
                desc = "VoiceManager wraps android.speech.tts — the AI reads its final reply aloud at the end of each task (up to 500 chars).",
                active = true,
            )
        }
        // ASR coming soon (greyed out)
        item {
            AudioCard(
                icon = Icons.Outlined.GraphicEq,
                title = "On-device ASR",
                method = "ML Kit Speech Recognition",
                status = "COMING SOON",
                desc = "Offline speech-to-text via ML Kit — will replace Google Voice Intent so dictation works without an internet connection.",
                active = false,
            )
        }
        // Audio stats
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatBox(label = "TTS ENGINE", value = "Android", modifier = Modifier.weight(1f))
                StatBox(label = "ASR ENGINE", value = "Intent", modifier = Modifier.weight(1f))
                StatBox(label = "LANGUAGES", value = "en-US", modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun AudioCard(
    icon: ImageVector,
    title: String,
    method: String,
    status: String,
    desc: String,
    active: Boolean,
) {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme

    Row(
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
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(
                    if (active) custom.accent else custom.surface3,
                    RoundedCornerShape(8.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (active) Color.White else custom.fg3,
                modifier = Modifier.size(16.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    color = m3.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .background(
                            if (active) custom.accent.copy(alpha = 0.15f) else custom.surface3,
                            RoundedCornerShape(4.dp),
                        )
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                ) {
                    Text(
                        text = status,
                        color = if (active) custom.accent else custom.fg3,
                        fontFamily = MonoFontFamily,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Spacer(Modifier.size(2.dp))
            Text(
                text = method,
                color = custom.fg2,
                fontFamily = MonoFontFamily,
                fontSize = 10.sp,
            )
            Spacer(Modifier.size(4.dp))
            Text(
                text = desc,
                color = custom.fg2,
                fontSize = 11.sp,
                lineHeight = 14.sp,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Task 31 — Functional section: Settings (Prompt / Browser / Search / Toggles)
// ---------------------------------------------------------------------------

@Composable
private fun SettingsSection(onOpenSystemPromptEditor: () -> Unit = {}) {
    val context = LocalContext.current
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme

    var promptMode by remember { mutableStateOf(AIProvider.getPromptMode(context)) }
    var browserMode by remember { mutableStateOf(AIProvider.getBrowserMode(context)) }
    var searchMethod by remember { mutableStateOf(AIProvider.getSearchMethod(context)) }
    var visionMode by remember { mutableStateOf(AIProvider.getVisionMode(context)) }  // v2.2.3
    var dynamicPrompt by remember { mutableStateOf(promptMode == "dynamic") }
    var alwaysLearn by remember { mutableStateOf(true) }
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
                subtitle = "AI behavior · search · learning",
            )
        }

        item {
            SettingsGroup(title = "PROMPT MODE") {
                SelectorRow(
                    label = "Prompt Mode",
                    options = listOf("smart", "dynamic", "balanced", "fast", "custom"),
                    selected = promptMode,
                    onSelect = {
                        promptMode = it
                        AIProvider.setPromptMode(context, it)
                        if (it == "dynamic") dynamicPrompt = true
                        toast = "Prompt mode: $it"
                    },
                )
                // v2.0.9 Task 95: Open the full-screen System Prompt editor
                // (Custom edit + Smart / Balanced / Fast / Default read-only views).
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(custom.surface2, RoundedCornerShape(8.dp))
                        .border(1.dp, custom.border, RoundedCornerShape(8.dp))
                        .clickable {
                            onOpenSystemPromptEditor()
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Edit System Prompt",
                            color = m3.onSurface,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = "Open the prompt editor (custom + built-in views)",
                            color = custom.fg3,
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                        )
                    }
                    Text(
                        text = "Open →",
                        color = custom.accent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
        item {
            SettingsGroup(title = "BROWSER MODE") {
                SelectorRow(
                    label = "Browser Mode",
                    options = listOf("auto", "in_app", "chrome"),  // v2.3.0: "auto" uses embedded WebView
                    selected = browserMode,
                    onSelect = {
                        browserMode = it
                        AIProvider.setBrowserMode(context, it)
                        toast = "Browser mode: $it"
                    },
                )
            }
        }
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
        // v2.2.3: Vision Mode — controls how AI reads the screen
        item {
            SettingsGroup(title = "VISION MODE") {
                SelectorRow(
                    label = "Screen Reading",
                    options = listOf("auto", "mlkit", "vlm"),
                    selected = visionMode,
                    onSelect = {
                        visionMode = it
                        AIProvider.setVisionMode(context, it)
                        toast = "Vision mode: $it"
                    },
                )
            }
        }
        item {
            SettingsGroup(title = "BEHAVIOR") {
                ToggleRow(
                    label = "Dynamic Prompt",
                    desc = "Auto-select relevant tools per task (saves tokens)",
                    checked = dynamicPrompt,
                    onCheck = {
                        dynamicPrompt = it
                        if (it && promptMode != "dynamic") {
                            promptMode = "dynamic"
                            AIProvider.setPromptMode(context, "dynamic")
                        } else if (!it && promptMode == "dynamic") {
                            promptMode = "smart"
                            AIProvider.setPromptMode(context, "smart")
                        }
                        toast = "Dynamic prompt: $it"
                    },
                )
                // v3.0.8: Contextual Prompts toggle (replaces Prompt Caching + Dynamic Prompt)
                var contextualPrompts by remember { mutableStateOf(AIProvider.isContextualPromptsEnabled(context)) }
                ToggleRow(
                    label = "Contextual Prompts",
                    desc = "Select relevant tool groups per task (85% token savings). 'Hi' → tiny prompt, research → web tools only",
                    checked = contextualPrompts,
                    onCheck = {
                        contextualPrompts = it
                        AIProvider.setContextualPromptsEnabled(context, it)
                        toast = "Contextual prompts: $it"
                    },
                )
                ToggleRow(
                    label = "Always Learn",
                    desc = "Save successful workflows + failure lessons to memory",
                    checked = alwaysLearn,
                    onCheck = {
                        alwaysLearn = it
                        toast = "Always learn: $it"
                    },
                )
            }
        }
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

// ---------------------------------------------------------------------------
// Functional section: LLM Providers (uses AIProvider.PROVIDERS)
// ---------------------------------------------------------------------------

@Composable
private fun LlmSection(onOpenProviderSettings: () -> Unit = {}) {
    val context = LocalContext.current

    val currentProvider = remember { AIProvider.getCurrentProvider(context) }
    val currentModel = remember { AIProvider.getModel(context) }
    val providers = remember { AIProvider.PROVIDERS }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            SectionHeader(
                title = "LLM Providers",
                subtitle = "active: ${currentProvider.name} · ${currentModel}",
            )
        }
        // Task 36 wiring — "Configure" button routes to the full-screen
        // ProviderSettingsScreen where the user can edit API key / model / endpoint.
        item {
            ConfigureProviderCard(onConfigure = onOpenProviderSettings)
        }
        items(providers) { provider ->
            val isActive = provider.id == currentProvider.id
            ProviderRow(provider = provider, isActive = isActive, currentModel = currentModel)
        }
    }
}

@Composable
private fun ConfigureProviderCard(onConfigure: () -> Unit) {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(custom.accent.copy(alpha = 0.10f), RoundedCornerShape(8.dp))
            .border(1.dp, custom.accent.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .clickable(onClick = onConfigure)
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(custom.accent, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.Lock,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(14.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Configure provider",
                color = m3.onSurface,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.size(2.dp))
            Text(
                text = "Edit API key · model · endpoint →",
                color = custom.fg2,
                fontFamily = MonoFontFamily,
                fontSize = 11.sp,
            )
        }
        Icon(
            Icons.AutoMirrored.Outlined.ArrowForward,
            contentDescription = "Open",
            tint = custom.accent,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun ProviderRow(provider: AIProvider.ProviderInfo, isActive: Boolean, currentModel: String) {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isActive) custom.accent.copy(alpha = 0.08f) else custom.ink2, RoundedCornerShape(8.dp))
            .border(
                1.dp,
                if (isActive) custom.accent.copy(alpha = 0.5f) else custom.inkBorder,
                RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(
                    if (isActive) custom.accent else custom.surface3,
                    RoundedCornerShape(8.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.Lock,
                contentDescription = null,
                tint = if (isActive) Color.White else custom.fg3,
                modifier = Modifier.size(14.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = provider.name,
                    color = m3.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                )
                if (isActive) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .background(custom.accent.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    ) {
                        Text(
                            text = "ACTIVE",
                            color = custom.accent,
                            fontFamily = MonoFontFamily,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
            Text(
                text = provider.description,
                color = custom.fg2,
                fontSize = 11.sp,
                lineHeight = 15.sp,
            )
            Spacer(Modifier.size(4.dp))
            Text(
                text = "${provider.models.size} models · endpoint ${provider.defaultEndpoint.take(48)}…",
                color = custom.fg3,
                fontFamily = MonoFontFamily,
                fontSize = 10.sp,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Functional section: Permissions (uses ContextCompat.checkSelfPermission)
// ---------------------------------------------------------------------------
// Issue 53 — full grant UI:
//   • Each row shows a Grant button when not granted
//   • Grant All button at top requests all runtime perms in one batch
//   • Special perms (Overlay / All Files / Accessibility) deep-link into
//     the relevant system Settings screen
//   • ON_RESUME lifecycle observer bumps refreshKey so granted state is
//     re-checked when the user returns from system Settings
// ---------------------------------------------------------------------------

private enum class PermissionType { RUNTIME, OVERLAY, ALL_FILES, ACCESSIBILITY, INTERNET }

private enum class PermissionGroup(val label: String) {
    REQUIRED("Required"),
    STORAGE("Storage"),
    AUDIO_CAMERA("Audio / Camera"),
    CONTACTS_SMS("Contacts / SMS"),
    LOCATION_CALENDAR("Location / Calendar"),
    NOTIFICATIONS("Notifications"),
}

private data class PermissionEntry(
    val perm: String,        // Manifest.permission.* constant (or pseudo-key for special perms)
    val display: String,     // "Camera" / "Microphone" / ...
    val type: PermissionType,
    val group: PermissionGroup,
)

private val PERMISSIONS = listOf(
    // Required
    PermissionEntry("__accessibility__", "Accessibility Service", PermissionType.ACCESSIBILITY, PermissionGroup.REQUIRED),
    PermissionEntry(Manifest.permission.SYSTEM_ALERT_WINDOW, "Display Over Other Apps", PermissionType.OVERLAY, PermissionGroup.REQUIRED),
    PermissionEntry(Manifest.permission.INTERNET, "Internet", PermissionType.INTERNET, PermissionGroup.REQUIRED),
    // Storage
    PermissionEntry(Manifest.permission.READ_EXTERNAL_STORAGE, "Read External Storage", PermissionType.RUNTIME, PermissionGroup.STORAGE),
    PermissionEntry(Manifest.permission.MANAGE_EXTERNAL_STORAGE, "All Files Access", PermissionType.ALL_FILES, PermissionGroup.STORAGE),
    PermissionEntry("android.permission.READ_MEDIA_IMAGES", "Read Media (Images)", PermissionType.RUNTIME, PermissionGroup.STORAGE),
    // Audio / Camera
    PermissionEntry(Manifest.permission.RECORD_AUDIO, "Microphone", PermissionType.RUNTIME, PermissionGroup.AUDIO_CAMERA),
    PermissionEntry(Manifest.permission.CAMERA, "Camera", PermissionType.RUNTIME, PermissionGroup.AUDIO_CAMERA),
    // Contacts / SMS
    PermissionEntry(Manifest.permission.READ_CONTACTS, "Read Contacts", PermissionType.RUNTIME, PermissionGroup.CONTACTS_SMS),
    PermissionEntry(Manifest.permission.CALL_PHONE, "Call Phone", PermissionType.RUNTIME, PermissionGroup.CONTACTS_SMS),
    PermissionEntry(Manifest.permission.SEND_SMS, "Send SMS", PermissionType.RUNTIME, PermissionGroup.CONTACTS_SMS),
    // Location / Calendar
    PermissionEntry(Manifest.permission.ACCESS_FINE_LOCATION, "Fine Location", PermissionType.RUNTIME, PermissionGroup.LOCATION_CALENDAR),
    PermissionEntry(Manifest.permission.READ_CALENDAR, "Read Calendar", PermissionType.RUNTIME, PermissionGroup.LOCATION_CALENDAR),
    // Notifications
    PermissionEntry(Manifest.permission.POST_NOTIFICATIONS, "Post Notifications", PermissionType.RUNTIME, PermissionGroup.NOTIFICATIONS),
)

/**
 * Returns true iff our AgentAccessibilityService is enabled in the system's
 * Accessibility settings. Used to mark the Accessibility permission row as
 * granted / missing in the Hub's Permissions section.
 */
private fun isAccessibilityEnabled(context: android.content.Context): Boolean {
    val enabled = try {
        android.provider.Settings.Secure.getInt(
            context.contentResolver,
            android.provider.Settings.Secure.ACCESSIBILITY_ENABLED,
            0,
        ) == 1
    } catch (_: Exception) {
        false
    }
    if (!enabled) return false
    val expected = "${context.packageName}/com.ai.agent.accessibility.AgentAccessibilityService"
    val list = try {
        android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        )
    } catch (_: Exception) {
        null
    } ?: return false
    val splitter = android.text.TextUtils.SimpleStringSplitter(':').apply { setString(list) }
    while (splitter.hasNext()) {
        if (splitter.next().equals(expected, ignoreCase = true)) return true
    }
    return false
}

@Composable
private fun PermissionsSection() {
    val context = LocalContext.current
    val custom = LocalCustomColors.current

    // refreshKey — bumped when runtime-permission result arrives OR on ON_RESUME.
    // Re-checks the granted state of every permission (incl. special perms that
    // can only be granted via system Settings → Accessibility / Overlay / All Files).
    var refreshKey by remember { mutableStateOf(0) }

    val permLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ -> refreshKey++ }

    // Bump refreshKey whenever the user comes back from system Settings (where
    // overlay, all-files, and accessibility permissions are granted).
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) refreshKey++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Compute granted state for every permission — memoized on refreshKey so a
    // state change triggers a single re-evaluation per refresh.
    val permissionStates = remember(refreshKey) {
        PERMISSIONS.associate { entry ->
            val granted = when (entry.type) {
                PermissionType.RUNTIME -> ContextCompat.checkSelfPermission(context, entry.perm) ==
                    PackageManager.PERMISSION_GRANTED
                PermissionType.OVERLAY -> android.provider.Settings.canDrawOverlays(context)
                PermissionType.ALL_FILES -> android.os.Environment.isExternalStorageManager()
                PermissionType.INTERNET -> true  // auto-granted on install
                PermissionType.ACCESSIBILITY -> isAccessibilityEnabled(context)
            }
            entry.perm to granted
        }
    }

    val grantedCount = permissionStates.count { it.value }
    val total = permissionStates.size

    fun requestPermission(entry: PermissionEntry) {
        when (entry.type) {
            PermissionType.RUNTIME -> permLauncher.launch(arrayOf(entry.perm))
            PermissionType.OVERLAY -> {
                val intent = android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:${context.packageName}"),
                ).apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
                try { context.startActivity(intent) } catch (_: Exception) {}
            }
            PermissionType.ALL_FILES -> {
                val intent = android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION,
                ).apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
                try { context.startActivity(intent) } catch (_: Exception) {}
            }
            PermissionType.ACCESSIBILITY -> {
                val intent = android.content.Intent(
                    android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS,
                ).apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
                try { context.startActivity(intent) } catch (_: Exception) {}
            }
            PermissionType.INTERNET -> {}  // always granted
        }
    }

    fun grantAll() {
        val allRuntimePerms = PERMISSIONS
            .filter { it.type == PermissionType.RUNTIME }
            .map { it.perm }
            .toTypedArray()
        if (allRuntimePerms.isNotEmpty()) permLauncher.launch(allRuntimePerms)
    }

    // Group permission entries by their PermissionGroup, preserving declared order.
    val grouped = PERMISSIONS.groupBy { it.group }

    // Critical permissions — if any of these is missing the agent can't run.
    // Matches MainActivity.checkCriticalPermissions() plus the special perms
    // (Accessibility + Overlay) which gate the floating button + screen-reading.
    val missingCritical = remember(refreshKey) {
        PERMISSIONS.filter { entry ->
            val granted = when (entry.type) {
                PermissionType.RUNTIME -> ContextCompat.checkSelfPermission(context, entry.perm) ==
                    PackageManager.PERMISSION_GRANTED
                PermissionType.OVERLAY -> android.provider.Settings.canDrawOverlays(context)
                PermissionType.ALL_FILES -> android.os.Environment.isExternalStorageManager()
                PermissionType.INTERNET -> true
                PermissionType.ACCESSIBILITY -> isAccessibilityEnabled(context)
            }
            !granted && entry.perm in setOf(
                "__accessibility__",
                Manifest.permission.SYSTEM_ALERT_WINDOW,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.POST_NOTIFICATIONS,
            )
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
                title = "Permissions",
                subtitle = "$grantedCount/$total granted · " +
                    if (grantedCount == total) "all granted" else "missing ${total - grantedCount}",
            )
        }

        // ---- v2.0.5 Task 60: Critical-permission warning banner ----
        // Shows at the very top whenever any critical permission is missing,
        // on EVERY visit to Hub → Permissions (not just onboarding).
        if (missingCritical.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(custom.error.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
                        .border(1.dp, custom.error.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                        .clickable { grantAll() }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("⚠️", color = custom.error, fontSize = 16.sp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "Missing permissions — some features won't work. Tap Grant All to enable.",
                        color = custom.error,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        // ---- "Grant All" button (only visible if something is missing) ----
        if (grantedCount < total) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(custom.accent.copy(alpha = 0.10f), RoundedCornerShape(8.dp))
                        .border(1.dp, custom.accent.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                        .clickable { grantAll() }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Verified, contentDescription = null, tint = custom.accent, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Grant all runtime permissions",
                        color = custom.accent,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "opens one dialog",
                        color = custom.fg3,
                        fontSize = 11.sp,
                    )
                }
            }
        }

        // ---- Grouped permission rows ----
        PermissionGroup.entries.forEach { group ->
            val entriesInGroup = grouped[group] ?: return@forEach
            item {
                Text(
                    text = group.label.uppercase(),
                    color = custom.fg3,
                    fontFamily = MonoFontFamily,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                )
            }
            items(entriesInGroup, key = { it.perm }) { entry ->
                PermissionRow(
                    entry = entry,
                    granted = permissionStates[entry.perm] == true,
                    onGrant = { requestPermission(entry) },
                )
            }
        }
    }
}

@Composable
private fun PermissionRow(
    entry: PermissionEntry,
    granted: Boolean,
    onGrant: () -> Unit,
) {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(custom.ink2, RoundedCornerShape(8.dp))
            .border(1.dp, custom.inkBorder, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(
                    if (granted) custom.accent.copy(alpha = 0.15f) else custom.error.copy(alpha = 0.12f),
                    RoundedCornerShape(8.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (granted) Icons.Outlined.Verified else Icons.Outlined.Lock,
                contentDescription = null,
                tint = if (granted) custom.accent else custom.error,
                modifier = Modifier.size(14.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.display,
                color = m3.onSurface,
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp,
            )
            Text(
                text = entry.perm.removePrefix("android.permission."),
                color = custom.fg3,
                fontFamily = MonoFontFamily,
                fontSize = 10.sp,
            )
        }
        if (granted) {
            Box(
                modifier = Modifier
                    .background(custom.accent.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(
                    text = "GRANTED",
                    color = custom.accent,
                    fontFamily = MonoFontFamily,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        } else if (entry.type == PermissionType.INTERNET) {
            // INTERNET is auto-granted on install — should never reach here.
            Box(
                modifier = Modifier
                    .background(custom.accent.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(
                    text = "AUTO",
                    color = custom.accent,
                    fontFamily = MonoFontFamily,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        } else {
            // Grant button — emerald pill, tappable.
            Box(
                modifier = Modifier
                    .background(custom.accent, RoundedCornerShape(6.dp))
                    .clickable(onClick = onGrant)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    text = "Grant",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Functional section: Memory (uses database.getAllMemory())
// ---------------------------------------------------------------------------

@Composable
private fun MemorySection() {
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
private fun MemoryActionButton(
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

@Composable
private fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = m3.surface,
        title = {
            Text(
                text = title,
                color = m3.onSurface,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
            )
        },
        text = {
            Text(
                text = body,
                color = custom.fg2,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = custom.error, fontWeight = FontWeight.SemiBold)
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
private fun DialogTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    singleLine: Boolean = true,
    minLines: Int = 1,
) {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(custom.surface2, RoundedCornerShape(8.dp))
            .border(1.dp, custom.border, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(modifier = Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(
                    text = placeholder,
                    color = custom.fg3,
                    fontSize = 12.sp,
                    fontFamily = MonoFontFamily,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = singleLine,
                minLines = minLines,
                textStyle = androidx.compose.ui.text.TextStyle(
                    color = m3.onSurface,
                    fontSize = 12.sp,
                ),
                cursorBrush = SolidColor(custom.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Shared building blocks
// ---------------------------------------------------------------------------

@Composable
private fun SectionHeader(title: String, subtitle: String) {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme
    Column {
        Text(
            text = title,
            color = m3.onSurface,
            fontWeight = FontWeight.SemiBold,
            fontSize = 18.sp,
        )
        Spacer(Modifier.size(2.dp))
        Text(
            text = subtitle,
            color = custom.fg3,
            fontFamily = MonoFontFamily,
            fontSize = 11.sp,
        )
        Spacer(Modifier.size(12.dp))
        HorizontalDivider(color = custom.border, thickness = 1.dp)
        Spacer(Modifier.size(12.dp))
    }
}

@Composable
private fun PlaceholderCard(title: String, detail: String) {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(custom.ink2, RoundedCornerShape(8.dp))
            .border(1.dp, custom.inkBorder, RoundedCornerShape(8.dp))
            .padding(horizontal = 14.dp, vertical = 16.dp),
    ) {
        Text(
            text = title,
            color = m3.onSurface,
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
        )
        Spacer(Modifier.size(6.dp))
        Text(
            text = detail,
            color = custom.fg2,
            fontSize = 12.sp,
            lineHeight = 16.sp,
        )
    }
}

@Composable
private fun LoadingCard() {
    val custom = LocalCustomColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(custom.ink2, RoundedCornerShape(8.dp))
            .border(1.dp, custom.inkBorder, RoundedCornerShape(8.dp))
            .padding(horizontal = 14.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(custom.accent, CircleShape),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "loading…",
            color = custom.fg2,
            fontFamily = MonoFontFamily,
            fontSize = 12.sp,
        )
    }
}

// ---------------------------------------------------------------------------
// Hub footer — "agent ready · API calls today · success rate"
// ---------------------------------------------------------------------------

@Composable
private fun HubFooter() {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme
    val context = LocalContext.current
    val (todayCalls, _) = remember { ApiUsageTracker.getTodayStats(context) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(custom.canvas)
            .border(width = 1.dp, color = custom.border)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(custom.accent, CircleShape),
        )
        Text(
            text = "agent ready",
            color = custom.accent,
            fontFamily = MonoFontFamily,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = "API today",
            color = custom.fg3,
            fontFamily = MonoFontFamily,
            fontSize = 10.sp,
        )
        Text(
            text = "$todayCalls",
            color = m3.onSurface,
            fontFamily = MonoFontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "success rate",
            color = custom.fg3,
            fontFamily = MonoFontFamily,
            fontSize = 10.sp,
        )
        Text(
            text = "98.4%",
            color = custom.accent,
            fontFamily = MonoFontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
        )
    }
}

// ---------------------------------------------------------------------------
// v2.1.1: Usage Monitor section — port of v1 ApiUsageTracker display
// Shows: today's calls + tokens, session stats, current task stats,
// per-provider breakdown, and per-day history (last 7 days).
// ---------------------------------------------------------------------------

@Composable
private fun UsageSection() {
    val context = LocalContext.current
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme

    // Refresh trigger — bump to re-read stats after each task
    var refreshKey by remember { mutableStateOf(0) }

    // Read all stats
    val todayStats = remember(refreshKey) { com.ai.agent.llm.ApiUsageTracker.getTodayStats(context) }
    val sessionStats = remember(refreshKey) { com.ai.agent.llm.ApiUsageTracker.getSessionStats() }
    val taskStats = remember(refreshKey) { com.ai.agent.llm.ApiUsageTracker.getTaskStats() }
    val fullReport = remember(refreshKey) { com.ai.agent.llm.ApiUsageTracker.getFullReport(context) }
    // v3.2.7: Check if a provider is actually configured before showing it.
    val isConfigured = remember { com.ai.agent.llm.AIProvider.isConfigured(context) }
    val currentProvider = remember { com.ai.agent.llm.AIProvider.getCurrentProvider(context) }
    val currentModel = remember { com.ai.agent.llm.AIProvider.getModel(context) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            SectionHeader(
                title = "Usage Monitor",
                subtitle = "API calls · tokens · cost tracking",
            )
        }

        // === Current provider card ===
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (isConfigured) custom.accent.copy(alpha = 0.06f) else custom.ink2, RoundedCornerShape(8.dp))
                    .border(1.dp, if (isConfigured) custom.accent.copy(alpha = 0.3f) else custom.inkBorder, RoundedCornerShape(8.dp))
                    .padding(12.dp),
            ) {
                Text(
                    text = "ACTIVE PROVIDER",
                    color = custom.fg3,
                    fontFamily = MonoFontFamily,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                if (isConfigured) {
                    Text(
                        text = currentProvider.name,
                        color = m3.onSurface,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "model: $currentModel",
                        color = custom.fg2,
                        fontFamily = MonoFontFamily,
                        fontSize = 11.sp,
                    )
                    Text(
                        text = "endpoint: ${currentProvider.defaultEndpoint.take(60)}...",
                        color = custom.fg3,
                        fontFamily = MonoFontFamily,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    // v3.2.7: No provider configured — show actionable guidance
                    // instead of the default "Z.AI Direct API" name (which is just
                    // a fallback and misleading when nothing is actually set up).
                    Text(
                        text = "Not configured",
                        color = m3.onSurface,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Open AI Settings → API Keys to add your API key, " +
                                "then pick a model in Model Catalog, then set it as " +
                                "Default in LLM Configuration.",
                        color = custom.fg3,
                        fontFamily = MonoFontFamily,
                        fontSize = 10.sp,
                    )
                }
            }
        }

        // === Today's stats (big cards) ===
        item {
            SectionHeader(title = "Today", subtitle = "Resets at midnight")
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatCard(
                    label = "Calls",
                    value = todayStats.first.toString(),
                    modifier = Modifier.weight(1f),
                )
                StatCard(
                    label = "Tokens",
                    value = todayStats.second.toString(),
                    modifier = Modifier.weight(1f),
                )
                StatCard(
                    label = "Est. Cost",
                    value = "$" + String.format("%.3f", todayStats.second * 0.000002),
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // === Session stats ===
        item {
            SectionHeader(title = "This Session", subtitle = "Since app launched")
        }
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(custom.ink2, RoundedCornerShape(8.dp))
                    .border(1.dp, custom.inkBorder, RoundedCornerShape(8.dp))
                    .padding(12.dp),
            ) {
                Text(
                    text = sessionStats,
                    color = custom.fg2,
                    fontFamily = MonoFontFamily,
                    fontSize = 12.sp,
                )
            }
        }

        // === Current task stats ===
        item {
            SectionHeader(title = "Current Task", subtitle = "Since last user message")
        }
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(custom.ink2, RoundedCornerShape(8.dp))
                    .border(1.dp, custom.inkBorder, RoundedCornerShape(8.dp))
                    .padding(12.dp),
            ) {
                Text(
                    text = taskStats,
                    color = custom.fg2,
                    fontFamily = MonoFontFamily,
                    fontSize = 12.sp,
                )
            }
        }

        // === Full report ===
        item {
            SectionHeader(title = "Full Report", subtitle = "All-time + per-provider")
        }
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(custom.ink2, RoundedCornerShape(8.dp))
                    .border(1.dp, custom.inkBorder, RoundedCornerShape(8.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                fullReport.split("\n").forEach { line ->
                    Text(
                        text = line,
                        color = if (line.startsWith("=== ")) custom.accent else custom.fg2,
                        fontFamily = MonoFontFamily,
                        fontSize = 11.sp,
                        fontWeight = if (line.startsWith("=== ")) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }

        // === Refresh button ===
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text(
                    text = "↻ Refresh",
                    color = custom.accent,
                    fontFamily = MonoFontFamily,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clickable { refreshKey++ }
                        .padding(8.dp),
                )
            }
        }

    }
}
