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
    private const val TAG = "ApiUsageTracker"

    // Session stats (resets when app process dies)
    private var sessionCalls = 0
    private var sessionPromptTokens = 0
    private var sessionCompletionTokens = 0

    // Task stats (resets when a new user message starts)
    private var taskCalls = 0
    private var taskPromptTokens = 0
    private var taskCompletionTokens = 0
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
        taskStartTime = System.currentTimeMillis()
    }

    /**
     * Record an API call with token usage.
     * Called by LLMClient after each successful request.
     */
    fun recordCall(context: Context, promptTokens: Int, completionTokens: Int, provider: String) {
        val totalTokens = promptTokens + completionTokens

        // Session stats
        sessionCalls++
        sessionPromptTokens += promptTokens
        sessionCompletionTokens += completionTokens

        // Task stats
        taskCalls++
        taskPromptTokens += promptTokens
        taskCompletionTokens += completionTokens

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
        } else {
            editor.putInt(KEY_TOTAL_CALLS, prefs.getInt(KEY_TOTAL_CALLS, 0) + 1)
            editor.putInt(KEY_TOTAL_PROMPT_TOKENS, prefs.getInt(KEY_TOTAL_PROMPT_TOKENS, 0) + promptTokens)
            editor.putInt(KEY_TOTAL_COMPLETION_TOKENS, prefs.getInt(KEY_TOTAL_COMPLETION_TOKENS, 0) + completionTokens)
        }
        editor.apply()

        Log.i(TAG, "API call #$taskCalls (task) / $sessionCalls (session) / ${prefs.getInt(KEY_TOTAL_CALLS, 0)} (today) | " +
                "tokens: +$totalTokens (prompt=$promptTokens, completion=$completionTokens) | " +
                "provider: $provider")
    }

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
     */
    fun getFullReport(context: Context): String {
        val prefs = getPrefs(context)
        val dailyCalls = prefs.getInt(KEY_TOTAL_CALLS, 0)
        val dailyPrompt = prefs.getInt(KEY_TOTAL_PROMPT_TOKENS, 0)
        val dailyCompletion = prefs.getInt(KEY_TOTAL_COMPLETION_TOKENS, 0)
        val dailyTotal = dailyPrompt + dailyCompletion
        val sessionTotal = sessionPromptTokens + sessionCompletionTokens

        return buildString {
            append("=== API Usage Report ===\n\n")
            append("📊 Current Task:\n")
            append("  Calls: $taskCalls\n")
            append("  Tokens: ${taskPromptTokens + taskCompletionTokens}\n")
            append("  (prompt: $taskPromptTokens, completion: $taskCompletionTokens)\n\n")
            append("📱 This Session:\n")
            append("  Calls: $sessionCalls\n")
            append("  Tokens: $sessionTotal\n\n")
            append("📅 Today (${today()}):\n")
            append("  Calls: $dailyCalls\n")
            append("  Tokens: $dailyTotal\n")
            append("  (prompt: $dailyPrompt, completion: $dailyCompletion)\n\n")
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
        taskCalls = 0
        taskPromptTokens = 0
        taskCompletionTokens = 0
        Log.i(TAG, "All usage stats reset")
    }
}
