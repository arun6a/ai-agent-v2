package com.ai.agent.tools.categories

import android.content.Context
import android.util.Log
import com.ai.agent.accessibility.AgentAccessibilityService
import com.ai.agent.llm.LLMClient
import com.ai.agent.storage.AgentDatabase
import com.ai.agent.tools.ToolExecutor

/**
 * Rule tools category — extracted from ToolExecutor in v3.3.13.
 *
 * Tools: createRule, listRules, deleteRule, modifyRule.
 *
 * No behavior changes — code moved verbatim from ToolExecutor.kt.
 */
object RuleTools {

    private const val TAG = "RuleTools"

    suspend fun execute(
        call: LLMClient.ToolCall,
        context: Context,
        @Suppress("UNUSED_PARAMETER") service: AgentAccessibilityService?,
        database: AgentDatabase
    ): ToolExecutor.ToolResult {
        return when (call.name) {
            // ==================== RULE MANAGEMENT (Proactive Assistant) ====================
            "createRule" -> {
                val name = call.args["name"] as? String ?: return ToolExecutor.ToolResult(false, "missing name")
                val triggerType = call.args["triggerType"] as? String
                    ?: return ToolExecutor.ToolResult(false, "missing triggerType (use 'time', 'notification', 'battery_low', or 'charging')")
                val triggerValue = call.args["triggerValue"] as? String
                    ?: return ToolExecutor.ToolResult(false, "missing triggerValue (e.g., '07:00', 'com.whatsapp:Mom', '20')")
                val action = call.args["action"] as? String ?: return ToolExecutor.ToolResult(false, "missing action")
                val days = call.args["days"] as? String  // nullable — "mon,tue,wed,thu,fri" or null for daily

                // v2.5.0: Expanded trigger types — all supported by manifest receivers.
                val validTypes = listOf(
                    // Time-based
                    "time",
                    // Notifications + SMS + calls
                    "notification", "incoming_call", "sms_received",
                    // Battery + power
                    "battery_low", "battery_full", "charging", "discharging",
                    // Audio + headset
                    "headset_connected", "headset_disconnected", "music_started",
                    // Screen + user state
                    "screen_on", "screen_off", "user_unlocked",
                    // Network
                    "wifi_connected", "wifi_disconnected",
                    // Apps
                    "app_installed", "app_uninstalled", "app_opened", "app_closed",
                    // System events
                    "airplane_mode", "storage_low", "day_changed",
                    "calendar_event_starting", "usb_connected", "docked",
                    "brightness_high", "brightness_low",
                    // v2.5.0 NEW triggers
                    "location_entered", "location_exited",
                    "bluetooth_connected", "bluetooth_disconnected"
                )
                if (triggerType !in validTypes) {
                    return ToolExecutor.ToolResult(false, "invalid triggerType. Use one of: $validTypes")
                }

                // Validate time format if time-based
                if (triggerType == "time") {
                    val parts = triggerValue.split(":")
                    if (parts.size != 2 || parts[0].toIntOrNull() !in 0..23 || parts[1].toIntOrNull() !in 0..59) {
                        return ToolExecutor.ToolResult(false, "invalid time format. Use HH:MM (24-hour), e.g., '07:00'")
                    }
                    // Validate days if provided
                    if (days != null && days.isNotEmpty()) {
                        val validDays = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")
                        val parsed = days.split(",").map { it.trim().lowercase() }
                        val invalid = parsed.filter { it !in validDays }
                        if (invalid.isNotEmpty()) {
                            return ToolExecutor.ToolResult(false, "invalid day(s): $invalid. Use 3-letter lowercase: mon, tue, wed, thu, fri, sat, sun")
                        }
                    }
                }

                // v2.5.2: Duplicate rule detection — don't create a rule if an identical one exists.
                val existingRules = database.getRules(enabledOnly = false)
                val duplicate = existingRules.find { existing ->
                    existing.triggerType == triggerType &&
                    existing.triggerValue == triggerValue &&
                    existing.action == action
                }
                if (duplicate != null) {
                    return ToolExecutor.ToolResult(true, "Rule already exists (id=${duplicate.id}, name='${duplicate.name}'). No duplicate created. " +
                        "Use listRules to see all rules, or deleteRule(${duplicate.id}) to remove it.")
                }

                val ruleId = database.addRule(name, triggerType, triggerValue, action, days)
                // Schedule with AlarmManager if it's a time rule
                if (triggerType == "time") {
                    try {
                        com.ai.agent.rules.RuleScheduler(context).scheduleTimeRule(
                            database.getRules().find { it.id == ruleId }!!
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to schedule rule", e)
                    }
                }
                ToolExecutor.ToolResult(true, "Created rule '$name' (id=$ruleId, trigger=$triggerType:$triggerValue" +
                    (if (days != null) ", days=$days" else "") + "). " +
                    if (triggerType == "time") "It has been scheduled with the system AlarmManager and will fire even if the app is closed." else "")
            }
            "listRules" -> {
                val rules = database.getRules(enabledOnly = false)
                if (rules.isEmpty()) {
                    ToolExecutor.ToolResult(true, "No rules configured yet. Create one with createRule().")
                } else {
                    val sb = StringBuilder("Rules (${rules.size}):\n")
                    rules.forEach { r ->
                        val status = if (r.enabled) "ON" else "OFF"
                        val daysStr = if (r.days.isNullOrEmpty()) "daily" else r.days
                        sb.append("- [${r.id}] [$status] ${r.name}\n")
                        sb.append("    trigger: ${r.triggerType} = ${r.triggerValue}\n")
                        sb.append("    days: $daysStr\n")
                        sb.append("    action: ${r.action}\n\n")
                    }
                    ToolExecutor.ToolResult(true, sb.toString())
                }
            }
            "deleteRule" -> {
                val id = (call.args["id"] as? Number)?.toLong() ?: return ToolExecutor.ToolResult(false, "missing id")
                // Cancel scheduled alarm if it's a time rule
                try {
                    com.ai.agent.rules.RuleScheduler(context).cancelRule(id)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to cancel alarm for rule $id", e)
                }
                database.deleteRule(id)
                ToolExecutor.ToolResult(true, "Deleted rule id=$id")
            }
            "modifyRule" -> {
                val id = (call.args["id"] as? Number)?.toLong() ?: return ToolExecutor.ToolResult(false, "missing id")
                val existing = database.getRules().find { it.id == id }
                    ?: return ToolExecutor.ToolResult(false, "rule id=$id not found")
                val newName = call.args["name"] as? String
                val newTriggerType = call.args["triggerType"] as? String
                val newTriggerValue = call.args["triggerValue"] as? String
                val newAction = call.args["action"] as? String
                val newDays = call.args["days"] as? String  // pass "" to clear, "mon,tue" to set, null to leave unchanged
                val newEnabled = (call.args["enabled"] as? Boolean)

                database.updateRule(
                    id = id,
                    name = newName,
                    triggerType = newTriggerType,
                    triggerValue = newTriggerValue,
                    action = newAction,
                    days = newDays,
                    enabled = newEnabled
                )
                // Reschedule if it's a time rule
                val updated = database.getRules().find { it.id == id }
                if (updated != null && updated.triggerType == "time" && updated.enabled) {
                    try {
                        com.ai.agent.rules.RuleScheduler(context).cancelRule(id)
                        com.ai.agent.rules.RuleScheduler(context).scheduleTimeRule(updated)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to reschedule rule $id", e)
                    }
                } else if (updated != null && updated.triggerType == "time" && !updated.enabled) {
                    try {
                        com.ai.agent.rules.RuleScheduler(context).cancelRule(id)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to cancel alarm for rule $id", e)
                    }
                }
                ToolExecutor.ToolResult(true, "Modified rule id=$id")
            }
            else -> ToolExecutor.ToolResult(false, "Unknown rule tool: ${call.name}")
        }
    }
}
