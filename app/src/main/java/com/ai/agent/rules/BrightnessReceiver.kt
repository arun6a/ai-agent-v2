package com.ai.agent.rules

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.ai.agent.storage.AgentDatabase

/**
 * Brightness trigger (v6.1.0) — fires when screen brightness changes significantly.
 * triggerType: "brightness_high" (above 80%) or "brightness_low" (below 20%)
 */
class BrightnessReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BrightnessReceiver"
        const val ACTION_BRIGHTNESS_CHECK = "com.ai.agent.BRIGHTNESS_CHECK"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_BRIGHTNESS_CHECK) return
        checkBrightness(context)
        scheduleNextCheck(context)
    }

    private fun checkBrightness(context: Context) {
        try {
            val brightness = android.provider.Settings.System.getInt(
                context.contentResolver,
                android.provider.Settings.System.SCREEN_BRIGHTNESS,
                128
            )
            val percent = (brightness * 100) / 255

            val db = AgentDatabase(context)
            if (percent > 80) {
                val rules = db.getRules(enabledOnly = true).filter { it.triggerType == "brightness_high" }
                for (rule in rules) {
                    RuleTriggerReceiver.triggerRuleAction(context, rule, "Brightness: $percent%")
                    db.logAction("Brightness high rule fired: ${rule.name}")
                }
            } else if (percent < 20) {
                val rules = db.getRules(enabledOnly = true).filter { it.triggerType == "brightness_low" }
                for (rule in rules) {
                    RuleTriggerReceiver.triggerRuleAction(context, rule, "Brightness: $percent%")
                    db.logAction("Brightness low rule fired: ${rule.name}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check brightness", e)
        }
    }

    private fun scheduleNextCheck(context: Context) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val intent = Intent(context, BrightnessReceiver::class.java).apply {
                action = ACTION_BRIGHTNESS_CHECK
            }
            val pendingIntent = android.app.PendingIntent.getBroadcast(
                context, 997, intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M)
                    android.app.PendingIntent.FLAG_IMMUTABLE else 0
            )
            val nextCheck = System.currentTimeMillis() + (2 * 60 * 1000) // 2 minutes
            alarmManager.set(android.app.AlarmManager.RTC_WAKEUP, nextCheck, pendingIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule next brightness check", e)
        }
    }
}
