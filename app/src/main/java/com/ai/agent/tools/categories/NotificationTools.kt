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
     */
    private fun replyViaNotification(context: Context, packageName: String, replyText: String): Boolean {
        val listener = com.ai.agent.rules.NotificationListener.instance
        if (listener != null) {
            return listener.replyToNotification(packageName, replyText)
        }
        Log.w(TAG, "replyViaNotification: NotificationListener not connected — use openWhatsAppChat instead")
        return false
    }
}
