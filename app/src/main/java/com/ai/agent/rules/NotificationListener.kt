package com.ai.agent.rules

import android.app.Notification
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.ai.agent.storage.AgentDatabase

/**
 * v3.0.0: NotificationListener — clean notification trigger + instant reply.
 *
 * ONE file handles:
 * 1. Notification matching (package + sender)
 * 2. Instant direct reply (no WorkManager delay)
 * 3. Smart rule execution via RuleEngine
 *
 * Flow:
 *   Notification arrives → match rules →
 *     IF direct reply: reply IMMEDIATELY (500ms delay for WhatsApp to post reply action)
 *     IF smart rule: RuleEngine.execute(context, rule) → LLM decides
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
        Log.i(TAG, "✅ NotificationListener connected — ready to receive notifications")
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

        // Find matching notification rules
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
                Log.i(TAG, "🔔 Notification rule matched: ${rule.name} (from $packageName: $title)")

                val action = rule.action
                val isDirectReply = RuleEngine.isDirectRule(action) &&
                    action.contains("replyToNotification")

                if (isDirectReply) {
                    // DIRECT REPLY: Reply IMMEDIATELY (500ms delay for WhatsApp to post reply action)
                    val replyText = parseReplyText(action)
                    Log.i(TAG, "Direct reply — waiting 500ms then replying: $replyText")
                    Handler(Looper.getMainLooper()).postDelayed({
                        try {
                            val replied = replyToNotification(packageName, replyText)
                            db.logAction("Reply ${if (replied) "✓" else "✗"}: ${rule.name} → $replyText")
                            if (!replied) {
                                Log.w(TAG, "Direct reply failed — falling back to RuleEngine")
                                RuleEngine.execute(this, rule, "notification: $title — $text")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Direct reply exception", e)
                        }
                    }, 500L)
                } else {
                    // SMART RULE: Let RuleEngine handle it (LLM call)
                    Log.i(TAG, "Smart rule — executing via RuleEngine")
                    RuleEngine.execute(this, rule, "notification from $packageName: $title — $text")
                }
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
    }

    /**
     * Reply to a notification via RemoteInput.
     * Finds the reply action in the notification + sends the reply text.
     */
    fun replyToNotification(packageName: String, replyText: String): Boolean {
        try {
            val allNotifs = activeNotifications ?: return false
            val activeNotifs = allNotifs.filter { it.packageName == packageName }
            if (activeNotifs.isNullOrEmpty()) {
                Log.w(TAG, "No active notifications from $packageName")
                return false
            }

            for (sbn in activeNotifs) {
                val notification = sbn.notification ?: continue
                val actions = notification.actions ?: continue

                for (action in actions) {
                    val remoteInputs = action.remoteInputs
                    if (remoteInputs.isNullOrEmpty()) continue

                    Log.i(TAG, "Found reply action in $packageName notification")

                    val replyIntent = Intent().apply {
                        addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                    }

                    val results = android.os.Bundle()
                    for (remoteInput in remoteInputs) {
                        results.putCharSequence(remoteInput.resultKey, replyText)
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        NotificationReplyHelper.addResultsToIntent(remoteInputs, replyIntent, results)
                    }

                    action.actionIntent?.send(this, 0, replyIntent)
                    Log.i(TAG, "✅ Replied to $packageName: $replyText")
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

    /**
     * Parse reply text from a direct rule action.
     * Formats: replyToNotification("com.whatsapp", "I'll call you back")
     *          replyToNotification(package="com.whatsapp", message="reply text")
     */
    private fun parseReplyText(action: String): String {
        try {
            val cleanAction = action.removePrefix("direct:").removePrefix("tool:").trim()
            val openParen = cleanAction.indexOf('(')
            val closeParen = cleanAction.lastIndexOf(')')
            if (openParen == -1 || closeParen == -1 || closeParen <= openParen) return ""
            val argsStr = cleanAction.substring(openParen + 1, closeParen)

            // If "message=" is present, extract that value
            val messageMatch = Regex("""message\s*=\s*["']([^"']*)["']""").find(argsStr)
            if (messageMatch != null) return messageMatch.groupValues[1]

            // Otherwise, extract the LAST quoted string (first arg is usually package)
            val quotedStrings = Regex("""["']([^"']*)["']""").findAll(argsStr).toList()
            return when {
                quotedStrings.size >= 2 -> quotedStrings[1].groupValues[1]
                quotedStrings.size == 1 -> quotedStrings[0].groupValues[1]
                else -> ""
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse reply text from: $action", e)
            return ""
        }
    }
}
