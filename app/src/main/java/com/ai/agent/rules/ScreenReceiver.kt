package com.ai.agent.rules

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.ai.agent.service.AgentService
import com.ai.agent.storage.AgentDatabase

/**
 * Receives ACTION_SCREEN_ON / ACTION_SCREEN_OFF broadcasts to fire rules with:
 * - triggerType="screen_on" — fires when user unlocks phone (screen turns on)
 * - triggerType="screen_off" — fires when screen turns off (timeout or manual)
 *
 * Note: ACTION_SCREEN_ON/OFF can only be registered programmatically (not in manifest),
 * so we register this receiver from AgentService.
 */
class ScreenReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ScreenReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.i(TAG, "Screen event: $action")

        val db = AgentDatabase(context)
        val rules = db.getRules(enabledOnly = true)

        when (action) {
            Intent.ACTION_SCREEN_ON -> {
                rules.filter { it.triggerType == "screen_on" }.forEach { rule ->
                    fireRule(context, rule, "screen_on", "Screen turned on (phone unlocked)")
                }
            }
            Intent.ACTION_SCREEN_OFF -> {
                rules.filter { it.triggerType == "screen_off" }.forEach { rule ->
                    fireRule(context, rule, "screen_off", "Screen turned off")
                }
            }
            Intent.ACTION_USER_PRESENT -> {
                // Fires when user actually unlocks (after screen on)
                rules.filter { it.triggerType == "user_unlocked" }.forEach { rule ->
                    fireRule(context, rule, "user_unlocked", "User unlocked the phone")
                }
            }
        }
    }

    private fun fireRule(context: Context, rule: AgentDatabase.Rule, triggerType: String, status: String) {
        Log.i(TAG, "Firing screen rule: ${rule.name}")
        db_log(context, rule, status)

        val serviceIntent = Intent(context, AgentService::class.java).apply {
            action = AgentService.ACTION_PROCESS_RULE
            putExtra(AgentService.EXTRA_RULE_NAME, rule.name)
            putExtra(AgentService.EXTRA_RULE_ACTION, rule.action)
            putExtra(AgentService.EXTRA_RULE_TRIGGER_TYPE, triggerType)
            putExtra(AgentService.EXTRA_NOTIFICATION_TITLE, "Screen event")
            putExtra(AgentService.EXTRA_NOTIFICATION_TEXT, status)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }

    private fun db_log(context: Context, rule: AgentDatabase.Rule, status: String) {
        try {
            AgentDatabase(context).logAction("Screen rule fired: ${rule.name} — $status")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to log action", e)
        }
    }
}
