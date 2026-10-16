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
        private const val DB_VERSION = 5  // v3.4.0: added FTS5 search tables for memory + knowledge

        // Conversations table
        const val TABLE_CONVERSATIONS = "conversations"
        const val COL_ID = "id"
        const val COL_ROLE = "role"  // "user" or "ai"
        const val COL_TEXT = "text"
        const val COL_TIMESTAMP = "timestamp"
        // v2.2.0 Issue 113a — session grouping
        const val COL_SESSION_ID = "session_id"

        // v2.2.0 Issue 113a — Sessions table (chat thread grouping)
        const val TABLE_CHAT_SESSIONS = "chat_sessions"
        const val COL_SESSION_TITLE = "title"
        const val COL_SESSION_CREATED_AT = "created_at"
        const val COL_SESSION_LAST_ACTIVE = "last_active"

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
                $COL_TIMESTAMP INTEGER,
                $COL_SESSION_ID INTEGER DEFAULT 1
            )
        """.trimIndent())

        // v2.2.0 Issue 113a — Chat sessions table
        db.execSQL("""
            CREATE TABLE $TABLE_CHAT_SESSIONS (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_SESSION_TITLE TEXT,
                $COL_SESSION_CREATED_AT INTEGER,
                $COL_SESSION_LAST_ACTIVE INTEGER
            )
        """.trimIndent())
        // Seed a default session so legacy conversations (session_id=1) have a parent row.
        val now = System.currentTimeMillis()
        db.execSQL(
            "INSERT INTO $TABLE_CHAT_SESSIONS " +
                "($COL_ID, $COL_SESSION_TITLE, $COL_SESSION_CREATED_AT, $COL_SESSION_LAST_ACTIVE) " +
                "VALUES (1, 'Default', 0, $now)"
        )

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

        // v3.4.0: FTS5 full-text search tables for memory + knowledge.
        // BM25 ranking + porter stemming (handles "wakes" → "wake", etc.).
        createFts5Tables(db)
    }

    /**
     * v3.4.0: Create FTS5 virtual tables + triggers to keep them in sync with
     * the memory and knowledge tables. Called from onCreate() and onUpgrade().
     */
    private fun createFts5Tables(db: SQLiteDatabase) {
        try {
            // === Memory FTS5 ===
            // content='memory' means FTS5 is linked to the memory table via rowid.
            // porter tokenizer: English stemming (wakes → wake, running → run)
            // unicode61: handles non-English characters (Hindi, etc.)
            db.execSQL("""
                CREATE VIRTUAL TABLE IF NOT EXISTS memory_fts USING fts5(
                    key, value,
                    content='memory', content_rowid='rowid',
                    tokenize='porter unicode61'
                )
            """.trimIndent())
            // Triggers: keep FTS5 in sync automatically.
            db.execSQL("""
                CREATE TRIGGER IF NOT EXISTS memory_fts_ai AFTER INSERT ON $TABLE_MEMORY BEGIN
                    INSERT INTO memory_fts(rowid, key, value) VALUES (new.rowid, new.key, new.value);
                END
            """.trimIndent())
            db.execSQL("""
                CREATE TRIGGER IF NOT EXISTS memory_fts_ad AFTER DELETE ON $TABLE_MEMORY BEGIN
                    INSERT INTO memory_fts(memory_fts, rowid, key, value) VALUES('delete', old.rowid, old.key, old.value);
                END
            """.trimIndent())
            db.execSQL("""
                CREATE TRIGGER IF NOT EXISTS memory_fts_au AFTER UPDATE ON $TABLE_MEMORY BEGIN
                    INSERT INTO memory_fts(memory_fts, rowid, key, value) VALUES('delete', old.rowid, old.key, old.value);
                    INSERT INTO memory_fts(rowid, key, value) VALUES (new.rowid, new.key, new.value);
                END
            """.trimIndent())

            // === Knowledge FTS5 ===
            db.execSQL("""
                CREATE VIRTUAL TABLE IF NOT EXISTS knowledge_fts USING fts5(
                    name, content,
                    content='knowledge', content_rowid='rowid',
                    tokenize='porter unicode61'
                )
            """.trimIndent())
            db.execSQL("""
                CREATE TRIGGER IF NOT EXISTS knowledge_fts_ai AFTER INSERT ON $TABLE_KNOWLEDGE BEGIN
                    INSERT INTO knowledge_fts(rowid, name, content) VALUES (new.rowid, new.name, new.content);
                END
            """.trimIndent())
            db.execSQL("""
                CREATE TRIGGER IF NOT EXISTS knowledge_fts_ad AFTER DELETE ON $TABLE_KNOWLEDGE BEGIN
                    INSERT INTO knowledge_fts(knowledge_fts, rowid, name, content) VALUES('delete', old.rowid, old.name, old.content);
                END
            """.trimIndent())
            db.execSQL("""
                CREATE TRIGGER IF NOT EXISTS knowledge_fts_au AFTER UPDATE ON $TABLE_KNOWLEDGE BEGIN
                    INSERT INTO knowledge_fts(knowledge_fts, rowid, name, content) VALUES('delete', old.rowid, old.name, old.content);
                    INSERT INTO knowledge_fts(rowid, name, content) VALUES (new.rowid, new.name, new.content);
                END
            """.trimIndent())

            android.util.Log.i("AgentDatabase", "FTS5 tables + triggers created")
        } catch (e: Exception) {
            android.util.Log.e("AgentDatabase", "Failed to create FTS5 tables (may not be supported): ${e.message}")
        }
    }

    /**
     * v3.4.0: Backfill FTS5 index from existing memory/knowledge rows.
     * Called during onUpgrade() when migrating to DB version 5.
     */
    private fun backfillFts5(db: SQLiteDatabase) {
        try {
            db.execSQL("INSERT INTO memory_fts(rowid, key, value) SELECT rowid, key, value FROM memory")
            db.execSQL("INSERT INTO knowledge_fts(rowid, name, content) SELECT rowid, name, content FROM knowledge")
            android.util.Log.i("AgentDatabase", "FTS5 backfill complete")
        } catch (e: Exception) {
            android.util.Log.w("AgentDatabase", "FTS5 backfill (may already have data): ${e.message}")
        }
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
        if (oldVersion < 4) {
            // v2.2.0 Issue 113a — chat sessions table + session_id column.
            try {
                db.execSQL(
                    "ALTER TABLE $TABLE_CONVERSATIONS ADD COLUMN $COL_SESSION_ID INTEGER DEFAULT 1"
                )
                android.util.Log.i("AgentDatabase", "Added $COL_SESSION_ID column to $TABLE_CONVERSATIONS")
            } catch (e: Exception) {
                android.util.Log.w("AgentDatabase", "session_id column already exists: ${e.message}")
            }
            try {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS $TABLE_CHAT_SESSIONS (
                        $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                        $COL_SESSION_TITLE TEXT,
                        $COL_SESSION_CREATED_AT INTEGER,
                        $COL_SESSION_LAST_ACTIVE INTEGER
                    )
                """.trimIndent())
                // Insert a default session for existing conversations (session_id=1)
                val now = System.currentTimeMillis()
                db.execSQL(
                    "INSERT OR IGNORE INTO $TABLE_CHAT_SESSIONS " +
                        "($COL_ID, $COL_SESSION_TITLE, $COL_SESSION_CREATED_AT, $COL_SESSION_LAST_ACTIVE) " +
                        "VALUES (1, 'Default', 0, $now)"
                )
                android.util.Log.i("AgentDatabase", "Upgraded DB to v4 — added chat_sessions table")
            } catch (e: Exception) {
                android.util.Log.e("AgentDatabase", "Failed to create chat_sessions table", e)
            }
        }
        if (oldVersion < 5) {
            // v3.4.0: FTS5 full-text search for memory + knowledge.
            android.util.Log.i("AgentDatabase", "Upgrading DB to v5 — adding FTS5 tables...")
            createFts5Tables(db)
            backfillFts5(db)
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

    /**
     * v3.4.0: Knowledge recall using FTS5 BM25 ranking.
     * Falls back to Jaccard if FTS5 is not available.
     */
    fun recallKnowledge(query: String, limit: Int = 5): List<Triple<String, String, Float>> {
        // v3.4.0: Try FTS5 first.
        val ftsQuery = buildFts5Query(query)
        if (ftsQuery.isNotEmpty()) {
            try {
                val cursor = readableDatabase.rawQuery(
                    "SELECT k.name, k.content, bm25(knowledge_fts) as score " +
                    "FROM knowledge_fts JOIN knowledge k ON k.rowid = knowledge_fts.rowid " +
                    "WHERE knowledge_fts MATCH ? " +
                    "ORDER BY score LIMIT ?",
                    arrayOf(ftsQuery, limit.toString())
                )
                val results = mutableListOf<Triple<String, String, Float>>()
                cursor.use {
                    while (it.moveToNext()) {
                        val name = it.getString(0) ?: ""
                        val content = it.getString(1) ?: ""
                        val rawScore = it.getFloat(2)
                        val normalized = (kotlin.math.abs(rawScore) / 10f).coerceIn(0f, 1f)
                        results.add(Triple(name, content, normalized))
                    }
                }
                if (results.isNotEmpty()) return results
            } catch (e: Exception) {
                android.util.Log.w("AgentDatabase", "FTS5 knowledge search failed, falling back: ${e.message}")
            }
        }
        // Fallback: old Jaccard.
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

    // === v2.2.0 Issue 113a — Chat Sessions ===

    /** Create a new chat session and return its row id. */
    fun createChatSession(title: String = "New Chat"): Long {
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put(COL_SESSION_TITLE, title)
            put(COL_SESSION_CREATED_AT, now)
            put(COL_SESSION_LAST_ACTIVE, now)
        }
        return writableDatabase.insert(TABLE_CHAT_SESSIONS, null, values)
    }

    /**
     * Persist a conversation row tagged with its session id, and bump the
     * session's last_active timestamp so the session list stays ordered by
     * recency.
     */
    fun addConversationToSession(sessionId: Long, role: String, text: String) {
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put(COL_ROLE, role)
            put(COL_TEXT, text)
            put(COL_TIMESTAMP, now)
            put(COL_SESSION_ID, sessionId)
        }
        writableDatabase.insert(TABLE_CONVERSATIONS, null, values)
        val sessionValues = ContentValues().apply { put(COL_SESSION_LAST_ACTIVE, now) }
        writableDatabase.update(
            TABLE_CHAT_SESSIONS, sessionValues,
            "$COL_ID = ?", arrayOf(sessionId.toString()),
        )
    }

    /**
     * Return all conversations for a given session in chronological order,
     * as (role, text, timestamp) triples.
     */
    fun getConversationsForSession(sessionId: Long): List<Triple<String, String, Long>> {
        val result = mutableListOf<Triple<String, String, Long>>()
        val cursor = readableDatabase.query(
            TABLE_CONVERSATIONS,
            arrayOf(COL_ROLE, COL_TEXT, COL_TIMESTAMP),
            "$COL_SESSION_ID = ?", arrayOf(sessionId.toString()),
            null, null, "$COL_TIMESTAMP ASC",
        )
        cursor.use {
            while (it.moveToNext()) {
                result.add(Triple(it.getString(0), it.getString(1), it.getLong(2)))
            }
        }
        return result
    }

    /** Count messages in a session (used by Hub → History). */
    fun getSessionMessageCount(sessionId: Long): Int {
        val cursor = readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM $TABLE_CONVERSATIONS WHERE $COL_SESSION_ID = ?",
            arrayOf(sessionId.toString()),
        )
        return cursor.use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    /** All chat sessions, most recently active first. */
    fun getChatSessions(): List<ChatSession> {
        val result = mutableListOf<ChatSession>()
        val cursor = readableDatabase.query(
            TABLE_CHAT_SESSIONS,
            arrayOf(COL_ID, COL_SESSION_TITLE, COL_SESSION_CREATED_AT, COL_SESSION_LAST_ACTIVE),
            null, null, null, null,
            "$COL_SESSION_LAST_ACTIVE DESC",
        )
        cursor.use {
            while (it.moveToNext()) {
                result.add(
                    ChatSession(
                        id = it.getLong(0),
                        title = it.getString(1) ?: "Untitled",
                        createdAt = it.getLong(2),
                        lastActive = it.getLong(3),
                    ),
                )
            }
        }
        return result
    }

    /** Delete a chat session and all of its messages. */
    fun deleteChatSession(sessionId: Long) {
        writableDatabase.delete(
            TABLE_CONVERSATIONS,
            "$COL_SESSION_ID = ?", arrayOf(sessionId.toString()),
        )
        writableDatabase.delete(
            TABLE_CHAT_SESSIONS,
            "$COL_ID = ?", arrayOf(sessionId.toString()),
        )
    }

    /**
     * Return the most recently active session id, creating a fresh session
     * if the table is empty. Used at app launch to resume the last chat.
     */
    fun getCurrentSessionId(): Long {
        val cursor = readableDatabase.query(
            TABLE_CHAT_SESSIONS, arrayOf(COL_ID),
            null, null, null, null,
            "$COL_SESSION_LAST_ACTIVE DESC", "1",
        )
        cursor.use {
            if (it.moveToFirst()) return it.getLong(0)
        }
        return createChatSession()
    }

    /** Rename an existing chat session. */
    fun renameChatSession(sessionId: Long, newTitle: String) {
        val values = ContentValues().apply {
            put(COL_SESSION_TITLE, newTitle)
            put(COL_SESSION_LAST_ACTIVE, System.currentTimeMillis())
        }
        writableDatabase.update(
            TABLE_CHAT_SESSIONS, values,
            "$COL_ID = ?", arrayOf(sessionId.toString()),
        )
    }

    /** v2.2.0 — chat session row model. */
    data class ChatSession(
        val id: Long,
        val title: String,
        val createdAt: Long,
        val lastActive: Long,
    )

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
    /**
     * v3.4.0: Semantic recall using FTS5 BM25 ranking.
     *
     * Replaces the old Jaccard word-overlap approach which failed when query
     * words didn't match memory words exactly (e.g. "what time do I wake up"
     * wouldn't find "I wake up at 6am").
     *
     * FTS5 does porter stemming (wakes → wake) + BM25 ranking (relevance score).
     * Falls back to Jaccard if FTS5 is not available (old SQLite versions).
     *
     * Returns list of (key, value, score) sorted by most similar first.
     * Score is 0..1 where 1 = perfect match, 0 = no match.
     */
    fun recallSimilar(query: String, limit: Int = 5): List<Triple<String, String, Float>> {
        // v3.4.0: Try FTS5 first — much better than Jaccard.
        val ftsQuery = buildFts5Query(query)
        if (ftsQuery.isNotEmpty()) {
            try {
                val cursor = readableDatabase.rawQuery(
                    "SELECT m.key, m.value, bm25(memory_fts) as score " +
                    "FROM memory_fts JOIN memory m ON m.rowid = memory_fts.rowid " +
                    "WHERE memory_fts MATCH ? " +
                    "ORDER BY score LIMIT ?",
                    arrayOf(ftsQuery, limit.toString())
                )
                val results = mutableListOf<Triple<String, String, Float>>()
                cursor.use {
                    while (it.moveToNext()) {
                        val key = it.getString(0) ?: ""
                        val value = it.getString(1) ?: ""
                        // bm25 returns NEGATIVE scores (more negative = better).
                        // Convert to 0..1: 1/(1+abs(score)) so 0.0=best, 1.0=worst.
                        // Actually bm25 is negative, so abs() gives us a positive number
                        // where smaller = better match. We want higher = better for API compat.
                        val rawScore = it.getFloat(2)
                        // Normalize: abs(raw) gives 0..inf. 1/(1+abs) gives 1..0.
                        // But bm25 of -5 (good match) → 1/(1+5) = 0.167 (low).
                        // Better: use 1/(1+abs(raw)/10) so -5 → 1/(1+0.5) = 0.67 (high).
                        val normalized = (kotlin.math.abs(rawScore) / 10f).coerceIn(0f, 1f)
                        results.add(Triple(key, value, normalized))
                    }
                }
                if (results.isNotEmpty()) {
                    android.util.Log.i("AgentDatabase", "FTS5 recallSimilar('$query'): ${results.size} hits")
                    return results
                }
            } catch (e: Exception) {
                android.util.Log.w("AgentDatabase", "FTS5 search failed, falling back to Jaccard: ${e.message}")
            }
        }
        // Fallback: old Jaccard word-overlap (for old SQLite without FTS5).
        return recallSimilarJaccard(query, limit)
    }

    /**
     * v3.4.0: Old Jaccard word-overlap recall — kept as fallback for old SQLite.
     */
    private fun recallSimilarJaccard(query: String, limit: Int = 5): List<Triple<String, String, Float>> {
        val allMemory = getAllMemory()
        if (allMemory.isEmpty()) return emptyList()

        val queryWords = query.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length > 2 }
            .toSet()

        if (queryWords.isEmpty()) return emptyList()

        val scored = allMemory.map { (key, value) ->
            val combinedText = "$key $value".lowercase()
            val memoryWords = combinedText
                .replace(Regex("[^a-z0-9\\s]"), " ")
                .split(Regex("\\s+"))
                .filter { it.length > 2 }
                .toSet()

            val intersection = queryWords.intersect(memoryWords).size
            val union = queryWords.union(memoryWords).size
            val score = if (union > 0) intersection.toFloat() / union else 0f

            Triple(key, value, score)
        }

        return scored
            .filter { it.third > 0 }
            .sortedByDescending { it.third }
            .take(limit)
    }

    /**
     * v3.4.0: Build an FTS5 MATCH query string from a natural-language query.
     * Sanitizes the input, splits into words, and joins with OR.
     * "what time do I wake up" → "what OR time OR wake OR up"
     */
    private fun buildFts5Query(query: String): String {
        return query.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length > 2 }  // skip short words (the, a, is, etc.)
            .joinToString(" OR ")
            .trim()
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

    // v3.0.5: Get recent action log entries (for Rules History page)
    data class ActionLogEntry(
        val id: Long,
        val text: String,
        val timestamp: Long,
    )

    fun getRecentActions(limit: Int = 50): List<ActionLogEntry> {
        val cursor = readableDatabase.query(
            TABLE_LOG,
            arrayOf(COL_ID, COL_ACTION_TEXT, COL_TIMESTAMP),
            null, null, null, null,
            "$COL_TIMESTAMP DESC",
            limit.toString()
        )
        val entries = mutableListOf<ActionLogEntry>()
        cursor.use {
            while (it.moveToNext()) {
                entries.add(ActionLogEntry(
                    id = it.getLong(0),
                    text = it.getString(1),
                    timestamp = it.getLong(2),
                ))
            }
        }
        return entries
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
