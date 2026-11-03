package com.ai.agent.tools.planning

/**
 * v4.0.76: ConversationStore — tracks iteration history for the agent loop.
 *
 * Stores each iteration's text (brain reply + tool results) so the model
 * can recall specific old steps via recallConversationStep(step) and get a
 * progress summary via summarizeProgress().
 *
 * This is the "externalized memory" pattern — instead of sending the entire
 * conversation every iteration (which grows unbounded), we store it here
 * and let the model request specific parts on-demand.
 *
 * The store is cleared when a new task starts (new user message).
 */
object ConversationStore {
    @Volatile
    private var iterations: MutableList<IterationEntry> = mutableListOf()

    @Volatile
    private var progressSummary: String? = null  // cached summary (set by compression)

    data class IterationEntry(
        val step: Int,
        val brainReply: String,
        val toolCallsSummary: String,  // e.g. "launchApp(twitter), readScreen()"
        val toolResultsSummary: String,  // truncated results
    )

    fun addIteration(step: Int, brainReply: String, toolCallsSummary: String, toolResultsSummary: String) {
        iterations.add(IterationEntry(step, brainReply.take(500), toolCallsSummary, toolResultsSummary.take(1000)))
        // Cap at 80 entries (matches max iterations)
        if (iterations.size > 80) {
            iterations = iterations.takeLast(80).toMutableList()
        }
    }

    fun getIteration(step: Int): IterationEntry? {
        return iterations.find { it.step == step }
    }

    fun getAllIterations(): List<IterationEntry> = iterations.toList()

    fun getRecentIterations(count: Int): List<IterationEntry> {
        return iterations.takeLast(count)
    }

    fun setProgressSummary(summary: String) {
        progressSummary = summary
    }

    fun getProgressSummary(): String? = progressSummary

    fun size(): Int = iterations.size

    fun clear() {
        iterations.clear()
        progressSummary = null
    }
}
