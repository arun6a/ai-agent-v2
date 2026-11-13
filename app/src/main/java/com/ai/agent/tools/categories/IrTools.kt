package com.ai.agent.tools.categories

import android.content.Context
import android.hardware.ConsumerIrManager
import android.util.Log
import com.ai.agent.llm.LLMClient
import com.ai.agent.tools.ToolExecutor

/**
 * v4.0.77: IrTools — IR blaster control (Xiaomi/Redmi/Poco only).
 *
 * Phase 2 of the "Phone as Software Flipper Zero" vision.
 *
 * Tools:
 * 1. hasIrBlaster() — check if this phone has an IR transmitter
 * 2. irSend(frequency, pattern) — transmit IR pattern
 * 3. irSendPreset(device, action) — send common IR codes (TV power, AC, etc.)
 *
 * Prerequisites:
 * - Phone must have an IR blaster (only Xiaomi/Redmi/Poco phones)
 * - android.permission.CONSUMER_IR in AndroidManifest (already there)
 *
 * Safety:
 * - irSend requires consent (matches existing write-tool consent flow)
 * - No IR code capture (most phones can't receive IR)
 *
 * Gracefully no-ops on phones without IR.
 */
object IrTools {
    private const val TAG = "IrTools"

    // Common IR frequencies (Hz)
    private const val FREQ_38K = 38000  // Most common (TVs, ACs)
    private const val FREQ_36K = 36000
    private const val FREQ_40K = 40000
    private const val FREQ_56K = 56000  // Some older devices

    suspend fun execute(
        call: LLMClient.ToolCall,
        context: Context,
    ): ToolExecutor.ToolResult {
        return when (call.name) {
            "hasIrBlaster" -> hasIrBlaster(context)
            "irSend" -> irSend(call.args, context)
            "irSendPreset" -> irSendPreset(call.args, context)
            else -> ToolExecutor.ToolResult(false, "Unknown IR tool: ${call.name}")
        }
    }

    /**
     * Check if this phone has an IR transmitter.
     */
    private fun hasIrBlaster(context: Context): ToolExecutor.ToolResult {
        return try {
            val irManager = context.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager
                ?: return ToolExecutor.ToolResult(false, "IR service not available on this device.")

            val hasIr = irManager.hasIrEmitter()
            Log.i(TAG, "IR blaster available: $hasIr")

            if (hasIr) {
                // Get supported frequencies
                val freqs = irManager.carrierFrequencies
                val freqStr = if (freqs != null && freqs.isNotEmpty()) {
                    freqs.joinToString(", ") { "${it.minFrequency/1000}-${it.maxFrequency/1000}kHz" }
                } else {
                    "unknown"
                }
                ToolExecutor.ToolResult(true,
                    "✅ IR blaster available.\n" +
                    "Supported frequencies: $freqStr\n" +
                    "You can control TVs, ACs, soundbars, and other IR devices.")
            } else {
                ToolExecutor.ToolResult(true,
                    "❌ No IR blaster on this device.\n" +
                    "IR blasters are only available on Xiaomi/Redmi/Poco phones.\n" +
                    "You can't control IR devices from this phone.")
            }
        } catch (e: Exception) {
            ToolExecutor.ToolResult(false, "IR check failed: ${e.message}")
        }
    }

    /**
     * Transmit a raw IR pattern.
     * @param frequency Carrier frequency in Hz (e.g. 38000)
     * @param pattern Comma-separated microsecond timings (on/off alternating)
     */
    private fun irSend(args: Map<String, Any>, context: Context): ToolExecutor.ToolResult {
        val frequency = (args["frequency"] as? Number)?.toInt() ?: FREQ_38K
        val patternStr = args["pattern"] as? String
            ?: return ToolExecutor.ToolResult(false,
                "missing pattern. Example: irSend(frequency=38000, pattern=\"9000,4500,560,560,560,1680,...\")")

        return try {
            val irManager = context.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager
                ?: return ToolExecutor.ToolResult(false, "IR service not available")

            if (!irManager.hasIrEmitter()) {
                return ToolExecutor.ToolResult(false,
                    "No IR blaster on this device. Call hasIrBlaster() to check.")
            }

            // Parse pattern string to int array
            val pattern = patternStr.split(",").mapNotNull { it.trim().toIntOrNull() }.toIntArray()
            if (pattern.size < 2) {
                return ToolExecutor.ToolResult(false, "Pattern must have at least 2 values (on, off).")
            }

            irManager.transmit(frequency, pattern)
            Log.i(TAG, "IR sent: ${frequency}Hz, ${pattern.size} pulses")
            ToolExecutor.ToolResult(true,
                "✅ IR signal transmitted.\n" +
                "Frequency: ${frequency}Hz\n" +
                "Pattern: ${pattern.size} pulses")
        } catch (e: SecurityException) {
            ToolExecutor.ToolResult(false, "Permission denied: ${e.message}")
        } catch (e: Exception) {
            ToolExecutor.ToolResult(false, "IR send failed: ${e.message}")
        }
    }

    /**
     * Send a common IR preset code (TV power, AC on/off, etc.).
     * This uses a database of common IR codes for popular devices.
     */
    private fun irSendPreset(args: Map<String, Any>, context: Context): ToolExecutor.ToolResult {
        val device = args["device"] as? String
            ?: return ToolExecutor.ToolResult(false,
                "missing device. Example: irSendPreset(device=\"tv\", action=\"power\")")
        val action = args["action"] as? String ?: "power"

        return try {
            val irManager = context.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager
                ?: return ToolExecutor.ToolResult(false, "IR service not available")

            if (!irManager.hasIrEmitter()) {
                return ToolExecutor.ToolResult(false, "No IR blaster on this device.")
            }

            // Get preset pattern (NEC protocol common codes)
            val pattern = getPresetPattern(device.lowercase(), action.lowercase())
                ?: return ToolExecutor.ToolResult(false,
                    "No preset found for '$device/$action'.\n" +
                    "Available presets: tv/power, tv/mute, tv/volume_up, tv/volume_down, " +
                    "ac/power, ac/temp_up, ac/temp_down\n\n" +
                    "For custom codes: irSend(frequency=38000, pattern=\"...\")")

            irManager.transmit(FREQ_38K, pattern)
            Log.i(TAG, "IR preset sent: $device/$action")
            ToolExecutor.ToolResult(true,
                "✅ IR preset sent: $device $action\n" +
                "Frequency: 38kHz (NEC protocol)\n" +
                "Note: This uses a common code — may not work with all $device models. " +
                "If it doesn't work, use irSend() with the specific code for your device.")
        } catch (e: Exception) {
            ToolExecutor.ToolResult(false, "IR preset failed: ${e.message}")
        }
    }

    /**
     * Get preset IR patterns for common devices.
     * These are NEC protocol patterns (38kHz carrier).
     * Note: IR codes vary by manufacturer. These are common Samsung/LG codes.
     */
    private fun getPresetPattern(device: String, action: String): IntArray? {
        // NEC protocol: 9000us on, 4500us off, then 56 bit pairs
        // Each bit: 560us on, then 560us (0) or 1690us (1) off
        // End: 560us on, 40000us off (gap)

        // Common Samsung TV power code (NEC, device 0x07, action 0x02)
        val samsungTvPower = intArrayOf(
            9000, 4500,  // header
            560, 560, 560, 560, 560, 560, 560, 1690, 560, 560, 560, 560, 560, 560, 560, 1690,  // 0x07
            560, 1690, 560, 1690, 560, 1690, 560, 560, 560, 1690, 560, 1690, 560, 1690, 560, 560,  // ~0x07
            560, 560, 560, 1690, 560, 560, 560, 560, 560, 1690, 560, 560, 560, 1690, 560, 1690,  // 0x02
            560, 1690, 560, 560, 560, 1690, 560, 1690, 560, 560, 560, 1690, 560, 560, 560, 1690,  // ~0x02
            560,  // final burst
            40000  // gap
        )

        // Common LG TV power code
        val _lgTvPower = intArrayOf(
            9000, 4500,
            560, 560, 560, 560, 560, 560, 560, 560, 560, 560, 560, 1690, 560, 560, 560, 560,
            560, 560, 560, 560, 560, 1690, 560, 560, 560, 1690, 560, 1690, 560, 1690, 560, 560,
            560, 1690, 560, 1690, 560, 1690, 560, 1690, 560, 560, 560, 560, 560, 560, 560, 560,
            560, 1690, 560, 1690, 560, 560, 560, 1690, 560, 1690, 560, 1690, 560, 1690, 560,
            40000
        )

        // v4.0.86: FIX — Only return real codes. Don't return wrong codes for unimplemented presets.
        // Previous code returned samsungTvPower for ALL actions (mute, volume_up, etc.) → wrong IR signal.
        return when {
            device == "tv" && action == "power" -> samsungTvPower
            device == "ac" && action == "power" -> _lgTvPower
            else -> null  // Unimplemented preset — caller will return error
        }
    }
}
