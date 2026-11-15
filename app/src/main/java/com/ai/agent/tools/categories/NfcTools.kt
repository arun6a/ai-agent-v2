package com.ai.agent.tools.categories

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.util.Log
import com.ai.agent.llm.LLMClient
import com.ai.agent.tools.ToolExecutor

/**
 * v4.0.77: NfcTools — NFC tag reading + writing.
 *
 * Phase 1 of the "Phone as Software Flipper Zero" vision.
 *
 * Tools:
 * 1. readNfcTag() — reads UID + type + NDEF data from a tag
 * 2. writeNfcTag(data) — writes NDEF text to a writable tag
 * 3. listNfcHistory() — shows all tags Aira has read (from memory)
 *
 * Prerequisites:
 * - android.permission.NFC in AndroidManifest
 * - NFC must be enabled in system settings
 *
 * Safety:
 * - readNfcTag: READ-ONLY (no consent needed)
 * - writeNfcTag: WRITE (consent required — matches existing consent flow)
 * - emulateNfcCard: deferred (Phase 2 — requires HCE service)
 */
object NfcTools {
    private const val TAG = "NfcTools"

    suspend fun execute(
        call: LLMClient.ToolCall,
        context: Context,
    ): ToolExecutor.ToolResult {
        return when (call.name) {
            "readNfcTag" -> readNfcTag(context)
            "writeNfcTag" -> writeNfcTag(call.args, context)
            "listNfcHistory" -> listNfcHistory(context)
            else -> ToolExecutor.ToolResult(false, "Unknown NFC tool: ${call.name}")
        }
    }

    /**
     * Read the last detected NFC tag.
     * Note: NFC reading requires the tag to be presented to the phone while
     * Aira is in the foreground. The tag data is captured via the foreground
     * dispatch in AgentService.
     *
     * This tool returns the LAST tag that was detected (cached in memory).
     * If no tag was detected, instructs the user to tap a tag.
     */
    private fun readNfcTag(context: Context): ToolExecutor.ToolResult {
        return try {
            val adapter = NfcAdapter.getDefaultAdapter(context)
            if (adapter == null) {
                return ToolExecutor.ToolResult(false, "NFC is not available on this device.")
            }
            if (!adapter.isEnabled) {
                return ToolExecutor.ToolResult(false,
                    "NFC is disabled. Please enable it in Settings → Connected devices → NFC.")
            }

            // Check for cached tag (set by AgentService's foreground dispatch)
            val cachedTag = NfcTagCache.getLastTag()
            if (cachedTag != null) {
                Log.i(TAG, "Returning cached NFC tag: ${cachedTag.uid}")
                return ToolExecutor.ToolResult(true, cachedTag.format())
            }

            // No cached tag — instruct user to tap one
            ToolExecutor.ToolResult(true,
                "No NFC tag detected yet.\n" +
                "Please tap an NFC tag on the back of your phone while Aira is open.\n" +
                "I'll read it automatically when you tap.\n\n" +
                "Note: NFC must be enabled in system settings.")
        } catch (e: Exception) {
            ToolExecutor.ToolResult(false, "NFC read failed: ${e.message}")
        }
    }

    /**
     * Write NDEF text data to a writable NFC tag.
     * REQUIRES consent — matches existing consent flow for write operations.
     */
    private fun writeNfcTag(args: Map<String, Any>, context: Context): ToolExecutor.ToolResult {
        val text = args["text"] as? String
            ?: return ToolExecutor.ToolResult(false, "missing text. Example: writeNfcTag(text=\"Hello World\")")

        return try {
            val adapter = NfcAdapter.getDefaultAdapter(context)
            if (adapter == null) {
                return ToolExecutor.ToolResult(false, "NFC is not available on this device.")
            }
            if (!adapter.isEnabled) {
                return ToolExecutor.ToolResult(false, "NFC is disabled. Enable in Settings.")
            }

            val tag = NfcTagCache.getLastAndroidTag()
            if (tag == null) {
                return ToolExecutor.ToolResult(false,
                    "No writable NFC tag detected. Tap a writable tag on your phone first.")
            }

            val ndef = Ndef.get(tag)
            if (ndef == null) {
                return ToolExecutor.ToolResult(false,
                    "This tag is not NDEF-formatable. It may be a read-only MIFARE Classic tag.")
            }

            // Create NDEF message
            val ndefRecord = NdefRecord.createTextRecord("en", text)
            val ndefMessage = NdefMessage(arrayOf(ndefRecord))

            // Check if tag is writable
            if (ndef.isWritable) {
                ndef.connect()
                try {
                    if (ndef.maxSize < ndefMessage.toByteArray().size) {
                        return ToolExecutor.ToolResult(false,
                            "Tag too small. Message: ${ndefMessage.toByteArray().size} bytes, Tag max: ${ndef.maxSize} bytes.")
                    }
                    ndef.writeNdefMessage(ndefMessage)
                    Log.i(TAG, "Wrote NDEF text to tag: $text")
                    ToolExecutor.ToolResult(true,
                        "✅ Wrote \"$text\" to NFC tag.\n" +
                        "Size: ${ndefMessage.toByteArray().size} bytes\n" +
                        "Tag type: ${tagToString(ndef.type)}")
                } finally {
                    ndef.close()
                }
            } else {
                ToolExecutor.ToolResult(false, "This tag is read-only and cannot be written to.")
            }
        } catch (e: Exception) {
            ToolExecutor.ToolResult(false, "NFC write failed: ${e.message}")
        }
    }

    /**
     * List all NFC tags Aira has read (from memory store).
     */
    private fun listNfcHistory(context: Context): ToolExecutor.ToolResult {
        val history = NfcTagCache.getAllTags()
        return if (history.isEmpty()) {
            ToolExecutor.ToolResult(true, "No NFC tags have been read yet.")
        } else {
            val sb = StringBuilder("NFC tags read (${history.size}):\n")
            for (tag in history.takeLast(20)) {
                sb.append("- ${tag.uid} (${tag.type})")
                if (tag.textData != null) sb.append(": ${tag.textData.take(50)}")
                sb.append(" @ ${tag.timestamp}\n")
            }
            ToolExecutor.ToolResult(true, sb.toString())
        }
    }

    private fun tagToString(type: String): String = when (type) {
        "org.nfcforum.ndef.type2" -> "NFC Forum Type 2"
        "org.nfcforum.ndef.type4" -> "NFC Forum Type 4"
        "com.nxp.ndef.mifareclassic" -> "MIFARE Classic"
        "com.nxp.ndef.mifareultralight" -> "MIFARE Ultralight"
        else -> type
    }
}

/**
 * In-memory cache for the last detected NFC tag.
 * AgentService's foreground dispatch writes here when a tag is detected.
 */
object NfcTagCache {
    data class TagInfo(
        val uid: String,
        val type: String,
        val textData: String?,
        val rawTechList: List<String>,
        val timestamp: String,
    ) {
        fun format(): String {
            val sb = StringBuilder("NFC Tag:\n")
            sb.append("UID: $uid\n")
            sb.append("Type: $type\n")
            sb.append("Technologies: ${rawTechList.joinToString(", ")}\n")
            if (textData != null) {
                sb.append("NDEF Text: $textData\n")
            }
            sb.append("Read at: $timestamp\n")
            return sb.toString()
        }
    }

    @Volatile
    private var lastTag: TagInfo? = null
    @Volatile
    private var lastAndroidTag: Tag? = null
    @Volatile
    private var allTags: MutableList<TagInfo> = mutableListOf()

    fun setLastTag(tag: Tag?, uid: String, type: String, textData: String?, techList: List<String>) {
        val info = TagInfo(
            uid = uid,
            type = type,
            textData = textData,
            rawTechList = techList,
            timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date())
        )
        lastTag = info
        lastAndroidTag = tag
        allTags.add(info)
        if (allTags.size > 100) allTags = allTags.takeLast(100).toMutableList()
    }

    fun getLastTag(): TagInfo? = lastTag
    fun getLastAndroidTag(): Tag? = lastAndroidTag
    fun getAllTags(): List<TagInfo> = allTags.toList()

    fun clear() {
        lastTag = null
        lastAndroidTag = null
        allTags.clear()
    }
}
