package com.ai.agent.storage

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * SQLite database for persistent storage.
 * Stores: conversations, memory (user facts), rules, action log.
 */
class AgentDatabase(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val DB_NAME = "ai_agent.db"
        private const val DB_VERSION = 3  // v6.0.0: added knowledge table

        // Conversations table
        const val TABLE_CONVERSATIONS = "conversations"
        const val COL_ID = "id"
        const val COL_ROLE = "role"  // "user" or "ai"
        const val COL_TEXT = "text"
        const val COL_TIMESTAMP = "timestamp"

        // Memory table (user facts and preferences)
        const val TABLE_MEMORY = "memory"
        const val COL_KEY = "key"
        const val COL_VALUE = "value"

        // Rules table
        const val TABLE_RULES = "rules"
        const val COL_NAME = "name"
        const val COL_TRIGGER_TYPE = "trigger_type"  // "time", "notification", "battery_low", "charging"
        const val COL_TRIGGER_VALUE = "trigger_value"  // "07:00", "com.whatsapp:Mom", "20"
        const val COL_ACTION = "action"  // natural language description
        const val COL_ENABLED = "enabled"
        const val COL_DAYS = "days"  // comma-separated day-of-week: "mon,tue,wed,thu,fri" or null for daily

        // Action log
        const val TABLE_LOG = "action_log"
        const val COL_ACTION_TEXT = "action_text"

        // v6.0.0: Knowledge table (documents, longer content)
        const val TABLE_KNOWLEDGE = "knowledge"
        const val COL_CONTENT = "content"
        const val COL_SOURCE = "source"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $TABLE_CONVERSATIONS (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_ROLE TEXT,
                $COL_TEXT TEXT,
                $COL_TIMESTAMP INTEGER
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE $TABLE_MEMORY (
                $COL_KEY TEXT PRIMARY KEY,
                $COL_VALUE TEXT
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE $TABLE_RULES (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_NAME TEXT,
                $COL_TRIGGER_TYPE TEXT,
                $COL_TRIGGER_VALUE TEXT,
                $COL_ACTION TEXT,
                $COL_ENABLED INTEGER DEFAULT 1,
                $COL_DAYS TEXT
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE $TABLE_LOG (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_ACTION_TEXT TEXT,
                $COL_TIMESTAMP INTEGER
            )
        """.trimIndent())

        // v6.0.0: Knowledge table (for documents, longer content — separate from memory)
        db.execSQL("""
            CREATE TABLE $TABLE_KNOWLEDGE (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_NAME TEXT,
                $COL_CONTENT TEXT,
                $COL_SOURCE TEXT,
                $COL_TIMESTAMP INTEGER
            )
        """.trimIndent())
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // v2: add `days` column to rules table for day-of-week scheduling.
            try {
                db.execSQL("ALTER TABLE $TABLE_RULES ADD COLUMN $COL_DAYS TEXT")
                android.util.Log.i("AgentDatabase", "Upgraded DB to v2 — added $COL_DAYS column to rules")
            } catch (e: Exception) {
                android.util.Log.e("AgentDatabase", "Failed to add $COL_DAYS column — recreating tables", e)
                db.execSQL("DROP TABLE IF EXISTS $TABLE_CONVERSATIONS")
                db.execSQL("DROP TABLE IF EXISTS $TABLE_MEMORY")
                db.execSQL("DROP TABLE IF EXISTS $TABLE_RULES")
                db.execSQL("DROP TABLE IF EXISTS $TABLE_LOG")
                onCreate(db)
            }
        }
        if (oldVersion < 3) {
            // v6.0.0: add knowledge table for documents
            try {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS $TABLE_KNOWLEDGE (
                        $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                        $COL_NAME TEXT,
                        $COL_CONTENT TEXT,
                        $COL_SOURCE TEXT,
                        $COL_TIMESTAMP INTEGER
                    )
                """.trimIndent())
                android.util.Log.i("AgentDatabase", "Upgraded DB to v3 — added knowledge table")
            } catch (e: Exception) {
                android.util.Log.e("AgentDatabase", "Failed to create knowledge table", e)
            }
        }
    }

    // === Knowledge (v6.0.0) ===

    fun saveKnowledge(name: String, content: String, source: String = "manual") {
        val values = ContentValues().apply {
            put(COL_NAME, name)
            put(COL_CONTENT, content)
            put(COL_SOURCE, source)
            put(COL_TIMESTAMP, System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict(TABLE_KNOWLEDGE, null, values, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getAllKnowledge(): List<Triple<String, String, String>> {
        val result = mutableListOf<Triple<String, String, String>>()
        val cursor = readableDatabase.query(
            TABLE_KNOWLEDGE, arrayOf(COL_NAME, COL_CONTENT, COL_SOURCE),
            null, null, null, null, "$COL_TIMESTAMP DESC"
        )
        cursor.use {
            while (it.moveToNext()) {
                result.add(Triple(
                    it.getString(it.getColumnIndexOrThrow(COL_NAME)) ?: "",
                    it.getString(it.getColumnIndexOrThrow(COL_CONTENT)) ?: "",
                    it.getString(it.getColumnIndexOrThrow(COL_SOURCE)) ?: ""
                ))
            }
        }
        return result
    }

    fun recallKnowledge(query: String, limit: Int = 5): List<Triple<String, String, Float>> {
        val all = getAllKnowledge()
        if (all.isEmpty()) return emptyList()
        val queryWords = query.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length > 2 }
            .toSet()
        if (queryWords.isEmpty()) return emptyList()
        return all.map { (name, content, _) ->
            val combinedText = "$name $content".lowercase()
            val memoryWords = combinedText
                .replace(Regex("[^a-z0-9\\s]"), " ")
                .split(Regex("\\s+"))
                .filter { it.length > 2 }
                .toSet()
            val intersection = queryWords.intersect(memoryWords).size
            val union = queryWords.union(memoryWords).size
            val score = if (union > 0) intersection.toFloat() / union else 0f
            Triple(name, content, score)
        }.filter { it.third > 0 }
            .sortedByDescending { it.third }
            .take(limit)
    }

    fun deleteKnowledge(name: String) {
        writableDatabase.delete(TABLE_KNOWLEDGE, "$COL_NAME = ?", arrayOf(name))
    }

    // === Conversations ===

    fun addConversation(role: String, text: String) {
        val values = ContentValues().apply {
            put(COL_ROLE, role)
            put(COL_TEXT, text)
            put(COL_TIMESTAMP, System.currentTimeMillis())
        }
        writableDatabase.insert(TABLE_CONVERSATIONS, null, values)
    }

    fun getRecentConversations(limit: Int = 20): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        val cursor = readableDatabase.query(
            TABLE_CONVERSATIONS, arrayOf(COL_ROLE, COL_TEXT),
            null, null, null, null,
            "$COL_TIMESTAMP DESC", limit.toString()
        )
        cursor.use {
            while (it.moveToNext()) {
                result.add(it.getString(0) to it.getString(1))
            }
        }
        return result.reversed()  // return in chronological order
    }

    fun clearConversations() {
        writableDatabase.delete(TABLE_CONVERSATIONS, null, null)
    }

    fun clearMemory() {
        writableDatabase.delete(TABLE_MEMORY, null, null)
    }

    // === Memory ===

    fun remember(key: String, value: String) {
        val values = ContentValues().apply {
            put(COL_KEY, key)
            put(COL_VALUE, value)
        }
        writableDatabase.insertWithOnConflict(TABLE_MEMORY, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** v6.0.0: Forget a memory by key */
    fun forgetMemory(key: String) {
        writableDatabase.delete(TABLE_MEMORY, "$COL_KEY = ?", arrayOf(key))
    }

    fun recall(key: String): String? {
        val cursor = readableDatabase.query(
            TABLE_MEMORY, arrayOf(COL_VALUE),
            "$COL_KEY = ?", arrayOf(key),
            null, null, null
        )
        return cursor.use { if (it.moveToFirst()) it.getString(0) else null }
    }

    fun getAllMemory(): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val cursor = readableDatabase.query(TABLE_MEMORY, arrayOf(COL_KEY, COL_VALUE), null, null, null, null, null)
        cursor.use {
            while (it.moveToNext()) {
                result[it.getString(0)] = it.getString(1)
            }
        }
        return result
    }

    /**
     * Semantic recall — finds memories that are SIMILAR to the query, not just exact matches.
     * Uses simple word overlap similarity (TF-IDF inspired).
     * Returns list of (key, value, similarityScore) sorted by most similar first.
     */
    fun recallSimilar(query: String, limit: Int = 5): List<Triple<String, String, Float>> {
        val allMemory = getAllMemory()
        if (allMemory.isEmpty()) return emptyList()

        // Tokenize query into words (lowercase, remove punctuation)
        val queryWords = query.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length > 2 }
            .toSet()

        if (queryWords.isEmpty()) return emptyList()

        // Score each memory by word overlap
        val scored = allMemory.map { (key, value) ->
            val combinedText = "$key $value".lowercase()
            val memoryWords = combinedText
                .replace(Regex("[^a-z0-9\\s]"), " ")
                .split(Regex("\\s+"))
                .filter { it.length > 2 }
                .toSet()

            // Jaccard similarity: intersection / union
            val intersection = queryWords.intersect(memoryWords).size
            val union = queryWords.union(memoryWords).size
            val score = if (union > 0) intersection.toFloat() / union else 0f

            Triple(key, value, score)
        }

        // Filter: only return memories with some overlap (score > 0)
        // Sort by score descending
        return scored
            .filter { it.third > 0 }
            .sortedByDescending { it.third }
            .take(limit)
    }

    /**
     * Search memory by content (not just key) — finds memories where the value contains the query.
     */
    fun searchMemory(query: String): List<Pair<String, String>> {
        val allMemory = getAllMemory()
        val lowerQuery = query.lowercase()
        return allMemory.filter { (key, value) ->
            key.lowercase().contains(lowerQuery) || value.lowercase().contains(lowerQuery)
        }.toList()
    }

    // === Rules ===

    fun addRule(name: String, triggerType: String, triggerValue: String, action: String, days: String? = null): Long {
        val values = ContentValues().apply {
            put(COL_NAME, name)
            put(COL_TRIGGER_TYPE, triggerType)
            put(COL_TRIGGER_VALUE, triggerValue)
            put(COL_ACTION, action)
            put(COL_ENABLED, 1)
            put(COL_DAYS, days)
        }
        return writableDatabase.insert(TABLE_RULES, null, values)
    }

    fun updateRule(id: Long, name: String? = null, triggerType: String? = null,
                   triggerValue: String? = null, action: String? = null,
                   days: String? = null, enabled: Boolean? = null): Int {
        val values = ContentValues().apply {
            if (name != null) put(COL_NAME, name)
            if (triggerType != null) put(COL_TRIGGER_TYPE, triggerType)
            if (triggerValue != null) put(COL_TRIGGER_VALUE, triggerValue)
            if (action != null) put(COL_ACTION, action)
            if (enabled != null) put(COL_ENABLED, if (enabled) 1 else 0)
            // days is nullable — only update if explicitly provided (not the default sentinel)
            // Caller must pass "" to clear, or null to leave unchanged.
            // We use a sentinel here: if days parameter is provided as non-null, update it.
        }
        // Handle days explicitly — null means "don't touch", "" means clear, "mon,tue" means set
        // We can't distinguish in ContentValues, so we use a separate call when days is non-null.
        if (days != null) {
            values.put(COL_DAYS, if (days.isEmpty()) null else days)
        }
        return writableDatabase.update(TABLE_RULES, values, "$COL_ID = ?", arrayOf(id.toString()))
    }

    fun getRules(enabledOnly: Boolean = false): List<Rule> {
        val result = mutableListOf<Rule>()
        val where = if (enabledOnly) "$COL_ENABLED = 1" else null
        val cursor = readableDatabase.query(TABLE_RULES, null, where, null, null, null, null)
        cursor.use {
            while (it.moveToNext()) {
                result.add(Rule(
                    id = it.getLong(it.getColumnIndexOrThrow(COL_ID)),
                    name = it.getString(it.getColumnIndexOrThrow(COL_NAME)),
                    triggerType = it.getString(it.getColumnIndexOrThrow(COL_TRIGGER_TYPE)),
                    triggerValue = it.getString(it.getColumnIndexOrThrow(COL_TRIGGER_VALUE)),
                    action = it.getString(it.getColumnIndexOrThrow(COL_ACTION)),
                    enabled = it.getInt(it.getColumnIndexOrThrow(COL_ENABLED)) == 1,
                    days = if (it.getColumnIndex(COL_DAYS) >= 0) it.getString(it.getColumnIndex(COL_DAYS)) else null
                ))
            }
        }
        return result
    }

    fun deleteRule(id: Long) {
        writableDatabase.delete(TABLE_RULES, "$COL_ID = ?", arrayOf(id.toString()))
    }

    fun toggleRule(id: Long, enabled: Boolean) {
        val values = ContentValues().apply { put(COL_ENABLED, if (enabled) 1 else 0) }
        writableDatabase.update(TABLE_RULES, values, "$COL_ID = ?", arrayOf(id.toString()))
    }

    // === Action Log ===

    fun logAction(actionText: String) {
        val values = ContentValues().apply {
            put(COL_ACTION_TEXT, actionText)
            put(COL_TIMESTAMP, System.currentTimeMillis())
        }
        writableDatabase.insert(TABLE_LOG, null, values)
    }

    data class Rule(
        val id: Long,
        val name: String,
        val triggerType: String,
        val triggerValue: String,
        val action: String,
        val enabled: Boolean,
        val days: String? = null  // "mon,tue,wed,thu,fri" or null for daily
    )
}
