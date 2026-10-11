package com.ai.agent.rules

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.ai.agent.storage.AgentDatabase

/**
 * Listens for notifications from all apps.
 * When a notification matches a rule, triggers the rule action.
 */
class NotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "NotificationListener"
        @Volatile
        var instance: NotificationListener? = null
            private set
    }

    private val db by lazy { AgentDatabase(this) }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.i(TAG, "NotificationListener connected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val packageName = sbn.packageName ?: return
        val notification = sbn.notification ?: return
        val extras = notification.extras

        val title = extras.getString(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: text

        Log.d(TAG, "Notification from $packageName: $title - $text")

        // Check if any rules match this package
        val rules = db.getRules(enabledOnly = true).filter { it.triggerType == "notification" }
        for (rule in rules) {
            val trigger = rule.triggerValue
            val shouldTrigger = when {
                // "com.whatsapp:Mom" — match package AND sender
                trigger.contains(":") -> {
                    val parts = trigger.split(":", limit = 2)
                    val pkg = parts[0]
                    val sender = parts.getOrNull(1) ?: ""
                    packageName == pkg && (title.contains(sender, ignoreCase = true) || text.contains(sender, ignoreCase = true))
                }
                // Just package name — match any notification from this app
                else -> packageName == trigger
            }

            if (shouldTrigger) {
                Log.i(TAG, "Notification rule fired: ${rule.name} (from $packageName)")
                triggerRule(rule, title, bigText)
            }
        }
    }

    private fun triggerRule(rule: AgentDatabase.Rule, notifTitle: String, notifText: String) {
        val serviceIntent = Intent(this, com.ai.agent.service.AgentService::class.java).apply {
            action = com.ai.agent.service.AgentService.ACTION_PROCESS_RULE
            putExtra(com.ai.agent.service.AgentService.EXTRA_RULE_NAME, rule.name)
            putExtra(com.ai.agent.service.AgentService.EXTRA_RULE_ACTION, rule.action)
            putExtra(com.ai.agent.service.AgentService.EXTRA_RULE_TRIGGER_TYPE, rule.triggerType)
            putExtra(com.ai.agent.service.AgentService.EXTRA_NOTIFICATION_TITLE, notifTitle)
            putExtra(com.ai.agent.service.AgentService.EXTRA_NOTIFICATION_TEXT, notifText)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        db.logAction("Notification rule fired: ${rule.name} (title=$notifTitle) — ${rule.action}")
    }

    /**
     * Reply directly to a notification WITHOUT opening the app.
     * Uses Android's RemoteInput API — sends the reply through the notification's reply action.
     * Works for: WhatsApp, Telegram, SMS, Hangouts, etc.
     * Returns true if reply was sent.
     */
    fun replyToNotification(packageName: String, replyText: String): Boolean {
        try {
            // Get all active notifications
            val allNotifs = activeNotifications ?: return false
            val activeNotifs = allNotifs.filter { it.packageName == packageName }
            if (activeNotifs.isNullOrEmpty()) {
                Log.w(TAG, "No active notifications from $packageName")
                return false
            }

            // Find the notification with a reply action
            for (sbn in activeNotifs) {
                val notification = sbn.notification ?: continue
                val actions = notification.actions ?: continue

                for (action in actions) {
                    val remoteInputs = action.remoteInputs
                    if (remoteInputs.isNullOrEmpty()) continue

                    Log.i(TAG, "Found reply action in $packageName notification")

                    // Build the reply intent — RemoteInput requires the results to be added to it
                    val replyIntent = Intent().apply {
                        addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                    }

                    // Build the results bundle: {resultKey → replyText}
                    val results = android.os.Bundle()
                    for (remoteInput in remoteInputs) {
                        results.putCharSequence(remoteInput.resultKey, replyText)
                    }

                    // Attach the results to the intent — must pass the SAME RemoteInput array
                    // that came from the action (not a copy), so the system can match them.
                    // Use the Java helper to avoid Kotlin overload resolution issues with
                    // platform-typed arrays. Note: actual platform signature is
                    // addResultsToIntent(RemoteInput[], Intent, Bundle) — order: remoteInputs first.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        NotificationReplyHelper.addResultsToIntent(remoteInputs, replyIntent, results)
                    }

                    // Send the reply via the action's PendingIntent
                    action.actionIntent?.send(this, 0, replyIntent)
                    Log.i(TAG, "Replied to $packageName: $replyText")
                    return true
                }
            }

            Log.w(TAG, "No reply action found in $packageName notifications")
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reply to notification", e)
            return false
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
    }
}
