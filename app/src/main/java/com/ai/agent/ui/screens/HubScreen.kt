package com.ai.agent.ui.screens

import android.content.res.Configuration
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Rule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.agent.ui.components.LocalCustomColors
import com.ai.agent.ui.screens.hub.AiSettingsSection
import com.ai.agent.ui.screens.hub.FilesSection
import com.ai.agent.ui.screens.hub.HistorySection
import com.ai.agent.ui.screens.hub.KnowledgeSection
import com.ai.agent.ui.screens.hub.LogsSection
import com.ai.agent.ui.screens.hub.MemorySection
import com.ai.agent.ui.screens.hub.PermissionsSection
import com.ai.agent.ui.screens.hub.RulesSection
import com.ai.agent.ui.screens.hub.SettingsSection
import com.ai.agent.ui.screens.hub.SkillsSection
import com.ai.agent.ui.screens.hub.ToolsSection
import com.ai.agent.ui.screens.hub.UsageSection
import com.ai.agent.ui.theme.MonoFontFamily

// ============================================================================
// HubScreen — v4.0.0 stack navigation redesign.
// ----------------------------------------------------------------------------
// OLD (v3.x): side-nav split view (200dp sidebar + content area)
// NEW (v4.0.0): hub home grid → section detail page with back button
//
// - Hub home: grouped category cards (AUTOMATION, AI CONFIG, DATA, SYSTEM, etc.)
// - Click a card → full-page section detail with sticky top bar (back + title)
// - Seamless front/back navigation via AnimatedContent
// - Responsive: 2 cols on phone, 3 cols on tablet
// - No stale count badges, no "SECTIONS · 12" counter, no HubFooter
// ============================================================================

enum class HubSection(val label: String, val icon: ImageVector) {
    HISTORY("History", Icons.Outlined.History),
    RULES("Automation", Icons.Outlined.Rule),
    SKILLS("Skills", Icons.Outlined.Psychology),
    TOOLS("Tools", Icons.Outlined.Build),
    MEMORY("Memory", Icons.Outlined.Memory),
    KNOWLEDGE("Knowledge", Icons.Outlined.BookmarkBorder),
    FILES("Files", Icons.Outlined.Folder),
    AI_SETTINGS("AI Settings", Icons.Outlined.SmartToy),
    USAGE("Usage", Icons.Outlined.BarChart),
    PERMISSIONS("Permissions", Icons.Outlined.Verified),
    LOGS("Logs", Icons.Outlined.Description),
    SETTINGS("Settings", Icons.Outlined.Settings),
}

/** Category group for the hub home grid. */
enum class HubCategory(val title: String, val sections: List<HubSection>) {
    AUTOMATION("AUTOMATION", listOf(HubSection.RULES, HubSection.SKILLS)),
    AI("AI CONFIGURATION", listOf(HubSection.AI_SETTINGS, HubSection.USAGE, HubSection.PERMISSIONS)),
    DATA("DATA & MEMORY", listOf(HubSection.MEMORY, HubSection.KNOWLEDGE, HubSection.FILES)),
    SYSTEM("SYSTEM", listOf(HubSection.TOOLS, HubSection.LOGS, HubSection.SETTINGS)),
    CONVERSATION("CONVERSATION", listOf(HubSection.HISTORY)),
}

@Composable
fun HubScreen(
    initialSection: HubSectionArg? = null,  // v4.0.1: null = open Hub Home (not Skills)
    onBackToChat: () -> Unit = {},
    onOpenProviderSettings: () -> Unit = {},
    onEditSkill: (skillName: String?, isReadOnly: Boolean) -> Unit = { _, _ -> },
    onOpenSystemPromptEditor: () -> Unit = {},
    onContinueSession: (Long) -> Unit = {},
) {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme

    var selectedSection by remember { mutableStateOf<HubSection?>(initialSection?.toHubSection()) }

    AnimatedContent(
        targetState = selectedSection,
        transitionSpec = {
            if (initialState == null && targetState != null) {
                // home → detail: slide in from right
                fadeIn(tween(200)) togetherWith fadeOut(tween(150))
            } else if (initialState != null && targetState == null) {
                // detail → home: slide out to right
                fadeIn(tween(200)) togetherWith fadeOut(tween(150))
            } else {
                fadeIn(tween(100)) togetherWith fadeOut(tween(100))
            }
        },
        label = "hub_transition",
    ) { section ->
        if (section == null) {
            HubHome(
                onSectionClick = { selectedSection = it },
                onBackToChat = onBackToChat,
            )
        } else {
            SectionDetailPage(
                section = section,
                onBack = { selectedSection = null },
                onOpenProviderSettings = onOpenProviderSettings,
                onEditSkill = onEditSkill,
                onOpenSystemPromptEditor = onOpenSystemPromptEditor,
                onContinueSession = onContinueSession,
            )
        }
    }
}

// ============================================================================
// Hub Home — list of categorized section rows
// ============================================================================

@Composable
private fun HubHome(
    onSectionClick: (HubSection) -> Unit,
    onBackToChat: () -> Unit,
) {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(custom.canvas),
    ) {
        // ---- Top bar ----
        HubTopBar(title = "Aira Lab", onBack = onBackToChat, showBack = true)
        // ---- List ----
        LazyColumn(
            modifier = Modifier
                .fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
        ) {
            HubCategory.entries.forEach { category ->
                // Category header
                item {
                    Text(
                        text = category.title,
                        color = custom.fg3,
                        fontFamily = MonoFontFamily,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                    )
                }
                // Section rows in this category
                items(category.sections) { section ->
                    HubListItem(
                        section = section,
                        onClick = { onSectionClick(section) },
                    )
                }
            }
        }
    }
}

@Composable
private fun HubListItem(
    section: HubSection,
    onClick: () -> Unit,
) {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(custom.accent.copy(alpha = 0.15f), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = section.icon,
                contentDescription = section.label,
                tint = custom.accent,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.size(14.dp))
        Text(
            text = section.label,
            color = m3.onSurface,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

// ============================================================================
// Section Detail Page — full-screen section with sticky top bar (back button)
// ============================================================================

@Composable
private fun SectionDetailPage(
    section: HubSection,
    onBack: () -> Unit,
    onOpenProviderSettings: () -> Unit,
    onEditSkill: (String?, Boolean) -> Unit,
    onOpenSystemPromptEditor: () -> Unit,
    onContinueSession: (Long) -> Unit,
) {
    val custom = LocalCustomColors.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(custom.canvas),
    ) {
        // Sticky top bar with back button
        HubTopBar(title = section.label, onBack = onBack, showBack = true)

        // Section content — fills remaining space, scrolls internally
        Box(
            modifier = Modifier
                .fillMaxSize(),
        ) {
            when (section) {
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
                HubSection.LOGS -> LogsSection()
                HubSection.SETTINGS -> SettingsSection(onOpenSystemPromptEditor = onOpenSystemPromptEditor)
            }
        }
    }
}

// ============================================================================
// Shared top bar — used by both HubHome and SectionDetailPage
// ============================================================================

@Composable
private fun HubTopBar(
    title: String,
    onBack: () -> Unit,
    showBack: Boolean,
) {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(m3.surface)
            .border(width = 1.dp, color = custom.border)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showBack) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = custom.fg2,
                )
            }
        }
        Spacer(Modifier.size(4.dp))
        Text(
            text = title,
            color = m3.onSurface,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
        )
    }
}

// ---------------------------------------------------------------------------
// Argument wrapper so navigation can pass an initial section (e.g. Settings →
// open the Hub directly on the Settings section).
// ---------------------------------------------------------------------------

enum class HubSectionArg { HISTORY, RULES, SKILLS, TOOLS, MEMORY, KNOWLEDGE, FILES, AI_SETTINGS, USAGE, PERMISSIONS, LOGS, SETTINGS
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
        HubSectionArg.LOGS -> HubSection.LOGS
        HubSectionArg.SETTINGS -> HubSection.SETTINGS
    }
}
