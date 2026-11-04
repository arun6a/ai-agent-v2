package com.ai.agent.proactive

import android.content.Context
import android.util.Log

/**
 * v4.0.76: ProactiveSettings — user configuration for proactive behavior.
 *
 * Stores:
 * - Master toggle (on/off)
 * - Quiet hours (no suggestions during sleep)
 * - Auto-execute (opt-in, default OFF for safety)
 * - Cooldown (min time between suggestions)
 * - Max dismissals before permanent silence
 * - Tick interval (how often to run the proactive LLM call)
 * - Personality (freeform prompt for proactive behavior)
 *
 * The proactive system observes user patterns + suggests actions.
 * When enabled, ProactiveTickWorker runs every N minutes, makes a small
 * LLM call (~800 tokens at Level 1), and suggests/acts based on context.
 *
 * Token cost: 288 calls/day × 800 tokens = ~230K tokens/day (fits free tier).
 * Without capability levels: 288 × 10K = 2.88M/day (DOESN'T FIT).
 */
object ProactiveSettings {
    private const val TAG = "ProactiveSettings"
    private const val PREFS = "aira_proactive"
    private const val KEY_ENABLED = "proactive_enabled"
    private const val KEY_QUIET_START = "quiet_start"
    private const val KEY_QUIET_END = "quiet_end"
    private const val KEY_AUTO_EXECUTE = "auto_execute"
    private const val KEY_COOLDOWN_MS = "cooldown_ms"
    private const val KEY_MAX_DISMISSALS = "max_dismissals"
    private const val KEY_TICK_INTERVAL_MIN = "tick_interval_min"
    private const val KEY_PERSONALITY = "personality"

    const val DEFAULT_ENABLED = false
    const val DEFAULT_QUIET_START = "22:00"
    const val DEFAULT_QUIET_END = "07:00"
    const val DEFAULT_AUTO_EXECUTE = false
    const val DEFAULT_COOLDOWN_MS = 3600000L  // 1 hour
    const val DEFAULT_MAX_DISMISSALS = 3
    const val DEFAULT_TICK_INTERVAL_MIN = 5
    const val DEFAULT_PERSONALITY = "You are Aira, a helpful phone assistant. Suggest actions that would be useful to the user based on context (time, battery, location, recent activity). Be brief and relevant. Don't suggest trivial things."

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, DEFAULT_ENABLED)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, enabled).apply()
        Log.i(TAG, "Proactive behavior: $enabled")
    }

    fun getQuietStart(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_QUIET_START, DEFAULT_QUIET_START) ?: DEFAULT_QUIET_START

    fun getQuietEnd(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_QUIET_END, DEFAULT_QUIET_END) ?: DEFAULT_QUIET_END

    fun setQuietHours(context: Context, start: String, end: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_QUIET_START, start).putString(KEY_QUIET_END, end).apply()
    }

    fun isAutoExecuteEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_EXECUTE, DEFAULT_AUTO_EXECUTE)

    fun setAutoExecute(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_AUTO_EXECUTE, enabled).apply()
    }

    fun getCooldownMs(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_COOLDOWN_MS, DEFAULT_COOLDOWN_MS)

    fun setCooldownMs(context: Context, ms: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_COOLDOWN_MS, ms).apply()
    }

    fun getMaxDismissals(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_MAX_DISMISSALS, DEFAULT_MAX_DISMISSALS)

    fun getTickIntervalMin(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_TICK_INTERVAL_MIN, DEFAULT_TICK_INTERVAL_MIN)

    fun setTickIntervalMin(context: Context, min: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_TICK_INTERVAL_MIN, min).apply()
    }

    fun getPersonality(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PERSONALITY, DEFAULT_PERSONALITY) ?: DEFAULT_PERSONALITY

    fun setPersonality(context: Context, personality: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PERSONALITY, personality).apply()
    }

    /**
     * Check if current time is within quiet hours.
     * Returns true if user should NOT be disturbed.
     */
    fun isInQuietHours(context: Context): Boolean {
        val start = getQuietStart(context)
        val end = getQuietEnd(context)
        val now = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
            .format(java.util.Date())
        return if (start <= end) {
            now in start..end
        } else {
            now >= start || now <= end
        }
    }
}
