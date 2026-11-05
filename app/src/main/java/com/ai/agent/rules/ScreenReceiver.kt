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
                // v4.0.80: GAP 4 FIX — Record screen-on pattern with hour
                // v4.0.85: Check if proactive is enabled before recording
                if (com.ai.agent.proactive.ProactiveSettings.isEnabled(context)) {
                    try {
                        val hour = java.text.SimpleDateFormat("HH", java.util.Locale.US).format(java.util.Date())
                        com.ai.agent.proactive.ProactiveObserver.recordTrigger(
                            context, "screen_on:$hour", null
                        )
                    } catch (_: Exception) {}
                }

                rules.filter { it.triggerType == "screen_on" }.forEach { rule ->
                    fireRule(context, rule, "Screen turned on (phone unlocked)")
                }
            }
            Intent.ACTION_SCREEN_OFF -> {
                rules.filter { it.triggerType == "screen_off" }.forEach { rule ->
                    fireRule(context, rule, "Screen turned off")
                }
            }
            Intent.ACTION_USER_PRESENT -> {
                // Fires when user actually unlocks (after screen on)
                rules.filter { it.triggerType == "user_unlocked" }.forEach { rule ->
                    fireRule(context, rule, "User unlocked the phone")
                }
            }
        }
    }

    private fun fireRule(context: Context, rule: AgentDatabase.Rule, status: String) {
        Log.i(TAG, "Firing rule: " + rule.name + " — " + status)
        RuleEngine.execute(context, rule, status)
    }
}

