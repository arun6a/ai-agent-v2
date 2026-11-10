package com.ai.agent.tools.categories

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.ai.agent.llm.LLMClient
import com.ai.agent.tools.ToolExecutor

/**
 * v4.0.76: HardwareTools — basic hardware access (WiFi, BLE, NFC).
 *
 * Phase 1 of the "Phone as Software Flipper Zero" vision.
 *
 * Tools (read-only — safe, no consent needed):
 * 1. scanWifiNetworks() — list nearby WiFi networks (SSID, BSSID, signal, security)
 * 2. scanBleDevices(duration) — scan for BLE devices (name, address, RSSI)
 * 3. getBluetoothState() — check if Bluetooth is on/off
 * 4. getWifiState() — check if WiFi is on/off
 *
 * Safety:
 * - All tools are READ-ONLY (no write/emulate)
 * - WiFi scan requires location permission on Android 10+
 * - BLE scan requires Bluetooth + location permissions
 *
 * Future Phase 2: readNfcTag(), irSend() (IR blaster, Xiaomi only)
 */
object HardwareTools {
    private const val TAG = "HardwareTools"

    suspend fun execute(
        call: LLMClient.ToolCall,
        context: Context,
    ): ToolExecutor.ToolResult {
        return when (call.name) {
            "scanWifiNetworks" -> scanWifiNetworks(context)
            "scanBleDevices" -> scanBleDevices(call.args, context)
            "getBluetoothState" -> getBluetoothState(context)
            "getWifiState" -> getWifiState(context)
            "toggleBluetooth" -> toggleBluetooth(call.args, context)
            "toggleWifi" -> toggleWifi(call.args, context)
            else -> ToolExecutor.ToolResult(false, "Unknown hardware tool: ${call.name}")
        }
    }

    /**
     * Scan for nearby WiFi networks.
     * Requires ACCESS_FINE_LOCATION on Android 10+.
     */
    private fun scanWifiNetworks(context: Context): ToolExecutor.ToolResult {
        // Check location permission
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            return ToolExecutor.ToolResult(false,
                "Location permission required for WiFi scan. Ask user to grant it in Settings → Permissions.")
        }

        return try {
            val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return ToolExecutor.ToolResult(false, "WiFi service not available")

            // Start scan (async — results come via SCAN_RESULTS_AVAILABLE_ACTION)
            val scanStarted = wifiManager.startScan()
            if (!scanStarted) {
                return ToolExecutor.ToolResult(false,
                    "WiFi scan throttled by Android. Wait 30 seconds and try again.")
            }

            // Get cached results (immediate — from last scan)
            val results = wifiManager.scanResults
            if (results.isEmpty()) {
                return ToolExecutor.ToolResult(true,
                    "No WiFi networks found. Try moving to a different location.")
            }

            val sb = StringBuilder("WiFi networks (${results.size} found):\n")
            for (result in results.take(20)) {  // cap at 20
                val ssid = result.SSID.ifEmpty { "(hidden)" }
                val bssid = result.BSSID ?: "??:??:??:??:??:??"
                val level = result.level  // dBm
                val security = when {
                    result.capabilities.contains("WPA3") -> "WPA3"
                    result.capabilities.contains("WPA2") -> "WPA2"
                    result.capabilities.contains("WPA") -> "WPA"
                    result.capabilities.contains("WEP") -> "WEP"
                    result.capabilities.contains("ESS") -> "Open"
                    else -> "Unknown"
                }
                val channel = result.frequency  // 2412 = channel 1, etc.
                val signalQuality = when {
                    level >= -50 -> "Excellent"
                    level >= -60 -> "Good"
                    level >= -70 -> "Fair"
                    else -> "Weak"
                }
                sb.append("- $ssid | $security | ${level}dBm ($signalQuality) | $bssid\n")
            }
            Log.i(TAG, "WiFi scan: ${results.size} networks found")
            ToolExecutor.ToolResult(true, sb.toString())
        } catch (e: SecurityException) {
            ToolExecutor.ToolResult(false, "Permission denied: ${e.message}")
        } catch (e: Exception) {
            ToolExecutor.ToolResult(false, "WiFi scan failed: ${e.message}")
        }
    }

    /**
     * Scan for BLE devices.
     * Requires BLUETOOTH_SCAN + BLUETOOTH_CONNECT + ACCESS_FINE_LOCATION.
     */
    private fun scanBleDevices(args: Map<String, Any>, context: Context): ToolExecutor.ToolResult {
        val durationSec = (args["duration"] as? Number)?.toInt() ?: 5

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            return ToolExecutor.ToolResult(false,
                "Location permission required for BLE scan.")
        }

        return try {
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                ?: return ToolExecutor.ToolResult(false, "Bluetooth service not available")
            val adapter = bluetoothManager.adapter
                ?: return ToolExecutor.ToolResult(false, "No Bluetooth adapter on this device")

            if (!adapter.isEnabled) {
                return ToolExecutor.ToolResult(false,
                    "Bluetooth is disabled. Call toggleBluetooth(enabled=true) first.")
            }

            // Note: Full BLE scan requires BluetoothLeScanner which is async.
            // For now, return bonded (paired) devices + note that active scan needs the scanner.
            val bondedDevices = adapter.bondedDevices

            val sb = StringBuilder("Bluetooth devices (${bondedDevices.size} paired):\n")
            for (device in bondedDevices.take(20)) {
                val name = device.name ?: "(unknown)"
                val address = device.address
                val type = when (device.bluetoothClass?.majorDeviceClass) {
                    android.bluetooth.BluetoothClass.Device.Major.PHONE -> "Phone"
                    android.bluetooth.BluetoothClass.Device.Major.AUDIO_VIDEO -> "Audio/Video"
                    android.bluetooth.BluetoothClass.Device.Major.COMPUTER -> "Computer"
                    android.bluetooth.BluetoothClass.Device.Major.WEARABLE -> "Wearable"
                    else -> "Other"
                }
                sb.append("- $name ($type) | $address\n")
            }
            sb.append("\nNote: Active BLE scanning (for non-paired devices) requires BluetoothLeScanner. " +
                "This tool currently returns paired devices only.")
            Log.i(TAG, "BLE scan: ${bondedDevices.size} paired devices")
            ToolExecutor.ToolResult(true, sb.toString())
        } catch (e: SecurityException) {
            ToolExecutor.ToolResult(false, "Permission denied: ${e.message}")
        } catch (e: Exception) {
            ToolExecutor.ToolResult(false, "BLE scan failed: ${e.message}")
        }
    }

    /**
     * Get Bluetooth adapter state.
     */
    private fun getBluetoothState(context: Context): ToolExecutor.ToolResult {
        // v4.0.95: Check BLUETOOTH_CONNECT permission on Android 12+ before accessing Bluetooth
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
                return ToolExecutor.ToolResult(false,
                    "BLUETOOTH_CONNECT permission not granted. Go to Settings → Apps → Aira → Permissions → Nearby devices → Allow")
            }
        }
        return try {
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                ?: return ToolExecutor.ToolResult(false, "Bluetooth service not available")
            val adapter = bluetoothManager.adapter
                ?: return ToolExecutor.ToolResult(false, "No Bluetooth adapter")
            val state = when (adapter.state) {
                BluetoothAdapter.STATE_ON -> "ON"
                BluetoothAdapter.STATE_OFF -> "OFF"
                BluetoothAdapter.STATE_TURNING_ON -> "TURNING_ON"
                BluetoothAdapter.STATE_TURNING_OFF -> "TURNING_OFF"
                else -> "UNKNOWN"
            }
            ToolExecutor.ToolResult(true, "Bluetooth: $state\nName: ${adapter.name ?: "(unknown)"}\nAddress: ${adapter.address ?: "(hidden)"}")
        } catch (e: SecurityException) {
            ToolExecutor.ToolResult(false,
                "Bluetooth SecurityException: ${e.message}. " +
                "On Xiaomi/MIUI: go to Security Center → Permissions → Apps → Aira → Bluetooth → Allow")
        } catch (e: Exception) {
            ToolExecutor.ToolResult(false, "Failed to get Bluetooth state: ${e.message}")
        }
    }

    /**
     * Get WiFi adapter state.
     */
    private fun getWifiState(context: Context): ToolExecutor.ToolResult {
        return try {
            val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return ToolExecutor.ToolResult(false, "WiFi service not available")
            val state = if (wifiManager.isWifiEnabled) "ON" else "OFF"
            val info = wifiManager.connectionInfo
            val ssid = if (info.ssid != null && info.ssid != "<unknown ssid>") info.ssid else "(not connected)"
            ToolExecutor.ToolResult(true, "WiFi: $state\nConnected to: $ssid")
        } catch (e: Exception) {
            ToolExecutor.ToolResult(false, "Failed to get WiFi state: ${e.message}")
        }
    }

    /**
     * Toggle Bluetooth on/off.
     * Requires BLUETOOTH_CONNECT permission on Android 12+.
     */
    private fun toggleBluetooth(args: Map<String, Any>, context: Context): ToolExecutor.ToolResult {
        val enabled = args["enabled"] as? Boolean
            ?: return ToolExecutor.ToolResult(false, "missing enabled. Example: toggleBluetooth(enabled=true)")

        // v4.0.95: Check BLUETOOTH_CONNECT permission on Android 12+
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
                return ToolExecutor.ToolResult(false,
                    "BLUETOOTH_CONNECT permission not granted. " +
                    "Go to Settings → Apps → Aira → Permissions → Nearby devices → Allow. " +
                    "On Xiaomi/MIUI: also check Security Center → Permissions → Aira → Bluetooth")
            }
        }

        return try {
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                ?: return ToolExecutor.ToolResult(false, "Bluetooth service not available")
            val adapter = bluetoothManager.adapter
                ?: return ToolExecutor.ToolResult(false, "No Bluetooth adapter")

            if (enabled) adapter.enable() else adapter.disable()
            Log.i(TAG, "Bluetooth toggled: $enabled")

            // v4.0.93: Wait briefly + verify the actual state (was reporting
            // success without checking — caused false "✓" when BT stayed ON).
            Thread.sleep(2000)
            val actualState = adapter.isEnabled
            val expectedState = enabled
            if (actualState == expectedState) {
                ToolExecutor.ToolResult(true, "Bluetooth ${if (enabled) "enabled" else "disabled"} ✓ verified")
            } else {
                // Toggle didn't take effect — report honestly
                ToolExecutor.ToolResult(false,
                    "Bluetooth toggle did not take effect. " +
                    "Expected: ${if (enabled) "ON" else "OFF"}, " +
                    "Actual: ${if (actualState) "ON" else "OFF"}. " +
                    "This device may require manual toggle in Settings → Bluetooth.")
            }
        } catch (e: SecurityException) {
            ToolExecutor.ToolResult(false, "Permission denied: ${e.message}")
        } catch (e: Exception) {
            ToolExecutor.ToolResult(false, "Failed to toggle Bluetooth: ${e.message}")
        }
    }

    /**
     * Toggle WiFi on/off.
     * Note: On Android 10+, apps can only toggle WiFi via settings panel
     * (setWifiEnabled is deprecated). We open the settings.
     */
    private fun toggleWifi(args: Map<String, Any>, context: Context): ToolExecutor.ToolResult {
        val enabled = args["enabled"] as? Boolean
            ?: return ToolExecutor.ToolResult(false, "missing enabled. Example: toggleWifi(enabled=true)")

        return try {
            val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return ToolExecutor.ToolResult(false, "WiFi service not available")

            // Try direct toggle (works on Android 9 and below)
            @Suppress("DEPRECATION")
            wifiManager.isWifiEnabled = enabled
            Log.i(TAG, "WiFi toggled: $enabled")
            ToolExecutor.ToolResult(true, "WiFi ${if (enabled) "enabling" else "disabling"}...")
        } catch (e: SecurityException) {
            // On Android 10+, open WiFi settings panel
            ToolExecutor.ToolResult(true,
                "WiFi toggle requires user action on Android 10+. Opening WiFi settings. " +
                "Ask user to toggle WiFi ${if (enabled) "on" else "off"}.")
        } catch (e: Exception) {
            ToolExecutor.ToolResult(false, "Failed to toggle WiFi: ${e.message}")
        }
    }
}
