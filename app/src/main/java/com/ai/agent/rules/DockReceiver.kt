package com.ai.agent.rules

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.ai.agent.storage.AgentDatabase

/**
 * Dock event trigger (v6.1.0) — fires when phone is docked/undocked.
 * triggerType: "docked" or "undocked"
 */
class DockReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "DockReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_DOCK_EVENT) return
        val dockState = intent.getIntExtra(Intent.EXTRA_DOCK_STATE, -1)
        val triggerType = when (dockState) {
            Intent.EXTRA_DOCK_STATE_CAR, Intent.EXTRA_DOCK_STATE_DESK,
            Intent.EXTRA_DOCK_STATE_LE_DESK, Intent.EXTRA_DOCK_STATE_HE_DESK -> "docked"
            Intent.EXTRA_DOCK_STATE_UNDOCKED -> "undocked"
            else -> return
        }
        Log.i(TAG, "Dock event: $triggerType (state=$dockState)")
        triggerRules(context, triggerType)
    }

    private fun triggerRules(context: Context, triggerType: String) {
        try {
            val db = AgentDatabase(context)
            val rules = db.getRules(enabledOnly = true).filter { it.triggerType == triggerType }
            for (rule in rules) {
                RuleTriggerReceiver.triggerRuleAction(context, rule)
                db.logAction("Dock rule fired: ${rule.name}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to trigger rules", e)
        }
    }
}
