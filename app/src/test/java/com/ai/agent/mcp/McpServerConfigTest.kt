package com.ai.agent.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for McpServerConfig — the data class for MCP server configuration.
 * Tests isHealthy(), isTokenExpired(), namespacedToolName(), displayString().
 */
class McpServerConfigTest {

    // === IS HEALTHY ===

    @Test
    fun `isHealthy returns true for fresh server with no failures`() {
        val config = McpServerConfig(name = "test", transport = "http")
        assertTrue(config.isHealthy())
    }

    @Test
    fun `isHealthy returns true for server with successes and no failures`() {
        val config = McpServerConfig(name = "test", transport = "http").copy(successCount = 10, failureCount = 0)
        assertTrue(config.isHealthy())
    }

    @Test
    fun `isHealthy returns true for server with 1 failure and 0 successes`() {
        val config = McpServerConfig(name = "test", transport = "http").copy(successCount = 0, failureCount = 1)
        assertTrue(config.isHealthy())
    }

    @Test
    fun `isHealthy returns true for server with 2 failures and 0 successes`() {
        val config = McpServerConfig(name = "test", transport = "http").copy(successCount = 0, failureCount = 2)
        assertTrue(config.isHealthy())
    }

    @Test
    fun `isHealthy returns false for server with 3 failures and 0 successes`() {
        val config = McpServerConfig(name = "test", transport = "http").copy(successCount = 0, failureCount = 3)
        assertFalse(config.isHealthy())
    }

    @Test
    fun `isHealthy returns false for server with 4 failures and 0 successes`() {
        val config = McpServerConfig(name = "test", transport = "http").copy(successCount = 0, failureCount = 4)
        assertFalse(config.isHealthy())
    }

    @Test
    fun `isHealthy returns true for server with 3 failures but 1 success`() {
        val config = McpServerConfig(name = "test", transport = "http").copy(successCount = 1, failureCount = 3)
        assertTrue(config.isHealthy())
    }

    @Test
    fun `isHealthy returns true for server with 4 failures and 1 success`() {
        val config = McpServerConfig(name = "test", transport = "http").copy(successCount = 1, failureCount = 4)
        assertTrue(config.isHealthy())
    }

    @Test
    fun `isHealthy returns false for server with 5 failures regardless of successes`() {
        val config = McpServerConfig(name = "test", transport = "http").copy(successCount = 10, failureCount = 5)
        assertFalse(config.isHealthy())
    }

    @Test
    fun `isHealthy returns false for server with 6 failures`() {
        val config = McpServerConfig(name = "test", transport = "http").copy(successCount = 5, failureCount = 6)
        assertFalse(config.isHealthy())
    }

    @Test
    fun `isHealthy returns false for server with 100 failures`() {
        val config = McpServerConfig(name = "test", transport = "http").copy(successCount = 100, failureCount = 100)
        assertFalse(config.isHealthy())
    }

    // === IS TOKEN EXPIRED ===

    @Test
    fun `isTokenExpired returns false for non-oauth auth`() {
        val config = McpServerConfig(name = "test", transport = "http", authType = "none")
        assertFalse(config.isTokenExpired())
    }

    @Test
    fun `isTokenExpired returns false for bearer auth`() {
        val config = McpServerConfig(name = "test", transport = "http", authType = "bearer")
        assertFalse(config.isTokenExpired())
    }

    @Test
    fun `isTokenExpired returns false for oauth with tokenExpiresAt zero`() {
        val config = McpServerConfig(name = "test", transport = "http", authType = "oauth").copy(tokenExpiresAt = 0L)
        assertFalse(config.isTokenExpired())
    }

    @Test
    fun `isTokenExpired returns true for oauth token expired in past`() {
        val config = McpServerConfig(name = "test", transport = "http", authType = "oauth")
            .copy(tokenExpiresAt = System.currentTimeMillis() - 10000)
        assertTrue(config.isTokenExpired())
    }

    @Test
    fun `isTokenExpired returns false for oauth token expiring in 10 minutes`() {
        val config = McpServerConfig(name = "test", transport = "http", authType = "oauth")
            .copy(tokenExpiresAt = System.currentTimeMillis() + 600000)
        assertFalse(config.isTokenExpired())
    }

    @Test
    fun `isTokenExpired returns true for oauth token expiring in 4 minutes`() {
        val config = McpServerConfig(name = "test", transport = "http", authType = "oauth")
            .copy(tokenExpiresAt = System.currentTimeMillis() + 240000)
        assertTrue(config.isTokenExpired())
    }

    @Test
    fun `isTokenExpired returns false for oauth token expiring in 6 minutes`() {
        val config = McpServerConfig(name = "test", transport = "http", authType = "oauth")
            .copy(tokenExpiresAt = System.currentTimeMillis() + 360000)
        assertFalse(config.isTokenExpired())
    }

    @Test
    fun `isTokenExpired returns false at exactly 5 min boundary`() {
        val config = McpServerConfig(name = "test", transport = "http", authType = "oauth")
            .copy(tokenExpiresAt = System.currentTimeMillis() + 300000)
        assertFalse(config.isTokenExpired())
    }

    // === NAMESPACED TOOL NAME ===

    @Test
    fun `namespacedToolName returns mcp__server__tool format`() {
        val config = McpServerConfig(name = "gmail", transport = "http")
        assertEquals("mcp__gmail__send_email", config.namespacedToolName("send_email"))
    }

    @Test
    fun `namespacedToolName with simple server name`() {
        val config = McpServerConfig(name = "test", transport = "http")
        assertEquals("mcp__test__read_file", config.namespacedToolName("read_file"))
    }

    @Test
    fun `namespacedToolName with hyphenated server name`() {
        val config = McpServerConfig(name = "my-server", transport = "http")
        assertEquals("mcp__my-server__tool", config.namespacedToolName("tool"))
    }

    @Test
    fun `namespacedToolName with empty server name`() {
        val config = McpServerConfig(name = "", transport = "http")
        assertEquals("mcp____tool", config.namespacedToolName("tool"))
    }

    @Test
    fun `namespacedToolName with empty tool name`() {
        val config = McpServerConfig(name = "server", transport = "http")
        assertEquals("mcp__server__", config.namespacedToolName(""))
    }

    @Test
    fun `namespacedToolName always starts with mcp__`() {
        val config = McpServerConfig(name = "any", transport = "http")
        val result = config.namespacedToolName("anything")
        assertTrue(result.startsWith("mcp__"))
    }

    // === DISPLAY STRING ===

    @Test
    fun `displayString shows connected status with green dot`() {
        val config = McpServerConfig(name = "gmail", transport = "http").copy(connected = true)
        val result = config.displayString()
        assertTrue(result.contains("🟢"))
        assertFalse(result.contains("🔴"))
    }

    @Test
    fun `displayString shows disconnected status with red dot`() {
        val config = McpServerConfig(name = "gmail", transport = "http").copy(connected = false)
        val result = config.displayString()
        assertTrue(result.contains("🔴"))
        assertFalse(result.contains("🟢"))
    }

    @Test
    fun `displayString shows HTTP transport for http`() {
        val config = McpServerConfig(name = "gmail", transport = "http")
        assertTrue(config.displayString().contains("HTTP"))
    }

    @Test
    fun `displayString shows stdio transport for stdio`() {
        val config = McpServerConfig(name = "local", transport = "stdio")
        assertTrue(config.displayString().contains("stdio"))
    }

    @Test
    fun `displayString shows tool count`() {
        val config = McpServerConfig(name = "gmail", transport = "http").copy(
            tools = listOf(
                McpToolInfo("send_email", "Send email", "{}"),
                McpToolInfo("read_email", "Read email", "{}"),
                McpToolInfo("delete_email", "Delete email", "{}")
            )
        )
        assertTrue(config.displayString().contains("3"))
    }

    @Test
    fun `displayString shows zero tools when empty`() {
        val config = McpServerConfig(name = "gmail", transport = "http")
        assertTrue(config.displayString().contains("0"))
    }

    @Test
    fun `displayString includes server name`() {
        val config = McpServerConfig(name = "my-server", transport = "http")
        assertTrue(config.displayString().contains("my-server"))
    }

    // === MCP TOOL INFO ===

    @Test
    fun `McpToolInfo fullName generates namespaced name`() {
        val tool = McpToolInfo("send_email", "Send email", "{}")
        assertEquals("mcp__gmail__send_email", tool.fullName("gmail"))
    }

    @Test
    fun `McpToolInfo fullName with different server`() {
        val tool = McpToolInfo("read_file", "Read file", "{}")
        assertEquals("mcp__filesystem__read_file", tool.fullName("filesystem"))
    }

    @Test
    fun `McpToolInfo fullName with empty server name`() {
        val tool = McpToolInfo("tool", "desc", "{}")
        assertEquals("mcp____tool", tool.fullName(""))
    }

    // === DEFAULT VALUES ===

    @Test
    fun `default config has empty name and http transport`() {
        val config = McpServerConfig(name = "", transport = "http")
        assertEquals("", config.name)
        assertEquals("http", config.transport)
    }

    @Test
    fun `default config has no auth`() {
        val config = McpServerConfig(name = "test", transport = "http")
        assertEquals("none", config.authType)
        assertEquals("", config.bearerToken)
    }

    @Test
    fun `default config has empty OAuth fields`() {
        val config = McpServerConfig(name = "test", transport = "http")
        assertEquals("", config.accessToken)
        assertEquals("", config.refreshToken)
        assertEquals("", config.clientId)
        assertEquals(0L, config.tokenExpiresAt)
    }

    @Test
    fun `default config is not connected`() {
        val config = McpServerConfig(name = "test", transport = "http")
        assertFalse(config.connected)
    }

    @Test
    fun `default config has empty tools list`() {
        val config = McpServerConfig(name = "test", transport = "http")
        assertTrue(config.tools.isEmpty())
    }

    @Test
    fun `default config has zero health stats`() {
        val config = McpServerConfig(name = "test", transport = "http")
        assertEquals(0, config.successCount)
        assertEquals(0, config.failureCount)
        assertEquals(0L, config.lastCallTime)
    }

    @Test
    fun `default config has manual addedBy`() {
        val config = McpServerConfig(name = "test", transport = "http")
        assertEquals("manual", config.addedBy)
    }

    @Test
    fun `default config is not official`() {
        val config = McpServerConfig(name = "test", transport = "http")
        assertFalse(config.isOfficial)
    }

    // === DATA CLASS COPY ===

    @Test
    fun `copy preserves name and transport`() {
        val original = McpServerConfig(name = "gmail", transport = "http")
        val copy = original.copy(connected = true)
        assertEquals("gmail", copy.name)
        assertEquals("http", copy.transport)
        assertTrue(copy.connected)
    }

    @Test
    fun `copy with updated tools preserves other fields`() {
        val original = McpServerConfig(name = "gmail", transport = "http", authType = "oauth")
        val tools = listOf(McpToolInfo("send_email", "Send", "{}"))
        val copy = original.copy(tools = tools)
        assertEquals(1, copy.tools.size)
        assertEquals("oauth", copy.authType)
        assertEquals("gmail", copy.name)
    }
}
