package com.ai.agent.rules

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsMessage
import android.util.Log
import com.ai.agent.service.AgentService
import com.ai.agent.storage.AgentDatabase

/**
 * Receives SMS_RECEIVED broadcasts to fire rules with triggerType="sms_received".
 *
 * The triggerValue can be:
 * - "*" or "any" — match any SMS
 * - "+919001234567" — match a specific sender number
 * - "OTP" — match SMS containing "OTP" in the body
 *
 * Note: requires RECEIVE_SMS permission (separate from READ_SMS / SEND_SMS).
 */
class SMSReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SMSReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        val db = AgentDatabase(context)
        val rules = db.getRules(enabledOnly = true).filter { it.triggerType == "sms_received" }
        if (rules.isEmpty()) return  // no SMS rules — skip processing

        // Combine all message parts
        val sender = messages[0].displayOriginatingAddress ?: messages[0].originatingAddress ?: "unknown"
        val body = messages.joinToString("") { it.displayMessageBody ?: it.messageBody ?: "" }

        Log.i(TAG, "SMS from $sender: ${body.take(80)}...")

        for (rule in rules) {
            val trigger = rule.triggerValue.trim()
            val shouldFire = when {
                trigger == "*" || trigger.equals("any", true) -> true
                trigger == sender -> true
                body.contains(trigger, ignoreCase = true) -> true
                else -> false
            }
            if (shouldFire) {
                fireRule(context, rule, sender, body)
            }
        }
    }

    private fun fireRule(context: Context, rule: AgentDatabase.Rule, sender: String, body: String) {
        Log.i(TAG, "Firing SMS rule: ${rule.name}")
        val db = AgentDatabase(context)
        db.logAction("SMS rule fired: ${rule.name} — from $sender")

        val status = "SMS from $sender: ${body.take(200)}"

        val serviceIntent = Intent(context, AgentService::class.java).apply {
            action = AgentService.ACTION_PROCESS_RULE
            putExtra(AgentService.EXTRA_RULE_NAME, rule.name)
            putExtra(AgentService.EXTRA_RULE_ACTION, rule.action)
            putExtra(AgentService.EXTRA_RULE_TRIGGER_TYPE, "sms_received")
            putExtra(AgentService.EXTRA_NOTIFICATION_TITLE, "SMS from $sender")
            putExtra(AgentService.EXTRA_NOTIFICATION_TEXT, status)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
