package com.ai.agent.util

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * v3.7.2: In-app log capture — ring buffer of the last 500 log lines.
 *
 * Why: User has no logcat access (Android 13+ + MIUI). This captures logs
 * in-memory so they can be viewed in Hub → Logs + copied/shared to AI
 * for debugging.
 *
 * Usage — call [i] / [w] / [e] instead of (or alongside) `Log.i(TAG, msg)`:
 *   LogCapture.i("ChatViewModel", "Tier 2 picked, model=GLM-4.6")
 *   LogCapture.w("ToolExecutor", "actionChain PAUSED at action 3")
 *
 * Each call ALSO writes to system logcat (so ADB users still see it), then
 * appends to the ring buffer. Older lines drop off automatically at 500.
 */
object LogCapture {
    private const val MAX_LINES = 500

    data class LogLine(
        val timestamp: Long,
        val tag: String,
        val level: String,
        val message: String,
    )

    private val buffer = ConcurrentLinkedDeque<LogLine>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun log(tag: String, level: String, message: String) {
        // Also write to system logcat (so ADB users still see it).
        when (level) {
            "I" -> Log.i(tag, message)
            "W" -> Log.w(tag, message)
            "E" -> Log.e(tag, message)
            "D" -> Log.d(tag, message)
        }

        // Add to ring buffer.
        buffer.addLast(LogLine(System.currentTimeMillis(), tag, level, message))
        while (buffer.size > MAX_LINES) {
            buffer.pollFirst()
        }
    }

    /** Convenience for info-level logs (most common). */
    fun i(tag: String, message: String) = log(tag, "I", message)

    /** Convenience for warn-level logs. */
    fun w(tag: String, message: String) = log(tag, "W", message)

    /** Convenience for error-level logs. */
    fun e(tag: String, message: String) = log(tag, "E", message)

    /** Convenience for debug-level logs. */
    fun d(tag: String, message: String) = log(tag, "D", message)

    /** Get all captured logs (oldest first, newest last). */
    fun getAll(): List<LogLine> = buffer.toList()

    /** Get logs filtered by tag. */
    fun getByTag(tag: String): List<LogLine> = buffer.filter { it.tag == tag }

    /** Get logs filtered by level. */
    fun getByLevel(level: String): List<LogLine> = buffer.filter { it.level == level }

    /** Clear the buffer. */
    fun clear() {
        buffer.clear()
        log("LogCapture", "I", "Log buffer cleared")
    }

    /** Format a log line for display/copy. */
    fun formatLine(line: LogLine): String {
        val time = timeFormat.format(Date(line.timestamp))
        return "$time ${line.level}/${line.tag}: ${line.message}"
    }

    /** Get all logs as a single string (for copy/export). */
    fun getAllAsText(): String = buffer.joinToString("\n") { formatLine(it) }

    /**
     * v4.0.7: Get logs from the last task only.
     * A "task" starts at a line containing "===== Agent loop started" and ends
     * at the next "===== Agent loop started" or the end of the buffer.
     * Returns all lines from the last task start to now.
     */
    fun getLastTaskAsText(): String {
        val all = buffer.toList()
        val lastTaskStart = all.indexOfLast { it.message.contains("===== Agent loop started") }
        if (lastTaskStart == -1) return getAllAsText()
        return all.drop(lastTaskStart).joinToString("\n") { formatLine(it) }
    }

    /**
     * v4.0.7: Get the count of errors (level "E") currently in the buffer.
     */
    fun getErrorCount(): Int = buffer.count { it.level == "E" }

    /**
     * v4.0.7: Get the count of warnings (level "W") currently in the buffer.
     */
    fun getWarningCount(): Int = buffer.count { it.level == "W" }

    /** Get the current line count. */
    fun size(): Int = buffer.size
}
