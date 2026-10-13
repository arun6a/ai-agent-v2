package com.ai.agent.rules

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.ai.agent.storage.AgentDatabase

/**
 * v3.0.0: RuleTriggerReceiver — receives AlarmManager broadcasts for TIME rules.
 *
 * Flow: AlarmManager → RuleTriggerReceiver → RuleEngine.execute() → Done.
 *
 * No WorkManager, no AgentService, no wrapping.
 */
class RuleTriggerReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "RuleTriggerReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "⏰ AlarmManager broadcast received: ${intent.action}")

        val ruleId = intent.getLongExtra(RuleScheduler.EXTRA_RULE_ID, -1L)
        if (ruleId == -1L) {
            Log.e(TAG, "No rule_id extra — ignoring")
            return
        }

        try {
            val db = AgentDatabase(context)
            val rule = db.getRules(enabledOnly = false).find { it.id == ruleId }
            if (rule == null) {
                Log.e(TAG, "Rule id=$ruleId not found in DB — probably deleted")
                return
            }
            if (!rule.enabled) {
                Log.i(TAG, "Rule id=$ruleId is disabled — skipping")
                return
            }

            Log.i(TAG, "Rule fired: ${rule.name} → action: ${rule.action}")

            // v3.0.0: Execute DIRECTLY via RuleEngine — no WorkManager, no AgentService
            RuleEngine.execute(context, rule, "time rule: ${rule.triggerValue}")

            // Reschedule for next occurrence
            if (rule.triggerType == "time") {
                val scheduler = RuleScheduler(context)
                scheduler.scheduleTimeRule(rule)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process rule id=$ruleId", e)
        }
    }
}
