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
        // v4.0.67: Changed from per-PACKAGE to per-CONTACT cooldown.
        // Key is now "com.whatsapp:Mom" instead of "com.whatsapp".
        // This allows Mom and Dad to both get replies within 10 seconds.
        private const val REPLY_COOLDOWN_MS = 10_000L  // 10 seconds
        private val lastReplyTime = mutableMapOf<String, Long>()  // "com.whatsapp:Mom" → timestamp

        // v4.0.67: Message queue — when a notification arrives during cooldown,
        // store it for later processing instead of silently dropping it.
        // After cooldown expires, the queued messages are processed.
        private data class PendingReply(
            val packageName: String,
            val senderTitle: String,
            val text: String,
            val bigText: String,
            val convKey: String,
            val rule: AgentDatabase.Rule,
            val timestamp: Long,
        )
        private val pendingReplies = mutableListOf<PendingReply>()

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

        // v4.0.80: GAP 4 FIX — Record notification pattern for proactive observer
        try {
            com.ai.agent.proactive.ProactiveObserver.recordTrigger(
                this, "notif:$packageName", title.take(50)
            )
        } catch (_: Exception) {}

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
                // v4.0.67: Per-CONTACT cooldown (not per-package).
                // Key: "com.whatsapp:Mom" — so Mom and Dad can both get replies.
                val cooldownKey = if (title.isNotEmpty()) {
                    "$packageName:${title.replace(" ", "_").replace(":", "_")}"
                } else {
                    packageName  // fallback if no sender name
                }

                // v4.0.67: Compute convKey BEFORE cooldown check (needed for queue)
                val convKey = if (trigger.contains(":")) {
                    trigger  // Rule is sender-specific: "com.whatsapp:Mom"
                } else {
                    // Rule matches all senders for this app — use sender title as sub-key
                    val senderKey = if (title.isNotEmpty()) title.replace(" ", "_").replace(":", "_") else "unknown"
                    "$packageName:$senderKey"
                }

                // v3.0.3: Anti-loop cooldown check (v4.0.67: per-contact)
                val now = System.currentTimeMillis()
                val lastReply = lastReplyTime[cooldownKey] ?: 0L
                if (now - lastReply < REPLY_COOLDOWN_MS) {
                    Log.i(TAG, "⏭️ Skipping $cooldownKey — cooldown active (${(now - lastReply) / 1000}s ago)")
                    // v4.0.67: Queue the message instead of dropping it!
                    // Combine with existing pending messages from the same sender.
                    val existingPending = pendingReplies.find { it.convKey == convKey }
                    if (existingPending != null) {
                        // v4.0.67: Combine multiple messages from the same sender
                        val combinedText = "${existingPending.text}\n${text}"
                        val combinedBigText = if (bigText.isNotEmpty()) "${existingPending.bigText}\n${bigText}" else combinedText
                        val idx = pendingReplies.indexOf(existingPending)
                        pendingReplies[idx] = existingPending.copy(
                            text = combinedText,
                            bigText = combinedBigText,
                            timestamp = now
                        )
                        Log.i(TAG, "📨 Queued message for $convKey (combined: ${combinedText.take(100)}...)")
                    } else {
                        // First pending message for this sender
                        pendingReplies.add(PendingReply(
                            packageName = packageName,
                            senderTitle = title,
                            text = text,
                            bigText = bigText,
                            convKey = convKey,
                            rule = rule,
                            timestamp = now,
                        ))
                        Log.i(TAG, "📨 Queued message for $convKey (will reply after cooldown expires)")
                    }
                    return  // Don't process now — will be processed after cooldown
                }

                Log.i(TAG, "🔔 Notification rule matched: ${rule.name} (from $packageName: $title)")

                // v3.0.5: Save incoming message to conversation memory
                // v4.0.67: convKey already computed above (before cooldown check)
                ConversationMemory.addIncoming(this, convKey, "$title: $text")

                val action = rule.action
                val isDirectReply = RuleEngine.isDirectRule(action) &&
                    action.contains("replyToNotification")

                if (isDirectReply) {
                    // DIRECT REPLY: Reply IMMEDIATELY (500ms delay for WhatsApp to post reply action)
                    val replyText = parseReplyText(action)
                    Log.i(TAG, "Direct reply — waiting 500ms then replying: $replyText")
                    // Set cooldown NOW (before reply) so the "reply sent" confirmation doesn't trigger another reply
                    // v4.0.67: Per-contact cooldown key
                    lastReplyTime[cooldownKey] = System.currentTimeMillis()
                    // v4.0.67: Capture sender for targeted reply
                    val senderName = title
                    Handler(Looper.getMainLooper()).postDelayed({
                        try {
                            // v4.0.67: Reply to SPECIFIC sender (not first notification)
                            val replied = replyToNotification(packageName, replyText, senderName)
                            db.logAction("Reply ${if (replied) "✓" else "✗"}: ${rule.name} → $replyText")
                            // v3.0.5: Save outgoing reply to conversation memory
                            if (replied) {
                                ConversationMemory.addOutgoing(this, convKey, replyText)
                            }
                            if (!replied) {
                                Log.w(TAG, "Direct reply failed — falling back to RuleEngine")
                                RuleEngine.execute(this, rule, "notification: $title — $text")
                            }
                            // v4.0.67: Process pending queue after reply
                            processPendingReplies()
                        } catch (e: Exception) {
                            Log.e(TAG, "Direct reply exception", e)
                        }
                    }, 500L)
                } else {
                    // SMART RULE: Set cooldown + let RuleEngine handle it (LLM call)
                    // v4.0.67: Per-contact cooldown key
                    lastReplyTime[cooldownKey] = System.currentTimeMillis()
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
     * v4.0.67: Process pending replies from the queue.
     * Called after each reply is sent. Checks if any queued messages have
     * passed their cooldown and processes them.
     *
     * For each pending reply:
     * 1. Check if cooldown has expired for that contact
     * 2. If yes → process the reply (direct or smart)
     * 3. Remove from queue
     */
    private fun processPendingReplies() {
        if (pendingReplies.isEmpty()) return

        val now = System.currentTimeMillis()
        val toProcess = mutableListOf<PendingReply>()

        // Find all pending replies whose cooldown has expired
        val iterator = pendingReplies.iterator()
        while (iterator.hasNext()) {
            val pending = iterator.next()
            val cooldownKey = if (pending.senderTitle.isNotEmpty()) {
                "${pending.packageName}:${pending.senderTitle.replace(" ", "_").replace(":", "_")}"
            } else {
                pending.packageName
            }
            val lastReply = lastReplyTime[cooldownKey] ?: 0L
            if (now - lastReply >= REPLY_COOLDOWN_MS) {
                toProcess.add(pending)
                iterator.remove()
            }
        }

        if (toProcess.isEmpty()) return

        Log.i(TAG, "📬 Processing ${toProcess.size} pending reply(ies) from queue")

        // Process each pending reply (sequentially, with small delay between)
        for ((index, pending) in toProcess.withIndex()) {
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    processSingleReply(pending)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to process pending reply for ${pending.convKey}", e)
                }
            }, index * 2000L)  // 2 second delay between each reply
        }
    }

    /**
     * v4.0.67: Process a single pending reply from the queue.
     * Uses the combined message text (if multiple messages were merged).
     */
    private fun processSingleReply(pending: PendingReply) {
        val rule = pending.rule
        val action = rule.action
        val isDirectReply = RuleEngine.isDirectRule(action) && action.contains("replyToNotification")

        // Save the combined incoming message to conversation memory
        ConversationMemory.addIncoming(this, pending.convKey, "${pending.senderTitle}: ${pending.text}")

        // Set cooldown for this contact
        val cooldownKey = if (pending.senderTitle.isNotEmpty()) {
            "${pending.packageName}:${pending.senderTitle.replace(" ", "_").replace(":", "_")}"
        } else {
            pending.packageName
        }
        lastReplyTime[cooldownKey] = System.currentTimeMillis()

        if (isDirectReply) {
            val replyText = parseReplyText(action)
            Log.i(TAG, "📬 Queued direct reply to ${pending.senderTitle}: $replyText")
            val replied = replyToNotification(pending.packageName, replyText, pending.senderTitle)
            db.logAction("Reply ${if (replied) "✓" else "✗"}: ${rule.name} → $replyText (queued)")
            if (replied) {
                ConversationMemory.addOutgoing(this, pending.convKey, replyText)
            }
        } else {
            Log.i(TAG, "📬 Queued smart reply for ${pending.senderTitle}")
            val history = ConversationMemory.getHistoryForPrompt(this, pending.convKey)
            RuleEngine.execute(this, rule,
                "📩 INCOMING NOTIFICATION from ${pending.packageName}:\n" +
                "  Title: ${pending.senderTitle}\n" +
                "  Message: ${pending.text}\n" +
                (if (pending.bigText.isNotEmpty() && pending.bigText != pending.text) "  Full text: ${pending.bigText}\n" else "") +
                "\n📜 CONVERSATION HISTORY (last 10 exchanges with this contact):\n$history\n\n" +
                "🤖 YOUR TASK:\n" +
                "1. Read the incoming message carefully — understand what the sender is saying\n" +
                "2. Consider the conversation history — has this topic come up before?\n" +
                "3. Reply APPROPRIATELY based on the rule action below — don't just send a canned response\n" +
                "4. Use replyToNotification(package=\"${pending.packageName}\", message=\"your reply\") to send the reply\n" +
                "5. After sending the reply, call complete(success=true, reason=\"...\")\n\n" +
                "IMPORTANT: Reply to the SPECIFIC message they sent, not a generic greeting."
            )
        }

        // Process any more pending replies that might have become available
        processPendingReplies()
    }

    /**
     * Reply to a notification via RemoteInput.
     * Finds the reply action in the notification + sends the reply text.
     *
     * v4.0.67: Now accepts senderName to reply to a SPECIFIC conversation.
     * If senderName is provided, only replies to notifications from that sender.
     * If null/empty, replies to the first matching notification (old behavior).
     *
     * @param packageName The app package (e.g., "com.whatsapp")
     * @param replyText The text to send as a reply
     * @param senderName Optional — the sender's name (e.g., "Mom") to target a specific conversation
     * @return true if reply was sent successfully
     */
    fun replyToNotification(packageName: String, replyText: String, senderName: String? = null): Boolean {
        try {
            val allNotifs = activeNotifications ?: return false
            var activeNotifs = allNotifs.filter { it.packageName == packageName }
            if (activeNotifs.isNullOrEmpty()) {
                Log.w(TAG, "No active notifications from $packageName")
                return false
            }

            // v4.0.67: If senderName provided, filter to that specific sender's notification
            if (!senderName.isNullOrBlank()) {
                val senderNotifs = activeNotifs.filter { sbn ->
                    val title = sbn.notification?.extras?.getString(Notification.EXTRA_TITLE)?.toString() ?: ""
                    val text = sbn.notification?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
                    title.contains(senderName, ignoreCase = true) || text.contains(senderName, ignoreCase = true)
                }
                if (senderNotifs.isNotEmpty()) {
                    Log.i(TAG, "Found ${senderNotifs.size} notification(s) from '$senderName' in $packageName")
                    activeNotifs = senderNotifs
                } else {
                    Log.w(TAG, "No notification from '$senderName' in $packageName — replying to first available")
                    // Fallback: reply to first available (better than not replying at all)
                }
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
                    Log.i(TAG, "✅ Replied to $packageName${if (senderName != null) " ($senderName)" else ""}: $replyText")
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
