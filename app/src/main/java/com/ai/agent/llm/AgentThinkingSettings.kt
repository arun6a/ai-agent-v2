package com.ai.agent.llm

import android.content.Context

/**
 * v3.5.0: AgentThinkingSettings — single registry for ALL brain behavior toggles.
 *
 * Consolidates settings that were previously scattered across:
 *   - AIProvider (contextual prompts, prompt mode, vision mode, browser mode)
 *   - AutoFallbackSettings (fallback keys, fallback models, auto-pick cheapest)
 *   - SettingsSection (alwaysLearn — was a dead switch, never persisted)
 *
 * Also adds 3 new thinking-layer toggles:
 *   - Periodic reflection
 *   - Task planning for complex tasks
 *   - Post-action verification for high-stakes tools
 *
 * ALL toggles are stored in SharedPreferences "ai_agent_provider" so they
 * live next to the rest of the AI config. Every toggle has a safe default.
 *
 * The "Reset to recommended defaults" button in BrainBehaviorPage calls
 * resetToDefaults() to restore all safe values.
 */
object AgentThinkingSettings {

    private const val PREFS = "ai_agent_provider"

    // === THINKING toggles (new in v3.5.0) ===
    private const val KEY_REFLECTION = "thinking_reflection_v350"
    private const val KEY_PLANNING = "thinking_planning_v350"
    private const val KEY_VERIFICATION = "thinking_verification_v350"

    // === LEARNING toggles ===
    private const val KEY_ALWAYS_LEARN = "learning_always_learn_v350"  // v3.5.0: was dead switch, now persisted
    private const val KEY_KNOWLEDGE_AUTO_RECALL = "learning_knowledge_auto_recall_v350"

    // === THINKING ===

    /** Periodic Reflection: brain checks progress every 5 iterations. Default ON. */
    fun isReflectionEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_REFLECTION, true)

    fun setReflectionEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_REFLECTION, enabled).apply()
    }

    /** Task Planning: brain creates a plan for complex tasks (Tier 3 + >50 chars). Default ON. */
    fun isPlanningEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_PLANNING, true)

    fun setPlanningEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_PLANNING, enabled).apply()
    }

    /** Post-Action Verification: readScreen after high-stakes tools. Default ON. */
    fun isVerificationEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_VERIFICATION, true)

    fun setVerificationEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_VERIFICATION, enabled).apply()
    }

    // === LEARNING ===

    /**
     * Always Learn: save successful workflows + failure lessons to memory.
     * v3.5.0: FIX — was a dead switch (mutableStateOf(true) with no persistence
     * and ChatViewModel never read it). Now persisted + ChatViewModel checks it.
     * Default ON.
     */
    fun isAlwaysLearnEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ALWAYS_LEARN, true)

    fun setAlwaysLearnEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ALWAYS_LEARN, enabled).apply()
    }

    /**
     * Knowledge Auto-Recall: proactively search saved docs when user asks a question.
     * Default ON. Can be turned OFF to save tokens or for manual control.
     */
    fun isKnowledgeAutoRecallEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_KNOWLEDGE_AUTO_RECALL, true)

    fun setKnowledgeAutoRecallEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_KNOWLEDGE_AUTO_RECALL, enabled).apply()
    }

    // === RESET ===

    /**
     * Reset all thinking/learning toggles to recommended defaults.
     * Does NOT touch routing/failover toggles (those are in AutoFallbackSettings)
     * or prompt/vision/browser selectors (those are in AIProvider).
     * Only resets the toggles managed by this class.
     */
    fun resetToDefaults(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_REFLECTION, true)
            .putBoolean(KEY_PLANNING, true)
            .putBoolean(KEY_VERIFICATION, true)
            .putBoolean(KEY_ALWAYS_LEARN, true)
            .putBoolean(KEY_KNOWLEDGE_AUTO_RECALL, true)
            .apply()
    }
}
