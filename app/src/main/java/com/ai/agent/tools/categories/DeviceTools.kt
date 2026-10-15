package com.ai.agent.tools.categories

import android.content.Context
import com.ai.agent.accessibility.AgentAccessibilityService
import com.ai.agent.llm.LLMClient
import com.ai.agent.storage.AgentDatabase
import com.ai.agent.tools.ToolExecutor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Device tools category — extracted from ToolExecutor in v3.3.12.
 *
 * Tools: getBatteryLevel, getCurrentTime, getBluetoothState, getNetworkInfo,
 * getDeviceInfo, pingHost, getVolume, setVolume, setBrightness,
 * toggleFlashlight, toggleWifi, speak.
 *
 * No behavior changes — code moved verbatim from ToolExecutor.kt.
 */
object DeviceTools {

    suspend fun execute(
        call: LLMClient.ToolCall,
        context: Context,
        @Suppress("UNUSED_PARAMETER") service: AgentAccessibilityService?,
        @Suppress("UNUSED_PARAMETER") database: AgentDatabase
    ): ToolExecutor.ToolResult {
        return when (call.name) {
            "getBatteryLevel" -> {
                val bm = context.getSystemService(android.content.Context.BATTERY_SERVICE) as android.os.BatteryManager
                val level = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
                ToolExecutor.ToolResult(true, "Battery level: $level%")
            }
            "getCurrentTime" -> {
                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss EEEE", java.util.Locale.getDefault())
                ToolExecutor.ToolResult(true, "Current time: ${sdf.format(java.util.Date())}")
            }
            "getBluetoothState" -> {
                try {
                    val bm = context.getSystemService(android.content.Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
                    val adapter = bm.adapter
                    if (adapter != null) {
                        ToolExecutor.ToolResult(true, "Bluetooth: ${if (adapter.isEnabled) "ON" else "OFF"}\nName: ${adapter.name ?: "N/A"}\nAddress: ${adapter.address}")
                    } else {
                        ToolExecutor.ToolResult(false, "Bluetooth not available")
                    }
                } catch (e: Exception) { ToolExecutor.ToolResult(false, "Bluetooth error: ${e.message}") }
            }
            "getNetworkInfo" -> {
                try {
                    val wifiManager = context.getSystemService(android.content.Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                    val connManager = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                    val info = connManager.activeNetworkInfo
                    val wifiOn = wifiManager.isWifiEnabled
                    val connected = info?.isConnected == true
                    val type = info?.typeName ?: "none"
                    ToolExecutor.ToolResult(true, "WiFi: ${if (wifiOn) "ON" else "OFF"}, Connected: $connected, Type: $type")
                } catch (e: Exception) {
                    ToolExecutor.ToolResult(false, "Error: ${e.message}")
                }
            }
            "getDeviceInfo" -> {
                val info = buildString {
                    append("Model: ${android.os.Build.MODEL}\n")
                    append("Brand: ${android.os.Build.BRAND}\n")
                    append("Manufacturer: ${android.os.Build.MANUFACTURER}\n")
                    append("Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})\n")
                    append("Device: ${android.os.Build.DEVICE}\n")
                    append("Board: ${android.os.Build.BOARD}\n")
                    val bm = context.getSystemService(android.content.Context.BATTERY_SERVICE) as android.os.BatteryManager
                    append("Battery: ${bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)}%\n")
                    val am = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                    val mi = android.app.ActivityManager.MemoryInfo()
                    am.getMemoryInfo(mi)
                    append("RAM: ${mi.availMem / 1048576}MB / ${mi.totalMem / 1048576}MB\n")
                    append("Storage: ${android.os.Environment.getDataDirectory().let { java.io.File(it.path).freeSpace / 1048576 }}MB free\n")
                    val wm = context.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager
                    val display = wm.defaultDisplay
                    val size = android.graphics.Point()
                    display.getRealSize(size)
                    append("Screen: ${size.x}x${size.y}\n")
                    append("Root: ${if (java.io.File("/system/bin/su").exists()) "YES" else "NO"}\n")
                }
                ToolExecutor.ToolResult(true, info)
            }
            "pingHost" -> {
                val host = call.args["host"] as? String ?: return ToolExecutor.ToolResult(false, "missing host")
                try {
                    val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "ping -c 3 $host"))
                    val output = process.inputStream.bufferedReader().readText()
                    process.waitFor()
                    ToolExecutor.ToolResult(true, output)
                } catch (e: Exception) { ToolExecutor.ToolResult(false, "Ping error: ${e.message}") }
            }
            "getVolume" -> {
                val audioManager = context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
                val vol = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                val max = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                ToolExecutor.ToolResult(true, "Volume: $vol/$max")
            }
            "setVolume" -> {
                val level = (call.args["level"] as? Number)?.toInt() ?: return ToolExecutor.ToolResult(false, "missing level (0-15)")
                val audioManager = context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
                audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, level, 0)
                ToolExecutor.ToolResult(true, "Volume set to $level")
            }
            "setBrightness" -> {
                // v2.5.1: Accept level as Number OR String (direct rules pass strings).
                // Accept 0-100 (percent) OR 0-255 (raw). Auto-detect: if value > 100, treat as raw.
                val rawLevel = call.args["level"] ?: call.args["value"] ?: call.args["brightness"]
                val level: Int = when (rawLevel) {
                    is Number -> rawLevel.toInt()
                    is String -> rawLevel.toIntOrNull() ?: return ToolExecutor.ToolResult(false, "invalid level: $rawLevel (must be number)")
                    else -> return ToolExecutor.ToolResult(false, "missing level (0-100 percent or 0-255 raw)")
                }
                // v2.5.1: If user passes 0-100, treat as percentage → convert to 0-255.
                val finalLevel = if (level <= 100) (level * 255 / 100) else level
                try {
                    // v2.5.1: Set brightness mode to MANUAL first (otherwise setting value is ignored)
                    android.provider.Settings.System.putInt(
                        context.contentResolver,
                        android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE,
                        android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                    )
                    android.provider.Settings.System.putInt(
                        context.contentResolver,
                        android.provider.Settings.System.SCREEN_BRIGHTNESS,
                        finalLevel
                    )
                    ToolExecutor.ToolResult(true, "Brightness set to $finalLevel (user requested $level${if (level <= 100) "%" else ""})")
                } catch (e: SecurityException) {
                    ToolExecutor.ToolResult(false, "Cannot change brightness — need WRITE_SETTINGS permission. Grant in Settings → Apps → Aira → Modify system settings.")
                } catch (e: Exception) {
                    ToolExecutor.ToolResult(false, "Cannot change brightness: ${e.message}")
                }
            }
            "toggleFlashlight" -> {
                try {
                    val cameraManager = context.getSystemService(android.content.Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
                    val cameraId = cameraManager.cameraIdList[0]
                    cameraManager.setTorchMode(cameraId, true)
                    delay(2000)
                    cameraManager.setTorchMode(cameraId, false)
                    ToolExecutor.ToolResult(true, "Flashlight toggled")
                } catch (e: Exception) {
                    ToolExecutor.ToolResult(false, "Flashlight error: ${e.message}")
                }
            }
            "toggleWifi" -> {
                // Note: requires CHANGE_WIFI_STATE permission, may not work on Android 10+
                try {
                    val wifiManager = context.getSystemService(android.content.Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                    val enabled = call.args["enabled"] as? Boolean ?: !wifiManager.isWifiEnabled
                    @Suppress("DEPRECATION")
                    wifiManager.setWifiEnabled(enabled)
                    ToolExecutor.ToolResult(true, "WiFi ${if (enabled) "enabled" else "disabled"}")
                } catch (e: Exception) {
                    ToolExecutor.ToolResult(false, "Cannot toggle WiFi: ${e.message}")
                }
            }
            // v3.0.0: speak tool — for rules to use TTS (Text-to-Speech)
            "speak" -> {
                val text = call.args["text"] as? String ?: call.args["message"] as? String
                    ?: return ToolExecutor.ToolResult(false, "missing text to speak")
                try {
                    val ttsReady = CompletableDeferred<Boolean>()
                    val ttsInstance = android.speech.tts.TextToSpeech(context) { status ->
                        ttsReady.complete(status == android.speech.tts.TextToSpeech.SUCCESS)
                    }
                    // Wait for TTS to initialize (max 2 seconds)
                    val ready = withTimeoutOrNull(2000) { ttsReady.await() }
                    if (ready == true) {
                        ttsInstance.language = java.util.Locale("en", "IN")
                        ttsInstance.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "rule_speak_${System.currentTimeMillis()}")
                        delay(500)  // Give TTS a moment to start speaking
                        ToolExecutor.ToolResult(true, "Spoken: $text")
                    } else {
                        ToolExecutor.ToolResult(false, "TTS failed to initialize")
                    }
                } catch (e: Exception) {
                    ToolExecutor.ToolResult(false, "TTS error: ${e.message}")
                }
            }
            else -> ToolExecutor.ToolResult(false, "Unknown device tool: ${call.name}")
        }
    }
}
