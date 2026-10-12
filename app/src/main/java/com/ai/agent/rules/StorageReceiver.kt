package com.ai.agent.rules

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.ai.agent.storage.AgentDatabase

/**
 * Storage low trigger (v6.1.0) — wraps SystemEventReceiver's storage_low action
 * for standalone registration if needed.
 */
class StorageReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "StorageReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_DEVICE_STORAGE_LOW) return
        Log.i(TAG, "Storage low!")
        triggerRules(context)
    }

    private fun triggerRules(context: Context) {
        try {
            val db = AgentDatabase(context)
            val rules = db.getRules(enabledOnly = true).filter { it.triggerType == "storage_low" }
            for (rule in rules) {
                RuleTriggerReceiver.triggerRuleAction(context, rule, "Storage is low")
                db.logAction("Storage low rule fired: ${rule.name}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to trigger rules", e)
        }
    }
}
