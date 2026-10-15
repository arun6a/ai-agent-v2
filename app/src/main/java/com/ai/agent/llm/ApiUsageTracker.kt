package com.ai.agent.llm

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * Tracks API usage: calls, tokens, cost per session and per task.
 * Stored in SharedPreferences for persistence across app restarts.
 */
object ApiUsageTracker {
    private const val PREFS = "ai_agent_usage"
    private const val KEY_TOTAL_CALLS = "total_calls"
    private const val KEY_TOTAL_PROMPT_TOKENS = "total_prompt_tokens"
    private const val KEY_TOTAL_COMPLETION_TOKENS = "total_completion_tokens"
    private const val KEY_DAY = "day"
    private const val KEY_TASK_CALLS = "task_calls"
    private const val KEY_TASK_TOKENS = "task_tokens"
    // v3.3.11: cost tracking (in micro-dollars to avoid float precision issues).
    // 1 micro-dollar = $0.000001. So $1.50 = 1,500,000 micro-dollars.
    private const val KEY_TOTAL_COST_MICRO = "total_cost_micro_v3311"
    private const val TAG = "ApiUsageTracker"

    // Session stats (resets when app process dies)
    private var sessionCalls = 0
    private var sessionPromptTokens = 0
    private var sessionCompletionTokens = 0
    private var sessionCostMicro = 0L  // v3.3.11

    // Task stats (resets when a new user message starts)
    private var taskCalls = 0
    private var taskPromptTokens = 0
    private var taskCompletionTokens = 0
    private var taskCostMicro = 0L  // v3.3.11
    private var taskStartTime = 0L

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private fun today(): String {
        return java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date())
    }

    /**
     * Reset task counter when a new user message starts.
     */
    fun startNewTask() {
        taskCalls = 0
        taskPromptTokens = 0
        taskCompletionTokens = 0
        taskCostMicro = 0L
        taskStartTime = System.currentTimeMillis()
    }

    /**
     * Record an API call with token usage.
     * Called by LLMClient after each successful request.
     *
     * v3.3.11: also calculates + tracks cost based on the model's pricing.
     * SavedModelsStore is queried for the model's inputPrice + outputPrice
     * (per 1M tokens). If the model isn't found or is free, cost = 0.
     */
    fun recordCall(context: Context, promptTokens: Int, completionTokens: Int, provider: String) {
        val totalTokens = promptTokens + completionTokens

        // v3.3.11: calculate cost for this call.
        // Look up the current model's pricing from SavedModelsStore.
        val costMicro = calculateCostMicro(context, promptTokens, completionTokens)

        // Session stats
        sessionCalls++
        sessionPromptTokens += promptTokens
        sessionCompletionTokens += completionTokens
        sessionCostMicro += costMicro

        // Task stats
        taskCalls++
        taskPromptTokens += promptTokens
        taskCompletionTokens += completionTokens
        taskCostMicro += costMicro

        // Daily stats (reset at UTC midnight)
        val prefs = getPrefs(context)
        val savedDay = prefs.getString(KEY_DAY, "") ?: ""
        val currentDay = today()

        val editor = prefs.edit()
        if (savedDay != currentDay) {
            // New day — reset daily counters
            editor.putString(KEY_DAY, currentDay)
            editor.putInt(KEY_TOTAL_CALLS, 1)
            editor.putInt(KEY_TOTAL_PROMPT_TOKENS, promptTokens)
            editor.putInt(KEY_TOTAL_COMPLETION_TOKENS, completionTokens)
            editor.putLong(KEY_TOTAL_COST_MICRO, costMicro)  // v3.3.11
        } else {
            editor.putInt(KEY_TOTAL_CALLS, prefs.getInt(KEY_TOTAL_CALLS, 0) + 1)
            editor.putInt(KEY_TOTAL_PROMPT_TOKENS, prefs.getInt(KEY_TOTAL_PROMPT_TOKENS, 0) + promptTokens)
            editor.putInt(KEY_TOTAL_COMPLETION_TOKENS, prefs.getInt(KEY_TOTAL_COMPLETION_TOKENS, 0) + completionTokens)
            editor.putLong(KEY_TOTAL_COST_MICRO, prefs.getLong(KEY_TOTAL_COST_MICRO, 0L) + costMicro)  // v3.3.11
        }
        editor.apply()

        Log.i(TAG, "API call #$taskCalls (task) / $sessionCalls (session) / ${prefs.getInt(KEY_TOTAL_CALLS, 0)} (today) | " +
                "tokens: +$totalTokens (prompt=$promptTokens, completion=$completionTokens) | " +
                "provider: $provider | cost: ${formatCost(costMicro)}")
    }

    /**
     * v3.3.11: Calculate the cost of a single API call in micro-dollars.
     *
     * Pricing is per 1M tokens. inputPrice is for prompt tokens, outputPrice
     * is for completion tokens. Returns 0 if the model is free or not found.
     *
     * Example: GPT-4o at $2.50/$10.00 per 1M
     *   1000 prompt + 500 completion tokens:
     *   = (1000/1M * $2.50) + (500/1M * $10.00)
     *   = $0.0025 + $0.005
     *   = $0.0075
     *   = 7500 micro-dollars
     */
    private fun calculateCostMicro(context: Context, promptTokens: Int, completionTokens: Int): Long {
        try {
            val modelId = AIProvider.getModel(context)
            SavedModelsStore.load(context)
            // Find the saved model by modelId.
            val model = SavedModelsStore.getAllModels().find { it.modelId == modelId }
                ?: return 0L
            val inputPricePerM = model.inputPrice  // $ per 1M prompt tokens
            val outputPricePerM = model.outputPrice  // $ per 1M completion tokens
            if (inputPricePerM == 0.0 && outputPricePerM == 0.0) return 0L  // free model
            // cost = (promptTokens / 1M * inputPrice) + (completionTokens / 1M * outputPrice)
            // In micro-dollars: (promptTokens * inputPrice + completionTokens * outputPrice) * 1_000_000 / 1_000_000
            // Simplified: promptTokens * inputPrice + completionTokens * outputPrice (in micro-dollars)
            val costMicro = (promptTokens.toDouble() * inputPricePerM + completionTokens.toDouble() * outputPricePerM).toLong()
            return costMicro
        } catch (e: Exception) {
            Log.w(TAG, "Cost calculation failed: ${e.message}")
            return 0L
        }
    }

    /**
     * v3.3.11: Format micro-dollars as a human-readable $ string.
     * 7500 micro → "$0.0075"
     * 1500000 micro → "$1.50"
     */
    fun formatCost(micro: Long): String {
        val dollars = micro / 1_000_000.0
        return when {
            dollars == 0.0 -> "$0.00"
            dollars < 0.01 -> "<$0.01"
            dollars < 1.0 -> String.format("$%.4f", dollars)
            else -> String.format("$%.2f", dollars)
        }
    }

    /**
     * v3.3.11: Get today's total cost in micro-dollars.
     */
    fun getTodayCostMicro(context: Context): Long {
        return getPrefs(context).getLong(KEY_TOTAL_COST_MICRO, 0L)
    }

    /**
     * v3.3.11: Get session total cost in micro-dollars.
     */
    fun getSessionCostMicro(): Long = sessionCostMicro

    /**
     * v3.3.11: Get task total cost in micro-dollars.
     */
    fun getTaskCostMicro(): Long = taskCostMicro

    /**
     * Get task stats (current task only).
     */
    fun getTaskStats(): String {
        val elapsed = (System.currentTimeMillis() - taskStartTime) / 1000
        val totalTokens = taskPromptTokens + taskCompletionTokens
        return "Calls: $taskCalls | Tokens: $totalTokens (${taskPromptTokens}p+${taskCompletionTokens}c) | ${elapsed}s"
    }

    /**
     * Get task calls count (for display in chat).
     */
    fun getTaskCalls(): Int = taskCalls

    /**
     * Get session stats (since app started).
     */
    fun getSessionStats(): String {
        val totalTokens = sessionPromptTokens + sessionCompletionTokens
        return "Session: $sessionCalls calls, $totalTokens tokens"
    }

    /**
     * Get daily stats (resets at midnight).
     */
    fun getDailyStats(context: Context): String {
        val prefs = getPrefs(context)
        val calls = prefs.getInt(KEY_TOTAL_CALLS, 0)
        val prompt = prefs.getInt(KEY_TOTAL_PROMPT_TOKENS, 0)
        val completion = prefs.getInt(KEY_TOTAL_COMPLETION_TOKENS, 0)
        val total = prompt + completion
        return "Today: $calls calls, $total tokens"
    }

    /**
     * Get today's stats as Pair(calls, totalTokens) — for AgentFragment UI.
     * v3.3.11: now also returns cost via getTodayCostMicro().
     */
    fun getTodayStats(context: Context): Pair<Int, Int> {
        val prefs = getPrefs(context)
        val calls = prefs.getInt(KEY_TOTAL_CALLS, 0)
        val prompt = prefs.getInt(KEY_TOTAL_PROMPT_TOKENS, 0)
        val completion = prefs.getInt(KEY_TOTAL_COMPLETION_TOKENS, 0)
        return Pair(calls, prompt + completion)
    }

    /**
     * Get full report for Settings.
     * v3.3.11: includes cost ($).
     */
    fun getFullReport(context: Context): String {
        val prefs = getPrefs(context)
        val dailyCalls = prefs.getInt(KEY_TOTAL_CALLS, 0)
        val dailyPrompt = prefs.getInt(KEY_TOTAL_PROMPT_TOKENS, 0)
        val dailyCompletion = prefs.getInt(KEY_TOTAL_COMPLETION_TOKENS, 0)
        val dailyTotal = dailyPrompt + dailyCompletion
        val dailyCostMicro = prefs.getLong(KEY_TOTAL_COST_MICRO, 0L)
        val sessionTotal = sessionPromptTokens + sessionCompletionTokens

        return buildString {
            append("=== API Usage Report ===\n\n")
            append("📊 Current Task:\n")
            append("  Calls: $taskCalls\n")
            append("  Tokens: ${taskPromptTokens + taskCompletionTokens}\n")
            append("  Cost: ${formatCost(taskCostMicro)}\n\n")
            append("📱 This Session:\n")
            append("  Calls: $sessionCalls\n")
            append("  Tokens: $sessionTotal\n")
            append("  Cost: ${formatCost(sessionCostMicro)}\n\n")
            append("📅 Today (${today()}):\n")
            append("  Calls: $dailyCalls\n")
            append("  Tokens: $dailyTotal\n")
            append("  Cost: ${formatCost(dailyCostMicro)}\n")
            if (dailyCalls > 0) {
                val avgTokens = dailyTotal / dailyCalls
                append("  Avg tokens/call: $avgTokens\n")
            }
        }
    }

    /**
     * Reset all stats (for debugging/testing).
     */
    fun resetAll(context: Context) {
        getPrefs(context).edit().clear().apply()
        sessionCalls = 0
        sessionPromptTokens = 0
        sessionCompletionTokens = 0
        sessionCostMicro = 0L
        taskCalls = 0
        taskPromptTokens = 0
        taskCompletionTokens = 0
        taskCostMicro = 0L
        Log.i(TAG, "All usage stats reset")
    }
}
