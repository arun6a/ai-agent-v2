package com.ai.agent.rules

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.ai.agent.accessibility.AgentAccessibilityService
import com.ai.agent.storage.AgentDatabase

/**
 * App open/close trigger (v6.1.0) — detects when apps open or close.
 * Uses accessibility service's TYPE_WINDOW_STATE_CHANGED events.
 *
 * This receiver is triggered by the accessibility service when it detects
 * a foreground app change. The service sends a broadcast with:
 * - packageName: the app that opened
 * - eventType: "opened" or "closed"
 */
class AppOpenReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AppOpenReceiver"
        const val ACTION_APP_OPENED = "com.ai.agent.APP_OPENED"
        const val ACTION_APP_CLOSED = "com.ai.agent.APP_CLOSED"
        const val EXTRA_PACKAGE = "package_name"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val packageName = intent.getStringExtra(EXTRA_PACKAGE) ?: return
        val triggerType = when (intent.action) {
            ACTION_APP_OPENED -> "app_opened"
            ACTION_APP_CLOSED -> "app_closed"
            else -> return
        }
        Log.i(TAG, "App $triggerType: $packageName")
        triggerRules(context, triggerType, packageName)
    }

    private fun triggerRules(context: Context, triggerType: String, packageName: String) {
        try {
            val db = AgentDatabase(context)
            val rules = db.getRules(enabledOnly = true).filter { it.triggerType == triggerType }
            for (rule in rules) {
                val matchValue = rule.triggerValue
                if (matchValue == "*" || matchValue.equals(packageName, ignoreCase = true)) {
                    val appInfo = "App: $packageName\nEvent: $triggerType"
                    RuleTriggerReceiver.triggerRuleAction(context, rule, appInfo)
                    db.logAction("App open/close rule fired: ${rule.name} ($packageName)")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to trigger rules", e)
        }
    }
}
