package com.ai.agent.util

/**
 * v4.0.99: Pure-logic rule validators — extracted from RuleTools for unit testing.
 *
 * Validates:
 * - Time format (HH:MM 24-hour)
 * - Day names (mon, tue, wed, thu, fri, sat, sun)
 * - Trigger types
 */
object RuleValidator {

    /**
     * Validate HH:MM time format (24-hour).
     * Returns true if valid, false otherwise.
     * Valid: "07:00", "23:59", "00:00"
     * Invalid: "7:00", "24:00", "07:60", "07", "abc"
     */
    fun isValidTimeFormat(value: String): Boolean {
        val parts = value.split(":")
        if (parts.size != 2) return false
        val hour = parts[0].toIntOrNull() ?: return false
        val minute = parts[1].toIntOrNull() ?: return false
        return hour in 0..23 && minute in 0..59
    }

    /**
     * Validate day abbreviations.
     * Input: "mon,tue,wed,thu,fri" or "mon" or null
     * Returns null if valid, or an error message if invalid.
     */
    fun validateDays(days: String?): String? {
        if (days.isNullOrEmpty()) return null  // null/empty = daily (valid)
        val validDays = setOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")
        val parsed = days.split(",").map { it.trim().lowercase() }
        val invalid = parsed.filter { it !in validDays }
        return if (invalid.isNotEmpty()) {
            "invalid day(s): $invalid. Use 3-letter lowercase: mon, tue, wed, thu, fri, sat, sun"
        } else null
    }

    /**
     * Validate trigger type against the list of supported types.
     */
    fun validateTriggerType(triggerType: String): Boolean {
        return triggerType in VALID_TRIGGER_TYPES
    }

    val VALID_TRIGGER_TYPES = setOf(
        "time", "interval",
        "notification", "incoming_call", "sms_received",
        "battery_low", "battery_full", "charging", "discharging",
        "headset_connected", "headset_disconnected", "music_started",
        "screen_on", "screen_off", "user_unlocked",
        "wifi_connected", "wifi_disconnected",
        "app_installed", "app_uninstalled", "app_opened", "app_closed",
        "airplane_mode", "storage_low", "day_changed",
        "calendar_event_starting", "usb_connected", "docked",
        "brightness_high", "brightness_low",
        "location_entered", "location_exited",
        "bluetooth_connected", "bluetooth_disconnected"
    )
}
