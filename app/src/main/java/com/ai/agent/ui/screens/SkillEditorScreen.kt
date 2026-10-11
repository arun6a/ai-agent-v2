package com.ai.agent.ui.screens

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.agent.skills.SkillManager
import com.ai.agent.ui.components.LocalCustomColors
import com.ai.agent.ui.theme.MonoFontFamily
import java.io.File

// ============================================================================
// SkillEditorScreen — full-screen editor for viewing / editing / creating a
// single skill (.md file with YAML frontmatter).
// ----------------------------------------------------------------------------
// Three modes (selected via parameters):
//
//  • View   (skillName != null, isReadOnly = true)  → built-in skill
//      - All fields read-only
//      - "Copy to editable" button → copies the .md into the user skills dir
//        and pops back so the user can edit their copy.
//
//  • Edit   (skillName != null, isReadOnly = false) → existing user skill
//      - All fields editable
//      - Save → writes to /storage/emulated/0/Documents/ai-workspace/skills/<name>.md
//
//  • Create (skillName == null)                      → brand new user skill
//      - Empty fields (version defaults to "1.0")
//      - Save → writes new file under user skills dir
//      - Filename is auto-derived from the skill name
//
// Storage paths:
//   built-in (read-only): assets/skills/*.md
//   user-created (rw):    /storage/emulated/0/Documents/ai-workspace/skills/*.md
// ============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillEditorScreen(
    skillName: String? = null,
    isReadOnly: Boolean = false,
    onBack: () -> Unit,
) {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme
    val context = LocalContext.current

    // ---- Form state ----
    var name by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var trigger by rememberSaveable { mutableStateOf("") }
    var toolsUsed by rememberSaveable { mutableStateOf("") }
    var version by rememberSaveable { mutableStateOf("1.0") }
    var body by rememberSaveable { mutableStateOf("") }

    // Track which fileName the loaded skill came from (so Edit mode overwrites
    // the same file rather than re-deriving from the name).
    var loadedFileName by rememberSaveable { mutableStateOf<String?>(null) }

    // ---- Load existing skill (View + Edit modes) ----
    LaunchedEffect(skillName) {
        if (skillName != null) {
            val mgr = SkillManager(context)
            mgr.loadSkills()
            val skill = mgr.getSkill(skillName) ?: mgr.findSkill(skillName)
            if (skill != null) {
                name = skill.name
                description = skill.description
                trigger = skill.trigger
                toolsUsed = skill.toolsUsed.joinToString(", ")
                version = skill.version
                body = skill.body
                loadedFileName = skill.fileName
            }
        }
    }

    // The caller (HubScreen via AppNavigation) decides read-only mode based
    // on whether the file lives under the user skills dir.
    val effectiveReadOnly = isReadOnly

    // ---- Save logic (Edit + Create modes) ----
    fun saveSkill() {
        if (name.isBlank()) {
            Toast.makeText(context, "Skill name cannot be empty", Toast.LENGTH_SHORT).show()
            return
        }
        // Preserve the original filename when editing the same skill — so renaming
        // the "name" frontmatter field doesn't orphan the old file.
        val filename = loadedFileName?.takeIf { it.endsWith(".md") }
            ?: deriveFileName(name)
        val toolsList = toolsUsed.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(", ")
        val content = buildString {
            appendLine("---")
            appendLine("name: ${name.trim()}")
            appendLine("description: ${description.trim()}")
            appendLine("trigger: ${trigger.trim()}")
            appendLine("tools_used: [$toolsList]")
            appendLine("version: ${version.trim().ifEmpty { "1.0" }}")
            appendLine("---")
            appendLine()
            appendLine(body)
        }

        val dir = File("/storage/emulated/0/Documents/ai-workspace/skills")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, filename)

        try {
            file.writeText(content)
            Toast.makeText(context, "Skill saved: $filename", Toast.LENGTH_SHORT).show()
            onBack()
        } catch (e: Exception) {
            Toast.makeText(context, "Failed to save: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ---- "Copy to editable" for read-only built-in skills ----
    fun copyToEditable() {
        if (name.isBlank()) return
        val filename = deriveFileName(name)
        val toolsList = toolsUsed.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(", ")
        val content = buildString {
            appendLine("---")
            appendLine("name: ${name.trim()}")
            appendLine("description: ${description.trim()}")
            appendLine("trigger: ${trigger.trim()}")
            appendLine("tools_used: [$toolsList]")
            appendLine("version: ${version.trim().ifEmpty { "1.0" }}")
            appendLine("---")
            appendLine()
            appendLine(body)
        }
        val dir = File("/storage/emulated/0/Documents/ai-workspace/skills")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, filename)
        try {
            file.writeText(content)
            Toast.makeText(context, "Copied to $filename — now editable", Toast.LENGTH_SHORT).show()
            // After copying, the user-owned version is editable. Pop back to
            // the Hub so the user can re-open it in Edit mode.
            onBack()
        } catch (e: Exception) {
            Toast.makeText(context, "Copy failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    val title = when {
        skillName == null -> "New Skill"
        effectiveReadOnly -> "View Skill"
        else -> "Edit Skill"
    }

    Scaffold(
        containerColor = custom.canvas,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = title,
                            color = m3.onSurface,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp,
                        )
                        if (effectiveReadOnly) {
                            Spacer(Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .background(custom.surface3, RoundedCornerShape(6.dp))
                                    .border(1.dp, custom.border, RoundedCornerShape(6.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            ) {
                                Text(
                                    text = "READ-ONLY",
                                    color = custom.fg3,
                                    fontFamily = MonoFontFamily,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
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
                    if (effectiveReadOnly) {
                        // Built-in skill: show a "copy to editable" button.
                        Box(
                            modifier = Modifier
                                .padding(end = 12.dp)
                                .background(custom.accent, RoundedCornerShape(8.dp))
                                .clickable(onClick = { copyToEditable() })
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Text(
                                text = "Copy to editable",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    } else {
                        // Edit / Create: save button.
                        IconButton(onClick = { saveSkill() }) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = if (skillName == null) "Create" else "Save",
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ===== METADATA =====
            item { FieldLabel("NAME") }
            item {
                SkillField(
                    value = name,
                    onValueChange = { name = it },
                    readOnly = effectiveReadOnly,
                    placeholder = "e.g. morning-briefing",
                    singleLine = true,
                )
            }

            item { FieldLabel("DESCRIPTION") }
            item {
                SkillField(
                    value = description,
                    onValueChange = { description = it },
                    readOnly = effectiveReadOnly,
                    placeholder = "What this skill does",
                    singleLine = true,
                )
            }

            item { FieldLabel("TRIGGER") }
            item {
                SkillField(
                    value = trigger,
                    onValueChange = { trigger = it },
                    readOnly = effectiveReadOnly,
                    placeholder = "keyword1|keyword2|phrase here",
                    singleLine = true,
                )
            }

            item { FieldLabel("TOOLS USED") }
            item {
                SkillField(
                    value = toolsUsed,
                    onValueChange = { toolsUsed = it },
                    readOnly = effectiveReadOnly,
                    placeholder = "tool1, tool2, tool3",
                    singleLine = true,
                )
            }

            item { FieldLabel("VERSION") }
            item {
                SkillField(
                    value = version,
                    onValueChange = { version = it },
                    readOnly = effectiveReadOnly,
                    placeholder = "1.0",
                    singleLine = true,
                    keyboardType = KeyboardType.Ascii,
                )
            }

            // ===== BODY =====
            item {
                Spacer(Modifier.height(4.dp))
                FieldLabel("BODY (MARKDOWN)")
            }
            item {
                SkillBodyField(
                    value = body,
                    onValueChange = { body = it },
                    readOnly = effectiveReadOnly,
                    placeholder = "# My Skill\n\nWhen the user asks for ...",
                )
            }

            // ===== SAVE BUTTON (Edit/Create only) =====
            if (!effectiveReadOnly) {
                item {
                    Spacer(Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(custom.accent, RoundedCornerShape(10.dp))
                            .clickable(onClick = { saveSkill() })
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (skillName == null) "Create skill" else "Save skill",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }

            // ===== READ-ONLY NOTE for built-in skills =====
            if (effectiveReadOnly) {
                item {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(custom.surface2, RoundedCornerShape(10.dp))
                            .border(1.dp, custom.border, RoundedCornerShape(10.dp))
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Lock,
                            contentDescription = null,
                            tint = custom.fg3,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Built-in skills ship with the APK and cannot be modified. " +
                                "Tap \"Copy to editable\" above to create an editable copy in your workspace.",
                            color = custom.fg2,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                        )
                    }
                }
            }

            // Trailing spacer so the last item has bottom breathing room.
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

// ---------------------------------------------------------------------------
// Sub-components
// ---------------------------------------------------------------------------

@Composable
private fun FieldLabel(text: String) {
    val custom = LocalCustomColors.current
    Text(
        text = text,
        color = custom.fg3,
        fontFamily = MonoFontFamily,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun SkillField(
    value: String,
    onValueChange: (String) -> Unit,
    readOnly: Boolean,
    placeholder: String,
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        readOnly = readOnly,
        singleLine = singleLine,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text(placeholder, color = custom.fg3, fontSize = 13.sp) },
        textStyle = TextStyle(
            color = if (readOnly) custom.fg2 else m3.onSurface,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        ),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = custom.accent,
            unfocusedBorderColor = custom.border,
            cursorColor = custom.accent,
            focusedContainerColor = m3.surface,
            unfocusedContainerColor = if (readOnly) custom.surface2 else m3.surface,
        ),
        shape = RoundedCornerShape(8.dp),
    )
}

@Composable
private fun SkillBodyField(
    value: String,
    onValueChange: (String) -> Unit,
    readOnly: Boolean,
    placeholder: String,
) {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        readOnly = readOnly,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 200.dp),
        placeholder = {
            Text(
                placeholder,
                color = custom.fg3,
                fontSize = 12.sp,
                fontFamily = MonoFontFamily,
            )
        },
        textStyle = TextStyle(
            color = if (readOnly) custom.fg2 else m3.onSurface,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            fontFamily = MonoFontFamily,
        ),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = custom.accent,
            unfocusedBorderColor = custom.border,
            cursorColor = custom.accent,
            focusedContainerColor = m3.surface,
            unfocusedContainerColor = if (readOnly) custom.surface2 else m3.surface,
        ),
        shape = RoundedCornerShape(8.dp),
    )
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/**
 * Returns true if a skill file with the given name exists in the user skills
 * directory (i.e. was created by the user and is editable / deletable).
 *
 * Used by both [SkillEditorScreen] (to decide read-only mode) and
 * [HubScreen] (to show the "user" vs "built-in" badge + long-press menu).
 */
fun isUserCreatedSkill(fileName: String): Boolean {
    val userDir = File("/storage/emulated/0/Documents/ai-workspace/skills")
    return File(userDir, fileName).exists()
}

/** Convert a human-readable skill name into a safe filename ending in .md. */
private fun deriveFileName(name: String): String {
    val base = name.lowercase()
        .replace(" ", "_")
        .replace(Regex("[^a-z0-9_-]"), "")
        .ifBlank { "skill" }
    return "$base.md"
}
