package com.ai.agent.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for TodoStoreParser — extracted from TodoStore.
 *
 * Covers: parseTodos (status normalization, content filtering, JSON shape),
 * enforceSingleInProgress, formatForPrompt, parseOrError.
 */
class TodoStoreParserTest {

    private fun parse(json: String) = TodoStoreParser.parseTodos(json)

    // === BASIC PARSING ===

    @Test
    fun `empty array parses to empty list`() {
        assertEquals(emptyList<TodoStoreParser.TodoItem>(), parse("[]"))
    }

    @Test
    fun `single pending item`() {
        val items = parse("""[{"content":"do thing","status":"pending"}]""")
        assertEquals(1, items.size)
        assertEquals("do thing", items[0].content)
        assertEquals("pending", items[0].status)
    }

    @Test
    fun `single in_progress item`() {
        val items = parse("""[{"content":"x","status":"in_progress"}]""")
        assertEquals("in_progress", items[0].status)
    }

    @Test
    fun `single completed item`() {
        val items = parse("""[{"content":"x","status":"completed"}]""")
        assertEquals("completed", items[0].status)
    }

    @Test
    fun `default status is pending when missing`() {
        val items = parse("""[{"content":"x"}]""")
        assertEquals("pending", items[0].status)
    }

    @Test
    fun `default status is pending when empty string`() {
        val items = parse("""[{"content":"x","status":""}]""")
        assertEquals("pending", items[0].status)
    }

    // === STATUS NORMALIZATION (all recognized variants) ===

    @Test
    fun `status in-progress becomes in_progress`() {
        assertEquals("in_progress", parse("""[{"content":"x","status":"in-progress"}]""")[0].status)
    }

    @Test
    fun `status inprogress becomes in_progress`() {
        assertEquals("in_progress", parse("""[{"content":"x","status":"inprogress"}]""")[0].status)
    }

    @Test
    fun `status current becomes in_progress`() {
        assertEquals("in_progress", parse("""[{"content":"x","status":"current"}]""")[0].status)
    }

    @Test
    fun `status active becomes in_progress`() {
        assertEquals("in_progress", parse("""[{"content":"x","status":"active"}]""")[0].status)
    }

    @Test
    fun `status complete becomes completed`() {
        assertEquals("completed", parse("""[{"content":"x","status":"complete"}]""")[0].status)
    }

    @Test
    fun `status done becomes completed`() {
        assertEquals("completed", parse("""[{"content":"x","status":"done"}]""")[0].status)
    }

    @Test
    fun `status finished becomes completed`() {
        assertEquals("completed", parse("""[{"content":"x","status":"finished"}]""")[0].status)
    }

    @Test
    fun `status uppercase is normalized`() {
        assertEquals("in_progress", parse("""[{"content":"x","status":"IN_PROGRESS"}]""")[0].status)
        assertEquals("completed", parse("""[{"content":"x","status":"COMPLETED"}]""")[0].status)
        assertEquals("pending", parse("""[{"content":"x","status":"PENDING"}]""")[0].status)
    }

    @Test
    fun `status with whitespace is trimmed`() {
        assertEquals("in_progress", parse("""[{"content":"x","status":" in_progress "}]""")[0].status)
    }

    @Test
    fun `unknown status becomes pending`() {
        assertEquals("pending", parse("""[{"content":"x","status":"blocked"}]""")[0].status)
        assertEquals("pending", parse("""[{"content":"x","status":"waiting"}]""")[0].status)
        assertEquals("pending", parse("""[{"content":"x","status":"skipped"}]""")[0].status)
    }

    // === GAP-1: "in progress" with SPACE is NOT recognized ===

    @Test
    fun `GAP-1 in progress with space becomes pending`() {
        // "in progress" (with space) is a common natural variant, but the
        // when-list doesn't include it → falls to "pending".
        assertEquals("pending", parse("""[{"content":"x","status":"in progress"}]""")[0].status)
    }

    // === CONTENT FILTERING ===

    @Test
    fun `empty content is dropped`() {
        assertEquals(0, parse("""[{"content":"","status":"pending"}]""").size)
    }

    @Test
    fun `whitespace-only content is dropped`() {
        assertEquals(0, parse("""[{"content":"   ","status":"pending"}]""").size)
    }

    @Test
    fun `content is trimmed`() {
        val items = parse("""[{"content":"  do thing  ","status":"pending"}]""")
        assertEquals("do thing", items[0].content)
    }

    @Test
    fun `mix of empty and non-empty keeps only non-empty`() {
        val items = parse("""[{"content":"a","status":"pending"},{"content":"","status":"pending"},{"content":"b","status":"pending"}]""")
        assertEquals(2, items.size)
        assertEquals("a", items[0].content)
        assertEquals("b", items[1].content)
    }

    @Test
    fun `non-object array element is skipped`() {
        // JSONArray with a string element → optJSONObject returns null → skipped
        val items = parse("""["raw string",{"content":"x","status":"pending"}]""")
        assertEquals(1, items.size)
        assertEquals("x", items[0].content)
    }

    // === enforceSingleInProgress ===

    @Test
    fun `single in_progress is kept`() {
        val items = listOf(
            TodoStoreParser.TodoItem("a", "in_progress"),
            TodoStoreParser.TodoItem("b", "pending"),
        )
        val result = TodoStoreParser.enforceSingleInProgress(items)
        assertEquals("in_progress", result[0].status)
        assertEquals("pending", result[1].status)
    }

    @Test
    fun `multiple in_progress keeps first demotes rest`() {
        val items = listOf(
            TodoStoreParser.TodoItem("a", "in_progress"),
            TodoStoreParser.TodoItem("b", "in_progress"),
            TodoStoreParser.TodoItem("c", "in_progress"),
        )
        val result = TodoStoreParser.enforceSingleInProgress(items)
        assertEquals("in_progress", result[0].status)
        assertEquals("pending", result[1].status)
        assertEquals("pending", result[2].status)
    }

    @Test
    fun `enforceSingleInProgress no in_progress is no-op`() {
        val items = listOf(
            TodoStoreParser.TodoItem("a", "pending"),
            TodoStoreParser.TodoItem("b", "completed"),
        )
        val result = TodoStoreParser.enforceSingleInProgress(items)
        assertEquals(2, result.size)
        assertEquals("pending", result[0].status)
        assertEquals("completed", result[1].status)
    }

    @Test
    fun `enforceSingleInProgress empty list is empty`() {
        assertEquals(emptyList<TodoStoreParser.TodoItem>(), TodoStoreParser.enforceSingleInProgress(emptyList()))
    }

    @Test
    fun `enforceSingleInProgress is non-destructive on input`() {
        val original = listOf(
            TodoStoreParser.TodoItem("a", "in_progress"),
            TodoStoreParser.TodoItem("b", "in_progress"),
        )
        TodoStoreParser.enforceSingleInProgress(original)
        // Input list is unchanged (we return a new list)
        assertEquals("in_progress", original[1].status)
    }

    // === formatForPrompt ===

    @Test
    fun `formatForPrompt empty list is empty string`() {
        assertEquals("", TodoStoreParser.formatForPrompt(emptyList()))
    }

    @Test
    fun `formatForPrompt contains plan header`() {
        val out = TodoStoreParser.formatForPrompt(listOf(TodoStoreParser.TodoItem("a", "pending")))
        assertTrue(out.contains("## Current Plan (from todoWrite)"))
    }

    @Test
    fun `formatForPrompt shows progress count`() {
        val items = listOf(
            TodoStoreParser.TodoItem("a", "completed"),
            TodoStoreParser.TodoItem("b", "in_progress"),
            TodoStoreParser.TodoItem("c", "pending"),
        )
        val out = TodoStoreParser.formatForPrompt(items)
        assertTrue(out.contains("Progress: 1/3 steps completed"))
    }

    @Test
    fun `formatForPrompt uses checkmark for completed`() {
        val out = TodoStoreParser.formatForPrompt(listOf(TodoStoreParser.TodoItem("a", "completed")))
        assertTrue(out.contains("✅"))
    }

    @Test
    fun `formatForPrompt uses cycle for in_progress`() {
        val out = TodoStoreParser.formatForPrompt(listOf(TodoStoreParser.TodoItem("a", "in_progress")))
        assertTrue(out.contains("🔄"))
    }

    @Test
    fun `formatForPrompt uses hourglass for pending`() {
        val out = TodoStoreParser.formatForPrompt(listOf(TodoStoreParser.TodoItem("a", "pending")))
        assertTrue(out.contains("⏳"))
    }

    @Test
    fun `formatForPrompt numbers items starting at 1`() {
        val items = listOf(
            TodoStoreParser.TodoItem("first", "pending"),
            TodoStoreParser.TodoItem("second", "pending"),
        )
        val out = TodoStoreParser.formatForPrompt(items)
        assertTrue(out.contains("1. first"))
        assertTrue(out.contains("2. second"))
    }

    @Test
    fun `formatForPrompt content is rendered`() {
        val out = TodoStoreParser.formatForPrompt(listOf(TodoStoreParser.TodoItem("download image", "pending")))
        assertTrue(out.contains("download image"))
    }

    // === parseOrError ===

    @Test
    fun `parseOrError valid JSON returns items and null error`() {
        val (items, err) = TodoStoreParser.parseOrError("""[{"content":"x","status":"pending"}]""")
        assertEquals(1, items.size)
        assertNull(err)
    }

    @Test
    fun `parseOrError invalid JSON returns empty and error message`() {
        val (items, err) = TodoStoreParser.parseOrError("""not json""")
        assertEquals(0, items.size)
        assertNotNull(err)
        assertTrue(err!!.contains("Error parsing todos"))
    }

    @Test
    fun `parseOrError error message mentions expected format`() {
        val (_, err) = TodoStoreParser.parseOrError("""{"wrong":"shape"}""")
        assertNotNull(err)
        assertTrue(err!!.contains("Expected JSON array"))
    }

    // === REALISTIC SHAPES (from agent-loop behavior) ===

    @Test
    fun `real shape - typical plan with mixed statuses`() {
        val json = """[
            {"content":"Search for wallpaper URL","status":"completed"},
            {"content":"Download image to /sdcard/Pictures/","status":"in_progress"},
            {"content":"Set as wallpaper","status":"pending"},
            {"content":"Clean up temp files","status":"pending"}
        ]"""
        val items = parse(json)
        assertEquals(4, items.size)
        assertEquals("completed", items[0].status)
        assertEquals("in_progress", items[1].status)
        assertEquals("pending", items[2].status)
        assertEquals("pending", items[3].status)
    }

    @Test
    fun `real shape - AI sends two in_progress - first wins`() {
        val json = """[
            {"content":"step one","status":"in_progress"},
            {"content":"step two","status":"in_progress"}
        ]"""
        val items = parse(json)
        assertEquals("in_progress", items[0].status)
        assertEquals("pending", items[1].status)
    }

    @Test
    fun `real shape - AI uses status field with different casing`() {
        val json = """[{"content":"x","status":"In_Progress"}]"""
        assertEquals("in_progress", parse(json)[0].status)
    }

    @Test
    fun `real shape - AI uses Done variant`() {
        val json = """[{"content":"x","status":"Done"}]"""
        assertEquals("completed", parse(json)[0].status)
    }

    @Test
    fun `real shape - empty plan resets to empty`() {
        val items = parse("[]")
        assertEquals(0, items.size)
        assertEquals("", TodoStoreParser.formatForPrompt(items))
    }

    @Test
    fun `real shape - long plan of 10 items`() {
        val sb = StringBuilder("[")
        for (i in 1..10) {
            if (i > 1) sb.append(",")
            sb.append("""{"content":"step $i","status":"pending"}""")
        }
        sb.append("]")
        val items = parse(sb.toString())
        assertEquals(10, items.size)
        assertEquals("step 1", items[0].content)
        assertEquals("step 10", items[9].content)
    }

    @Test
    fun `real shape - content with special chars`() {
        val json = """[{"content":"set volume to 50%","status":"pending"}]"""
        val items = parse(json)
        assertEquals("set volume to 50%", items[0].content)
    }

    @Test
    fun `real shape - content with unicode`() {
        val json = """[{"content":"Play café music","status":"pending"}]"""
        val items = parse(json)
        assertEquals("Play café music", items[0].content)
    }
}
