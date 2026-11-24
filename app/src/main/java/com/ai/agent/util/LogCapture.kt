package com.ai.agent.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicBoolean

/**
 * v3.7.2: In-app log capture — ring buffer of the last N log lines.
 * v4.0.40: Increased from 500 → 2000 lines.
 * v4.1.6: PERSISTENT LOGGING — logs saved to disk, survive app restarts + MIUI kills.
 *
 * How it works:
 *   1. Each log line is added to the in-memory ring buffer (fast display)
 *   2. Each line is ALSO appended to a file on disk (survives restart)
 *   3. On app start, loadFromDisk() reads the file back into the buffer
 *   4. File rotates when it exceeds MAX_FILE_SIZE (10MB → keep last 5MB)
 *
 * Log file: app external files dir (no permission needed)
 */
object LogCapture {
    private const val MAX_LINES = 5000
    private const val MAX_FILE_SIZE = 10 * 1024 * 1024L
    private const val ROTATE_KEEP_SIZE = 5 * 1024 * 1024L
    private const val LOG_FILE_NAME = "aira_log.txt"
    private const val LOG_DIR_NAME = "Aira/logs"

    data class LogLine(
        val timestamp: Long,
        val tag: String,
        val level: String,
        val message: String,
    )

    private val buffer = ConcurrentLinkedDeque<LogLine>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val dateTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    private var logFile: File? = null
    private var fileWriter: PrintWriter? = null
    private val initialized = AtomicBoolean(false)

    fun init(ctx: Context) {
        if (initialized.get()) return
        if (!initialized.compareAndSet(false, true)) return
        try {
            val dir = File(ctx.getExternalFilesDir(null), LOG_DIR_NAME).apply { mkdirs() }
            logFile = File(dir, LOG_FILE_NAME)
            loadFromDisk()
            fileWriter = PrintWriter(FileWriter(logFile, true))
            log("LogCapture", "I", "=== Aira started — log persistence initialized ===")
            log("LogCapture", "I", "Log file: ${logFile?.absolutePath}")
            log("LogCapture", "I", "Buffer loaded with ${buffer.size} lines from disk")
        } catch (e: Exception) {
            Log.e("LogCapture", "Failed to init log persistence: ${e.message}", e)
        }
    }

    fun log(tag: String, level: String, message: String) {
        when (level) {
            "I" -> Log.i(tag, message)
            "W" -> Log.w(tag, message)
            "E" -> Log.e(tag, message)
            "D" -> Log.d(tag, message)
            "T" -> Log.v(tag, "[T] $message")
            "A" -> Log.v(tag, "[A] $message")
            "U" -> Log.v(tag, "[U] $message")
        }

        val line = LogLine(System.currentTimeMillis(), tag, level, message)
        buffer.addLast(line)
        while (buffer.size > MAX_LINES) { buffer.pollFirst() }

        try {
            fileWriter?.let { fw ->
                fw.println(formatLineWithDate(line))
                fw.flush()
            }
        } catch (_: Exception) {}
        checkFileRotation()
    }

    private fun loadFromDisk() {
        val file = logFile ?: return
        if (!file.exists()) return
        try {
            if (file.length() > MAX_FILE_SIZE) rotateFile()
            file.readLines().forEach { line ->
                if (line.isBlank()) return@forEach
                parseLine(line)?.let { buffer.addLast(it) }
            }
            while (buffer.size > MAX_LINES) buffer.pollFirst()
        } catch (e: Exception) {
            Log.e("LogCapture", "Failed to load logs from disk: ${e.message}")
        }
    }

    private fun parseLine(line: String): LogLine? {
        return try {
            val regex = Regex("""(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}) ([IWEATUD])/([^:]+): (.*)""")
            val match = regex.find(line) ?: return null
            val timestamp = dateTimeFormat.parse(match.groupValues[1])?.time ?: System.currentTimeMillis()
            LogLine(timestamp, match.groupValues[2], match.groupValues[3].trim(), match.groupValues[4])
        } catch (_: Exception) { null }
    }

    private fun checkFileRotation() {
        val file = logFile ?: return
        try { if (file.length() > MAX_FILE_SIZE) rotateFile() } catch (_: Exception) {}
    }

    private fun rotateFile() {
        val file = logFile ?: return
        try {
            fileWriter?.close(); fileWriter = null
            val allLines = file.readLines()
            val keepLines = allLines.takeLast(MAX_LINES)
            file.writeText(keepLines.joinToString("\n") + "\n")
            fileWriter = PrintWriter(FileWriter(file, true))
            Log.i("LogCapture", "Log file rotated: kept ${keepLines.size} lines")
        } catch (e: Exception) {
            Log.e("LogCapture", "Failed to rotate: ${e.message}")
        }
    }

    fun i(tag: String, message: String) = log(tag, "I", message)
    fun w(tag: String, message: String) = log(tag, "W", message)
    fun e(tag: String, message: String) = log(tag, "E", message)
    fun d(tag: String, message: String) = log(tag, "D", message)

    fun getAll(): List<LogLine> = buffer.toList()
    fun getByTag(tag: String): List<LogLine> = buffer.filter { it.tag == tag }
    fun getByLevel(level: String): List<LogLine> = buffer.filter { it.level == level }

    fun clear() {
        buffer.clear()
        try {
            fileWriter?.close()
            logFile?.writeText("")
            fileWriter = PrintWriter(FileWriter(logFile, true))
            log("LogCapture", "I", "=== Log buffer + file cleared ===")
        } catch (_: Exception) {}
    }

    fun formatLine(line: LogLine): String {
        val time = timeFormat.format(Date(line.timestamp))
        return "$time ${line.level}/${line.tag}: ${line.message}"
    }

    private fun formatLineWithDate(line: LogLine): String {
        val time = dateTimeFormat.format(Date(line.timestamp))
        return "$time ${line.level}/${line.tag}: ${line.message}"
    }

    fun getAllAsText(): String = buffer.joinToString("\n") { formatLine(it) }

    fun getLastTaskAsText(): String {
        val all = buffer.toList()
        val lastTaskStart = all.indexOfLast { it.message.contains("===== Agent loop started") }
        if (lastTaskStart == -1) return getAllAsText()
        return all.drop(lastTaskStart).joinToString("\n") { formatLine(it) }
    }

    fun getErrorCount(): Int = buffer.count { it.level == "E" }
    fun getWarningCount(): Int = buffer.count { it.level == "W" }
    fun size(): Int = buffer.size

    fun tokens(tag: String, message: String) = log(tag, "T", message)
    fun api(tag: String, message: String) = log(tag, "A", message)
    fun tool(tag: String, message: String) = log(tag, "U", message)

    fun getSessionSummary(): String {
        val info = buffer.count { it.level == "I" }
        val warn = buffer.count { it.level == "W" }
        val error = buffer.count { it.level == "E" }
        val tokenLogs = buffer.filter { it.level == "T" }
        val apiLogs = buffer.filter { it.level == "A" }
        val toolLogs = buffer.filter { it.level == "U" }
        val fileSize = logFile?.length() ?: 0
        return buildString {
            append("Session Summary:\n")
            append("  Total log lines: ${buffer.size}/$MAX_LINES\n")
            append("  Info (I): $info\n")
            append("  Warnings (W): $warn\n")
            append("  Errors (E): $error\n")
            append("  Token logs (T): ${tokenLogs.size}\n")
            append("  API call logs (A): ${apiLogs.size}\n")
            append("  Tool execution logs (U): ${toolLogs.size}\n")
            append("  Log file: ${if (fileSize > 0) "${fileSize / 1024}KB" else "not initialized"}\n")
            append("  Log path: ${logFile?.absolutePath ?: "N/A"}\n")
        }
    }

    fun getLogFile(): File? = logFile

    fun getFileAsText(): String {
        return try { logFile?.readText() ?: getAllAsText() } catch (_: Exception) { getAllAsText() }
    }
}
