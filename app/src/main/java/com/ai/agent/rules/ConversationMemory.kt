package com.ai.agent.rules

import android.util.Log
import com.ai.agent.storage.AgentDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * v3.0.5: ConversationMemory — per-contact conversation history for smart rules.
 *
 * When WhatsApp notification arrives:
 *   1. Save the incoming message to conversation history
 *   2. When AI replies, save the reply too
 *   3. Next time same contact messages, pass history to LLM
 *
 * Stored as JSON file: /data/data/com.ai.agent.v2/files/conversations/com.whatsapp_Mom.json
 *
 * Format:
 * [
 *   {"role": "incoming", "text": "When are you coming?", "time": "2026-10-14 12:30"},
 *   {"role": "outgoing", "text": "I'll be home by 6 PM!", "time": "2026-10-14 12:30"},
 *   {"role": "incoming", "text": "Bring milk", "time": "2026-10-14 12:35"},
 *   {"role": "outgoing", "text": "Sure, I'll grab milk!", "time": "2026-10-14 12:35"}
 * ]
 *
 * Only keeps last 10 exchanges per contact (to save space + tokens).
 */
object ConversationMemory {
    private const val TAG = "ConversationMemory"
    private const val MAX_HISTORY = 20  // max 20 entries (10 exchanges)
    private const val CONV_DIR = "conversations"

    data class Message(
        val role: String,  // "incoming" or "outgoing"
        val text: String,
        val time: String,
    )

    /**
     * Get the conversation history for a contact.
     * Key format: "com.whatsapp" or "com.whatsapp:Mom"
     */
    fun getHistory(context: android.content.Context, key: String): List<Message> {
        val file = getConvFile(context, key)
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Message(
                    role = o.optString("role"),
                    text = o.optString("text"),
                    time = o.optString("time"),
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load history for $key", e)
            emptyList()
        }
    }

    /**
     * Add an incoming message to conversation history.
     */
    fun addIncoming(context: android.content.Context, key: String, text: String) {
        addMessage(context, key, Message("incoming", text, now()))
    }

    /**
     * Add an outgoing message (AI reply) to conversation history.
     */
    fun addOutgoing(context: android.content.Context, key: String, text: String) {
        addMessage(context, key, Message("outgoing", text, now()))
    }

    /**
     * Add a message to history + trim to MAX_HISTORY.
     */
    @Synchronized
    private fun addMessage(context: android.content.Context, key: String, msg: Message) {
        try {
            val history = getHistory(context, key).toMutableList()
            history.add(msg)
            // Trim to last MAX_HISTORY entries
            while (history.size > MAX_HISTORY) {
                history.removeAt(0)
            }
            // Save
            val arr = JSONArray()
            history.forEach { m ->
                arr.put(JSONObject().apply {
                    put("role", m.role)
                    put("text", m.text)
                    put("time", m.time)
                })
            }
            val file = getConvFile(context, key)
            file.parentFile?.mkdirs()
            file.writeText(arr.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save message for $key", e)
        }
    }

    /**
     * Build conversation history as readable text for LLM prompt.
     */
    fun getHistoryForPrompt(context: android.content.Context, key: String): String {
        val history = getHistory(context, key)
        if (history.isEmpty()) return "(no previous conversation)"

        val sb = StringBuilder()
        for (msg in history) {
            val speaker = if (msg.role == "incoming") "Contact" else "You (AI)"
            sb.append("${msg.time} $speaker: ${msg.text}\n")
        }
        return sb.toString().trim()
    }

    /**
     * Clear conversation history for a contact.
     */
    fun clear(context: android.content.Context, key: String) {
        getConvFile(context, key).delete()
    }

    /**
     * v4.0.41: List ALL conversation keys (contacts/apps that have history).
     * Returns list of conversation keys (e.g., "com.whatsapp", "com.whatsapp:Mom").
     */
    fun listAllConversations(context: android.content.Context): List<String> {
        val dir = File(context.filesDir, CONV_DIR)
        if (!dir.exists()) return emptyList()
        return dir.listFiles()?.map { file ->
            // Reverse the sanitization: replace _ with : (best effort)
            file.nameWithoutExtension.replace("_", ":")
        } ?: emptyList()
    }

    /**
     * v4.0.41: Get a summary of ALL conversations.
     * Returns a readable summary of all conversations with their last few messages.
     * Used by the getConversationSummary tool so the AI can answer:
     *   "what messages did I get?" / "who texted me?" / "summarize WhatsApp replies"
     */
    fun getAllConversationsSummary(context: android.content.Context): String {
        val dir = File(context.filesDir, CONV_DIR)
        if (!dir.exists() || dir.listFiles()?.isEmpty() != false) {
            return "No conversations stored yet."
        }
        val sb = StringBuilder("Conversations Summary:\n\n")
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return sb.toString()
        for (file in files) {
            val key = file.nameWithoutExtension.replace("_", ":")
            val history = getHistory(context, key)
            if (history.isEmpty()) continue
            val incoming = history.count { it.role == "incoming" }
            val outgoing = history.count { it.role == "outgoing" }
            val lastMsg = history.lastOrNull()
            sb.append("• $key ($incoming incoming, $outgoing replies)\n")
            sb.append("  Last: ${lastMsg?.time} ${if (lastMsg?.role == "incoming") "Them" else "You"}: ${lastMsg?.text?.take(80)}\n")
            // Show last 3 exchanges
            val recent = history.takeLast(6)
            for (msg in recent) {
                val speaker = if (msg.role == "incoming") "  → Them" else "  ← You"
                sb.append("$speaker: ${msg.text.take(100)}\n")
            }
            sb.append("\n")
        }
        return sb.toString()
    }

    /**
     * v4.0.41: Get conversation history for a specific contact/app.
     * contact can be "com.whatsapp", "com.whatsapp:Mom", or "all".
     */
    fun getConversationForAI(context: android.content.Context, contact: String): String {
        if (contact.lowercase() == "all") {
            return getAllConversationsSummary(context)
        }
        // Try exact match first, then partial match
        var key = contact
        var history = getHistory(context, key)
        if (history.isEmpty()) {
            // Try matching by partial key (e.g., "whatsapp" matches "com.whatsapp")
            val allKeys = listAllConversations(context)
            val match = allKeys.find { it.contains(contact, ignoreCase = true) }
            if (match != null) {
                key = match
                history = getHistory(context, key)
            }
        }
        if (history.isEmpty()) {
            return "No conversation history found for: $contact"
        }
        val sb = StringBuilder("Conversation: $key (${history.size} messages)\n\n")
        for (msg in history) {
            val speaker = if (msg.role == "incoming") "Them" else "You (AI reply)"
            sb.append("[${msg.time}] $speaker: ${msg.text}\n")
        }
        return sb.toString()
    }

    /**
     * Clear ALL conversation histories.
     */
    fun clearAll(context: android.content.Context) {
        val dir = File(context.filesDir, CONV_DIR)
        if (dir.exists()) {
            dir.listFiles()?.forEach { it.delete() }
        }
    }

    private fun getConvFile(context: android.content.Context, key: String): File {
        // Sanitize key for filename (replace : with _)
        val safeKey = key.replace(":", "_").replace("/", "_")
        return File(context.filesDir, "$CONV_DIR/$safeKey.json")
    }

    private fun now(): String {
        return java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
    }
}
