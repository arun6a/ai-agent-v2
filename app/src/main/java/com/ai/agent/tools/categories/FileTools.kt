package com.ai.agent.tools.categories

import android.content.Context
import com.ai.agent.accessibility.AgentAccessibilityService
import com.ai.agent.llm.LLMClient
import com.ai.agent.storage.AgentDatabase
import com.ai.agent.tools.ToolExecutor

/**
 * File tools category — extracted from ToolExecutor in v3.3.12.
 *
 * Tools: listFiles, readFile, writeFile, copyFile, moveFile, deleteFile,
 * createDirectory, listAttachments, readAttachment, sendAttachmentTo.
 *
 * No behavior changes — code moved verbatim from ToolExecutor.kt.
 */
object FileTools {

    suspend fun execute(
        call: LLMClient.ToolCall,
        context: Context,
        @Suppress("UNUSED_PARAMETER") service: AgentAccessibilityService?,
        @Suppress("UNUSED_PARAMETER") database: AgentDatabase
    ): ToolExecutor.ToolResult {
        return when (call.name) {
            "listFiles" -> {
                val path = call.args["path"] as? String ?: "/storage/emulated/0/Aira"
                try {
                    val dir = java.io.File(path)
                    if (!dir.exists()) return ToolExecutor.ToolResult(false, "Directory not found: $path")
                    val files = dir.listFiles()?.joinToString("\n") { f ->
                        "${if (f.isDirectory) "[DIR]" else "      "} ${f.name} (${f.length()} bytes)"
                    } ?: "(empty or no access)"
                    ToolExecutor.ToolResult(true, "Files in $path:\n$files")
                } catch (e: Exception) {
                    ToolExecutor.ToolResult(false, "Error: ${e.message}")
                }
            }
            "readFile" -> {
                val path = call.args["path"] as? String ?: return ToolExecutor.ToolResult(false, "missing path")
                try {
                    val content = java.io.File(path).readText()
                    ToolExecutor.ToolResult(true, content.take(2000))  // limit to 2000 chars
                } catch (e: Exception) {
                    ToolExecutor.ToolResult(false, "Error reading file: ${e.message}")
                }
            }
            "writeFile" -> {
                val path = call.args["path"] as? String ?: return ToolExecutor.ToolResult(false, "missing path")
                val content = call.args["content"] as? String ?: return ToolExecutor.ToolResult(false, "missing content")
                try {
                    val file = java.io.File(path)
                    file.parentFile?.mkdirs()
                    file.writeText(content)
                    ToolExecutor.ToolResult(true, "File written: $path (${content.length} chars)")
                } catch (e: Exception) {
                    ToolExecutor.ToolResult(false, "Error writing file: ${e.message}")
                }
            }
            "copyFile" -> {
                val src = call.args["source"] as? String ?: return ToolExecutor.ToolResult(false, "missing source")
                val dst = call.args["destination"] as? String ?: return ToolExecutor.ToolResult(false, "missing destination")
                try {
                    java.io.File(src).copyTo(java.io.File(dst), overwrite = true)
                    ToolExecutor.ToolResult(true, "Copied $src to $dst")
                } catch (e: Exception) { ToolExecutor.ToolResult(false, "Copy error: ${e.message}") }
            }
            "moveFile" -> {
                val src = call.args["source"] as? String ?: return ToolExecutor.ToolResult(false, "missing source")
                val dst = call.args["destination"] as? String ?: return ToolExecutor.ToolResult(false, "missing destination")
                try {
                    java.io.File(src).renameTo(java.io.File(dst))
                    ToolExecutor.ToolResult(true, "Moved $src to $dst")
                } catch (e: Exception) { ToolExecutor.ToolResult(false, "Move error: ${e.message}") }
            }
            "deleteFile" -> {
                val path = call.args["path"] as? String ?: return ToolExecutor.ToolResult(false, "missing path")
                try {
                    val file = java.io.File(path)
                    val deleted = file.delete()
                    ToolExecutor.ToolResult(deleted, if (deleted) "Deleted $path" else "Could not delete $path")
                } catch (e: Exception) { ToolExecutor.ToolResult(false, "Delete error: ${e.message}") }
            }
            "createDirectory" -> {
                val path = call.args["path"] as? String ?: return ToolExecutor.ToolResult(false, "missing path")
                try {
                    val dir = java.io.File(path)
                    val created = dir.mkdirs()
                    ToolExecutor.ToolResult(created || dir.exists(), "Directory $path ${if (created) "created" else "already exists"}")
                } catch (e: Exception) { ToolExecutor.ToolResult(false, "Error: ${e.message}") }
            }
            "listAttachments" -> {
                val dir = java.io.File("/storage/emulated/0/Aira/attachments")
                if (!dir.exists()) {
                    ToolExecutor.ToolResult(true, "No attachments directory. Files attached to chat messages will appear here.")
                } else {
                    val files = dir.listFiles()?.filter { it.isFile } ?: emptyList()
                    if (files.isEmpty()) {
                        ToolExecutor.ToolResult(true, "No attachments found.")
                    } else {
                        val sb = StringBuilder("Attachments (${files.size}):\n")
                        for (file in files.take(20)) {
                            val sizeKb = file.length() / 1024
                            sb.append("- ${file.name} (${sizeKb}KB)\n")
                        }
                        ToolExecutor.ToolResult(true, sb.toString())
                    }
                }
            }
            "readAttachment" -> {
                val filename = call.args["filename"] as? String ?: return ToolExecutor.ToolResult(false, "missing filename")
                val dir = java.io.File("/storage/emulated/0/Aira/attachments")
                val file = java.io.File(dir, filename)
                if (!file.exists()) {
                    // Try exact path
                    val directFile = java.io.File(filename)
                    if (directFile.exists()) {
                        val content = directFile.readText()
                        val truncated = if (content.length > 3000) content.take(3000) + "\n[...truncated]" else content
                        ToolExecutor.ToolResult(true, "File: $filename\n\nContent:\n$truncated")
                    } else {
                        ToolExecutor.ToolResult(false, "File not found: $filename")
                    }
                } else {
                    val ext = file.extension.lowercase()
                    when (ext) {
                        "txt", "md", "json", "csv", "xml", "html", "log" -> {
                            val content = file.readText()
                            val truncated = if (content.length > 3000) content.take(3000) + "\n[...truncated]" else content
                            ToolExecutor.ToolResult(true, "File: $filename (${file.length()} bytes)\n\nContent:\n$truncated")
                        }
                        "jpg", "jpeg", "png", "webp", "bmp" -> {
                            ToolExecutor.ToolResult(true, "Image file: $filename (${file.length() / 1024}KB). Use analyzeScreen or VLM to describe the image content.")
                        }
                        "pdf" -> {
                            ToolExecutor.ToolResult(true, "PDF file: $filename (${file.length() / 1024}KB). PDF text extraction not implemented — use the file path to share or upload.")
                        }
                        else -> {
                            ToolExecutor.ToolResult(true, "File: $filename (${file.length()} bytes, type: $ext). Binary file — use shareFile to send to another app.")
                        }
                    }
                }
            }
            "sendAttachmentTo" -> {
                val filename = call.args["filename"] as? String ?: return ToolExecutor.ToolResult(false, "missing filename")
                val appPackage = call.args["package"] as? String ?: return ToolExecutor.ToolResult(false, "missing package")
                val caption = call.args["caption"] as? String  // v6.1.0: optional caption
                val dir = java.io.File("/storage/emulated/0/Aira/attachments")
                val file = java.io.File(dir, filename).let { if (it.exists()) it else java.io.File(filename) }
                if (!file.exists()) return ToolExecutor.ToolResult(false, "File not found: $filename")
                try {
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        context, "${context.packageName}.fileprovider", file
                    )
                    val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = context.contentResolver.getType(uri) ?: "*/*"
                        putExtra(android.content.Intent.EXTRA_STREAM, uri)
                        if (caption != null) {
                            putExtra(android.content.Intent.EXTRA_TEXT, caption)
                        }
                        setPackage(appPackage)
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(shareIntent)
                    val msg = if (caption != null) "Sent $filename to $appPackage with caption: $caption" else "Sent $filename to $appPackage"
                    ToolExecutor.ToolResult(true, msg)
                } catch (e: Exception) {
                    ToolExecutor.ToolResult(false, "Failed to send: ${e.message}")
                }
            }
            else -> ToolExecutor.ToolResult(false, "Unknown file tool: ${call.name}")
        }
    }
}
