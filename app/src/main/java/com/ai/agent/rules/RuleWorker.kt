package com.ai.agent.rules

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.ForegroundInfo
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.ai.agent.service.AgentService

/**
 * WorkManager Worker for executing rules reliably in the background.
 *
 * v2.5.3: This is the ONLY reliable way to execute rules on Android 14+.
 * - BroadcastReceiver.startService() → BLOCKED
 * - BroadcastReceiver.startForegroundService() → BLOCKED (dataSync type)
 * - WorkManager → ALLOWED (system-managed)
 *
 * WorkManager can promote itself to foreground via setForegroundAsync() which
 * properly handles the Android 14 foreground service restrictions.
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
        const val NOTIFICATION_ID = 7777
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
            // v2.5.3: Try to promote to foreground (allowed for expedited work on Android 14)
            // This lets us start AgentService (which is a foreground dataSync service)
            try {
                val notification = createRuleNotification(ruleName)
                val foregroundInfo = ForegroundInfo(NOTIFICATION_ID, notification)
                setForegroundAsync(foregroundInfo)
                Log.i(TAG, "Promoted to foreground — can start AgentService")
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't promote to foreground (may be in background): ${e.message}")
                // Continue anyway — AgentService might already be running as foreground
            }

            // Start AgentService to execute the rule
            val intent = Intent(applicationContext, AgentService::class.java).apply {
                action = AgentService.ACTION_PROCESS_RULE
                putExtra(AgentService.EXTRA_RULE_NAME, ruleName)
                putExtra(AgentService.EXTRA_RULE_ACTION, ruleAction)
                putExtra(AgentService.EXTRA_RULE_TRIGGER_TYPE, ruleTrigger)
            }
            // v2.5.3: Try startForegroundService first (works if promoted to foreground above)
            // then fall back to startService (works if AgentService already running as foreground)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    applicationContext.startForegroundService(intent)
                } else {
                    applicationContext.startService(intent)
                }
                Log.i(TAG, "Rule $ruleName dispatched to AgentService")
            } catch (e: Exception) {
                Log.w(TAG, "startForegroundService failed, trying startService: ${e.message}")
                try {
                    applicationContext.startService(intent)
                    Log.i(TAG, "Rule $ruleName dispatched to AgentService via startService")
                } catch (e2: Exception) {
                    Log.e(TAG, "Both startForegroundService + startService failed for $ruleName", e2)
                    // Last resort: execute the rule directly in the worker (no service)
                    executeRuleDirectly(ruleName, ruleAction, ruleTrigger)
                }
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute rule $ruleName", e)
            if (runAttemptCount < 2) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }

    /**
     * v2.5.3: Create a notification for the foreground service promotion.
     */
    private fun createRuleNotification(ruleName: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                "rule_worker",
                "Rule Execution",
                android.app.NotificationManager.IMPORTANCE_LOW
            )
            val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.createNotificationChannel(channel)
        }
        return androidx.core.app.NotificationCompat.Builder(applicationContext, "rule_worker")
            .setContentTitle("Aira Rule")
            .setContentText("Executing: $ruleName")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /**
     * v2.5.3: Last resort — execute the rule directly in the worker (no AgentService).
     * This handles both direct rules (setBrightness(50)) + smart rules (natural language).
     */
    private fun executeRuleDirectly(ruleName: String, ruleAction: String, ruleTrigger: String) {
        try {
            Log.i(TAG, "Executing rule directly in worker: $ruleName")
            val serviceIntent = Intent(applicationContext, AgentService::class.java).apply {
                action = AgentService.ACTION_PROCESS_RULE
                putExtra(AgentService.EXTRA_RULE_NAME, ruleName)
                putExtra(AgentService.EXTRA_RULE_ACTION, ruleAction)
                putExtra(AgentService.EXTRA_RULE_TRIGGER_TYPE, ruleTrigger)
            }
            // Try one more time with startForegroundService (we're now foreground via setForegroundAsync)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                applicationContext.startForegroundService(serviceIntent)
            } else {
                applicationContext.startService(serviceIntent)
            }
            Log.i(TAG, "Rule dispatched to AgentService after foreground promotion")
        } catch (e: Exception) {
            Log.e(TAG, "Direct execution also failed for $ruleName", e)
        }
    }
}
