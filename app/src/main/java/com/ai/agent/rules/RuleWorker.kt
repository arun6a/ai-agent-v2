package com.ai.agent.rules

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.ai.agent.service.AgentService

/**
 * WorkManager Worker for executing rules reliably in the background.
 *
 * Why WorkManager (not just AlarmManager):
 * - Survives app kills (system-managed process)
 * - Survives reboots (auto-reschedules)
 * - Retries on failure
 * - Respects battery constraints
 * - No need for BootReceiver (WorkManager handles it)
 *
 * Used for time-based rules. When AlarmManager fires, it starts this Worker
 * instead of directly starting AgentService — giving us reliability + retry.
 */
class RuleWorker(
    context: Context,
    workerParams: WorkerParameters
) : Worker(context, workerParams) {

    companion object {
        private const val TAG = "RuleWorker"
        const val KEY_RULE_ID = "rule_id"
        const val KEY_RULE_NAME = "rule_name"
        const val KEY_RULE_ACTION = "rule_action"
        const val KEY_RULE_TRIGGER = "rule_trigger"
    }

    override fun doWork(): Result {
        val ruleName = inputData.getString(KEY_RULE_NAME) ?: "Unknown rule"
        val ruleAction = inputData.getString(KEY_RULE_ACTION) ?: ""
        val ruleTrigger = inputData.getString(KEY_RULE_TRIGGER) ?: "time"

        Log.i(TAG, "Worker executing rule: $ruleName (action: $ruleAction)")

        if (ruleAction.isEmpty()) {
            Log.w(TAG, "Empty rule action — skipping")
            return Result.success()
        }

        return try {
            // Start AgentService to execute the rule
            val intent = Intent(applicationContext, AgentService::class.java).apply {
                action = AgentService.ACTION_PROCESS_RULE
                putExtra(AgentService.EXTRA_RULE_NAME, ruleName)
                putExtra(AgentService.EXTRA_RULE_ACTION, ruleAction)
                putExtra(AgentService.EXTRA_RULE_TRIGGER_TYPE, ruleTrigger)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                applicationContext.startForegroundService(intent)
            } else {
                applicationContext.startService(intent)
            }

            Log.i(TAG, "Rule $ruleName dispatched to AgentService")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute rule $ruleName", e)
            // Retry once, then give up
            if (runAttemptCount < 2) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }
}
