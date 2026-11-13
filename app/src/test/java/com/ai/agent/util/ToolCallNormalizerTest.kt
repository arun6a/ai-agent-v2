package com.ai.agent.util

import com.ai.agent.llm.LLMClient
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for ToolCallNormalizer — extracted from ToolExecutor
 * (normalizePositionalArgs, v4.0.20 + v4.0.96).
 *
 * Covers the three layers:
 *  1. Global aliases (app/pkg → package; name → package only for package tools)
 *  2. Tool-specific aliases (v4.0.96, from real log analysis)
 *  3. Positional → named arg mapping for text-parsed tool calls
 *
 * The v4.0.20 regression is a named test: a GLOBAL name→package alias broke
 * createRule/createSkill/saveToKnowledge/searchContacts (all legitimately use
 * `name`). Tests marked GAP-N document current buggy behavior — see
 * ToolCallNormalizer.kt header.
 */
class ToolCallNormalizerTest {

    private fun n(name: String, vararg args: Pair<String, Any>) =
        ToolCallNormalizer.normalize(LLMClient.ToolCall(name, args.toMap()))

    // === GLOBAL ALIASES: app / pkg → package ===

    @Test
    fun `launchApp app alias becomes package`() {
        assertEquals(mapOf("package" to "com.x"), n("launchApp", "app" to "com.x").args)
    }

    @Test
    fun `launchApp pkg alias becomes package`() {
        assertEquals(mapOf("package" to "com.x"), n("launchApp", "pkg" to "com.x").args)
    }

    @Test
    fun `launchApp name alias becomes package - package tools only`() {
        assertEquals(mapOf("package" to "twitter"), n("launchApp", "name" to "twitter").args)
    }

    @Test
    fun `searchInApp name alias becomes package`() {
        val args = n("searchInApp", "name" to "twitter", "query" to "cats").args
        assertEquals(mapOf("package" to "twitter", "query" to "cats"), args)
    }

    @Test
    fun `forceStopApp name alias becomes package`() {
        assertEquals(mapOf("package" to "com.x"), n("forceStopApp", "name" to "com.x").args)
    }

    @Test
    fun `getAppInfo name alias becomes package`() {
        assertEquals(mapOf("package" to "com.x"), n("getAppInfo", "name" to "com.x").args)
    }

    @Test
    fun `uninstallApp name alias becomes package`() {
        assertEquals(mapOf("package" to "com.x"), n("uninstallApp", "name" to "com.x").args)
    }

    @Test
    fun `searchInChrome name alias becomes package`() {
        assertEquals(mapOf("package" to "com.android.chrome"), n("searchInChrome", "name" to "com.android.chrome").args)
    }

    // === THE v4.0.20 REGRESSION (name→package must stay scoped) ===

    @Test
    fun `createRule name arg is NOT stolen by package alias`() {
        val args = n("createRule", "name" to "morning", "action" to "speak(hi)").args
        assertEquals("morning", args["name"])
        assertEquals(null, args["package"])
    }

    @Test
    fun `createSkill name arg is preserved`() {
        assertEquals("mySkill", n("createSkill", "name" to "mySkill").args["name"])
    }

    @Test
    fun `saveToKnowledge name arg is preserved`() {
        assertEquals("wifi", n("saveToKnowledge", "name" to "wifi").args["name"])
    }

    @Test
    fun `searchContacts name arg is preserved`() {
        assertEquals("bob", n("searchContacts", "name" to "bob").args["name"])
    }

    // === GLOBAL ALIAS QUIRKS ===

    @Test
    fun `GAP-1 alias key removed when canonical already present - FIXED v4 0 100`() {
        // v4.0.100: Alias is now always removed, even when canonical exists.
        val args = n("launchApp", "package" to "com.right", "app" to "com.wrong").args
        assertEquals(mapOf("package" to "com.right"), args)  // "app" is discarded
    }

    @Test
    fun `GAP-1 first alias wins, second removed - FIXED v4 0 100`() {
        // v4.0.100: First alias becomes canonical, second alias is removed.
        val args = n("launchApp", "app" to "from-app", "pkg" to "from-pkg").args
        assertEquals(mapOf("package" to "from-app"), args)  // "pkg" is discarded
    }

    @Test
    fun `GAP-2 global app alias applies to tools without package param`() {
        // setVolume gets a "package" arg it never asked for
        assertEquals(mapOf("package" to "x"), n("setVolume", "app" to "x").args)
    }

    @Test
    fun `GAP-2 global pkg alias applies to any tool`() {
        assertEquals(mapOf("package" to "5"), n("setBrightness", "pkg" to "5").args)
    }

    // === TOOL-SPECIFIC ALIASES (v4.0.96) ===

    @Test
    fun `recallMemory key alias becomes query`() {
        assertEquals(mapOf("query" to "wifi"), n("recallMemory", "key" to "wifi").args)
    }

    @Test
    fun `recallMemory q alias becomes query`() {
        assertEquals(mapOf("query" to "wifi"), n("recallMemory", "q" to "wifi").args)
    }

    @Test
    fun `recallMemory search alias becomes query`() {
        assertEquals(mapOf("query" to "wifi"), n("recallMemory", "search" to "wifi").args)
    }

    @Test
    fun `recallMemory canonical query wins, alias discarded - FIXED v4 0 100`() {
        // v4.0.100: Alias "key" is removed when canonical "query" exists.
        val args = n("recallMemory", "query" to "right", "key" to "wrong").args
        assertEquals(mapOf("query" to "right"), args)  // "key" is discarded
    }

    @Test
    fun `recallKnowledge key alias becomes query`() {
        assertEquals(mapOf("query" to "facts"), n("recallKnowledge", "key" to "facts").args)
    }

    @Test
    fun `createCalendarEvent start alias becomes startTime`() {
        assertEquals(mapOf("startTime" to "9am"), n("createCalendarEvent", "start" to "9am").args)
    }

    @Test
    fun `createCalendarEvent duration alias becomes durationMinutes`() {
        assertEquals(mapOf("durationMinutes" to 30), n("createCalendarEvent", "duration" to 30).args)
    }

    @Test
    fun `createCalendarEvent dur alias becomes durationMinutes`() {
        assertEquals(mapOf("durationMinutes" to 15), n("createCalendarEvent", "dur" to 15).args)
    }

    @Test
    fun `createRule ruleName alias becomes name`() {
        assertEquals(mapOf("name" to "morning"), n("createRule", "ruleName" to "morning").args)
    }

    @Test
    fun `createRule rule_name alias becomes name`() {
        assertEquals(mapOf("name" to "morning"), n("createRule", "rule_name" to "morning").args)
    }

    @Test
    fun `createConnector desc alias becomes description`() {
        assertEquals(mapOf("description" to "d"), n("createConnector", "desc" to "d").args)
    }

    @Test
    fun `createConnector baseUrl alias becomes apiBase`() {
        assertEquals(mapOf("apiBase" to "https://x.com"), n("createConnector", "baseUrl" to "https://x.com").args)
    }

    @Test
    fun `createConnector base alias becomes apiBase`() {
        assertEquals(mapOf("apiBase" to "https://x.com"), n("createConnector", "base" to "https://x.com").args)
    }

    @Test
    fun `createConnector url alias becomes apiBase`() {
        assertEquals(mapOf("apiBase" to "https://x.com"), n("createConnector", "url" to "https://x.com").args)
    }

    @Test
    fun `unknown tool gets no tool-specific aliases`() {
        assertEquals(mapOf("key" to "k"), n("someFutureTool", "key" to "k").args)
    }

    // === POSITIONAL → NAMED (the `speak(0=...)` log failure fix) ===

    @Test
    fun `tap positional args become x and y`() {
        assertEquals(mapOf("x" to 970, "y" to 180), n("tap", "0" to 970, "1" to 180).args)
    }

    @Test
    fun `tap mixed named and positional`() {
        assertEquals(mapOf("x" to 5, "y" to 180), n("tap", "x" to 5, "1" to 180).args)
    }

    @Test
    fun `tap named arg wins over positional`() {
        assertEquals(mapOf("x" to 5, "y" to 180), n("tap", "x" to 5, "0" to 9, "1" to 180).args)
    }

    @Test
    fun `swipe positional args become x1 y1 x2 y2`() {
        val args = n("swipe", "0" to 100, "1" to 200, "2" to 300, "3" to 400).args
        assertEquals(mapOf("x1" to 100, "y1" to 200, "x2" to 300, "y2" to 400), args)
    }

    @Test
    fun `type positional arg becomes text`() {
        assertEquals(mapOf("text" to "hello"), n("type", "0" to "hello").args)
    }

    @Test
    fun `clickByText positional arg becomes text`() {
        assertEquals(mapOf("text" to "OK"), n("clickByText", "0" to "OK").args)
    }

    @Test
    fun `speak positional arg becomes text - v4_0_96 log fix`() {
        // From real logs: AI called speak(0="...") and the tool saw no text.
        assertEquals(mapOf("text" to "hello there"), n("speak", "0" to "hello there").args)
    }

    @Test
    fun `speak named text arg is untouched`() {
        assertEquals(mapOf("text" to "hi"), n("speak", "text" to "hi").args)
    }

    @Test
    fun `launchApp positional arg becomes package`() {
        assertEquals(mapOf("package" to "com.x"), n("launchApp", "0" to "com.x").args)
    }

    @Test
    fun `searchInApp positional args become package and query`() {
        assertEquals(
            mapOf("package" to "com.x", "query" to "msg"),
            n("searchInApp", "0" to "com.x", "1" to "msg").args
        )
    }

    @Test
    fun `webSearch positional args become query and fetchTop`() {
        assertEquals(
            mapOf("query" to "ai news", "fetchTop" to true),
            n("webSearch", "0" to "ai news", "1" to true).args
        )
    }

    @Test
    fun `openGoogleSearch positional arg becomes query`() {
        assertEquals(mapOf("query" to "q"), n("openGoogleSearch", "0" to "q").args)
    }

    @Test
    fun `browserOpen positional arg becomes url`() {
        assertEquals(mapOf("url" to "https://x.com"), n("browserOpen", "0" to "https://x.com").args)
    }

    @Test
    fun `openUrl positional arg becomes url`() {
        assertEquals(mapOf("url" to "https://y.com"), n("openUrl", "0" to "https://y.com").args)
    }

    @Test
    fun `browserClickText positional arg becomes selector`() {
        assertEquals(mapOf("selector" to "Login"), n("browserClickText", "0" to "Login").args)
    }

    @Test
    fun `browserFillForm positional args become field and value`() {
        assertEquals(
            mapOf("field" to "email", "value" to "a@b.c"),
            n("browserFillForm", "0" to "email", "1" to "a@b.c").args
        )
    }

    @Test
    fun `remember positional args become key and value`() {
        assertEquals(
            mapOf("key" to "wifi", "value" to "password123"),
            n("remember", "0" to "wifi", "1" to "password123").args
        )
    }

    @Test
    fun `recall positional arg becomes query`() {
        assertEquals(mapOf("query" to "meeting"), n("recall", "0" to "meeting").args)
    }

    @Test
    fun `recallSimilar positional arg becomes query`() {
        assertEquals(mapOf("query" to "q"), n("recallSimilar", "0" to "q").args)
    }

    @Test
    fun `searchMemory positional arg becomes query`() {
        assertEquals(mapOf("query" to "q"), n("searchMemory", "0" to "q").args)
    }

    @Test
    fun `setVolume positional arg becomes level`() {
        assertEquals(mapOf("level" to 50), n("setVolume", "0" to 50).args)
    }

    @Test
    fun `setBrightness positional arg becomes level`() {
        assertEquals(mapOf("level" to 80), n("setBrightness", "0" to 80).args)
    }

    @Test
    fun `sendSMS positional args become number and message`() {
        assertEquals(
            mapOf("number" to "555123", "message" to "hi"),
            n("sendSMS", "0" to "555123", "1" to "hi").args
        )
    }

    @Test
    fun `callContact positional arg becomes number`() {
        assertEquals(mapOf("number" to "555"), n("callContact", "0" to "555").args)
    }

    @Test
    fun `setAlarm positional args become hour and minute`() {
        assertEquals(mapOf("hour" to 7, "minute" to 30), n("setAlarm", "0" to 7, "1" to 30).args)
    }

    @Test
    fun `setTimer positional arg becomes seconds`() {
        assertEquals(mapOf("seconds" to 60), n("setTimer", "0" to 60).args)
    }

    // === NO-OP PATHS ===

    @Test
    fun `named-only args pass through unchanged`() {
        val args = n("tap", "x" to 1, "y" to 2).args
        assertEquals(mapOf("x" to 1, "y" to 2), args)
    }

    @Test
    fun `no-arg tool with no args is a no-op`() {
        assertEquals(emptyMap<String, Any>(), n("pressBack").args)
    }

    @Test
    fun `no-arg tool with stray positional arg keeps raw key`() {
        // Not in the positional map (empty signature) → {"0":1} survives as-is
        assertEquals(mapOf("0" to 1), n("pressBack", "0" to 1).args)
    }

    @Test
    fun `unknown tool keeps raw positional keys - GAP-5`() {
        assertEquals(mapOf("0" to "gps"), n("getCurrentLocation", "0" to "gps").args)
    }

    @Test
    fun `extra named args survive normalization`() {
        val args = n("tap", "0" to 970, "extra" to "e", "1" to 180).args
        assertEquals(mapOf("x" to 970, "extra" to "e", "y" to 180), args)
    }

    // === DOCUMENTED BUGS ===

    @Test
    fun `GAP-3 non-canonical numeric key is counted as positional then dropped`() {
        // "01".toIntOrNull() = 1 → triggers the positional path, but the
        // mapping only looks up exact "0"/"1" keys → "01" silently vanishes.
        val args = n("tap", "01" to 970, "1" to 180).args
        assertEquals(mapOf("y" to 180), args)
    }

    @Test
    fun `GAP-4 positional beyond signature length is dropped silently`() {
        val args = n("tap", "0" to 1, "1" to 2, "2" to 3).args
        assertEquals(mapOf("x" to 1, "y" to 2), args)
    }

    @Test
    fun `tool name is preserved exactly by the normalizer`() {
        assertEquals("createRule", n("createRule", "name" to "x").name)
    }

    // === REAL LOG SHAPES (from v4.0.96 log analysis) ===

    @Test
    fun `real shape - text-parsed tap with coordinates`() {
        // "tap(970, 180)" after text parsing
        val result = n("tap", "0" to 970, "1" to 180)
        assertEquals(LLMClient.ToolCall("tap", mapOf("x" to 970, "y" to 180)), result)
    }

    @Test
    fun `real shape - createRule with all correct names is untouched`() {
        val args = n(
            "createRule",
            "name" to "battery saver",
            "triggerType" to "battery",
            "action" to "setBrightness(20)"
        ).args
        assertEquals("battery saver", args["name"])
        assertEquals("battery", args["triggerType"])
        assertEquals("setBrightness(20)", args["action"])
    }

    @Test
    fun `real shape - createConnector with alias-heavy args`() {
        val args = n(
            "createConnector",
            "name" to "github",
            "desc" to "GitHub API",
            "baseUrl" to "https://api.github.com"
        ).args
        assertEquals("github", args["name"])          // not stolen (createConnector has no package param)
        assertEquals("GitHub API", args["description"])
        assertEquals("https://api.github.com", args["apiBase"])
    }
}
