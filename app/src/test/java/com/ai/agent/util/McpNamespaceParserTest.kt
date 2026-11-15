package com.ai.agent.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpNamespaceParserTest {

    // === PARSE: VALID INPUTS ===

    @Test
    fun `parse valid mcp__gmail__send_email`() {
        val result = McpNamespaceParser.parse("mcp__gmail__send_email")
        assertEquals(Pair("gmail", "send_email"), result)
    }

    @Test
    fun `parse valid mcp__filesystem__read_file`() {
        val result = McpNamespaceParser.parse("mcp__filesystem__read_file")
        assertEquals(Pair("filesystem", "read_file"), result)
    }

    @Test
    fun `parse valid mcp__github__create_issue`() {
        val result = McpNamespaceParser.parse("mcp__github__create_issue")
        assertEquals(Pair("github", "create_issue"), result)
    }

    @Test
    fun `parse valid with short names`() {
        val result = McpNamespaceParser.parse("mcp__a__b")
        assertEquals(Pair("a", "b"), result)
    }

    @Test
    fun `parse valid with long server name`() {
        val result = McpNamespaceParser.parse("mcp__very_long_server_name_here__tool")
        assertEquals(Pair("very_long_server_name_here", "tool"), result)
    }

    @Test
    fun `parse valid with long tool name`() {
        val result = McpNamespaceParser.parse("mcp__srv__very_long_tool_name_here")
        assertEquals(Pair("srv", "very_long_tool_name_here"), result)
    }

    @Test
    fun `parse valid with numbers in names`() {
        val result = McpNamespaceParser.parse("mcp__server2__tool3")
        assertEquals(Pair("server2", "tool3"), result)
    }

    @Test
    fun `parse tool name containing underscore`() {
        val result = McpNamespaceParser.parse("mcp__gmail__send_email_now")
        assertEquals(Pair("gmail", "send_email_now"), result)
    }

    @Test
    fun `parse tool name containing double underscore preserved`() {
        // limit=2 split means only first __ is separator; rest stays in tool name
        val result = McpNamespaceParser.parse("mcp__gmail__send__email")
        assertEquals(Pair("gmail", "send__email"), result)
    }

    @Test
    fun `parse with hyphens in server name`() {
        val result = McpNamespaceParser.parse("mcp__my-server__tool")
        assertEquals(Pair("my-server", "tool"), result)
    }

    @Test
    fun `parse with dots in server name`() {
        val result = McpNamespaceParser.parse("mcp__com.google.api__tool")
        assertEquals(Pair("com.google.api", "tool"), result)
    }

    // === PARSE: INVALID INPUTS ===

    @Test
    fun `parse returns null for non-mcp prefix`() {
        assertNull(McpNamespaceParser.parse("gmail__send_email"))
    }

    @Test
    fun `parse returns null for empty string`() {
        assertNull(McpNamespaceParser.parse(""))
    }

    @Test
    fun `parse returns null for just prefix`() {
        assertNull(McpNamespaceParser.parse("mcp__"))
    }

    @Test
    fun `parse returns null for prefix with server but no tool`() {
        assertNull(McpNamespaceParser.parse("mcp__gmail"))
    }

    @Test
    fun `parse returns null for prefix with separator but no names`() {
        assertNull(McpNamespaceParser.parse("mcp____"))
    }

    @Test
    fun `parse returns null for prefix with empty server`() {
        assertNull(McpNamespaceParser.parse("mcp____tool"))
    }

    @Test
    fun `parse returns null for prefix with empty tool`() {
        assertNull(McpNamespaceParser.parse("mcp__gmail__"))
    }

    @Test
    fun `parse returns null for non-string input`() {
        assertNull(McpNamespaceParser.parse("hello world"))
    }

    @Test
    fun `parse returns null for mcp prefix without separator`() {
        assertNull(McpNamespaceParser.parse("mcp__gmail"))
    }

    @Test
    fun `parse returns null for URL-like input`() {
        assertNull(McpNamespaceParser.parse("https://example.com/mcp"))
    }

    // === KNOWN BUG: server name containing __ ===

    @Test
    fun `BUG server name with double underscore breaks parsing`() {
        // "mcp__my__custom__tool" — split("__", limit=2) gives ["my", "custom__tool"]
        // So serverName="my", toolName="custom__tool" — WRONG!
        // The server was named "my__custom" but gets parsed as "my"
        val result = McpNamespaceParser.parse("mcp__my__custom__tool")
        assertEquals(Pair("my", "custom__tool"), result) // Documents the bug
    }

    // === BUILD ===

    @Test
    fun `build creates valid namespaced name`() {
        assertEquals("mcp__gmail__send_email", McpNamespaceParser.build("gmail", "send_email"))
    }

    @Test
    fun `build with empty server name`() {
        assertEquals("mcp____tool", McpNamespaceParser.build("", "tool"))
    }

    @Test
    fun `build with empty tool name`() {
        assertEquals("mcp__server__", McpNamespaceParser.build("server", ""))
    }

    @Test
    fun `build with hyphens`() {
        assertEquals("mcp__my-server__my-tool", McpNamespaceParser.build("my-server", "my-tool"))
    }

    @Test
    fun `build with dots`() {
        assertEquals("mcp__com.google__tool", McpNamespaceParser.build("com.google", "tool"))
    }

    // === ROUND-TRIP ===

    @Test
    fun `build then parse round-trip preserves names`() {
        val original = McpNamespaceParser.build("gmail", "send_email")
        val parsed = McpNamespaceParser.parse(original)
        assertEquals(Pair("gmail", "send_email"), parsed)
    }

    @Test
    fun `round-trip with complex names`() {
        val original = McpNamespaceParser.build("com.google.api", "read_firestore_document")
        val parsed = McpNamespaceParser.parse(original)
        assertEquals(Pair("com.google.api", "read_firestore_document"), parsed)
    }

    @Test
    fun `round-trip with hyphens`() {
        val original = McpNamespaceParser.build("my-server", "my-tool")
        val parsed = McpNamespaceParser.parse(original)
        assertEquals(Pair("my-server", "my-tool"), parsed)
    }

    // === IS MCP NAMESPACED ===

    @Test
    fun `isMcpNamespaced returns true for valid name`() {
        assertTrue(McpNamespaceParser.isMcpNamespaced("mcp__gmail__send_email"))
    }

    @Test
    fun `isMcpNamespaced returns false for non-mcp name`() {
        assertFalse(McpNamespaceParser.isMcpNamespaced("gmail__send_email"))
    }

    @Test
    fun `isMcpNamespaced returns false for empty string`() {
        assertFalse(McpNamespaceParser.isMcpNamespaced(""))
    }

    @Test
    fun `isMcpNamespaced returns false for just prefix`() {
        assertFalse(McpNamespaceParser.isMcpNamespaced("mcp__"))
    }

    @Test
    fun `isMcpNamespaced returns false for prefix without separator`() {
        assertFalse(McpNamespaceParser.isMcpNamespaced("mcp__gmail"))
    }

    @Test
    fun `isMcpNamespaced returns true for name with hyphens`() {
        assertTrue(McpNamespaceParser.isMcpNamespaced("mcp__my-server__tool"))
    }
}
