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
                        fireRule(context, rule, "App installed: $packageName")
                    }
                }
            }
            Intent.ACTION_PACKAGE_REMOVED -> {
                rules.filter { it.triggerType == "app_uninstalled" }.forEach { rule ->
                    val trigger = rule.triggerValue.trim()
                    val shouldFire = trigger == "*" || trigger.equals("any", true) || trigger == packageName
                    if (shouldFire) {
                        fireRule(context, rule, "App uninstalled: $packageName")
                    }
                }
            }
        }
    }

    private fun fireRule(context: Context, rule: AgentDatabase.Rule, status: String) {
        Log.i(TAG, "Firing rule: " + rule.name + " — " + status)
        com.ai.agent.rules.RuleTriggerReceiver.triggerRuleAction(context, rule, status)
    }
}

