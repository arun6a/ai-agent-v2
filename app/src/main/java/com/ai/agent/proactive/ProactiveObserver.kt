package com.ai.agent.proactive

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * v4.0.76: ProactiveObserver — records user behavior patterns.
 *
 * Hooks into existing receivers (Battery, Location, AppOpen, Calendar, etc.)
 * and records what the user does + when. The ProactiveTickWorker reads these
 * patterns to suggest relevant actions.
 *
 * Pattern confidence: 5+ occurrences across 3+ days = "established pattern"
 *
 * Examples of what gets recorded:
 * - User opens YouTube at 21:00 every weekday → suggest "play your usual playlist?"
 * - User sends SMS to Mom every Sunday 10:00 → suggest "text Mom?"
 * - Battery drops below 20% → suggest "turn on battery saver?"
 *
 * Privacy: patterns are stored locally only. Never sent to a server.
 */
object ProactiveObserver {
    private const val TAG = "ProactiveObserver"
    private const val PREFS = "aira_proactive_patterns"
    private const val KEY_PATTERNS = "patterns_json"

    data class Pattern(
        val trigger: String,        // e.g. "time:21:00", "battery:<20", "app:youtube"
        val suggestedAction: String, // e.g. "play music", "send sms to Mom"
        val occurrences: Int,        // how many times seen
        val lastSeen: Long,          // timestamp
        val dismissals: Int,         // how many times user dismissed
        val daysActive: Set<String>, // which days of week (1=Mon..7=Sun)
    )

    @Volatile
    private var patterns: MutableList<Pattern> = mutableListOf()
    @Volatile
    private var loaded = false

    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        try {
            val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_PATTERNS, "") ?: ""
            if (json.isNotEmpty()) {
                val arr = JSONArray(json)
                patterns.clear()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    patterns.add(Pattern(
                        trigger = o.optString("trigger"),
                        suggestedAction = o.optString("action"),
                        occurrences = o.optInt("occurrences"),
                        lastSeen = o.optLong("lastSeen"),
                        dismissals = o.optInt("dismissals"),
                        daysActive = o.optJSONArray("days")?.let { days ->
                            (0 until days.length()).map { days.optString(it) }.toSet()
                        } ?: emptySet()
                    ))
                }
            }
            loaded = true
            Log.i(TAG, "Loaded ${patterns.size} patterns")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load patterns", e)
        }
    }

    @Synchronized
    fun save(context: Context) {
        try {
            val arr = JSONArray()
            for (p in patterns) {
                arr.put(JSONObject().apply {
                    put("trigger", p.trigger)
                    put("action", p.suggestedAction)
                    put("occurrences", p.occurrences)
                    put("lastSeen", p.lastSeen)
                    put("dismissals", p.dismissals)
                    put("days", JSONArray(p.daysActive))
                })
            }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_PATTERNS, arr.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save patterns", e)
        }
    }

    /**
     * Record that a trigger fired (e.g. user opened YouTube at 21:00).
     * Creates a new pattern if first time, increments occurrences if existing.
     */
    fun recordTrigger(context: Context, trigger: String, action: String? = null) {
        load(context)
        val dayOfWeek = java.text.SimpleDateFormat("u", java.util.Locale.US)
            .format(java.util.Date())  // 1=Mon..7=Sun
        val now = System.currentTimeMillis()

        val existing = patterns.find { it.trigger == trigger }
        if (existing != null) {
            val idx = patterns.indexOf(existing)
            patterns[idx] = existing.copy(
                occurrences = existing.occurrences + 1,
                lastSeen = now,
                daysActive = existing.daysActive + dayOfWeek
            )
        } else if (action != null) {
            patterns.add(Pattern(
                trigger = trigger,
                suggestedAction = action,
                occurrences = 1,
                lastSeen = now,
                dismissals = 0,
                daysActive = setOf(dayOfWeek)
            ))
        }
        save(context)
    }

    /**
     * Get patterns that are "established" (5+ occurrences across 3+ days).
     * These are the ones worth suggesting to the user.
     */
    fun getEstablishedPatterns(context: Context): List<Pattern> {
        load(context)
        return patterns.filter { it.occurrences >= 5 && it.daysActive.size >= 3 && it.dismissals < 3 }
    }

    /**
     * Get patterns matching a current trigger.
     * Used by ProactiveTickWorker to find suggestions for the current context.
     */
    fun getPatternsForTrigger(context: Context, trigger: String): List<Pattern> {
        load(context)
        return patterns.filter { it.trigger == trigger }
    }

    /**
     * User dismissed a suggestion — increment dismissal count.
     * After 3 dismissals, the pattern is permanently silenced.
     */
    fun recordDismissal(context: Context, trigger: String) {
        load(context)
        val existing = patterns.find { it.trigger == trigger } ?: return
        val idx = patterns.indexOf(existing)
        patterns[idx] = existing.copy(dismissals = existing.dismissals + 1)
        save(context)
        if (existing.dismissals + 1 >= 3) {
            Log.i(TAG, "Pattern '$trigger' silenced after 3 dismissals")
        }
    }

    /**
     * Check if a pattern is in cooldown (suggested recently).
     */
    fun isInCooldown(context: Context, trigger: String): Boolean {
        load(context)
        val pattern = patterns.find { it.trigger == trigger } ?: return false
        val cooldownMs = ProactiveSettings.getCooldownMs(context)
        return System.currentTimeMillis() - pattern.lastSeen < cooldownMs
    }

    fun clearAll(context: Context) {
        patterns.clear()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        Log.i(TAG, "All patterns cleared")
    }
}
