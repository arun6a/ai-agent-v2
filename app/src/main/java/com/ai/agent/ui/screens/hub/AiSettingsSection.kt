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

// Hub section: AI Settings — wraps ModelSettingsScreen + Vision/Audio info.

// ---------------------------------------------------------------------------
// Task 29 — Functional section: Vision (3-tier fallback diagram + vision models)
// ---------------------------------------------------------------------------

internal fun isVisionCapable(modelId: String): Boolean {
    val m = modelId.lowercase()
    return m.contains("vision") || m.contains("-v") || m.contains("vlm") ||
        m.contains("pixtral") || m.contains("gpt-4-vision") || m.contains("vl-") ||
        m.contains("glm-4.5v") || m.contains("glm-4.1v") || m.contains("glm-5v") ||
        m.contains("llama-3.2") && m.contains("vision") || m.contains("gemma-4-31b")
}

// v3.2.0: AiSettingsSection now uses the new ModelSettingsScreen with 3 tabs
@Composable
internal fun AiSettingsSection(onOpenProviderSettings: () -> Unit = {}) {
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

// StatBox now lives in SharedHubComponents.kt (shared across sections).

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
