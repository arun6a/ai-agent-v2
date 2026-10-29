package com.ai.agent.rules

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
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
        // v3.0.3: Anti-loop cooldown — don't reply to same package within 10 seconds
        private const val REPLY_COOLDOWN_MS = 10_000L  // 10 seconds
        private val lastReplyTime = mutableMapOf<String, Long>()  // packageName → timestamp

        /**
         * v4.0.21: Check if the notification listener is connected + alive.
         * If it's enabled in settings but the instance is null (Android killed it),
         * request a rebind so it reconnects automatically.
         *
         * Call this on app startup or before executing a notification-triggered rule.
         * Returns true if the listener is currently connected + ready to receive
         * notifications.
         */
        fun ensureConnected(context: Context): Boolean {
            if (instance != null) return true  // already connected
            // Check if enabled in settings
            val enabled = isNotificationListenerEnabled(context)
            if (!enabled) return false
            // Enabled but not connected — request rebind
            try {
                val cn = ComponentName(context, NotificationListener::class.java)
                NotificationListenerService.requestRebind(cn)
                Log.i(TAG, "🔄 NotificationListener enabled but not connected — requested rebind")
            } catch (e: Exception) {
                Log.e(TAG, "requestRebind failed in ensureConnected", e)
            }
            return false  // not connected YET (rebind is async)
        }

        private fun isNotificationListenerEnabled(context: Context): Boolean {
            val expected = "${context.packageName}/${NotificationListener::class.java.name}"
            val enabled = Settings.Secure.getString(
                context.contentResolver, "enabled_notification_listeners"
            ) ?: ""
            return enabled.contains(expected)
        }
    }

    private val db by lazy { AgentDatabase(this) }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.i(TAG, "✅ NotificationListener connected — ready to receive notifications")

        // v3.0.2: Register ALL trigger receivers HERE (not in AgentService).
        // Same lesson as BrowserActivity → BrowserScreen fix:
        //   - AgentService (foreground service) gets KILLED by MIUI → receivers die
        //   - NotificationListenerService is system-bound → MIUI CANNOT kill it
        // So we register battery/headset/wifi/screen triggers here where they survive.
        try {
            RuleTriggerManager.registerAll(this)
            Log.i(TAG, "✅ ALL triggers registered in NotificationListener (MIUI-safe)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register triggers", e)
        }

        // Also schedule all time rules (belt + suspenders — in case AgentService hasn't started yet)
        try {
            RuleScheduler(this).scheduleAllTimeRules()
            Log.i(TAG, "✅ Time rules rescheduled")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to reschedule time rules", e)
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
        Log.w(TAG, "⚠️ NotificationListener disconnected — requesting rebind")
        // v4.0.21: Proactively request rebind. Android sometimes disconnects
        // the listener (app update, OOM, settings change). requestRebind()
        // asks the system to reconnect us automatically. This is the official
        // API for keeping a NotificationListenerService alive.
        try {
            requestRebind(ComponentName(this, NotificationListener::class.java))
            Log.i(TAG, "✅ Rebind requested — system will reconnect shortly")
        } catch (e: Exception) {
            Log.e(TAG, "requestRebind failed", e)
        }
        // Don't unregister triggers here — they'll be re-registered when listener reconnects
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
                // v3.0.3: Anti-loop cooldown check
                val now = System.currentTimeMillis()
                val lastReply = lastReplyTime[packageName] ?: 0L
                if (now - lastReply < REPLY_COOLDOWN_MS) {
                    Log.i(TAG, "⏭️ Skipping $packageName — cooldown active (${(now - lastReply) / 1000}s ago)")
                    return  // Skip ALL rules for this package (avoid loop)
                }

                Log.i(TAG, "🔔 Notification rule matched: ${rule.name} (from $packageName: $title)")

                // v3.0.5: Save incoming message to conversation memory
                // v4.0.41: Store per-contact, not just per-app.
                // If trigger is "com.whatsapp:Mom" → key = "com.whatsapp:Mom"
                // If trigger is "com.whatsapp" (any sender) → key = "com.whatsapp:$title"
                //   (title is the sender name/number from the notification)
                // This separates Mom's messages from Arjun's messages even if both
                // use WhatsApp and the rule matches "com.whatsapp".
                val convKey = if (trigger.contains(":")) {
                    trigger  // Rule is sender-specific: "com.whatsapp:Mom"
                } else {
                    // Rule matches all senders for this app — use sender title as sub-key
                    val senderKey = if (title.isNotEmpty()) title.replace(" ", "_").replace(":", "_") else "unknown"
                    "$packageName:$senderKey"
                }
                ConversationMemory.addIncoming(this, convKey, "$title: $text")

                val action = rule.action
                val isDirectReply = RuleEngine.isDirectRule(action) &&
                    action.contains("replyToNotification")

                if (isDirectReply) {
                    // DIRECT REPLY: Reply IMMEDIATELY (500ms delay for WhatsApp to post reply action)
                    val replyText = parseReplyText(action)
                    Log.i(TAG, "Direct reply — waiting 500ms then replying: $replyText")
                    // Set cooldown NOW (before reply) so the "reply sent" confirmation doesn't trigger another reply
                    lastReplyTime[packageName] = System.currentTimeMillis()
                    Handler(Looper.getMainLooper()).postDelayed({
                        try {
                            val replied = replyToNotification(packageName, replyText)
                            db.logAction("Reply ${if (replied) "✓" else "✗"}: ${rule.name} → $replyText")
                            // v3.0.5: Save outgoing reply to conversation memory
                            if (replied) {
                                ConversationMemory.addOutgoing(this, convKey, replyText)
                            }
                            if (!replied) {
                                Log.w(TAG, "Direct reply failed — falling back to RuleEngine")
                                RuleEngine.execute(this, rule, "notification: $title — $text")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Direct reply exception", e)
                        }
                    }, 500L)
                } else {
                    // SMART RULE: Set cooldown + let RuleEngine handle it (LLM call)
                    lastReplyTime[packageName] = System.currentTimeMillis()
                    Log.i(TAG, "Smart rule — executing via RuleEngine (with conversation memory)")
                    // v3.0.5: Pass conversation history so AI remembers previous messages
                    // v4.0.25: Explicitly tell the LLM to ANALYZE the incoming message
                    // + reply intelligently (not just a canned response). The user's rule
                    // action is natural language like "Reply in Tamil as my assistant".
                    val history = ConversationMemory.getHistoryForPrompt(this, convKey)
                    RuleEngine.execute(this, rule,
                        "📩 INCOMING NOTIFICATION from $packageName:\n" +
                        "  Title: $title\n" +
                        "  Message: $text\n" +
                        (if (bigText.isNotEmpty() && bigText != text) "  Full text: $bigText\n" else "") +
                        "\n📜 CONVERSATION HISTORY (last 10 exchanges with this contact):\n$history\n\n" +
                        "🤖 YOUR TASK:\n" +
                        "1. Read the incoming message carefully — understand what the sender is saying\n" +
                        "2. Consider the conversation history — has this topic come up before?\n" +
                        "3. Reply APPROPRIATELY based on the rule action below — don't just send a canned response\n" +
                        "4. Use replyToNotification(package=\"$packageName\", message=\"your reply\") to send the reply\n" +
                        "5. After sending the reply, call complete(success=true, reason=\"...\")\n\n" +
                        "IMPORTANT: Reply to the SPECIFIC message they sent, not a generic greeting."
                    )
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
     * Handles ALL formats the AI might generate:
     *   replyToNotification("com.whatsapp", "I'll call you back")
     *   replyToNotification(com.whatsapp, Got it)
     *   replyToNotification(package="com.whatsapp", message="reply text")
     *   replyToNotification(package="com.whatsapp", message=reply text)
     */
    private fun parseReplyText(action: String): String {
        try {
            val cleanAction = action.removePrefix("direct:").removePrefix("tool:").trim()
            val openParen = cleanAction.indexOf('(')
            val closeParen = cleanAction.lastIndexOf(')')
            if (openParen == -1 || closeParen == -1 || closeParen <= openParen) return ""
            val argsStr = cleanAction.substring(openParen + 1, closeParen)

            // Strategy 1: If "message=" is present, extract everything after it
            val messageIdx = argsStr.indexOf("message=", ignoreCase = true)
            if (messageIdx >= 0) {
                // Find the value after "message="
                val valueStart = messageIdx + "message=".length
                var value = argsStr.substring(valueStart).trim()
                // Strip surrounding quotes if present
                if (value.startsWith('"') || value.startsWith('\'')) {
                    val quote = value[0]
                    // Find closing quote
                    val endQuote = value.indexOf(quote, 1)
                    if (endQuote > 0) {
                        value = value.substring(1, endQuote)
                    }
                } else {
                    // No quotes — take everything up to comma or end
                    val comma = value.indexOf(',')
                    if (comma > 0) value = value.substring(0, comma)
                    value = value.trim()
                }
                if (value.isNotEmpty()) return value
            }

            // Strategy 2: Split by comma, take the LAST argument (first is package name)
            val parts = argsStr.split(",").map { it.trim() }
            if (parts.size >= 2) {
                // The reply text is the last argument
                var replyValue = parts.last().trim()
                // Strip surrounding quotes if present
                if (replyValue.startsWith('"') || replyValue.startsWith('\'')) {
                    val quote = replyValue[0]
                    val endQuote = replyValue.lastIndexOf(quote)
                    if (endQuote > 0) {
                        replyValue = replyValue.substring(1, endQuote)
                    }
                }
                if (replyValue.isNotEmpty()) return replyValue
            }

            // Strategy 3: Extract the last quoted string
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
