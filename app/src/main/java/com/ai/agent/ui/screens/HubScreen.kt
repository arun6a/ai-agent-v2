package com.ai.agent.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Rule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.agent.ui.components.HubNavItem
import com.ai.agent.ui.components.LocalCustomColors
import com.ai.agent.ui.screens.hub.AiSettingsSection
import com.ai.agent.ui.screens.hub.FilesSection
import com.ai.agent.ui.screens.hub.HistorySection
import com.ai.agent.ui.screens.hub.HubFooter
import com.ai.agent.ui.screens.hub.KnowledgeSection
import com.ai.agent.ui.screens.hub.MemorySection
import com.ai.agent.ui.screens.hub.PermissionsSection
import com.ai.agent.ui.screens.hub.RulesSection
import com.ai.agent.ui.screens.hub.SettingsSection
import com.ai.agent.ui.screens.hub.SkillsSection
import com.ai.agent.ui.screens.hub.ToolsSection
import com.ai.agent.ui.screens.hub.UsageSection
import com.ai.agent.ui.theme.MonoFontFamily

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
//
// v3.3.15: Section composables + shared building blocks extracted into
// `com.ai.agent.ui.screens.hub` package. Each section file owns its own
// state, helper functions, and data classes. HubScreen stays the router.
// See:
//   - hub/SharedHubComponents.kt   (SectionHeader, PlaceholderCard, LoadingCard, HubFooter, ...)
//   - hub/SkillsSection.kt         (SkillsSection + dialogs)
//   - hub/RulesSection.kt
//   - hub/ToolsSection.kt
//   - hub/HistorySection.kt
//   - hub/KnowledgeSection.kt
//   - hub/FilesSection.kt
//   - hub/MemorySection.kt
//   - hub/AiSettingsSection.kt     (Vision + Audio + ModelSettingsScreen wrapper)
//   - hub/SettingsSection.kt
//   - hub/LlmSection.kt
//   - hub/PermissionsSection.kt
//   - hub/UsageSection.kt
//   - hub/AppsSection.kt           (AppProfileManager integration; not currently routed)

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
