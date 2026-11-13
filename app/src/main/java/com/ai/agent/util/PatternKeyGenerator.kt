package com.ai.agent.util

/**
 * v4.0.100: Pattern key generator — extracted from PatternLearner.kt for testability.
 *
 * Generates a storage key from a user message for Pattern Learning.
 * Pure function, no Android dependencies — fully unit-testable.
 *
 * AUDIT NOTE: The current algorithm has COLLISION BUGS.
 * "play X on youtube" and "play Y on youtube" both map to the same key
 * if X is short enough (take(40) + strip non-alphanumerics).
 * These tests DOCUMENT the collision behavior.
 */
object PatternKeyGenerator {

    /**
     * Generate a pattern key from a user message.
     * Algorithm: "pattern_" + first 40 chars + spaces→underscores + strip non-alphanumerics + lowercase
     */
    fun generate(userMessage: String): String {
        return "pattern_" + userMessage.take(40)
            .replace(" ", "_")
            .replace(Regex("[^a-zA-Z0-9_]"), "")
            .lowercase()
    }
}
