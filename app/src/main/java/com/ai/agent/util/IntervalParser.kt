package com.ai.agent.util

/**
 * v4.0.98: Pure-logic interval parser — extracted from RuleTools for unit testing.
 *
 * Parses natural-language interval strings like:
 *   "every 2 hours" → Interval(2, HOURS)
 *   "30 minutes"    → Interval(30, MINUTES)
 *   "2h"            → Interval(2, HOURS)
 *   "every 30m"     → Interval(30, MINUTES)
 *
 * Returns null if the string can't be parsed.
 */
object IntervalParser {

    enum class Unit { HOURS, MINUTES }

    data class Interval(val value: Int, val unit: Unit) {
        /** Normalized string format: "every 2h" or "every 30m" */
        fun normalized(): String = "every ${value}${if (unit == Unit.HOURS) "h" else "m"}"

        /** Interval in milliseconds (for AlarmManager.setRepeating) */
        fun toMillis(): Long = if (unit == Unit.HOURS) value * 3600_000L else value * 60_000L
    }

    private val regex = Regex(
        "(?:every\\s+)?(\\d+)\\s*(hour|hr|hours|minute|min|mins|minutes|h|m)",
        RegexOption.IGNORE_CASE
    )

    /**
     * Parse an interval string. Returns null if invalid.
     */
    fun parse(input: String): Interval? {
        val match = regex.find(input) ?: return null
        val n = match.groupValues[1].toIntOrNull() ?: return null
        if (n <= 0) return null
        val unitStr = match.groupValues[2].lowercase()
        val unit = if (unitStr.startsWith("h")) Unit.HOURS else Unit.MINUTES
        return Interval(n, unit)
    }

    /**
     * Parse + normalize to "every Nh" or "every Nm" format.
     * Returns null if invalid.
     */
    fun normalize(input: String): String? {
        return parse(input)?.normalized()
    }
}
