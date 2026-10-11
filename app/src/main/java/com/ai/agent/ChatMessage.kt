package com.ai.agent

data class ChatMessage(
    val id: Long = System.currentTimeMillis(),
    val text: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val status: MessageStatus = MessageStatus.DONE
)

enum class MessageStatus {
    PENDING,    // waiting for AI
    THINKING,   // AI is processing
    DONE        // complete with response
}
