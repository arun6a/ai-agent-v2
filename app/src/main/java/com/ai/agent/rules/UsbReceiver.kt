package com.ai.agent.rules

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.util.Log
import com.ai.agent.storage.AgentDatabase

/**
 * USB device connected/disconnected trigger (v6.1.0).
 */
class UsbReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "UsbReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val triggerType = when (intent.action) {
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> "usb_connected"
            UsbManager.ACTION_USB_DEVICE_DETACHED -> "usb_disconnected"
            else -> return
        }
        Log.i(TAG, "USB event: $triggerType")
        triggerRules(context, triggerType)
    }

    private fun triggerRules(context: Context, triggerType: String) {
        try {
            val db = AgentDatabase(context)
            val rules = db.getRules(enabledOnly = true).filter { it.triggerType == triggerType }
            for (rule in rules) {
                RuleTriggerReceiver.triggerRuleAction(context, rule)
                db.logAction("USB rule fired: ${rule.name}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to trigger rules", e)
        }
    }
}
