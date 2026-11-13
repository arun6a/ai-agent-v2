package com.ai.agent.util

/**
 * v4.0.100: Shell command validator — extracted from CustomTools.kt for testability.
 *
 * Validates shell commands before execution to prevent dangerous operations.
 * Pure function, no Android dependencies — fully unit-testable.
 *
 * AUDIT NOTE: The denylist is TRIVIALLY BYPASSABLE.
 * These tests DOCUMENT current behavior (including gaps).
 */
object ShellCommandValidator {

    private val BLOCKED_PATTERNS = listOf(
        "rm -rf /", "rm -rf /sdcard", "rm -rf /storage",
        "mkfs", "dd if=", "> /dev/sd", "format",
        ":(){ :|:& };:", "fork bomb",
        "shutdown", "reboot", "halt",
        "chmod 777 /", "chown -R root",
    )

    fun findBlockedPattern(command: String): String? {
        val cmdLower = command.lowercase()
        return BLOCKED_PATTERNS.find { blocked ->
            cmdLower.contains(blocked.lowercase())
        }
    }

    fun isSafe(command: String): Boolean = findBlockedPattern(command) == null
}
