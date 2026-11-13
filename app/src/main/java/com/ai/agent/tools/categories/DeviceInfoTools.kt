package com.ai.agent.tools.categories

import android.content.Context
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.StatFs
import android.util.Log
import com.ai.agent.accessibility.AgentAccessibilityService
import com.ai.agent.llm.LLMClient
import com.ai.agent.storage.AgentDatabase
import com.ai.agent.tools.ToolExecutor
import java.io.File

/**
 * v4.0.30: Device info tools — 10 new read-only + action tools.
 *
 * Tools:
 * - getBrightness() — current screen brightness level (0-255 or 0-100%)
 * - getFlashlightState() — is the flashlight currently on?
 * - getDeviceUptime() — how long since last boot
 * - getMemoryInfo() — RAM total/available/used
 * - getStorageInfo() — disk space total/available/used
 * - getBatteryHealth() — battery health + temperature + technology
 * - getScreenOrientation() — portrait/landscape
 * - isCharging() — is the phone currently charging?
 * - getRingtoneMode() — silent/vibrate/normal
 * - setRingtoneMode(mode) — set ringer mode (silent/vibrate/normal)
 *
 * None of these need accessibility — they use system APIs directly.
 */
object DeviceInfoTools {
    private const val TAG = "DeviceInfoTools"

    suspend fun execute(
        call: LLMClient.ToolCall,
        context: Context,
        @Suppress("UNUSED_PARAMETER") service: AgentAccessibilityService?,
        @Suppress("UNUSED_PARAMETER") database: AgentDatabase
    ): ToolExecutor.ToolResult {
        return when (call.name) {
            "getBrightness" -> getBrightness(context)
            "getFlashlightState" -> getFlashlightState(context)
            "getDeviceUptime" -> getDeviceUptime()
            "getMemoryInfo" -> getMemoryInfo(context)
            "getStorageInfo" -> getStorageInfo()
            "getBatteryHealth" -> getBatteryHealth(context)
            "getScreenOrientation" -> getScreenOrientation(context)
            "isCharging" -> isCharging(context)
            "getRingtoneMode" -> getRingtoneMode(context)
            "setRingtoneMode" -> setRingtoneMode(call, context)
            else -> ToolExecutor.ToolResult(false, "Unknown device info tool: ${call.name}")
        }
    }

    private fun getBrightness(context: Context): ToolExecutor.ToolResult {
        return try {
            val mode = android.provider.Settings.System.getInt(
                context.contentResolver,
                android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE,
                android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            val isAuto = mode == android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
            val brightness = android.provider.Settings.System.getInt(
                context.contentResolver,
                android.provider.Settings.System.SCREEN_BRIGHTNESS,
                128
            )
            val percent = (brightness * 100 / 255)
            ToolExecutor.ToolResult(true,
                "Screen Brightness:\n" +
                "  Level: $brightness/255 ($percent%)\n" +
                "  Mode: ${if (isAuto) "AUTO" else "MANUAL"}")
        } catch (e: Exception) {
            ToolExecutor.ToolResult(false, "Could not read brightness: ${e.message}")
        }
    }

    private fun getFlashlightState(context: Context): ToolExecutor.ToolResult {
        return try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
                ?: return ToolExecutor.ToolResult(false, "CameraManager not available")
            // We can't query torch state directly without a camera ID + session.
            // Best effort: check if any camera has a torch + report availability.
            val cameraIds = cm.cameraIdList
            var torchAvailable = false
            for (id in cameraIds) {
                try {
                    val characteristics = cm.getCameraCharacteristics(id)
                    val hasTorch = characteristics.get(
                        android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE
                    ) == true
                    if (hasTorch) {
                        torchAvailable = true
                        break
                    }
                } catch (_: Exception) {}
            }
            ToolExecutor.ToolResult(true,
                "Flashlight:\n" +
                "  Torch available: $torchAvailable\n" +
                "  Cameras found: ${cameraIds.size}\n" +
                "  (Use toggleFlashlight() to turn on/off)")
        } catch (e: Exception) {
            ToolExecutor.ToolResult(false, "Flashlight check failed: ${e.message}")
        }
    }

    private fun getDeviceUptime(): ToolExecutor.ToolResult {
        return try {
            val uptimeMs = System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime() +
                android.os.SystemClock.elapsedRealtime()
            val elapsedRealtime = android.os.SystemClock.elapsedRealtime()
            val days = (elapsedRealtime / 86_400_000).toInt()
            val hours = ((elapsedRealtime % 86_400_000) / 3_600_000).toInt()
            val mins = ((elapsedRealtime % 3_600_000) / 60_000).toInt()
            ToolExecutor.ToolResult(true,
                "Device Uptime (since last boot):\n" +
                "  $days days, $hours hours, $mins minutes\n" +
                "  Total ms: $elapsedRealtime")
        } catch (e: Exception) {
            ToolExecutor.ToolResult(false, "Uptime check failed: ${e.message}")
        }
    }

    private fun getMemoryInfo(context: Context): ToolExecutor.ToolResult {
        return try {
            val mi = android.app.ActivityManager.MemoryInfo()
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            am.getMemoryInfo(mi)
            val totalMB = mi.totalMem / 1_048_576
            val availMB = mi.availMem / 1_048_576
            val usedMB = totalMB - availMB
            val thresholdMB = mi.threshold / 1_048_576
            val lowMem = mi.lowMemory
            ToolExecutor.ToolResult(true,
                "Memory (RAM):\n" +
                "  Total: ${totalMB} MB\n" +
                "  Used: ${usedMB} MB\n" +
                "  Available: ${availMB} MB\n" +
                "  Low memory threshold: $thresholdMB MB\n" +
                "  Currently low memory: $lowMem")
        } catch (e: Exception) {
            ToolExecutor.ToolResult(false, "Memory info failed: ${e.message}")
        }
    }

    private fun getStorageInfo(): ToolExecutor.ToolResult {
        return try {
            val stat = StatFs(android.os.Environment.getDataDirectory().path)
            val totalGB = stat.totalBytes / 1_073_741_824.0
            val availGB = stat.availableBytes / 1_073_741_824.0
            val usedGB = totalGB - availGB
            // External storage (SD card if present)
            val extStat = StatFs(android.os.Environment.getExternalStorageDirectory().path)
            val extTotalGB = extStat.totalBytes / 1_073_741_824.0
            val extAvailGB = extStat.availableBytes / 1_073_741_824.0
            ToolExecutor.ToolResult(true,
                "Storage:\n" +
                "  Internal:\n" +
                "    Total: ${"%.1f".format(totalGB)} GB\n" +
                "    Used: ${"%.1f".format(usedGB)} GB\n" +
                "    Available: ${"%.1f".format(availGB)} GB\n" +
                "  External (${android.os.Environment.getExternalStorageDirectory().path}):\n" +
                "    Total: ${"%.1f".format(extTotalGB)} GB\n" +
                "    Available: ${"%.1f".format(extAvailGB)} GB")
        } catch (e: Exception) {
            ToolExecutor.ToolResult(false, "Storage info failed: ${e.message}")
        }
    }

    private fun getBatteryHealth(context: Context): ToolExecutor.ToolResult {
        return try {
            val filter = android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
            val battery = context.registerReceiver(null, filter)
                ?: return ToolExecutor.ToolResult(false, "Could not get battery info")
            val level = battery.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
            val scale = battery.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
            val percent = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
            val temp = battery.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, -1) / 10.0
            val voltage = battery.getIntExtra(android.os.BatteryManager.EXTRA_VOLTAGE, -1) / 1000.0
            val healthCode = battery.getIntExtra(android.os.BatteryManager.EXTRA_HEALTH, -1)
            val health = when (healthCode) {
                1 -> "UNKNOWN"
                2 -> "GOOD"
                3 -> "OVERHEAT"
                4 -> "DEAD"
                5 -> "OVER_VOLTAGE"
                6 -> "UNSPECIFIED_FAILURE"
                7 -> "COLD"
                else -> "UNKNOWN($healthCode)"
            }
            val tech = battery.getStringExtra(android.os.BatteryManager.EXTRA_TECHNOLOGY) ?: "unknown"
            val status = battery.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
            val statusStr = when (status) {
                1 -> "UNKNOWN"
                2 -> "CHARGING"
                3 -> "DISCHARGING"
                4 -> "NOT_CHARGING"
                5 -> "FULL"
                else -> "UNKNOWN($status)"
            }
            ToolExecutor.ToolResult(true,
                "Battery Health:\n" +
                "  Level: $percent%\n" +
                "  Health: $health\n" +
                "  Status: $statusStr\n" +
                "  Temperature: $temp °C\n" +
                "  Voltage: $voltage V\n" +
                "  Technology: $tech")
        } catch (e: Exception) {
            ToolExecutor.ToolResult(false, "Battery health failed: ${e.message}")
        }
    }

    private fun getScreenOrientation(context: Context): ToolExecutor.ToolResult {
        return try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
            val rotation = wm.defaultDisplay.rotation
            val orientation = when (rotation) {
                0 -> "PORTRAIT (0°)"
                1 -> "LANDSCAPE (90°)"
                2 -> "REVERSE_PORTRAIT (180°)"
                3 -> "REVERSE_LANDSCAPE (270°)"
                else -> "UNKNOWN($rotation)"
            }
            ToolExecutor.ToolResult(true,
                "Screen Orientation:\n" +
                "  Rotation: $orientation\n" +
                "  Raw value: $rotation")
        } catch (e: Exception) {
            ToolExecutor.ToolResult(false, "Orientation check failed: ${e.message}")
        }
    }

    private fun isCharging(context: Context): ToolExecutor.ToolResult {
        return try {
            val filter = android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
            val battery = context.registerReceiver(null, filter)
                ?: return ToolExecutor.ToolResult(false, "Could not get battery info")
            val status = battery.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
            val isCharging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                status == android.os.BatteryManager.BATTERY_STATUS_FULL
            val plugged = battery.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, -1)
            val source = when (plugged) {
                1 -> "AC (charger)"
                2 -> "USB"
                4 -> "WIRELESS"
                else -> if (isCharging) "UNKNOWN" else "NOT_PLUGGED"
            }
            ToolExecutor.ToolResult(true,
                "Charging: ${if (isCharging) "YES" else "NO"}\n" +
                "  Power source: $source\n" +
                "  Status: $status")
        } catch (e: Exception) {
            ToolExecutor.ToolResult(false, "Charging check failed: ${e.message}")
        }
    }

    private fun getRingtoneMode(context: Context): ToolExecutor.ToolResult {
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            val mode = when (am.ringerMode) {
                0 -> "SILENT"
                1 -> "VIBRATE"
                2 -> "NORMAL"
                else -> "UNKNOWN"
            }
            ToolExecutor.ToolResult(true,
                "Ringtone Mode: $mode\n" +
                "  Media Volume: ${am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)}/${am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)}\n" +
                "  Ring Volume: ${am.getStreamVolume(android.media.AudioManager.STREAM_RING)}/${am.getStreamMaxVolume(android.media.AudioManager.STREAM_RING)}")
        } catch (e: Exception) {
            ToolExecutor.ToolResult(false, "Ringtone mode check failed: ${e.message}")
        }
    }

    private fun setRingtoneMode(call: LLMClient.ToolCall, context: Context): ToolExecutor.ToolResult {
        val mode = call.args["mode"] as? String
            ?: return ToolExecutor.ToolResult(false, "missing mode. Use 'silent', 'vibrate', or 'normal'")
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            val ringerMode = when (mode.lowercase()) {
                "silent" -> android.media.AudioManager.RINGER_MODE_SILENT
                "vibrate" -> android.media.AudioManager.RINGER_MODE_VIBRATE
                "normal" -> android.media.AudioManager.RINGER_MODE_NORMAL
                else -> return ToolExecutor.ToolResult(false, "invalid mode. Use 'silent', 'vibrate', or 'normal'")
            }
            am.ringerMode = ringerMode
            ToolExecutor.ToolResult(true, "Ringtone mode set to: ${mode.uppercase()}")
        } catch (e: Exception) {
            ToolExecutor.ToolResult(false, "Set ringtone mode failed: ${e.message}")
        }
    }
}
