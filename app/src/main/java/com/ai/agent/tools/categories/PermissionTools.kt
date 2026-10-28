package com.ai.agent.tools.categories

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.telephony.TelephonyManager
import android.util.Log
import com.ai.agent.accessibility.AgentAccessibilityService
import com.ai.agent.llm.LLMClient
import com.ai.agent.storage.AgentDatabase
import com.ai.agent.tools.ToolExecutor

/**
 * v4.0.30: Permission-based tools that use Android system APIs.
 *
 * Tools:
 * - readSms(limit) — read recent SMS messages (READ_SMS permission)
 * - vibrate(duration, pattern) — haptic feedback (VIBRATE permission)
 * - getPhoneState() — carrier, signal, SIM info (READ_PHONE_STATE permission)
 * - wakeLock(seconds) — keep CPU awake for long-running rules (WAKE_LOCK permission)
 *
 * All permissions are already declared in AndroidManifest.xml.
 */
object PermissionTools {
    private const val TAG = "PermissionTools"

    suspend fun execute(
        call: LLMClient.ToolCall,
        context: Context,
        @Suppress("UNUSED_PARAMETER") service: AgentAccessibilityService?,
        @Suppress("UNUSED_PARAMETER") database: AgentDatabase
    ): ToolExecutor.ToolResult {
        return when (call.name) {
            "readSms" -> readSms(call, context)
            "vibrate" -> vibrate(call, context)
            "getPhoneState" -> getPhoneState(context)
            "wakeLock" -> wakeLock(call, context)
            else -> ToolExecutor.ToolResult(false, "Unknown permission tool: ${call.name}")
        }
    }

    /**
     * Read recent SMS messages from the inbox.
     * Uses READ_SMS permission (already declared in manifest).
     */
    private fun readSms(call: LLMClient.ToolCall, context: Context): ToolExecutor.ToolResult {
        val limit = (call.args["limit"] as? Number)?.toInt() ?: 10
        return try {
            val uri = android.net.Uri.parse("content://sms/inbox")
            val projection = arrayOf("_id", "address", "date", "body")
            val sortOrder = "date DESC LIMIT $limit"
            val cursor = context.contentResolver.query(uri, projection, null, null, sortOrder)
                ?: return ToolExecutor.ToolResult(false, "Could not read SMS — cursor is null")

            val messages = StringBuilder("Recent SMS ($limit max):\n\n")
            val sdf = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
            var count = 0
            cursor.use {
                while (it.moveToNext() && count < limit) {
                    val address = it.getString(1) ?: "?"
                    val date = it.getLong(2)
                    val body = it.getString(3) ?: ""
                    messages.append("[${sdf.format(java.util.Date(date))}] $address:\n")
                    messages.append("  ${body.take(200)}\n\n")
                    count++
                }
            }
            if (count == 0) {
                ToolExecutor.ToolResult(true, "No SMS messages found in inbox.")
            } else {
                ToolExecutor.ToolResult(true, messages.toString())
            }
        } catch (e: SecurityException) {
            ToolExecutor.ToolResult(false, "Permission missing: Read SMS. Open Hub → Permissions to grant it.")
        } catch (e: Exception) {
            Log.e(TAG, "readSms failed", e)
            ToolExecutor.ToolResult(false, "Read SMS error: ${e.message}")
        }
    }

    /**
     * Vibrate the device for haptic feedback.
     * Uses VIBRATE permission (declared in manifest).
     * Useful for rules: direct:vibrate(duration=500) as a notification.
     */
    private fun vibrate(call: LLMClient.ToolCall, context: Context): ToolExecutor.ToolResult {
        val duration = (call.args["duration"] as? Number)?.toLong() ?: 500L
        val pattern = call.args["pattern"] as? String  // "short", "long", "double", "triple"
        return try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            } ?: return ToolExecutor.ToolResult(false, "Vibrator not available on this device")

            val patternTiming = when (pattern?.lowercase()) {
                "short" -> longArrayOf(0, 100)
                "long" -> longArrayOf(0, 1000)
                "double" -> longArrayOf(0, 200, 100, 200)
                "triple" -> longArrayOf(0, 150, 100, 150, 100, 150)
                else -> longArrayOf(0, duration)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(patternTiming, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(patternTiming, -1)
            }

            ToolExecutor.ToolResult(true, "Vibrated (${pattern ?: "$duration ms"})")
        } catch (e: Exception) {
            Log.e(TAG, "vibrate failed", e)
            ToolExecutor.ToolResult(false, "Vibrate error: ${e.message}")
        }
    }

    /**
     * Get phone state: carrier name, SIM status, network type, etc.
     * Uses READ_PHONE_STATE permission (already declared).
     */
    @Suppress("MissingPermission")
    private fun getPhoneState(context: Context): ToolExecutor.ToolResult {
        return try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                ?: return ToolExecutor.ToolResult(false, "TelephonyManager not available")

            val sb = StringBuilder("Phone State:\n")
            try { sb.append("  Carrier: ${tm.networkOperatorName}\n") } catch (_: Exception) {}
            try { sb.append("  SIM Operator: ${tm.simOperatorName}\n") } catch (_: Exception) {}
            try { sb.append("  SIM State: ${when (tm.simState) {
                0 -> "UNKNOWN"
                1 -> "ABSENT"
                2 -> "PIN_REQUIRED"
                3 -> "PUK_REQUIRED"
                4 -> "NETWORK_LOCKED"
                5 -> "READY"
                else -> "OTHER(${tm.simState})"
            }}\n") } catch (_: Exception) {}
            try { sb.append("  Network Country: ${tm.networkCountryIso}\n") } catch (_: Exception) {}
            try { sb.append("  SIM Country: ${tm.simCountryIso}\n") } catch (_: Exception) {}
            try { sb.append("  Phone Type: ${when (tm.phoneType) {
                0 -> "NONE"
                1 -> "GSM"
                2 -> "CDMA"
                3 -> "SIP"
                else -> "OTHER"
            }}\n") } catch (_: Exception) {}

            // Data state
            try {
                val dataState = when (tm.dataState) {
                    0 -> "DISCONNECTED"
                    1 -> "CONNECTING"
                    2 -> "CONNECTED"
                    3 -> "SUSPENDED"
                    else -> "OTHER"
                }
                sb.append("  Data: $dataState\n")
            } catch (_: Exception) {}

            // Signal strength (API 17+)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val signal = tm.signalStrength
                    if (signal != null) {
                        sb.append("  Signal Level: ${signal.level}/4\n")
                    }
                }
            } catch (_: Exception) {}

            sb.append("  Roaming: ${try { tm.isNetworkRoaming } catch (_: Exception) { "unknown" }}\n")

            ToolExecutor.ToolResult(true, sb.toString())
        } catch (e: SecurityException) {
            ToolExecutor.ToolResult(false, "Permission missing: Read Phone State. Open Hub → Permissions.")
        } catch (e: Exception) {
            Log.e(TAG, "getPhoneState failed", e)
            ToolExecutor.ToolResult(false, "Phone state error: ${e.message}")
        }
    }

    /**
     * Acquire a wake lock to keep the CPU awake.
     * Uses WAKE_LOCK permission (already declared).
     * Useful for rules that need to run for a long time without the phone sleeping.
     */
    private fun wakeLock(call: LLMClient.ToolCall, context: Context): ToolExecutor.ToolResult {
        val seconds = (call.args["seconds"] as? Number)?.toInt() ?: 60
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                ?: return ToolExecutor.ToolResult(false, "PowerManager not available")
            val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Aira:RuleWakeLock")
            wl.acquire(seconds * 1000L)
            ToolExecutor.ToolResult(true,
                "Wake lock acquired for $seconds seconds. CPU will stay awake even if screen is off. " +
                "Useful for long-running rules. Auto-releases after $seconds seconds.")
        } catch (e: Exception) {
            Log.e(TAG, "wakeLock failed", e)
            ToolExecutor.ToolResult(false, "Wake lock error: ${e.message}")
        }
    }
}
