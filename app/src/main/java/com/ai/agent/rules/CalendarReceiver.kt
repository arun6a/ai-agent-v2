package com.ai.agent.rules

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.CalendarContract
import android.util.Log
import com.ai.agent.storage.AgentDatabase
import java.util.Calendar

/**
 * Calendar event trigger (v6.1.0) — checks calendar for upcoming events.
 * Fires "calendar_event_starting" 5 minutes before event start.
 * Uses AlarmManager to schedule checks (not a persistent receiver).
 */
class CalendarReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "CalendarReceiver"
        const val ACTION_CALENDAR_CHECK = "com.ai.agent.CALENDAR_CHECK"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CALENDAR_CHECK) return
        Log.i(TAG, "Calendar check triggered")
        checkUpcomingEvents(context)
    }

    private fun checkUpcomingEvents(context: Context) {
        try {
            val now = System.currentTimeMillis()
            val fiveMinFromNow = now + (5 * 60 * 1000)
            val tenMinFromNow = now + (10 * 60 * 1000)

            val projection = arrayOf(
                CalendarContract.Events.TITLE,
                CalendarContract.Events.DTSTART,
                CalendarContract.Events.EVENT_LOCATION
            )
            val selection = "${CalendarContract.Events.DTSTART} BETWEEN ? AND ?"
            val selectionArgs = arrayOf(fiveMinFromNow.toString(), tenMinFromNow.toString())

            val cursor = context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${CalendarContract.Events.DTSTART} ASC"
            )

            cursor?.use {
                while (it.moveToNext()) {
                    val title = it.getString(0) ?: "Unknown"
                    val startTime = it.getLong(1)
                    val location = it.getString(2) ?: ""
                    Log.i(TAG, "Upcoming event: $title at $location (starts in 5 min)")
                    triggerCalendarRule(context, title, location, startTime)
                }
            }

            // Schedule next check (in 1 minute)
            scheduleNextCheck(context)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check calendar", e)
        }
    }

    private fun triggerCalendarRule(context: Context, eventTitle: String, location: String, startTime: Long) {
        try {
            val db = AgentDatabase(context)
            val rules = db.getRules(enabledOnly = true).filter { it.triggerType == "calendar_event_starting" }
            for (rule in rules) {
                // Pass event details as the rule context
                val eventInfo = "Event: $eventTitle\nLocation: $location\nStarts in 5 minutes"
                RuleTriggerReceiver.triggerRuleAction(context, rule, eventInfo)
                db.logAction("Calendar rule fired: ${rule.name} (event: $eventTitle)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to trigger calendar rules", e)
        }
    }

    private fun scheduleNextCheck(context: Context) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, CalendarReceiver::class.java).apply {
                action = ACTION_CALENDAR_CHECK
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context, 999, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
            )
            val nextCheck = System.currentTimeMillis() + (60 * 1000) // 1 minute
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextCheck, pendingIntent)
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, nextCheck, pendingIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule next calendar check", e)
        }
    }
}
