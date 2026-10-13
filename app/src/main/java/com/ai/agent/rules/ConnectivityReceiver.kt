package com.ai.agent.rules

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.util.Log
import com.ai.agent.service.AgentService

/**
 * v2.5.0: ConnectivityReceiver
 *
 * Fires when network connectivity changes (WiFi connected, cellular connected, etc.)
 * When network becomes available, processes the offline rule queue.
 *
 * Registered in AndroidManifest to listen for:
 *   - android.net.conn.CONNECTIVITY_CHANGE (deprecated but still fires)
 *   - android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED (modern)
 */
class ConnectivityReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ConnectivityReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (!isInitialStickyBroadcast) {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val isOnline = cm?.activeNetwork != null

            Log.i(TAG, "Connectivity changed: online=$isOnline")

            if (isOnline && NetworkHelper.isOnline(context)) {
                processOfflineQueue(context)
            }
        }
    }

    /**
     * Process all pending rules from the offline queue.
     * Called when network reconnects.
     */
    private fun processOfflineQueue(context: Context) {
        val queue = OfflineRuleQueue.loadQueue(context)
        if (queue.isEmpty()) {
            Log.i(TAG, "Offline queue empty — nothing to process")
            return
        }

        Log.i(TAG, "Processing offline queue: ${queue.size} pending rules")

        queue.forEach { pendingRule ->
            try {
                val serviceIntent = Intent(context, AgentService::class.java).apply {
                    action = AgentService.ACTION_PROCESS_RULE
                    putExtra(AgentService.EXTRA_RULE_NAME, pendingRule.ruleName)
                    putExtra(AgentService.EXTRA_RULE_ACTION, pendingRule.ruleAction)
                    putExtra(AgentService.EXTRA_RULE_TRIGGER_TYPE, pendingRule.triggerType)
                    putExtra(AgentService.EXTRA_NOTIFICATION_TITLE, pendingRule.notifTitle)
                    putExtra(AgentService.EXTRA_NOTIFICATION_TEXT, pendingRule.notifText)
                    // v2.5.0: mark as queued-so-don't-requeue-if-still-fails
                    putExtra("from_offline_queue", true)
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
                Log.i(TAG, "Re-triggered queued rule: ${pendingRule.ruleName}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to re-trigger rule: ${pendingRule.ruleName}", e)
            }
        }

        // Clear queue after processing (we tried each one — if they fail again, that's a different error)
        OfflineRuleQueue.clear(context)
    }
}
