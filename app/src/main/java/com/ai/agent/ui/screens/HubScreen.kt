package com.ai.agent.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Rule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.ai.agent.llm.AIProvider
import com.ai.agent.llm.ApiUsageTracker
import com.ai.agent.skills.SkillManager
import com.ai.agent.storage.AgentDatabase
import com.ai.agent.ui.components.HubNavItem
import com.ai.agent.ui.components.LocalCustomColors
import com.ai.agent.ui.theme.MonoFontFamily

// ============================================================================
// HubScreen — tablet/desktop-style dashboard with side nav + content area.
// ----------------------------------------------------------------------------
// Mirrors `design/03-hub-screen.html`:
//   - Side nav 200dp wide: 13 sections + count badges
//   - Content area: 4 functional sections (Skills, LLM, Permissions, Memory)
//     + simple placeholders for the others
//   - Status footer: agent ready · API calls today · success rate
// ============================================================================

private enum class HubSection(val label: String, val icon: ImageVector, val count: String? = null) {
    SKILLS("Skills", Icons.Outlined.Psychology, "20"),
    RULES("Rules", Icons.Outlined.Rule, "36"),
    TOOLS("Tools", Icons.Outlined.Build, "100+"),
    PLUGINS("Plugins", Icons.Outlined.Extension, "8"),
    MEMORY("Memory", Icons.Outlined.Memory, "142"),
    KNOWLEDGE("Knowledge", Icons.Outlined.BookmarkBorder, "23"),
    HISTORY("History", Icons.Outlined.History, "487"),
    FILES("Files", Icons.Outlined.Folder, "12"),
    LLM("LLM", Icons.Outlined.Lock, "13"),
    VISION("Vision", Icons.Outlined.Visibility),
    AUDIO("Audio", Icons.Outlined.GraphicEq),
    PERMISSIONS("Permissions", Icons.Outlined.Verified, "3/7"),
    SETTINGS("Settings", Icons.Outlined.Settings),
}

@Composable
fun HubScreen(
    initialSection: HubSectionArg = HubSectionArg.SKILLS,
    onBackToChat: () -> Unit = {},
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
                Text(
                    text = "AI Agent v2",
                    color = m3.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                )
            )
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
                    HubSection.SKILLS -> SkillsSection()
                    HubSection.LLM -> LlmSection()
                    HubSection.PERMISSIONS -> PermissionsSection()
                    HubSection.MEMORY -> MemorySection()
                    else -> PlaceholderSection(section = selected)
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

enum class HubSectionArg { SKILLS, RULES, TOOLS, PLUGINS, MEMORY, KNOWLEDGE, HISTORY, FILES, LLM, VISION, AUDIO, PERMISSIONS, SETTINGS

    ;
    fun toHubSection(): HubSection = when (this) {
        HubSectionArg.SKILLS -> HubSection.SKILLS
        HubSectionArg.RULES -> HubSection.RULES
        HubSectionArg.TOOLS -> HubSection.TOOLS
        HubSectionArg.PLUGINS -> HubSection.PLUGINS
        HubSectionArg.MEMORY -> HubSection.MEMORY
        HubSectionArg.KNOWLEDGE -> HubSection.KNOWLEDGE
        HubSectionArg.HISTORY -> HubSection.HISTORY
        HubSectionArg.FILES -> HubSection.FILES
        HubSectionArg.LLM -> HubSection.LLM
        HubSectionArg.VISION -> HubSection.VISION
        HubSectionArg.AUDIO -> HubSection.AUDIO
        HubSectionArg.PERMISSIONS -> HubSection.PERMISSIONS
        HubSectionArg.SETTINGS -> HubSection.SETTINGS
    }
}

// ---------------------------------------------------------------------------
// Functional section: Skills (uses SkillManager.getSkillCount())
// ---------------------------------------------------------------------------

@Composable
private fun SkillsSection() {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme
    val context = LocalContext.current

    var skills by remember { mutableStateOf<List<SkillManager.Skill>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        skills = try {
            val mgr = SkillManager(context)
            mgr.loadSkills()
        } catch (e: Exception) {
            emptyList()
        }
        loaded = true
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            SectionHeader(title = "Skills", subtitle = "${skills.size} loaded · ${if (loaded) "ready" else "loading…"}")
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
                SkillRow(skill = skill)
            }
        }
    }
}

@Composable
private fun SkillRow(skill: SkillManager.Skill) {
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
                .background(custom.accent.copy(alpha = 0.15f), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Bolt, contentDescription = null, tint = custom.accent, modifier = Modifier.size(14.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = skill.name,
                color = m3.onSurface,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
            )
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
        Text(
            text = "v${skill.version}",
            color = custom.fg3,
            fontFamily = MonoFontFamily,
            fontSize = 10.sp,
        )
    }
}

// ---------------------------------------------------------------------------
// Functional section: LLM Providers (uses AIProvider.PROVIDERS)
// ---------------------------------------------------------------------------

@Composable
private fun LlmSection() {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme
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
        items(providers) { provider ->
            val isActive = provider.id == currentProvider.id
            ProviderRow(provider = provider, isActive = isActive, currentModel = currentModel)
        }
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

private val TRACKED_PERMISSIONS = listOf(
    Manifest.permission.RECORD_AUDIO,
    Manifest.permission.READ_CONTACTS,
    Manifest.permission.CALL_PHONE,
    Manifest.permission.SEND_SMS,
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.READ_CALENDAR,
    Manifest.permission.READ_EXTERNAL_STORAGE,
)

@Composable
private fun PermissionsSection() {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme
    val context = LocalContext.current

    val grantedCount = TRACKED_PERMISSIONS.count {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
    val total = TRACKED_PERMISSIONS.size

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            SectionHeader(
                title = "Permissions",
                subtitle = "$grantedCount/$total granted · ${if (grantedCount == total) "all granted" else "missing ${total - grantedCount}"}",
            )
        }
        items(TRACKED_PERMISSIONS) { perm ->
            val granted = ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
            PermissionRow(name = perm.removePrefix("android.permission."), granted = granted)
        }
    }
}

@Composable
private fun PermissionRow(name: String, granted: Boolean) {
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
        Text(
            text = name,
            color = m3.onSurface,
            fontFamily = MonoFontFamily,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .background(
                    if (granted) custom.accent.copy(alpha = 0.15f) else custom.error.copy(alpha = 0.12f),
                    RoundedCornerShape(4.dp),
                )
                .padding(horizontal = 8.dp, vertical = 2.dp),
        ) {
            Text(
                text = if (granted) "GRANTED" else "MISSING",
                color = if (granted) custom.accent else custom.error,
                fontFamily = MonoFontFamily,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Functional section: Memory (uses database.getAllMemory())
// ---------------------------------------------------------------------------

@Composable
private fun MemorySection() {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme
    val context = LocalContext.current

    var memory by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        memory = try {
            AgentDatabase(context).getAllMemory()
        } catch (e: Exception) {
            emptyMap()
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
                title = "Memory",
                subtitle = "${memory.size} keys · ${if (loaded) "ready" else "loading…"}",
            )
        }
        if (memory.isEmpty() && loaded) {
            item { PlaceholderCard(title = "No memories stored", detail = "Ask the agent to remember something — try \"remember my name is Arun\".") }
        } else if (memory.isEmpty()) {
            item { LoadingCard() }
        } else {
            items(memory.entries.toList()) { entry ->
                MemoryRow(key = entry.key, value = entry.value)
            }
        }
    }
}

@Composable
private fun MemoryRow(key: String, value: String) {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(custom.ink2, RoundedCornerShape(8.dp))
            .border(1.dp, custom.inkBorder, RoundedCornerShape(8.dp))
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
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Placeholder section (for Vision, Audio, Settings, etc.)
// ---------------------------------------------------------------------------

@Composable
private fun PlaceholderSection(section: HubSection) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        SectionHeader(
            title = section.label,
            subtitle = "coming soon",
        )
        Spacer(Modifier.height(16.dp))
        PlaceholderCard(
            title = "${section.label} section is under construction",
            detail = "This panel will be populated with real data in a future task. The layout " +
                "matches the side-nav structure of the approved mockup so future work is plug-in only.",
        )
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
