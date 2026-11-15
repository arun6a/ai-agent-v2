package com.ai.agent.llm

/**
 * v3.2.0: ModelCapability — tags for categorizing AI models by what they can do.
 *
 * Used to filter models in AI Settings sections:
 * - LLM section shows models tagged Text + Tools
 * - Vision section shows models tagged Vision
 * - Audio section shows models tagged Audio/Multimodal
 * - Future: Image Gen, Video, Coding sections
 */
enum class ModelCapability {
    TEXT,           // Basic LLM (chat, rules, smart tasks)
    VISION,         // Can see images (screen reading, VLM)
    TOOLS,          // Supports function calling (agent loop)
    REASONING,      // Deep thinking (complex tasks)
    IMAGE_GEN,      // Can generate images
    VIDEO,          // Can generate video
    AUDIO,          // Can do TTS/STT
    CODING,         // Good at code generation
    MULTIMODAL,     // Text + vision + audio
    FAST,           // Low latency (quick replies)
    FREE;           // No cost

    companion object {
        /** Tags relevant for LLM/agent tasks */
        val LLM_TAGS = setOf(TEXT, TOOLS, REASONING)
        
        /** Tags relevant for vision/screen reading */
        val VISION_TAGS = setOf(VISION, MULTIMODAL)
        
        /** Tags relevant for audio */
        val AUDIO_TAGS = setOf(AUDIO, MULTIMODAL)
        
        /** Tags relevant for image generation */
        val IMAGE_TAGS = setOf(IMAGE_GEN)
    }
}
