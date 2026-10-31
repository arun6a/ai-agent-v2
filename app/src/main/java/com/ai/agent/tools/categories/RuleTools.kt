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

                // v4.0.52: Normalize triggerValue for notification rules — convert display
                // names to package names so the NotificationListener can match them.
                // E.g., "WhatsApp" → "com.whatsapp", "Telegram" → "com.telegram.messenger"
                var normalizedTriggerValue = triggerValue
                if (triggerType == "notification" && !triggerValue.contains(":")) {
                    val knownPackages = mapOf(
                        "whatsapp" to "com.whatsapp",
                        "whatsapp business" to "com.whatsapp.w4b",
                        "whatsapp bus" to "com.whatsapp.w4b",
                        "wa business" to "com.whatsapp.w4b",
                        "telegram" to "com.telegram.messenger",
                        "facebook" to "com.facebook.katana",
                        "messenger" to "com.facebook.orca",
                        "instagram" to "com.instagram.android",
                        "gmail" to "com.google.android.gm",
                        "youtube" to "com.google.android.youtube",
                        "twitter" to "com.twitter.android",
                        "x" to "com.twitter.android",
                        "tiktok" to "com.zhiliaoapp.musically",
                        "snapchat" to "com.snapchat.android",
                        "discord" to "com.discord",
                        "slack" to "com.slack",
                        "teams" to "com.microsoft.teams",
                        "outlook" to "com.microsoft.office.outlook",
                        "messages" to "com.google.android.apps.messaging",
                        "sms" to "com.google.android.apps.messaging",
                        "phone" to "com.android.dialer",
                        "calls" to "com.android.dialer",
                        "linkedin" to "com.linkedin.android",
                        "spotify" to "com.spotify.music",
                        "instagram" to "com.instagram.android"
                    )
                    val lookup = triggerValue.lowercase().trim()
                    val packageName = knownPackages[lookup]
                    if (packageName != null) {
                        normalizedTriggerValue = packageName
                        Log.i("RuleTools", "Normalized triggerValue: '$triggerValue' → '$packageName'")
                    } else if (!triggerValue.contains(".")) {
                        // Doesn't look like a package name (no dots) and not in our known list
                        return ToolExecutor.ToolResult(false,
                            "triggerValue '$triggerValue' looks like a display name, not a package name. " +
                            "Use the Android package name, e.g., 'com.whatsapp' (not 'WhatsApp'). " +
                            "Known packages: whatsapp→com.whatsapp, telegram→com.telegram.messenger, " +
                            "messenger→com.facebook.orca, gmail→com.google.android.gm. " +
                            "Or use format 'package:sender' e.g., 'com.whatsapp:Mom'.")
                    }
                } else if (triggerType == "notification" && triggerValue.contains(":")) {
                    // Format: "package:sender" — normalize the package part
                    val parts = triggerValue.split(":", limit = 2)
                    val pkgPart = parts[0].lowercase().trim()
                    val senderPart = parts.getOrNull(1) ?: ""
                    val knownPackages = mapOf(
                        "whatsapp" to "com.whatsapp",
                        "whatsapp business" to "com.whatsapp.w4b",
                        "whatsapp bus" to "com.whatsapp.w4b",
                        "wa business" to "com.whatsapp.w4b",
                        "telegram" to "com.telegram.messenger",
                        "facebook" to "com.facebook.katana",
                        "messenger" to "com.facebook.orca",
                        "instagram" to "com.instagram.android",
                        "gmail" to "com.google.android.gm"
                    )
                    val packageName = knownPackages[pkgPart]
                    if (packageName != null) {
                        normalizedTriggerValue = "$packageName:$senderPart"
                        Log.i("RuleTools", "Normalized triggerValue: '$triggerValue' → '$normalizedTriggerValue'")
                    }
                }

                // v4.0.52: Validate action for notification rules — if action is "replyToNotification"
                // without parentheses, reject + tell AI the correct format.
                if (triggerType == "notification") {
                    val cleanAction = action.removePrefix("direct:").removePrefix("tool:").trim()
                    // Check if action is just a bare tool name with no parentheses
                    if (cleanAction.matches(Regex("^[a-zA-Z_][a-zA-Z0-9_]*$")) &&
                        cleanAction.lowercase().contains("reply")) {
                        return ToolExecutor.ToolResult(false,
                            "action '$action' is missing arguments. For direct reply, use the FULL tool call: " +
                            "replyToNotification(package=\"com.whatsapp\", message=\"your reply text\"). " +
                            "For smart reply (AI decides), use natural language like 'Reply in Tamil as my assistant'.")
                    }
                }

                // v2.5.2: Duplicate rule detection — don't create a rule if an identical one exists.
                val existingRules = database.getRules(enabledOnly = false)
                val duplicate = existingRules.find { existing ->
                    existing.triggerType == triggerType &&
                    existing.triggerValue == normalizedTriggerValue &&
                    existing.action == action
                }
                if (duplicate != null) {
                    return ToolExecutor.ToolResult(true, "Rule already exists (id=${duplicate.id}, name='${duplicate.name}'). No duplicate created. " +
                        "Use listRules to see all rules, or deleteRule(${duplicate.id}) to remove it.")
                }

                // v4.0.52: Use normalizedTriggerValue (display name → package name conversion done above)
                val ruleId = database.addRule(name, triggerType, normalizedTriggerValue, action, days)
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
                val normalizedNote = if (normalizedTriggerValue != triggerValue) {
                    " (normalized from '$triggerValue')"
                } else ""
                ToolExecutor.ToolResult(true, "Created rule '$name' (id=$ruleId, trigger=$triggerType:$normalizedTriggerValue$normalizedNote" +
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
                // v4.0.21: Handle id as Number, String, or Int — models return it inconsistently.
                val id = parseId(call.args["id"]) ?: return ToolExecutor.ToolResult(false, "missing id")
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
                // v4.0.21: Handle id as Number, String, or Int.
                val id = parseId(call.args["id"]) ?: return ToolExecutor.ToolResult(false, "missing id")
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

    /**
     * v4.0.21: Parse a rule ID from any type the model might return.
     * Models return id as: Int(1), Long(1L), Double(1.0), String("1"), or even String("1.0").
     * This handles all cases + returns null if truly missing/unparseable.
     */
    private fun parseId(value: Any?): Long? {
        return when (value) {
            is Number -> value.toLong()
            is String -> value.trim().trim('"').trim('\'')
                .substringBefore('.').toLongOrNull()
            else -> null
        }
    }
}
