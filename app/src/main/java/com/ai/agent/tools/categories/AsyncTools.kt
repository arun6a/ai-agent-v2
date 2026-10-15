package com.ai.agent.tools.categories

import android.content.Context
import com.ai.agent.accessibility.AgentAccessibilityService
import com.ai.agent.llm.LLMClient
import com.ai.agent.storage.AgentDatabase
import com.ai.agent.tools.ToolExecutor
import kotlinx.coroutines.delay

/**
 * Async tools category — extracted from ToolExecutor in v3.3.13.
 *
 * Tools: wait, waitAndContinue.
 *
 * No behavior changes — code moved verbatim from ToolExecutor.kt.
 */
object AsyncTools {

    suspend fun execute(
        call: LLMClient.ToolCall,
        @Suppress("UNUSED_PARAMETER") context: Context,
        @Suppress("UNUSED_PARAMETER") service: AgentAccessibilityService?,
        @Suppress("UNUSED_PARAMETER") database: AgentDatabase
    ): ToolExecutor.ToolResult {
        return when (call.name) {
            "wait" -> {
                val seconds = (call.args["seconds"] as? Number)?.toInt() ?: 2
                delay(seconds * 1000L)
                ToolExecutor.ToolResult(true, "waited ${seconds}s")
            }
            "waitAndContinue" -> {
                val seconds = (call.args["seconds"] as? Number)?.toInt() ?: 10
                val reason = call.args["reason"] as? String ?: "waiting"
                delay(seconds * 1000L)
                ToolExecutor.ToolResult(true, "Waited ${seconds}s (${reason}). Continuing — check the status now.")
            }
            else -> ToolExecutor.ToolResult(false, "Unknown async tool: ${call.name}")
        }
    }
}
