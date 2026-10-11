package com.ai.agent.rules

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.ai.agent.storage.AgentDatabase
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Rule engine that checks for time-based rules every minute.
 * When a rule fires, it calls the onRuleTriggered callback.
 */
class RuleEngine(private val context: Context) {

    companion object {
        private const val TAG = "RuleEngine"
        private const val CHECK_INTERVAL = 60_000L  // 1 minute
    }

    private val db = AgentDatabase(context)
    private val handler = Handler(Looper.getMainLooper())
    private var lastTriggeredTime: String = ""

    var onRuleTriggered: ((AgentDatabase.Rule) -> Unit)? = null

    private val checkRunnable = object : Runnable {
        override fun run() {
            checkTimeRules()
            handler.postDelayed(this, CHECK_INTERVAL)
        }
    }

    fun start() {
        Log.i(TAG, "Rule engine started")
        handler.postDelayed(checkRunnable, CHECK_INTERVAL)
    }

    fun stop() {
        handler.removeCallbacks(checkRunnable)
        Log.i(TAG, "Rule engine stopped")
    }

    private fun checkTimeRules() {
        val now = Calendar.getInstance()
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        val currentTime = timeFormat.format(now.time)

        // Reset last triggered when minute changes
        if (currentTime != lastTriggeredTime) {
            lastTriggeredTime = currentTime

            val rules = db.getRules(enabledOnly = true)
            for (rule in rules) {
                if (rule.triggerType == "time" && rule.triggerValue == currentTime) {
                    Log.i(TAG, "Time rule fired: ${rule.name} at $currentTime")
                    onRuleTriggered?.invoke(rule)
                }
            }
        }
    }

    /**
     * Add a time-based rule.
     * @param name Rule name (e.g., "Morning briefing")
     * @param time Time in HH:mm format (e.g., "07:00")
     * @param action Natural language description of what to do
     */
    fun addTimeRule(name: String, time: String, action: String): Long {
        return db.addRule(name, "time", time, action)
    }

    /**
     * Add a notification-based rule.
     * @param name Rule name
     * @param trigger Package name or "package:sender" (e.g., "com.whatsapp:Mom")
     * @param action What to do when triggered
     */
    fun addNotificationRule(name: String, trigger: String, action: String): Long {
        return db.addRule(name, "notification", trigger, action)
    }

    fun getAllRules(): List<AgentDatabase.Rule> = db.getRules()

    fun deleteRule(id: Long) = db.deleteRule(id)

    fun toggleRule(id: Long, enabled: Boolean) = db.toggleRule(id, enabled)
}
