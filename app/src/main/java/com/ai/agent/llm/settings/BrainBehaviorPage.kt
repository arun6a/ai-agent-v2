package com.ai.agent.llm.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.agent.llm.AIProvider
import com.ai.agent.llm.AgentThinkingSettings
import com.ai.agent.llm.AutoFallbackSettings
import com.ai.agent.ui.components.LocalCustomColors
import com.ai.agent.ui.theme.MonoFontFamily

/**
 * v3.5.0: BrainBehaviorPage — unified settings for ALL brain behavior toggles.
 *
 * Replaces the old "Advanced" section (§7) in AI Settings.
 * Consolidates toggles that were scattered across:
 *   - Hub → Settings (prompt mode, vision mode, browser mode, always learn, contextual prompts)
 *   - AI Settings → LLM Config (contextual prompts)
 *   - AI Settings → Smart Routing (auto-fallback toggles)
 *   - AI Settings → Advanced (prompt mode)
 *
 * Each toggle card shows: title + one-line description of what it does.
 *
 * Groups:
 *   THINKING — reflection, planning, verification (new)
 *   LEARNING — always learn, knowledge auto-recall
 *   PROMPT — contextual prompts, prompt mode selector
 *   VISION — vision mode selector
 *   ROUTING — auto-fallback bindings, models, auto-pick cheapest
 *   BROWSER — browser mode selector
 *
 * "Reset to recommended defaults" button at the bottom.
 */
@Composable
fun BrainBehaviorPage(onToast: (String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val custom = LocalCustomColors.current

    // Thinking toggles
    var reflection by remember { mutableStateOf(AgentThinkingSettings.isReflectionEnabled(context)) }
    var planning by remember { mutableStateOf(AgentThinkingSettings.isPlanningEnabled(context)) }
    var verification by remember { mutableStateOf(AgentThinkingSettings.isVerificationEnabled(context)) }

    // Learning toggles
    var alwaysLearn by remember { mutableStateOf(AgentThinkingSettings.isAlwaysLearnEnabled(context)) }
    var knowledgeAutoRecall by remember { mutableStateOf(AgentThinkingSettings.isKnowledgeAutoRecallEnabled(context)) }

    // Prompt
    var contextualPrompts by remember { mutableStateOf(AIProvider.isContextualPromptsEnabled(context)) }
    var promptMode by remember { mutableStateOf(AIProvider.getPromptMode(context)) }

    // Vision
    var visionMode by remember { mutableStateOf(AIProvider.getVisionMode(context)) }

    // Routing
    var autoFallbackKeys by remember { mutableStateOf(AutoFallbackSettings.isEnabled(context)) }
    var autoFallbackModels by remember { mutableStateOf(AutoFallbackSettings.isAutoFallbackModelsEnabled(context)) }
    var autoPickCheapest by remember { mutableStateOf(AutoFallbackSettings.isAutoPickCheapestEnabled(context)) }

    // Browser
    var browserMode by remember { mutableStateOf(AIProvider.getBrowserMode(context)) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ConfigSectionHeader("Brain Behavior", "Control how the AI thinks, plans, and learns")

        // ===== THINKING =====
        BehaviorGroupHeader("💭 THINKING", custom)
        BehaviorToggleCard(
            title = "Periodic Reflection",
            description = "Every 5 steps, brain asks 'am I making progress?' If stuck, switches approach.",
            checked = reflection,
            onCheck = { reflection = it; AgentThinkingSettings.setReflectionEnabled(context, it); onToast("Periodic reflection: ${if (it) "ON" else "OFF"}") },
            custom = custom,
        )
        BehaviorToggleCard(
            title = "Task Planning",
            description = "For complex tasks, brain creates a step-by-step plan before starting. Adds 2-3s. Plan is advisory — brain adapts if reality differs.",
            checked = planning,
            onCheck = { planning = it; AgentThinkingSettings.setPlanningEnabled(context, it); onToast("Task planning: ${if (it) "ON" else "OFF"}") },
            custom = custom,
        )
        BehaviorToggleCard(
            title = "Post-Action Verification",
            description = "After send/delete/email actions, brain reads screen to confirm it worked. Catches 'thought I sent but didn't' bugs.",
            checked = verification,
            onCheck = { verification = it; AgentThinkingSettings.setVerificationEnabled(context, it); onToast("Post-action verification: ${if (it) "ON" else "OFF"}") },
            custom = custom,
        )

        Spacer(Modifier.height(8.dp))

        // ===== LEARNING =====
        BehaviorGroupHeader("📚 LEARNING", custom)
        BehaviorToggleCard(
            title = "Always Learn",
            description = "Saves successful workflows + failure lessons to memory for future tasks.",
            checked = alwaysLearn,
            onCheck = { alwaysLearn = it; AgentThinkingSettings.setAlwaysLearnEnabled(context, it); onToast("Always learn: ${if (it) "ON" else "OFF"}") },
            custom = custom,
        )
        BehaviorToggleCard(
            title = "Knowledge Auto-Recall",
            description = "When you ask a question, brain proactively searches saved documents for relevant info. Turn OFF to save tokens or for manual control.",
            checked = knowledgeAutoRecall,
            onCheck = { knowledgeAutoRecall = it; AgentThinkingSettings.setKnowledgeAutoRecallEnabled(context, it); onToast("Knowledge auto-recall: ${if (it) "ON" else "OFF"}") },
            custom = custom,
        )

        Spacer(Modifier.height(8.dp))

        // ===== PROMPT =====
        BehaviorGroupHeader("🎯 PROMPT", custom)
        BehaviorToggleCard(
            title = "Contextual Prompts",
            description = "Only shows brain tools relevant to the current task. Saves 85% of API tokens. Turn OFF if brain misses tools it should have.",
            checked = contextualPrompts,
            onCheck = { contextualPrompts = it; AIProvider.setContextualPromptsEnabled(context, it); onToast("Contextual prompts: ${if (it) "ON" else "OFF"}") },
            custom = custom,
        )
        // Prompt mode selector
        BehaviorSelectorCard(
            title = "Prompt Mode",
            description = "Smart: full prompt (~11K tokens) | Balanced: trimmed (~1.5K) | Fast: minimal (~750) | Custom: your own",
            options = listOf("smart", "balanced", "fast", "custom"),
            selected = promptMode,
            onSelect = { promptMode = it; AIProvider.setPromptMode(context, it); onToast("Prompt mode: $it") },
            custom = custom,
        )

        Spacer(Modifier.height(8.dp))

        // ===== VISION =====
        BehaviorGroupHeader("👁 VISION", custom)
        BehaviorSelectorCard(
            title = "Vision Mode",
            description = "Auto: a11y + ML Kit + VLM fallback | ML Kit: on-device only (0 cost) | VLM: cloud vision (best, costs tokens)",
            options = listOf("auto", "mlkit", "vlm"),
            selected = visionMode,
            onSelect = { visionMode = it; AIProvider.setVisionMode(context, it); onToast("Vision mode: $it") },
            custom = custom,
        )

        Spacer(Modifier.height(8.dp))

        // ===== ROUTING & FAILOVER =====
        BehaviorGroupHeader("🔄 ROUTING & FAILOVER", custom)
        BehaviorToggleCard(
            title = "Auto-Fallback Bindings",
            description = "If one API key fails (429/402/5xx), automatically try the next key for the same model.",
            checked = autoFallbackKeys,
            onCheck = { autoFallbackKeys = it; AutoFallbackSettings.setEnabled(context, it); onToast("Auto-fallback bindings: ${if (it) "ON" else "OFF"}") },
            custom = custom,
        )
        BehaviorToggleCard(
            title = "Auto-Fallback Models",
            description = "If ALL keys for the current model fail, automatically switch to a different saved model.",
            checked = autoFallbackModels,
            onCheck = { autoFallbackModels = it; AutoFallbackSettings.setAutoFallbackModelsEnabled(context, it); onToast("Auto-fallback models: ${if (it) "ON" else "OFF"}") },
            custom = custom,
        )
        BehaviorToggleCard(
            title = "Auto-Pick Cheapest",
            description = "Always picks the cheapest healthy API key instead of your priority order. Saves money. OFF = brain uses your priority order.",
            checked = autoPickCheapest,
            onCheck = { autoPickCheapest = it; AutoFallbackSettings.setAutoPickCheapestEnabled(context, it); onToast("Auto-pick cheapest: ${if (it) "ON" else "OFF"}") },
            custom = custom,
        )

        Spacer(Modifier.height(8.dp))

        // ===== BROWSER =====
        BehaviorGroupHeader("🌐 BROWSER", custom)
        BehaviorSelectorCard(
            title = "Browser Mode",
            description = "Auto: brain picks per task | In-App: embedded WebView | Chrome: opens Chrome app",
            options = listOf("auto", "in_app", "chrome"),
            selected = browserMode,
            onSelect = { browserMode = it; AIProvider.setBrowserMode(context, it); onToast("Browser mode: $it") },
            custom = custom,
        )

        Spacer(Modifier.height(16.dp))

        // ===== RESET BUTTON =====
        TextButton(
            onClick = {
                AgentThinkingSettings.resetToDefaults(context)
                AutoFallbackSettings.setEnabled(context, true)
                AutoFallbackSettings.setAutoFallbackModelsEnabled(context, true)
                AutoFallbackSettings.setAutoPickCheapestEnabled(context, false)
                AIProvider.setContextualPromptsEnabled(context, true)
                AIProvider.setPromptMode(context, "smart")
                AIProvider.setVisionMode(context, "auto")
                AIProvider.setBrowserMode(context, "auto")
                // Trigger recomposition
                reflection = true; planning = true; verification = true
                alwaysLearn = true; knowledgeAutoRecall = true
                contextualPrompts = true; promptMode = "smart"
                visionMode = "auto"
                autoFallbackKeys = true; autoFallbackModels = true; autoPickCheapest = false
                browserMode = "auto"
                onToast("Reset to recommended defaults ✓")
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Reset to recommended defaults", color = custom.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun BehaviorGroupHeader(title: String, custom: com.ai.agent.ui.components.CustomColors) {
    Text(title, color = custom.fg2, fontSize = 12.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
}

/**
 * A toggle card with title + one-line description + Switch.
 * This is the standard card used for every ON/OFF toggle in Brain Behavior.
 */
@Composable
private fun BehaviorToggleCard(
    title: String,
    description: String,
    checked: Boolean,
    onCheck: (Boolean) -> Unit,
    custom: com.ai.agent.ui.components.CustomColors,
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(custom.ink2, RoundedCornerShape(8.dp))
            .border(1.dp, custom.inkBorder, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold)
            Text(description, color = custom.fg3, fontSize = 9.sp, lineHeight = 12.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheck,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = custom.accent,
            ),
        )
    }
}

/**
 * A selector card with title + description + horizontal option chips.
 */
@Composable
private fun BehaviorSelectorCard(
    title: String,
    description: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    custom: com.ai.agent.ui.components.CustomColors,
) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .background(custom.ink2, RoundedCornerShape(8.dp))
            .border(1.dp, custom.inkBorder, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(title, color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold)
        Text(description, color = custom.fg3, fontSize = 9.sp, lineHeight = 12.sp)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            options.forEach { opt ->
                val isActive = opt == selected
                Text(
                    text = opt,
                    color = if (isActive) Color.White else custom.fg2,
                    fontSize = 9.sp,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier
                        .background(
                            if (isActive) custom.accent else custom.ink2,
                            RoundedCornerShape(10.dp),
                        )
                        .border(
                            1.dp,
                            if (isActive) custom.accent else custom.inkBorder,
                            RoundedCornerShape(10.dp),
                        )
                        .clickable { onSelect(opt) }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
    }
}
