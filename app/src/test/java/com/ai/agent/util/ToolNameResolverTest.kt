package com.ai.agent.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for ToolNameResolver — extracted from ToolExecutor.executeTool
 * (v4.0.18 case-insensitive matching fix).
 *
 * History: the brain sends "createrule" / "TAP"; v4.0.16's
 * capitalize-first-letter fix produced "Createrule" (broken); v4.0.18 looks
 * up the canonical name by case-insensitive equality against the available
 * tool list.
 */
class ToolNameResolverTest {

    // Simulated subset of Aira's available tool names
    private val available = listOf(
        "tap", "swipe", "typeText", "createRule", "createSkill", "sendSMS",
        "getBatteryLevel", "get_device_info", "searchMcpServers"
    )

    // === CASE-INSENSITIVE MATCHING (the v4.0.18 fix) ===

    @Test
    fun `all-lowercase name resolves to canonical`() {
        assertEquals("createRule", ToolNameResolver.resolve("createrule", available))
    }

    @Test
    fun `all-uppercase name resolves to canonical`() {
        assertEquals("tap", ToolNameResolver.resolve("TAP", available))
    }

    @Test
    fun `mixed case name resolves to canonical`() {
        assertEquals("createRule", ToolNameResolver.resolve("CreateRule", available))
    }

    @Test
    fun `inverted camelCase resolves`() {
        assertEquals("typeText", ToolNameResolver.resolve("TYPETEXT", available))
    }

    @Test
    fun `exact canonical name is returned unchanged`() {
        assertEquals("createRule", ToolNameResolver.resolve("createRule", available))
    }

    // === WHITESPACE ===

    @Test
    fun `leading and trailing whitespace is trimmed before matching`() {
        assertEquals("tap", ToolNameResolver.resolve("  tap  ", available))
    }

    @Test
    fun `whitespace around unknown name is trimmed in fallback`() {
        assertEquals("noSuchTool", ToolNameResolver.resolve("  noSuchTool  ", available))
    }

    // === FALLBACK (unknown tool) ===

    @Test
    fun `unknown name is returned trimmed and unchanged`() {
        assertEquals("unknownTool", ToolNameResolver.resolve("unknownTool", available))
    }

    @Test
    fun `empty name returns empty string`() {
        assertEquals("", ToolNameResolver.resolve("", available))
    }

    @Test
    fun `whitespace-only name returns empty string`() {
        assertEquals("", ToolNameResolver.resolve("   ", available))
    }

    @Test
    fun `empty available list falls back to trimmed raw name`() {
        assertEquals("tap", ToolNameResolver.resolve(" tap ", emptyList()))
    }

    // === MATCHING RULES ===

    @Test
    fun `underscored names match case-insensitively`() {
        assertEquals("get_device_info", ToolNameResolver.resolve("GET_DEVICE_INFO", available))
    }

    @Test
    fun `first match wins when two tools differ only by case - GAP-1`() {
        val tools = listOf("Foo", "foo")
        assertEquals("Foo", ToolNameResolver.resolve("foo", tools))
    }

    @Test
    fun `partial match does NOT resolve`() {
        // "tap" must not match "tape" — equality is exact after lowercasing
        val tools = listOf("tapeRecorder")
        assertEquals("tap", ToolNameResolver.resolve("tap", tools))
    }

    @Test
    fun `resolved name preserves the available list's casing`() {
        // The output is the AVAILABLE name, never the input's casing
        assertEquals("searchMcpServers", ToolNameResolver.resolve("SEARCHMCPSERVERS", available))
    }

    @Test
    fun `real log shape - createrule with args`() {
        // From the v4.0.18 fix: brain sent "createrule" with valid args
        assertEquals("createRule", ToolNameResolver.resolve("createrule", available))
    }
}
