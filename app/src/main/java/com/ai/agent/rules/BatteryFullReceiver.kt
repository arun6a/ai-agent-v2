package com.ai.agent.rules

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import com.ai.agent.storage.AgentDatabase

/**
 * Battery full trigger (v6.1.0) — fires when battery reaches 100% while charging.
 * Extends BatteryRuleReceiver logic.
 */
class BatteryFullReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BatteryFullReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BATTERY_CHANGED) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val percent = if (level >= 0 && scale > 0) (level * 100) / scale else -1
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                             status == BatteryManager.BATTERY_STATUS_FULL

            if (percent == 100 && isCharging) {
                Log.i(TAG, "Battery full (100%) while charging")
                triggerRules(context, "battery_full")
            }
        }
    }

    private fun triggerRules(context: Context, triggerType: String) {
        try {
            val db = AgentDatabase(context)
            val rules = db.getRules(enabledOnly = true).filter { it.triggerType == triggerType }
            for (rule in rules) {
                RuleTriggerReceiver.triggerRuleAction(context, rule)
                db.logAction("Battery full rule fired: ${rule.name}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to trigger rules", e)
        }
    }
}
