package com.ai.agent.rules

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.ai.agent.storage.AgentDatabase

/**
 * Day change trigger (v6.1.0) — fires at midnight every day.
 * Scheduled by AlarmManager in RuleScheduler.
 * triggerType: "day_changed"
 */
class DayChangeReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "DayChangeReceiver"
        const val ACTION_DAY_CHANGED = "com.ai.agent.DAY_CHANGED"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DAY_CHANGED &&
            intent.action != Intent.ACTION_DATE_CHANGED) return
        Log.i(TAG, "Day changed — triggering rules")
        triggerRules(context)
        // Reschedule for next midnight
        scheduleNextDay(context)
    }

    private fun triggerRules(context: Context) {
        try {
            val db = AgentDatabase(context)
            val rules = db.getRules(enabledOnly = true).filter { it.triggerType == "day_changed" }
            for (rule in rules) {
                RuleTriggerReceiver.triggerRuleAction(context, rule)
                db.logAction("Day change rule fired: ${rule.name}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to trigger rules", e)
        }
    }

    private fun scheduleNextDay(context: Context) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val intent = Intent(context, DayChangeReceiver::class.java).apply {
                action = ACTION_DAY_CHANGED
            }
            val pendingIntent = android.app.PendingIntent.getBroadcast(
                context, 996, intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M)
                    android.app.PendingIntent.FLAG_IMMUTABLE else 0
            )
            val cal = java.util.Calendar.getInstance().apply {
                add(java.util.Calendar.DAY_OF_YEAR, 1)
                set(java.util.Calendar.HOUR_OF_DAY, 0)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, cal.timeInMillis, pendingIntent)
            } else {
                alarmManager.setExact(android.app.AlarmManager.RTC_WAKEUP, cal.timeInMillis, pendingIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule next day change", e)
        }
    }
}
