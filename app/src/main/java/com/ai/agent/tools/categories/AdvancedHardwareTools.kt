package com.ai.agent.tools.categories

import android.content.Context
import android.hardware.usb.UsbManager
import android.util.Log
import com.ai.agent.llm.LLMClient
import com.ai.agent.tools.ToolExecutor

/**
 * v4.0.76: AdvancedHardwareTools — USB OTG access.
 *
 * Phase 2 of the "Phone as Software Flipper Zero" vision.
 *
 * Tools:
 * 1. listUsbDevices() — list connected USB devices
 * 2. getUsbDeviceInfo(deviceId) — detailed info about a USB device
 *
 * Safety:
 * - All tools are READ-ONLY (no write)
 * - USB serial communication requires additional permission flow
 * - USB HID (BadUSB) requires rooted device or special permissions
 *
 * Future Phase 3: usbSerialSend(data), usbHidSend(keystrokes)
 */
object AdvancedHardwareTools {
    private const val TAG = "AdvancedHardware"

    suspend fun execute(
        call: LLMClient.ToolCall,
        context: Context,
    ): ToolExecutor.ToolResult {
        return when (call.name) {
            "listUsbDevices" -> listUsbDevices(context)
            "getUsbDeviceInfo" -> getUsbDeviceInfo(call.args, context)
            else -> ToolExecutor.ToolResult(false, "Unknown advanced hardware tool: ${call.name}")
        }
    }

    /**
     * List all connected USB devices.
     */
    private fun listUsbDevices(context: Context): ToolExecutor.ToolResult {
        return try {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
                ?: return ToolExecutor.ToolResult(false, "USB service not available")

            val deviceList = usbManager.deviceList
            if (deviceList.isEmpty()) {
                return ToolExecutor.ToolResult(true,
                    "No USB devices connected. Connect a USB OTG device to scan.")
            }

            val sb = StringBuilder("USB devices (${deviceList.size} connected):\n")
            for ((_, device) in deviceList) {
                val name = device.deviceName
                val vendorId = String.format("0x%04X", device.vendorId)
                val productId = String.format("0x%04X", device.productId)
                val deviceClass = when (device.deviceClass) {
                    0 -> "Per-Interface"
                    2 -> "Communications"
                    3 -> "HID (Keyboard/Mouse)"
                    6 -> "Image"
                    7 -> "Printer"
                    8 -> "Mass Storage"
                    9 -> "Hub"
                    10 -> "CDC-Data"
                    11 -> "Smart Card"
                    13 -> "Content Security"
                    14 -> "Video"
                    15 -> "Personal Healthcare"
                    16 -> "Audio/Video"
                    17 -> "Billboard"
                    else -> "Unknown (${device.deviceClass})"
                }
                sb.append("- $name\n")
                sb.append("  Vendor: $vendorId, Product: $productId, Class: $deviceClass\n")
            }
            sb.append("\nTo communicate with a device: call getUsbDeviceInfo(deviceId=...) " +
                "for details, then use createCustomTool with a serial handler if needed.")
            Log.i(TAG, "USB scan: ${deviceList.size} devices")
            ToolExecutor.ToolResult(true, sb.toString())
        } catch (e: Exception) {
            ToolExecutor.ToolResult(false, "USB scan failed: ${e.message}")
        }
    }

    /**
     * Get detailed info about a specific USB device.
     */
    private fun getUsbDeviceInfo(args: Map<String, Any>, context: Context): ToolExecutor.ToolResult {
        val deviceId = args["deviceId"] as? String
            ?: return ToolExecutor.ToolResult(false,
                "missing deviceId. Example: getUsbDeviceInfo(deviceId=\"/dev/bus/usb/001/002\")")

        return try {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
                ?: return ToolExecutor.ToolResult(false, "USB service not available")

            val deviceList = usbManager.deviceList
            val device = deviceList[deviceId]
                ?: return ToolExecutor.ToolResult(false, "USB device '$deviceId' not found")

            val sb = StringBuilder("USB device: $deviceId\n")
            sb.append("Name: ${device.deviceName}\n")
            sb.append("Vendor ID: 0x${String.format("%04X", device.vendorId)}\n")
            sb.append("Product ID: 0x${String.format("%04X", device.productId)}\n")
            sb.append("Device Class: ${device.deviceClass}\n")
            sb.append("Subclass: ${device.deviceSubclass}\n")
            sb.append("Protocol: ${device.deviceProtocol}\n")
            sb.append("Interface count: ${device.interfaceCount}\n")

            // List interfaces
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                sb.append("Interface $i: ${iface.interfaceClass} " +
                    "(endpoints: ${iface.endpointCount})\n")
                for (j in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(j)
                    sb.append("  Endpoint $j: ${ep.type} ${ep.direction} (maxPacket: ${ep.maxPacketSize})\n")
                }
            }

            // Check if we have permission
            val hasPermission = usbManager.hasPermission(device)
            sb.append("\nPermission: ${if (hasPermission) "GRANTED" else "NOT GRANTED — request via createCustomTool"}\n")

            Log.i(TAG, "USB device info: $deviceId")
            ToolExecutor.ToolResult(true, sb.toString())
        } catch (e: Exception) {
            ToolExecutor.ToolResult(false, "Failed to get USB device info: ${e.message}")
        }
    }
}
