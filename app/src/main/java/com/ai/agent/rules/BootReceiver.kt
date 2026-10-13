package com.ai.agent.rules

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Receives ACTION_BOOT_COMPLETED to reschedule all time-based rules after a phone restart.
 *
 * AlarmManager alarms are cleared on device reboot — without this receiver, time rules
 * would not fire until the user manually opens the app (which would trigger
 * MainActivity.startRuleEngine → RuleScheduler.scheduleAllTimeRules).
 *
 * This receiver runs in the background after boot, looks up enabled time rules from the DB,
 * and reschedules each one with AlarmManager.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
                Log.i(TAG, "Boot completed — restarting AgentService + rescheduling rules")
                try {
                    // v3.0.0: Restart AgentService (registers all triggers + schedules time rules)
                    val serviceIntent = Intent(context, com.ai.agent.service.AgentService::class.java).apply {
                        action = com.ai.agent.service.AgentService.ACTION_START
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                    Log.i(TAG, "AgentService restarted after boot")

                    // Also reschedule time rules (belt + suspenders)
                    val scheduler = RuleScheduler(context)
                    scheduler.scheduleAllTimeRules()
                    Log.i(TAG, "Rescheduled all time rules successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to restart after boot", e)
                }
            }
        }
    }
}
