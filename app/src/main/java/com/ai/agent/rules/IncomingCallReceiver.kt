package com.ai.agent.rules

import android.content.Context
import android.content.Intent
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import com.ai.agent.service.AgentService
import com.ai.agent.storage.AgentDatabase

/**
 * Listens for incoming phone calls.
 *
 * When a call comes in (state RINGING), checks if any rule has triggerType="incoming_call".
 * The triggerValue can be:
 * - "*" or "any" — match any incoming call
 * - "+919001234567" — match a specific phone number
 * - "Mom" — match a contact name (looked up via contacts)
 *
 * Because PhoneStateListener can only be registered programmatically (not via manifest),
 * we register it in AgentService.onCreate() and unregister in onDestroy().
 */
class IncomingCallReceiver(private val context: Context) {

    companion object {
        private const val TAG = "IncomingCallReceiver"
    }

    private val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    private var lastRingingNumber: String? = null  // dedupe within the same call

    private val listener = object : PhoneStateListener() {
        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
            when (state) {
                TelephonyManager.CALL_STATE_RINGING -> {
                    val number = phoneNumber ?: "unknown"
                    if (number == lastRingingNumber) {
                        Log.d(TAG, "Duplicate RINGING event for $number — ignoring")
                        return
                    }
                    lastRingingNumber = number
                    Log.i(TAG, "Incoming call from: $number")
                    handleIncomingCall(number)
                }
                TelephonyManager.CALL_STATE_IDLE, TelephonyManager.CALL_STATE_OFFHOOK -> {
                    // Call ended or picked up — reset dedupe
                    lastRingingNumber = null
                }
            }
        }
    }

    fun start() {
        try {
            telephonyManager.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
            Log.i(TAG, "Started listening for incoming calls")
        } catch (e: SecurityException) {
            Log.e(TAG, "READ_PHONE_STATE permission not granted — cannot listen for calls", e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start call listener", e)
        }
    }

    fun stop() {
        try {
            telephonyManager.listen(listener, PhoneStateListener.LISTEN_NONE)
            Log.i(TAG, "Stopped listening for incoming calls")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop call listener", e)
        }
    }

    private fun handleIncomingCall(phoneNumber: String) {
        val db = AgentDatabase(context)
        val rules = db.getRules(enabledOnly = true).filter { it.triggerType == "incoming_call" }
        if (rules.isEmpty()) return

        // Look up contact name for the phone number
        val contactName = lookupContactName(phoneNumber)
        Log.i(TAG, "Call from $phoneNumber (contact: $contactName) — checking ${rules.size} rules")

        for (rule in rules) {
            val trigger = rule.triggerValue.trim()
            val shouldFire = when {
                trigger == "*" || trigger.equals("any", true) -> true
                trigger == phoneNumber -> true
                contactName != null && contactName.equals(trigger, ignoreCase = true) -> true
                else -> false
            }
            if (shouldFire) {
                val status = if (contactName != null) {
                    "Incoming call from $contactName ($phoneNumber)"
                } else {
                    "Incoming call from $phoneNumber"
                }
                fireRule(rule, status)
            }
        }
    }

    private fun lookupContactName(phoneNumber: String): String? {
        return try {
            val uri = android.net.Uri.withAppendedPath(
                android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                android.net.Uri.encode(phoneNumber)
            )
            context.contentResolver.query(
                uri,
                arrayOf(android.provider.ContactsContract.PhoneLookup.DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "READ_CONTACTS permission not granted — cannot look up contact name", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "Contact lookup failed for $phoneNumber", e)
            null
        }
    }

    private fun fireRule(rule: AgentDatabase.Rule, status: String) {
        val db = AgentDatabase(context)
        db.logAction("Incoming call rule fired: ${rule.name} — $status")

        val serviceIntent = Intent(context, AgentService::class.java).apply {
            action = AgentService.ACTION_PROCESS_RULE
            putExtra(AgentService.EXTRA_RULE_NAME, rule.name)
            putExtra(AgentService.EXTRA_RULE_ACTION, rule.action)
            putExtra(AgentService.EXTRA_RULE_TRIGGER_TYPE, "incoming_call")
            putExtra(AgentService.EXTRA_NOTIFICATION_TITLE, "Incoming call")
            putExtra(AgentService.EXTRA_NOTIFICATION_TEXT, status)
        }
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AgentService for rule ${rule.name}", e)
        }
    }
}
