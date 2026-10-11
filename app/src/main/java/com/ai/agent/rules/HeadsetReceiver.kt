package com.ai.agent.rules

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.ai.agent.service.AgentService
import com.ai.agent.storage.AgentDatabase

/**
 * Receives ACTION_HEADSET_PLUG broadcasts to fire rules with:
 * - triggerType="headset_connected" — fires when headphones are plugged in
 * - triggerType="headset_disconnected" — fires when headphones are unplugged
 *
 * The triggerValue is ignored (use "any").
 *
 * Note: Bluetooth headphones use a different broadcast (ACTION_ACL_CONNECTED).
 * For simplicity, we only handle wired headsets here. Bluetooth can be added later.
 */
class HeadsetReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "HeadsetReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_HEADSET_PLUG) return

        val state = intent.getIntExtra("state", -1)
        val name = intent.getStringExtra("name") ?: "headset"
        Log.i(TAG, "Headset state: $state (name=$name)")

        val db = AgentDatabase(context)
        val rules = db.getRules(enabledOnly = true)

        when (state) {
            1 -> {  // connected
                rules.filter { it.triggerType == "headset_connected" }.forEach { rule ->
                    fireRule(context, rule, "Headphones connected ($name)")
                }
            }
            0 -> {  // disconnected
                rules.filter { it.triggerType == "headset_disconnected" }.forEach { rule ->
                    fireRule(context, rule, "Headphones disconnected")
                }
            }
        }
    }

    private fun fireRule(context: Context, rule: AgentDatabase.Rule, status: String) {
        Log.i(TAG, "Firing headset rule: ${rule.name}")
        val db = AgentDatabase(context)
        db.logAction("Headset rule fired: ${rule.name} — $status")

        val serviceIntent = Intent(context, AgentService::class.java).apply {
            action = AgentService.ACTION_PROCESS_RULE
            putExtra(AgentService.EXTRA_RULE_NAME, rule.name)
            putExtra(AgentService.EXTRA_RULE_ACTION, rule.action)
            putExtra(AgentService.EXTRA_RULE_TRIGGER_TYPE, "headset")
            putExtra(AgentService.EXTRA_NOTIFICATION_TITLE, "Headset event")
            putExtra(AgentService.EXTRA_NOTIFICATION_TEXT, status)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
