package com.ai.agent.rules

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.ai.agent.storage.AgentDatabase

/**
 * Alarm dismissed trigger (v6.1.0) — best-effort detection.
 * Not all OEMs broadcast this, so it may not fire on all devices.
 */
class AlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AlarmReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        // OEM-specific actions for alarm dismissal
        val action = intent.action ?: return
        if (action.contains("alarm") && action.contains("dismiss")) {
            Log.i(TAG, "Alarm dismissed: $action")
            triggerRules(context)
        }
    }

    private fun triggerRules(context: Context) {
        try {
            val db = AgentDatabase(context)
            val rules = db.getRules(enabledOnly = true).filter { it.triggerType == "alarm_dismissed" }
            for (rule in rules) {
                RuleTriggerReceiver.triggerRuleAction(context, rule, "Alarm dismissed")
                db.logAction("Alarm dismissed rule fired: ${rule.name}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to trigger rules", e)
        }
    }
}
