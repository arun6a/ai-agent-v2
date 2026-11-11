package com.ai.agent.util

import org.json.JSONArray
import org.json.JSONObject

/**
 * v4.0.98: Pure-logic JSON normalizer — extracted from LLMClient.normalizeJsonValue.
 *
 * When parsing native function-calling responses (Gemini functionCall, OpenAI tool_calls,
 * Anthropic tool_use, manual JSON), `JSONObject.get(key)` returns `JSONObject` / `JSONArray`
 * for nested values — NOT `Map` / `List`. Tool handlers then do `as? String` or
 * `as? Map<String, Any>` and the cast fails silently, producing "missing argument" errors.
 *
 * This object converts:
 *   JSONObject  →  Map<String, Any>
 *   JSONArray   →  List<Any>
 *   primitives  →  unchanged
 *   null        →  null
 *
 * Extracted to a standalone object so it can be unit-tested without an Android Context.
 */
object JsonNormalizer {

    /**
     * Recursively normalize any JSON value into plain Kotlin types.
     * - JSONObject → Map<String, Any>
     * - JSONArray → List<Any>
     * - String/Int/Long/Double/Boolean → unchanged
     * - JSONObject.NULL / null → null
     */
    fun normalize(value: Any?): Any? {
        return when (value) {
            is JSONObject -> {
                val map = mutableMapOf<String, Any>()
                val keys = value.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = normalize(value.get(k))
                    if (v != null) map[k] = v
                }
                map
            }
            is JSONArray -> {
                val list = mutableListOf<Any>()
                for (i in 0 until value.length()) {
                    val item = normalize(value.opt(i))
                    if (item != null) list.add(item)
                }
                list
            }
            is String, is Int, is Long, is Double, is Boolean -> value
            // org.json.NULL and actual null both fall through to null
            else -> null
        }
    }

    /**
     * Normalize a JSONObject's keys+values into a Map<String, Any>.
     * Convenience method for the common case of parsing tool call args.
     */
    fun toMap(obj: JSONObject): Map<String, Any> {
        val map = mutableMapOf<String, Any>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = normalize(obj.get(k))
            if (v != null) map[k] = v
        }
        return map
    }

    /**
     * Normalize a JSONArray into a List<Any>.
     */
    fun toList(arr: JSONArray): List<Any> {
        val list = mutableListOf<Any>()
        for (i in 0 until arr.length()) {
            val item = normalize(arr.opt(i))
            if (item != null) list.add(item)
        }
        return list
    }
}
