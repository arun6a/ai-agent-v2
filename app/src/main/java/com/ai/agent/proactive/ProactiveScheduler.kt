package com.ai.agent.proactive

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * v4.0.77: ProactiveScheduler — runs ProactiveTickWorker periodically.
 *
 * Uses AlarmManager (not WorkManager) to avoid adding a dependency.
 * Schedules a repeating alarm every N minutes (default 5).
 * The alarm fires ProactiveAlarmReceiver → runs ProactiveTickWorker.tick().
 *
 * When proactive is disabled, the alarm is cancelled.
 * When the user changes the tick interval, the alarm is rescheduled.
 *
 * Token cost: ~800 tokens per tick × 288 ticks/day = ~230K tokens/day.
 * This fits within Gemini free tier (1500 calls/day).
 */
object ProactiveScheduler {
    private const val TAG = "ProactiveScheduler"
    private const val REQUEST_CODE = 7700  // unique
    private const val ACTION_PROACTIVE_TICK = "com.ai.agent.PROACTIVE_TICK"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Start or restart the proactive tick alarm.
     * Call this when:
     * - ProactiveSettings.isEnabled changes to true
     * - Tick interval changes
     * - App starts (BootReceiver)
     */
    fun schedule(context: Context) {
        if (!ProactiveSettings.isEnabled(context)) {
            cancel(context)
            return
        }

        val intervalMin = ProactiveSettings.getTickIntervalMin(context)
        val intervalMs = intervalMin * 60 * 1000L

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            ?: return

        val intent = Intent(context, ProactiveAlarmReceiver::class.java).apply {
            action = ACTION_PROACTIVE_TICK
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Use setInexactRepeating to save battery (Android batches alarms)
        alarmManager.setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + intervalMs,
            intervalMs,
            pendingIntent
        )
        Log.i(TAG, "Proactive tick scheduled every $intervalMin minutes")
    }

    /**
     * Cancel the proactive tick alarm.
     * Call when proactive is disabled.
     */
    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            ?: return
        val intent = Intent(context, ProactiveAlarmReceiver::class.java).apply {
            action = ACTION_PROACTIVE_TICK
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
        Log.i(TAG, "Proactive tick cancelled")
    }
}

/**
 * BroadcastReceiver that fires when the proactive tick alarm goes off.
 * Runs ProactiveTickWorker.tick() in a coroutine.
 */
class ProactiveAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "com.ai.agent.PROACTIVE_TICK") return
        Log.i("ProactiveAlarm", "Tick received — running ProactiveTickWorker")

        // Run the tick in a background coroutine (BroadcastReceiver must return quickly)
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = ProactiveTickWorker.tick(context)
                when (result) {
                    is ProactiveResult.Suggest -> {
                        // v4.0.87: Show a notification (app in background) AND publish to
                        // ProactiveBus so the open chat can show an inline banner.
                        showSuggestionNotification(context, result.action, result.reason)
                        ProactiveBus.publish(result)
                    }
                    is ProactiveResult.Execute -> {
                        // Auto-execute (if enabled) — would call AgentService here
                        Log.i("ProactiveAlarm", "Auto-execute: ${result.action}")
                        showSuggestionNotification(context, result.action, "Auto-executed: ${result.reason}")
                        // v4.0.87: Also surface as an inline banner (downgraded to suggestion).
                        ProactiveBus.publish(
                            ProactiveResult.Suggest(result.action, "Auto-executed: ${result.reason}")
                        )
                    }
                    is ProactiveResult.Silent -> {
                        Log.i("ProactiveAlarm", "Silent: ${result.reason}")
                    }
                }
            } catch (e: Exception) {
                Log.e("ProactiveAlarm", "Tick failed", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun showSuggestionNotification(context: Context, action: String, reason: String) {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE)
                as android.app.NotificationManager

            // Create notification channel (Android 8+)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val channel = android.app.NotificationChannel(
                    "aira_proactive",
                    "Aira Proactive Suggestions",
                    android.app.NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Suggestions from Aira based on your patterns"
                }
                notificationManager.createNotificationChannel(channel)
            }

            val notification = androidx.core.app.NotificationCompat.Builder(context, "aira_proactive")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Aira suggests: $action")
                .setContentText(reason)
                .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(reason))
                .setAutoCancel(true)
                .build()

            notificationManager.notify(System.currentTimeMillis().toInt(), notification)
        } catch (e: Exception) {
            Log.e("ProactiveAlarm", "Failed to show notification", e)
        }
    }
}
