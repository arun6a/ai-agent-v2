package com.ai.agent.util

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for JsonNormalizer — the function that converts org.json types
 * (JSONObject/JSONArray) into plain Kotlin types (Map/List) so tool handlers
 * can use `as? String` and `as? Map<String, Any>` casts successfully.
 *
 * This was the root cause of 7 failures in the v4.0.94 log analysis:
 * Gemini/OpenAI native function calling returns nested arrays as JSONArray,
 * but tool handlers expected String → "missing argument" errors.
 *
 * These tests run on the JVM (no Android device needed).
 * Run: ./gradlew testDebugUnitTest
 */
class JsonNormalizerTest {

    // === PRIMITIVE TYPES ===

    @Test
    fun `normalize String returns String unchanged`() {
        val result = JsonNormalizer.normalize("hello world")
        assertEquals("hello world", result)
    }

    @Test
    fun `normalize Int returns Int unchanged`() {
        val result = JsonNormalizer.normalize(42)
        assertEquals(42, result)
    }

    @Test
    fun `normalize Long returns Long unchanged`() {
        val result = JsonNormalizer.normalize(9999999999L)
        assertEquals(9999999999L, result)
    }

    @Test
    fun `normalize Double returns Double unchanged`() {
        val result = JsonNormalizer.normalize(3.14)
        assertEquals(3.14, result)
    }

    @Test
    fun `normalize Boolean returns Boolean unchanged`() {
        val result = JsonNormalizer.normalize(true)
        assertEquals(true, result)
    }

    @Test
    fun `normalize null returns null`() {
        val result = JsonNormalizer.normalize(null)
        assertNull(result)
    }

    @Test
    fun `normalize JSONObject_NULL returns null`() {
        val result = JsonNormalizer.normalize(JSONObject.NULL)
        assertNull(result)
    }

    // === JSONObject → Map ===

    @Test
    fun `normalize empty JSONObject returns empty Map`() {
        val obj = JSONObject()
        val result = JsonNormalizer.normalize(obj)
        assertNotNull(result)
        assertTrue(result is Map<*, *>)
        assertEquals(0, (result as Map<*, *>).size)
    }

    @Test
    fun `normalize JSONObject with String values returns Map`() {
        val obj = JSONObject("""{"name":"test","value":"hello"}""")
        val result = JsonNormalizer.normalize(obj) as Map<*, *>
        assertEquals("test", result["name"])
        assertEquals("hello", result["value"])
    }

    @Test
    fun `normalize JSONObject with Int values returns Map`() {
        val obj = JSONObject("""{"count":5,"index":10}""")
        val result = JsonNormalizer.normalize(obj) as Map<*, *>
        assertEquals(5, result["count"])
        assertEquals(10, result["index"])
    }

    @Test
    fun `normalize JSONObject with mixed types returns Map`() {
        val obj = JSONObject("""{"name":"Aira","version":98,"active":true}""")
        val result = JsonNormalizer.normalize(obj) as Map<*, *>
        assertEquals("Aira", result["name"])
        assertEquals(98, result["version"])
        assertEquals(true, result["active"])
    }

    // === JSONArray → List ===

    @Test
    fun `normalize empty JSONArray returns empty List`() {
        val arr = JSONArray()
        val result = JsonNormalizer.normalize(arr)
        assertNotNull(result)
        assertTrue(result is List<*>)
        assertEquals(0, (result as List<*>).size)
    }

    @Test
    fun `normalize JSONArray of Strings returns List of Strings`() {
        val arr = JSONArray("""["a","b","c"]""")
        val result = JsonNormalizer.normalize(arr) as List<*>
        assertEquals(3, result.size)
        assertEquals("a", result[0])
        assertEquals("b", result[1])
        assertEquals("c", result[2])
    }

    @Test
    fun `normalize JSONArray of Ints returns List of Ints`() {
        val arr = JSONArray("[1, 2, 3]")
        val result = JsonNormalizer.normalize(arr) as List<*>
        assertEquals(3, result.size)
        assertEquals(1, result[0])
        assertEquals(2, result[1])
        assertEquals(3, result[2])
    }

    // === NESTED STRUCTURES (the real-world test cases) ===

    /**
     * This is the EXACT bug from v4.0.96:
     * todoWrite(todos=[{"content":"step 1","status":"pending"}])
     * The `todos` arg arrived as JSONArray, but the tool handler expected String.
     */
    @Test
    fun `normalize nested JSONArray of JSONObjects for todoWrite`() {
        val obj = JSONObject("""{"todos":[{"content":"step 1","status":"pending"},{"content":"step 2","status":"completed"}]}""")
        val result = JsonNormalizer.normalize(obj) as Map<*, *>

        val todos = result["todos"]
        assertNotNull(todos)
        assertTrue("todos should be a List", todos is List<*>)
        assertEquals(2, (todos as List<*>).size)

        val first = todos[0] as Map<*, *>
        assertEquals("step 1", first["content"])
        assertEquals("pending", first["status"])

        val second = todos[1] as Map<*, *>
        assertEquals("step 2", second["content"])
        assertEquals("completed", second["status"])
    }

    /**
     * createConnector with endpoints as a List of objects (v4.0.96 fix).
     */
    @Test
    fun `normalize nested JSONArray of JSONObjects for createConnector`() {
        val obj = JSONObject("""{"name":"youtube","endpoints":[{"name":"search","method":"GET","path":"/search"}]}""")
        val result = JsonNormalizer.normalize(obj) as Map<*, *>

        assertEquals("youtube", result["name"])

        val endpoints = result["endpoints"] as List<*>
        assertEquals(1, endpoints.size)

        val endpoint = endpoints[0] as Map<*, *>
        assertEquals("search", endpoint["name"])
        assertEquals("GET", endpoint["method"])
        assertEquals("/search", endpoint["path"])
    }

    /**
     * actionChain with actions containing nested args objects.
     */
    @Test
    fun `normalize deeply nested structure for actionChain`() {
        val obj = JSONObject("""{"actions":[{"name":"launchApp","args":{"package":"com.whatsapp"}},{"name":"type","args":{"text":"hello"}}]}""")
        val result = JsonNormalizer.normalize(obj) as Map<*, *>

        val actions = result["actions"] as List<*>
        assertEquals(2, actions.size)

        val action1 = actions[0] as Map<*, *>
        assertEquals("launchApp", action1["name"])
        val args1 = action1["args"] as Map<*, *>
        assertEquals("com.whatsapp", args1["package"])

        val action2 = actions[1] as Map<*, *>
        assertEquals("type", action2["name"])
        val args2 = action2["args"] as Map<*, *>
        assertEquals("hello", args2["text"])
    }

    /**
     * JSON with null values — should be filtered out (not included in the Map).
     */
    @Test
    fun `normalize filters out null values from JSONObject`() {
        val obj = JSONObject("""{"name":"test","value":null}""")
        val result = JsonNormalizer.normalize(obj) as Map<*, *>
        assertEquals("test", result["name"])
        assertNull("null values should be filtered", result["value"])
        assertEquals(1, result.size)
    }

    /**
     * JSON with null items in array — should be filtered.
     */
    @Test
    fun `normalize filters out null items from JSONArray`() {
        val arr = JSONArray("[1, null, 3]")
        val result = JsonNormalizer.normalize(arr) as List<*>
        assertEquals(2, result.size)
        assertEquals(1, result[0])
        assertEquals(3, result[1])
    }

    // === CONVENIENCE METHODS ===

    @Test
    fun `toMap converts JSONObject to Map directly`() {
        val obj = JSONObject("""{"key1":"val1","key2":42}""")
        val map = JsonNormalizer.toMap(obj)
        assertEquals("val1", map["key1"])
        assertEquals(42, map["key2"])
    }

    @Test
    fun `toList converts JSONArray to List directly`() {
        val arr = JSONArray("""["a","b","c"]""")
        val list = JsonNormalizer.toList(arr)
        assertEquals(3, list.size)
        assertEquals("a", list[0])
        assertEquals("b", list[1])
        assertEquals("c", list[2])
    }

    // === EDGE CASES ===

    @Test
    fun `normalize nested empty objects`() {
        val obj = JSONObject("""{"outer":{"inner":{}}}""")
        val result = JsonNormalizer.normalize(obj) as Map<*, *>
        val outer = result["outer"] as Map<*, *>
        val inner = outer["inner"] as Map<*, *>
        assertEquals(0, inner.size)
    }

    @Test
    fun `normalize array of arrays`() {
        val arr = JSONArray("[[1,2],[3,4]]")
        val result = JsonNormalizer.normalize(arr) as List<*>
        assertEquals(2, result.size)
        val first = result[0] as List<*>
        assertEquals(1, first[0])
        assertEquals(2, first[1])
        val second = result[1] as List<*>
        assertEquals(3, second[0])
        assertEquals(4, second[1])
    }

    @Test
    fun `normalize complex real-world Gemini functionCall args`() {
        // This mimics the actual Gemini response structure from logs.txt
        val geminiArgs = JSONObject("""
            {"todos":[{"content":"Task 1","status":"completed"},{"content":"Task 2","status":"in_progress"}],"reason":"test"}
        """.trimIndent())
        val result = JsonNormalizer.toMap(geminiArgs)

        val todos = result["todos"] as List<*>
        assertEquals(2, todos.size)

        val task1 = todos[0] as Map<*, *>
        assertEquals("Task 1", task1["content"])
        assertEquals("completed", task1["status"])

        assertEquals("test", result["reason"])
    }
}
