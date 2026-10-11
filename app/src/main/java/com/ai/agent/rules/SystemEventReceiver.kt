package com.ai.agent.rules

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.ai.agent.storage.AgentDatabase

/**
 * System event triggers (v6.1.0) — handles:
 * - airplane_mode (on/off)
 * - storage_low
 * - locale_changed
 * - timezone_changed
 * - shutdown
 * - data_saver_on / data_saver_off
 */
class SystemEventReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SystemEventReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val triggerType = when (intent.action) {
            Intent.ACTION_AIRPLANE_MODE_CHANGED -> {
                val isOn = intent.getBooleanExtra("state", false)
                if (isOn) "airplane_mode_on" else "airplane_mode_off"
            }
            Intent.ACTION_DEVICE_STORAGE_LOW -> "storage_low"
            Intent.ACTION_LOCALE_CHANGED -> "locale_changed"
            Intent.ACTION_TIMEZONE_CHANGED -> "timezone_changed"
            Intent.ACTION_SHUTDOWN -> "shutdown"
            "android.net.action.DATA_SAVIER_CHANGED" -> "data_saver_changed"
            else -> return
        }

        Log.i(TAG, "System event: $triggerType")
        triggerRules(context, triggerType)
    }

    private fun triggerRules(context: Context, triggerType: String) {
        try {
            val db = AgentDatabase(context)
            val rules = db.getRules(enabledOnly = true).filter { it.triggerType == triggerType }
            for (rule in rules) {
                RuleTriggerReceiver.triggerRuleAction(context, rule)
                db.logAction("System event rule fired: ${rule.name} ($triggerType)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to trigger rules", e)
        }
    }
}
