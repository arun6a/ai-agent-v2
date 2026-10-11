package com.ai.agent.rules

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.ai.agent.service.AgentService
import com.ai.agent.storage.AgentDatabase

/**
 * Receives ACTION_PACKAGE_ADDED / ACTION_PACKAGE_REMOVED broadcasts to fire rules with:
 * - triggerType="app_installed" — fires when a new app is installed
 * - triggerType="app_uninstalled" — fires when an app is uninstalled
 *
 * The triggerValue can be:
 * - "*" or "any" — match any package
 * - "com.example.app" — match a specific package name
 *
 * Note: data scheme "package" must be declared in the intent filter for these to work.
 */
class PackageReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "PackageReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val packageName = intent.data?.schemeSpecificPart ?: return
        Log.i(TAG, "Package event: $action, package: $packageName")

        val db = AgentDatabase(context)
        val rules = db.getRules(enabledOnly = true)

        when (action) {
            Intent.ACTION_PACKAGE_ADDED -> {
                rules.filter { it.triggerType == "app_installed" }.forEach { rule ->
                    val trigger = rule.triggerValue.trim()
                    val shouldFire = trigger == "*" || trigger.equals("any", true) || trigger == packageName
                    if (shouldFire) {
                        fireRule(context, rule, "app_installed",
                            "App installed: $packageName")
                    }
                }
            }
            Intent.ACTION_PACKAGE_REMOVED -> {
                rules.filter { it.triggerType == "app_uninstalled" }.forEach { rule ->
                    val trigger = rule.triggerValue.trim()
                    val shouldFire = trigger == "*" || trigger.equals("any", true) || trigger == packageName
                    if (shouldFire) {
                        fireRule(context, rule, "app_uninstalled",
                            "App uninstalled: $packageName")
                    }
                }
            }
        }
    }

    private fun fireRule(context: Context, rule: AgentDatabase.Rule, triggerType: String, status: String) {
        Log.i(TAG, "Firing package rule: ${rule.name}")
        try { AgentDatabase(context).logAction("Package rule fired: ${rule.name} — $status") } catch (_: Exception) {}

        val serviceIntent = Intent(context, AgentService::class.java).apply {
            action = AgentService.ACTION_PROCESS_RULE
            putExtra(AgentService.EXTRA_RULE_NAME, rule.name)
            putExtra(AgentService.EXTRA_RULE_ACTION, rule.action)
            putExtra(AgentService.EXTRA_RULE_TRIGGER_TYPE, triggerType)
            putExtra(AgentService.EXTRA_NOTIFICATION_TITLE, "Package event")
            putExtra(AgentService.EXTRA_NOTIFICATION_TEXT, status)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
