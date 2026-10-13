package com.ai.agent.rules

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.ai.agent.storage.AgentDatabase
import java.util.Calendar

/**
 * Schedules time-based rules using AlarmManager.
 *
 * Why AlarmManager (not Handler polling):
 * - AlarmManager fires even if the app is killed (Handler doesn't)
 * - setExactAndAllowWhileIdle fires in Doze mode (Android 6+)
 * - Battery-efficient — no constant polling
 *
 * Each rule gets a unique requestCode = rule.id.toInt() so we can cancel individually.
 */
class RuleScheduler(private val context: Context) {

    companion object {
        private const val TAG = "RuleScheduler"
        const val ACTION_RULE_TRIGGER = "com.ai.agent.RULE_TRIGGER_ALARM"
        const val EXTRA_RULE_ID = "rule_id"
    }

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val db = AgentDatabase(context)

    /**
     * Schedule all enabled time-based rules. Call this on app start + on boot.
     */
    fun scheduleAllTimeRules() {
        val timeRules = db.getRules(enabledOnly = true).filter { it.triggerType == "time" }
        Log.i(TAG, "Scheduling ${timeRules.size} time rules")
        for (rule in timeRules) {
            scheduleTimeRule(rule)
        }
    }

    /**
     * Schedule a single time rule for its next occurrence.
     * If days is null/empty → fires daily
     * If days = "mon,tue,wed,thu,fri" → fires only on those days
     */
    fun scheduleTimeRule(rule: AgentDatabase.Rule) {
        if (rule.triggerType != "time") return
        val timeParts = rule.triggerValue.split(":")
        if (timeParts.size != 2) {
            Log.e(TAG, "Invalid time format: ${rule.triggerValue} (expected HH:MM)")
            return
        }
        val hour = timeParts[0].toIntOrNull() ?: run {
            Log.e(TAG, "Invalid hour: ${timeParts[0]}")
            return
        }
        val minute = timeParts[1].toIntOrNull() ?: run {
            Log.e(TAG, "Invalid minute: ${timeParts[1]}")
            return
        }

        val nextFire = getNextFireTime(hour, minute, rule.days)
        if (nextFire == null) {
            Log.e(TAG, "No valid next fire time for rule ${rule.name} (days=${rule.days})")
            return
        }

        val pendingIntent = createPendingIntent(rule.id)
        // Cancel any existing alarm for this rule first (in case it was rescheduled)
        alarmManager.cancel(pendingIntent)

        // v2.5.6: Android 14 (SDK 34) restricts setExactAndAllowWhileIdle() — requires user to
        // manually grant "Alarms & reminders" permission in Settings → Apps → Aira.
        // If not granted, the call silently fails → rule never fires.
        // FIX: Check canScheduleExactAlarms() first. If not allowed, fall back to set()
        // (less precise but works without special permission).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (alarmManager.canScheduleExactAlarms()) {
                // Exact alarm allowed — use setExactAndAllowWhileIdle (fires in Doze mode)
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    nextFire,
                    pendingIntent
                )
                Log.i(TAG, "Scheduled with setExactAndAllowWhileIdle (exact alarm allowed)")
            } else {
                // Exact alarm NOT allowed — fall back to set() (works without permission)
                // Less precise (can be delayed by Doze mode) but fires reliably
                alarmManager.set(AlarmManager.RTC_WAKEUP, nextFire, pendingIntent)
                Log.w(TAG, "Exact alarm NOT allowed — using set() (less precise). " +
                    "Grant permission: Settings → Apps → Aira → Alarms & reminders")
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                nextFire,
                pendingIntent
            )
        } else {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, nextFire, pendingIntent)
        }

        val nextFireStr = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(nextFire))
        Log.i(TAG, "Scheduled rule ${rule.name} (id=${rule.id}) for $nextFireStr")
    }

    /**
     * Cancel a scheduled rule.
     */
    fun cancelRule(ruleId: Long) {
        alarmManager.cancel(createPendingIntent(ruleId))
        Log.i(TAG, "Cancelled rule id=$ruleId")
    }

    /**
     * Reschedule all time rules (call after adding/modifying/deleting a rule).
     */
    fun rescheduleAll() {
        // Cancel all existing alarms for our rules
        val rules = db.getRules(enabledOnly = false)
        for (rule in rules) {
            if (rule.triggerType == "time") {
                alarmManager.cancel(createPendingIntent(rule.id))
            }
        }
        // Reschedule enabled time rules
        scheduleAllTimeRules()
    }

    /**
     * Calculate the next time this rule should fire.
     * If days is null/empty → fires tomorrow at HH:MM (or today if not yet passed)
     * If days = "mon,tue,..." → fires next matching day at HH:MM
     */
    private fun getNextFireTime(hour: Int, minute: Int, days: String?): Long? {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val allowedDays = parseDays(days)  // null = all days
        if (allowedDays != null && allowedDays.isEmpty()) return null

        // Try today first
        if (allowedDays == null || allowedDays.contains(target.get(Calendar.DAY_OF_WEEK))) {
            if (target.timeInMillis > now.timeInMillis) {
                return target.timeInMillis
            }
        }

        // Try next 7 days
        for (i in 1..7) {
            target.add(Calendar.DAY_OF_MONTH, 1)
            if (allowedDays == null || allowedDays.contains(target.get(Calendar.DAY_OF_WEEK))) {
                return target.timeInMillis
            }
        }
        return null
    }

    /**
     * Parse "mon,tue,wed,thu,fri,sat,sun" into list of Calendar day constants.
     * Returns null if input is null/empty (means "every day").
     */
    private fun parseDays(days: String?): List<Int>? {
        if (days.isNullOrBlank()) return null
        val dayMap = mapOf(
            "sun" to Calendar.SUNDAY,
            "mon" to Calendar.MONDAY,
            "tue" to Calendar.TUESDAY,
            "wed" to Calendar.WEDNESDAY,
            "thu" to Calendar.THURSDAY,
            "fri" to Calendar.FRIDAY,
            "sat" to Calendar.SATURDAY
        )
        return days.split(",").mapNotNull { d ->
            dayMap[d.trim().lowercase()]
        }
    }

    private fun createPendingIntent(ruleId: Long): PendingIntent {
        val intent = Intent(context, RuleTriggerReceiver::class.java).apply {
            action = ACTION_RULE_TRIGGER
            putExtra(EXTRA_RULE_ID, ruleId)
        }
        // FLAG_IMMUTABLE required on Android 12+; FLAG_UPDATE_CURRENT so we can reschedule
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getBroadcast(
            context,
            ruleId.toInt(),  // unique per rule
            intent,
            flags
        )
    }
}
