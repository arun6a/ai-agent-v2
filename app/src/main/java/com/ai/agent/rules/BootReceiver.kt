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
                Log.i(TAG, "Boot completed — rescheduling time rules")
                try {
                    val scheduler = RuleScheduler(context)
                    scheduler.scheduleAllTimeRules()
                    Log.i(TAG, "Rescheduled all time rules successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to reschedule time rules on boot", e)
                }
            }
        }
    }
}
