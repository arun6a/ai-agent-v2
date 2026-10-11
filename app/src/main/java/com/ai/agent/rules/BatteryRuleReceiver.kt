package com.ai.agent.rules

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import com.ai.agent.service.AgentService
import com.ai.agent.storage.AgentDatabase

/**
 * Receives battery + power connection broadcasts to fire rules with:
 * - triggerType="battery_low" — fires when battery drops below threshold
 * - triggerType="charging" — fires when phone starts charging
 * - triggerType="discharging" — fires when phone stops charging
 *
 * For battery_low with custom thresholds (e.g., "20%"), we periodically poll the
 * battery level and fire when crossing below the threshold for the first time.
 * To avoid re-firing repeatedly while still below threshold, we use a cooldown.
 */
class BatteryRuleReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BatteryRuleReceiver"
        private const val PREFS_NAME = "battery_rule_state"
        private const val KEY_LAST_FIRED_PREFIX = "last_fired_battery_"
        private const val COOLDOWN_MS = 30 * 60 * 1000L  // 30 minutes between fires of same rule
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.i(TAG, "Received: $action")

        val db = AgentDatabase(context)
        val rules = db.getRules(enabledOnly = true)
        if (rules.isEmpty()) return

        when (action) {
            Intent.ACTION_POWER_CONNECTED -> {
                // Charging rules fire immediately
                rules.filter { it.triggerType == "charging" }.forEach { rule ->
                    fireRule(context, rule, "charging", "Phone is now charging")
                }
            }
            Intent.ACTION_POWER_DISCONNECTED -> {
                // Discharging rules fire immediately
                rules.filter { it.triggerType == "discharging" }.forEach { rule ->
                    fireRule(context, rule, "discharging", "Phone stopped charging")
                }
            }
            Intent.ACTION_BATTERY_CHANGED -> {
                // Check battery_low rules with threshold
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val percent = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
                if (percent < 0) return

                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                rules.filter { it.triggerType == "battery_low" }.forEach { rule ->
                    val threshold = rule.triggerValue.toIntOrNull() ?: return@forEach  // skip invalid
                    if (percent < threshold) {
                        // Check cooldown to avoid re-firing
                        val lastFired = prefs.getLong(KEY_LAST_FIRED_PREFIX + rule.id, 0L)
                        val now = System.currentTimeMillis()
                        if (now - lastFired > COOLDOWN_MS) {
                            prefs.edit().putLong(KEY_LAST_FIRED_PREFIX + rule.id, now).apply()
                            fireRule(context, rule, "battery_low", "Battery at $percent% (below $threshold%)")
                        } else {
                            Log.d(TAG, "Battery rule ${rule.name} on cooldown")
                        }
                    } else {
                        // Battery recovered — reset cooldown so it can fire again next time it drops
                        prefs.edit().remove(KEY_LAST_FIRED_PREFIX + rule.id).apply()
                    }
                }
            }
        }
    }

    private fun fireRule(context: Context, rule: AgentDatabase.Rule, triggerType: String, status: String) {
        Log.i(TAG, "Firing rule: ${rule.name} (trigger=$triggerType, status=$status)")
        val db = AgentDatabase(context)
        db.logAction("Battery rule fired: ${rule.name} — $status")

        val serviceIntent = Intent(context, AgentService::class.java).apply {
            action = AgentService.ACTION_PROCESS_RULE
            putExtra(AgentService.EXTRA_RULE_NAME, rule.name)
            putExtra(AgentService.EXTRA_RULE_ACTION, rule.action)
            putExtra(AgentService.EXTRA_RULE_TRIGGER_TYPE, triggerType)
            putExtra(AgentService.EXTRA_NOTIFICATION_TITLE, "Battery/Power event")
            putExtra(AgentService.EXTRA_NOTIFICATION_TEXT, status)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
