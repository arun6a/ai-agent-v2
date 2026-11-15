package com.ai.agent.tools.categories

import android.content.Context
import android.util.Log
import com.ai.agent.accessibility.AgentAccessibilityService
import com.ai.agent.llm.LLMClient
import com.ai.agent.storage.AgentDatabase
import com.ai.agent.tools.ToolExecutor

/**
 * Notification tools category — extracted from ToolExecutor in v3.3.13.
 *
 * Tools: replyToNotification.
 *
 * Includes the replyViaNotification helper (moved verbatim from ToolExecutor.kt).
 *
 * No behavior changes — code moved verbatim from ToolExecutor.kt.
 */
object NotificationTools {

    private const val TAG = "NotificationTools"

    suspend fun execute(
        call: LLMClient.ToolCall,
        context: Context,
        @Suppress("UNUSED_PARAMETER") service: AgentAccessibilityService?,
        @Suppress("UNUSED_PARAMETER") database: AgentDatabase
    ): ToolExecutor.ToolResult {
        return when (call.name) {
            "replyToNotification" -> {
                val pkg = call.args["package"] as? String ?: return ToolExecutor.ToolResult(false, "missing package")
                val replyText = call.args["message"] as? String ?: return ToolExecutor.ToolResult(false, "missing message")
                try {
                    // NotificationListener.replyToNotification is a companion method — need instance
                    // For now, use the static approach via intent
                    val replied = replyViaNotification(context, pkg, replyText)
                    ToolExecutor.ToolResult(replied, if (replied) "Replied to $pkg: $replyText" else "Could not reply — no active notification with reply action from $pkg")
                } catch (e: Exception) {
                    ToolExecutor.ToolResult(false, "Reply error: ${e.message}")
                }
            }
            else -> ToolExecutor.ToolResult(false, "Unknown notification tool: ${call.name}")
        }
    }

    /**
     * Reply to a notification via NotificationListener's replyToNotification method.
     * Sends a message directly through the notification (no app opening needed).
     *
     * v4.0.24: If NotificationListener.instance is null (disconnected), try to
     * rebind it via requestRebind before giving up. This handles the case where
     * Android killed the listener silently (no onListenerDisconnected callback).
     */
    private fun replyViaNotification(context: Context, packageName: String, replyText: String): Boolean {
        val listener = com.ai.agent.rules.NotificationListener.instance
        if (listener != null) {
            return listener.replyToNotification(packageName, replyText)
        }
        // v4.0.24: Listener not connected — try to rebind it.
        Log.w(TAG, "NotificationListener not connected — requesting rebind before giving up")
        com.ai.agent.rules.NotificationListener.ensureConnected(context)
        // Check again after rebind request (rebind is async, but sometimes instant)
        val listenerRetry = com.ai.agent.rules.NotificationListener.instance
        if (listenerRetry != null) {
            Log.i(TAG, "NotificationListener reconnected after rebind request — retrying reply")
            return listenerRetry.replyToNotification(packageName, replyText)
        }
        Log.w(TAG, "replyViaNotification: NotificationListener still not connected after rebind — use openWhatsAppChat instead")
        return false
    }
}
