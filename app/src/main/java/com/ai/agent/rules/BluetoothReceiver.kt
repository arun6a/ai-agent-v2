package com.ai.agent.rules

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.ai.agent.storage.AgentDatabase

/**
 * Bluetooth device connected/disconnected trigger (v6.1.0).
 *
 * Fires rules with triggerType="bluetooth_connected" or "bluetooth_disconnected".
 * triggerValue can be "*" (any device) or a device name.
 */
class BluetoothReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BluetoothReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED -> {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_CONNECTION_STATE, BluetoothAdapter.ERROR)
                val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }
                val deviceName = device?.name ?: "Unknown"
                val deviceAddr = device?.address ?: ""

                when (state) {
                    BluetoothAdapter.STATE_CONNECTED -> {
                        Log.i(TAG, "Bluetooth connected: $deviceName")
                        triggerRules(context, "bluetooth_connected", deviceName)
                    }
                    BluetoothAdapter.STATE_DISCONNECTED -> {
                        Log.i(TAG, "Bluetooth disconnected: $deviceName")
                        triggerRules(context, "bluetooth_disconnected", deviceName)
                    }
                }
            }
        }
    }

    private fun triggerRules(context: Context, triggerType: String, deviceName: String) {
        try {
            val db = AgentDatabase(context)
            val rules = db.getRules(enabledOnly = true).filter { it.triggerType == triggerType }
            for (rule in rules) {
                val matchValue = rule.triggerValue
                if (matchValue == "*" || matchValue.equals(deviceName, ignoreCase = true)) {
                    RuleTriggerReceiver.triggerRuleAction(context, rule)
                    db.logAction("Bluetooth rule fired: ${rule.name} ($deviceName)")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to trigger rules", e)
        }
    }
}
