package com.ai.agent.util

/**
 * v4.0.100: Tier classifier — extracted from ContextualPromptBuilder.
 *
 * Classifies user messages into tiers (1=greeting, 2=action, 3=complex).
 * Pure function, no Android dependencies — fully unit-testable.
 */
object TierClassifier {

    private val NO_TOOL_PATTERNS = listOf(
        "hi", "hello", "hey", "hai", "hola", "namaste",
        "thanks", "thank you", "thx", "ok", "okay", "k",
        "yes", "no", "nope", "yeah", "yep",
        "how are you", "how's it going", "what's up", "sup",
        "what can you do", "who are you", "what are you",
        "good morning", "good night", "good afternoon", "good evening",
        "bye", "goodbye", "see you",
        "lol", "haha", "nice", "cool", "great", "awesome",
    )

    private val MEDIUM_COMPLEXITY_KEYWORDS = listOf(" and ", " then ", " after ")

    /**
     * Check if a message needs no tools (Tier 1 — greetings, simple replies).
     */
    fun isNoToolMessage(msg: String): Boolean {
        val lower = msg.lowercase().trim()
        return NO_TOOL_PATTERNS.any { pattern ->
            lower == pattern ||
            lower.startsWith(pattern + " ") ||
            lower.startsWith(pattern + "!") ||
            lower.startsWith(pattern + ".") ||
            lower.startsWith(pattern + "?")
        }
    }

    /**
     * Check if a message is medium complexity (3+ tool groups matched OR contains
     * "and"/"then"/"after" — multi-step tasks).
     */
    fun isMediumComplexity(groupCount: Int, msg: String): Boolean {
        val lower = msg.lowercase()
        return groupCount >= 3 || MEDIUM_COMPLEXITY_KEYWORDS.any { lower.contains(it) }
    }

    /**
     * Classify a message into a tier.
     * @param groupCount number of tool groups matched (0 = no match, 1+ = matched)
     * @return 1 (greeting), 2 (action), or 3 (complex)
     */
    fun classify(msg: String, groupCount: Int): Int {
        if (isNoToolMessage(msg)) return 1
        if (groupCount > 0) return 2
        return 3
    }
}
