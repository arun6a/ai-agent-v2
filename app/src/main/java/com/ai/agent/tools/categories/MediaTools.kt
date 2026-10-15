package com.ai.agent.tools.categories

import android.content.Context
import com.ai.agent.accessibility.AgentAccessibilityService
import com.ai.agent.llm.LLMClient
import com.ai.agent.storage.AgentDatabase
import com.ai.agent.tools.ToolExecutor

/**
 * Media tools category — extracted from ToolExecutor in v3.3.13.
 *
 * Tools: mediaPlayPause, mediaNext, mediaPrevious, takePhoto.
 *
 * No behavior changes — code moved verbatim from ToolExecutor.kt.
 */
object MediaTools {

    suspend fun execute(
        call: LLMClient.ToolCall,
        context: Context,
        @Suppress("UNUSED_PARAMETER") service: AgentAccessibilityService?,
        @Suppress("UNUSED_PARAMETER") database: AgentDatabase
    ): ToolExecutor.ToolResult {
        return when (call.name) {
            "mediaPlayPause" -> {
                val audioManager = context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
                audioManager.dispatchMediaKeyEvent(
                    android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                )
                audioManager.dispatchMediaKeyEvent(
                    android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                )
                ToolExecutor.ToolResult(true, "Play/Pause pressed")
            }
            "mediaNext" -> {
                val audioManager = context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
                audioManager.dispatchMediaKeyEvent(
                    android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_NEXT)
                )
                audioManager.dispatchMediaKeyEvent(
                    android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_MEDIA_NEXT)
                )
                ToolExecutor.ToolResult(true, "Next track")
            }
            "mediaPrevious" -> {
                val audioManager = context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
                audioManager.dispatchMediaKeyEvent(
                    android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                )
                audioManager.dispatchMediaKeyEvent(
                    android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                )
                ToolExecutor.ToolResult(true, "Previous track")
            }
            "takePhoto" -> {
                try {
                    val intent = android.content.Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).apply {
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    ToolExecutor.ToolResult(true, "Camera opened")
                } catch (e: Exception) {
                    ToolExecutor.ToolResult(false, "Cannot open camera: ${e.message}")
                }
            }
            else -> ToolExecutor.ToolResult(false, "Unknown media tool: ${call.name}")
        }
    }
}
