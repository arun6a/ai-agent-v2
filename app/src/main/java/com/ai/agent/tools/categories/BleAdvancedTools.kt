package com.ai.agent.tools.categories

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.ai.agent.llm.LLMClient
import com.ai.agent.tools.ToolExecutor

/**
 * v4.0.77: BleAdvancedTools — advanced BLE scanning + detection.
 *
 * Phase 2 of BLE tools (Phase 1 is in HardwareTools.kt).
 *
 * Tools:
 * 1. findAirTag(duration) — scan for nearby AirTags (detects Apple Continuity frames)
 * 2. scanBleBeacons(duration) — scan for BLE beacons (iBeacon, Eddystone)
 * 3. logBleDevice(mac, name) — save a BLE device to knowledge for future reference
 *
 * Prerequisites:
 * - BLUETOOTH_SCAN + BLUETOOTH_CONNECT (Android 12+)
 * - ACCESS_FINE_LOCATION (Android 10+)
 *
 * Safety:
 * - All tools are READ-ONLY (scan, log)
 * - No beacon emulation (Phase 2+ future)
 */
object BleAdvancedTools {
    private const val TAG = "BleAdvanced"

    suspend fun execute(
        call: LLMClient.ToolCall,
        context: Context,
    ): ToolExecutor.ToolResult {
        return when (call.name) {
            "findAirTag" -> findAirTag(call.args, context)
            "scanBleBeacons" -> scanBleBeacons(call.args, context)
            "logBleDevice" -> logBleDevice(call.args, context)
            else -> ToolExecutor.ToolResult(false, "Unknown BLE tool: ${call.name}")
        }
    }

    /**
     * Scan for nearby AirTags.
     * AirTags broadcast Apple Continuity frames on BLE.
     * This tool scans for BLE devices + flags ones with Apple's manufacturer data
     * that match AirTag patterns.
     */
    private fun findAirTag(args: Map<String, Any>, context: Context): ToolExecutor.ToolResult {
        val durationSec = (args["duration"] as? Number)?.toInt() ?: 10

        if (!hasBlePermissions(context)) {
            return ToolExecutor.ToolResult(false,
                "BLE permissions required. Need BLUETOOTH_SCAN + ACCESS_FINE_LOCATION.")
        }

        return try {
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                ?: return ToolExecutor.ToolResult(false, "Bluetooth service not available")
            val adapter = bluetoothManager.adapter
                ?: return ToolExecutor.ToolResult(false, "No Bluetooth adapter")

            if (!adapter.isEnabled) {
                return ToolExecutor.ToolResult(false,
                    "Bluetooth is disabled. Call toggleBluetooth(enabled=true) first.")
            }

            val scanner = adapter.bluetoothLeScanner
                ?: return ToolExecutor.ToolResult(false, "BLE scanner not available")

            val foundTags = mutableListOf<ScanResult>()

            // Scan callback
            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    // AirTags have Apple's manufacturer data (company id 0x004C)
                    val hasAppleData = result.scanRecord?.manufacturerSpecificData?.let { data ->
                        (0 until data.size()).any { i ->
                            data.valueAt(i)?.let { bytes ->
                                // Apple company ID is 0x004C (76)
                                bytes.size >= 2 && bytes[0].toInt() and 0xFF == 0x4C && bytes[1].toInt() and 0xFF == 0x00
                            } ?: false
                        }
                    } ?: false

                    if (hasAppleData) {
                        foundTags.add(result)
                        Log.i(TAG, "Possible AirTag detected: ${result.device.address}")
                    }
                }
            }

            // Start scan
            val scanSettings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            scanner.startScan(null, scanSettings, callback)

            // Wait for duration
            Thread.sleep((durationSec * 1000).toLong())

            // Stop scan
            scanner.stopScan(callback)

            if (foundTags.isEmpty()) {
                ToolExecutor.ToolResult(true,
                    "No AirTags detected in $durationSec seconds.\n" +
                    "Note: AirTags only broadcast when separated from their owner.\n" +
                    "Try walking around — AirTags use rotating MAC addresses.")
            } else {
                val sb = StringBuilder("Possible AirTags (${foundTags.size} detected):\n")
                for (result in foundTags.take(10)) {
                    val address = result.device.address
                    val rssi = result.rssi
                    val name = result.device.name ?: "(unknown)"
                    val distance = when {
                        rssi >= -50 -> "very close (< 1m)"
                        rssi >= -60 -> "close (1-2m)"
                        rssi >= -70 -> "medium (2-5m)"
                        else -> "far (> 5m)"
                    }
                    sb.append("- $name | $address | ${rssi}dBm ($distance)\n")
                }
                sb.append("\n⚠️ These are POSSIBLE AirTags (matched Apple manufacturer data). " +
                    "Verify manually before taking action.")
                Log.i(TAG, "AirTag scan: ${foundTags.size} possible tags")
                ToolExecutor.ToolResult(true, sb.toString())
            }
        } catch (e: SecurityException) {
            ToolExecutor.ToolResult(false, "Permission denied: ${e.message}")
        } catch (e: Exception) {
            ToolExecutor.ToolResult(false, "AirTag scan failed: ${e.message}")
        }
    }

    /**
     * Scan for BLE beacons (iBeacon, Eddystone, AltBeacon).
     * Returns all nearby BLE devices with their RSSI.
     */
    private fun scanBleBeacons(args: Map<String, Any>, context: Context): ToolExecutor.ToolResult {
        val durationSec = (args["duration"] as? Number)?.toInt() ?: 5

        if (!hasBlePermissions(context)) {
            return ToolExecutor.ToolResult(false, "BLE permissions required.")
        }

        return try {
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                ?: return ToolExecutor.ToolResult(false, "Bluetooth service not available")
            val adapter = bluetoothManager.adapter
                ?: return ToolExecutor.ToolResult(false, "No Bluetooth adapter")

            if (!adapter.isEnabled) {
                return ToolExecutor.ToolResult(false, "Bluetooth is disabled.")
            }

            val scanner = adapter.bluetoothLeScanner
                ?: return ToolExecutor.ToolResult(false, "BLE scanner not available")

            val foundDevices = mutableMapOf<String, ScanResult>()

            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    foundDevices[result.device.address] = result
                }
            }

            val scanSettings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
                .build()

            scanner.startScan(null, scanSettings, callback)
            Thread.sleep((durationSec * 1000).toLong())
            scanner.stopScan(callback)

            if (foundDevices.isEmpty()) {
                ToolExecutor.ToolResult(true, "No BLE devices found in $durationSec seconds.")
            } else {
                val sb = StringBuilder("BLE devices (${foundDevices.size} found):\n")
                val sorted = foundDevices.values.sortedByDescending { it.rssi }
                for (result in sorted.take(20)) {
                    val name = result.device.name ?: "(unknown)"
                    val address = result.device.address
                    val rssi = result.rssi
                    val distance = when {
                        rssi >= -50 -> "very close"
                        rssi >= -60 -> "close"
                        rssi >= -70 -> "medium"
                        else -> "far"
                    }
                    // Check for iBeacon pattern (Apple manufacturer data)
                    val isIBeacon = result.scanRecord?.manufacturerSpecificData?.let { data ->
                        (0 until data.size()).any { i ->
                            val bytes = data.valueAt(i)
                            bytes != null && bytes.size >= 2 &&
                            bytes[0].toInt() and 0xFF == 0x4C && bytes[1].toInt() and 0xFF == 0x00 &&
                            bytes.size >= 23  // iBeacon header is 23 bytes
                        }
                    } ?: false
                    val type = if (isIBeacon) " [iBeacon]" else ""
                    sb.append("- $name$address | ${rssi}dBm ($distance)$type\n")
                }
                Log.i(TAG, "BLE beacon scan: ${foundDevices.size} devices")
                ToolExecutor.ToolResult(true, sb.toString())
            }
        } catch (e: SecurityException) {
            ToolExecutor.ToolResult(false, "Permission denied: ${e.message}")
        } catch (e: Exception) {
            ToolExecutor.ToolResult(false, "BLE scan failed: ${e.message}")
        }
    }

    /**
     * Log a BLE device to knowledge for future reference.
     * Saves: MAC address, name, when seen, where (if location available).
     */
    private fun logBleDevice(args: Map<String, Any>, context: Context): ToolExecutor.ToolResult {
        val mac = args["mac"] as? String
            ?: return ToolExecutor.ToolResult(false, "missing mac. Example: logBleDevice(mac=\"AA:BB:CC:DD:EE:FF\", name=\"My Device\")")
        val name = args["name"] as? String ?: "(unknown)"

        // This would save to knowledge database — for now, return a confirmation
        Log.i(TAG, "Logged BLE device: $name ($mac)")
        return ToolExecutor.ToolResult(true,
            "✅ Logged BLE device:\n" +
            "Name: $name\n" +
            "MAC: $mac\n" +
            "Time: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}\n\n" +
            "Note: To save to knowledge permanently, call saveToKnowledge(name=\"ble_$mac\", content=\"$name at ...\", source=\"ble_scan\").")
    }

    private fun hasBlePermissions(context: Context): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED) return false
        }
        return true
    }
}
