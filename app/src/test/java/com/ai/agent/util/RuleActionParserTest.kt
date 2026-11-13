package com.ai.agent.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for RuleActionParser — the DIRECT-rule parsing logic extracted from
 * RuleEngine (v3.0.0). Covers prefix stripping, direct/smart classification,
 * multi-call splitting and argument parsing (positional + named).
 *
 * Several tests intentionally assert the CURRENT (buggy) behavior and are
 * marked with the GAP id they document — see RuleActionParser.kt header.
 */
class RuleActionParserTest {

    // === IS DIRECT RULE ===

    @Test
    fun `isDirectRule true for simple tool call`() {
        assertTrue(RuleActionParser.isDirectRule("setBrightness(50)"))
    }

    @Test
    fun `isDirectRule true for quoted string arg`() {
        assertTrue(RuleActionParser.isDirectRule("speak(\"hello\")"))
    }

    @Test
    fun `isDirectRule true for empty args`() {
        assertTrue(RuleActionParser.isDirectRule("vibrate()"))
    }

    @Test
    fun `isDirectRule true with direct prefix`() {
        assertTrue(RuleActionParser.isDirectRule("direct:setBrightness(50)"))
    }

    @Test
    fun `isDirectRule true with tool prefix`() {
        assertTrue(RuleActionParser.isDirectRule("tool:speak(\"hi\")"))
    }

    @Test
    fun `isDirectRule false for natural language`() {
        assertFalse(RuleActionParser.isDirectRule("Reply politely to messages"))
    }

    @Test
    fun `isDirectRule false for unclosed paren`() {
        assertFalse(RuleActionParser.isDirectRule("setBrightness(50"))
    }

    @Test
    fun `isDirectRule false for missing paren`() {
        assertFalse(RuleActionParser.isDirectRule("setBrightness50)"))
    }

    @Test
    fun `isDirectRule false when name starts with digit`() {
        assertFalse(RuleActionParser.isDirectRule("2fast(1)"))
    }

    @Test
    fun `isDirectRule true when name starts with underscore`() {
        assertTrue(RuleActionParser.isDirectRule("_hidden(1)"))
    }

    @Test
    fun `isDirectRule false when name contains space`() {
        assertFalse(RuleActionParser.isDirectRule("set brightness(50)"))
    }

    @Test
    fun `isDirectRule false for empty action`() {
        assertFalse(RuleActionParser.isDirectRule(""))
    }

    @Test
    fun `isDirectRule false for chained prefixes in tool-first order - GAP-6`() {
        // removePrefix("direct:") runs first, then removePrefix("tool:").
        // "tool:direct:foo(1)" → "direct:foo(1)" — colon survives → not direct.
        assertFalse(RuleActionParser.isDirectRule("tool:direct:foo(1)"))
    }

    @Test
    fun `isDirectRule true for chained prefixes in direct-first order`() {
        assertTrue(RuleActionParser.isDirectRule("direct:tool:foo(1)"))
    }

    @Test
    fun `GAP-7 leading whitespace no longer breaks classification - FIXED v4 0 100`() {
        // v4.0.100: trim() runs FIRST, then removePrefix.
        assertTrue(RuleActionParser.isDirectRule("  direct:foo(1)"))
    }

    @Test
    fun `isDirectRule true for multi-line action - FIXED v4 0 100 (DOTALL)`() {
        // v4.0.100: Added DOT_MATCHES_ALL flag — newlines inside parens are OK now.
        assertTrue(RuleActionParser.isDirectRule("speak(\"line1\nline2\")"))
    }

    @Test
    fun `isDirectRule true for garbage inside parens (greedy regex)`() {
        // greedy .* accepts almost anything between the parens
        assertTrue(RuleActionParser.isDirectRule("foo(a)b)"))
    }

    // === STRIP PREFIX ===

    @Test
    fun `stripPrefix removes direct prefix`() {
        assertEquals("foo(1)", RuleActionParser.stripPrefix("direct:foo(1)"))
    }

    @Test
    fun `stripPrefix removes tool prefix`() {
        assertEquals("foo(1)", RuleActionParser.stripPrefix("tool:foo(1)"))
    }

    @Test
    fun `stripPrefix leaves plain action`() {
        assertEquals("foo(1)", RuleActionParser.stripPrefix("foo(1)"))
    }

    @Test
    fun `GAP-7 stripPrefix trims first then removes prefix - FIXED v4 0 100`() {
        // v4.0.100: trim() runs FIRST, so leading whitespace no longer defeats removePrefix.
        assertEquals("foo(1)", RuleActionParser.stripPrefix("  direct:foo(1)  "))
    }

    @Test
    fun `stripPrefix removes prefix when whitespace is trailing only`() {
        assertEquals("foo(1)", RuleActionParser.stripPrefix("direct:foo(1)  "))
    }

    @Test
    fun `stripPrefix removes both chained prefixes`() {
        assertEquals("foo(1)", RuleActionParser.stripPrefix("direct:tool:foo(1)"))
    }

    @Test
    fun `stripPrefix of prefix-only string is empty`() {
        assertEquals("", RuleActionParser.stripPrefix("direct:"))
    }

    // === SPLIT TOOL CALLS ===

    @Test
    fun `splitToolCalls splits on semicolon`() {
        val calls = RuleActionParser.splitToolCalls("setBrightness(50); speak(\"hi\")")
        assertEquals(listOf("setBrightness(50)", "speak(\"hi\")"), calls)
    }

    @Test
    fun `splitToolCalls drops empty segments`() {
        val calls = RuleActionParser.splitToolCalls("a(1);;b(2);")
        assertEquals(listOf("a(1)", "b(2)"), calls)
    }

    @Test
    fun `splitToolCalls single call unchanged`() {
        assertEquals(listOf("a(1)"), RuleActionParser.splitToolCalls("a(1)"))
    }

    @Test
    fun `splitToolCalls empty string gives empty list`() {
        assertEquals(emptyList<String>(), RuleActionParser.splitToolCalls(""))
    }

    @Test
    fun `splitToolCalls whitespace only gives empty list`() {
        assertEquals(emptyList<String>(), RuleActionParser.splitToolCalls("   "))
    }

    @Test
    fun `splitToolCalls semicolon only gives empty list`() {
        assertEquals(emptyList<String>(), RuleActionParser.splitToolCalls(";"))
    }

    @Test
    fun `splitToolCalls is NOT quote-aware - semicolon inside string breaks call`() {
        // DOCUMENTS BUG: "speak(\"a;b\")" splits into two broken fragments.
        // Each fragment then fails parseToolCall → RuleEngine logs "Couldn't parse".
        val calls = RuleActionParser.splitToolCalls("speak(\"a;b\")")
        assertEquals(listOf("speak(\"a", "b\")"), calls)
    }

    // === PARSE TOOL CALL — BASICS ===

    @Test
    fun `parseToolCall int positional arg becomes Int`() {
        val parsed = RuleActionParser.parseToolCall("setBrightness(50)")
        assertEquals("setBrightness", parsed?.toolName)
        assertEquals(50, parsed?.args?.get("0"))
    }

    @Test
    fun `parseToolCall quoted string keeps spaces`() {
        val parsed = RuleActionParser.parseToolCall("speak(\"hello world\")")
        assertEquals("hello world", parsed?.args?.get("0"))
    }

    @Test
    fun `parseToolCall no args gives empty map`() {
        val parsed = RuleActionParser.parseToolCall("vibrate()")
        assertEquals("vibrate", parsed?.toolName)
        assertTrue(parsed?.args?.isEmpty() == true)
    }

    @Test
    fun `GAP-2 short phone number in quotes stays as String - FIXED v4 0 100`() {
        // v4.0.100: Quoted values are no longer coerced to Int.
        val parsed = RuleActionParser.parseToolCall("sendSMS(\"555123\",\"hi there\")")
        assertEquals("555123", parsed?.args?.get("0"))  // String, not Int
        assertEquals("hi there", parsed?.args?.get("1"))
    }

    @Test
    fun `long phone number overflows Int and survives as String`() {
        val parsed = RuleActionParser.parseToolCall("sendSMS(\"919876543210\",\"hi\")")
        assertEquals("919876543210", parsed?.args?.get("0"))
    }

    @Test
    fun `parseToolCall named args`() {
        val parsed = RuleActionParser.parseToolCall("setVolume(level=30, mode=\"ring\")")
        assertEquals(30, parsed?.args?.get("level"))
        assertEquals("ring", parsed?.args?.get("mode"))
    }

    @Test
    fun `parseToolCall named arg with equals inside value`() {
        val parsed = RuleActionParser.parseToolCall("foo(url=https://x.com?a=1)")
        assertEquals("https://x.com?a=1", parsed?.args?.get("url"))
    }

    @Test
    fun `parseToolCall single quoted arg`() {
        val parsed = RuleActionParser.parseToolCall("speak('hello')")
        assertEquals("hello", parsed?.args?.get("0"))
    }

    @Test
    fun `parseToolCall negative int`() {
        val parsed = RuleActionParser.parseToolCall("foo(-5)")
        assertEquals(-5, parsed?.args?.get("0"))
    }

    @Test
    fun `parseToolCall decimal stays string`() {
        val parsed = RuleActionParser.parseToolCall("foo(50.5)")
        assertEquals("50.5", parsed?.args?.get("0"))
    }

    @Test
    fun `parseToolCall boolean stays string`() {
        // No boolean coercion — everything non-Int is a String
        val parsed = RuleActionParser.parseToolCall("foo(true)")
        assertEquals("true", parsed?.args?.get("0"))
    }

    @Test
    fun `parseToolCall mixed int and string positional`() {
        val parsed = RuleActionParser.parseToolCall("foo(10,hello)")
        assertEquals(10, parsed?.args?.get("0"))
        assertEquals("hello", parsed?.args?.get("1"))
    }

    @Test
    fun `parseToolCall tool name with digits and underscore`() {
        val parsed = RuleActionParser.parseToolCall("tool_2x(1)")
        assertEquals("tool_2x", parsed?.toolName)
    }

    @Test
    fun `parseToolCall apostrophe inside double quotes survives`() {
        val parsed = RuleActionParser.parseToolCall("foo(\"it's\")")
        assertEquals("it's", parsed?.args?.get("0"))
    }

    @Test
    fun `parseToolCall trims inner whitespace`() {
        val parsed = RuleActionParser.parseToolCall("foo( spaced )")
        assertEquals("spaced", parsed?.args?.get("0"))
    }

    // === PARSE TOOL CALL — FAILURES ===

    @Test
    fun `parseToolCall returns null for plain text`() {
        assertNull(RuleActionParser.parseToolCall("not a tool call"))
    }

    @Test
    fun `parseToolCall returns null for unclosed paren`() {
        assertNull(RuleActionParser.parseToolCall("foo(50"))
    }

    @Test
    fun `parseToolCall returns null for name with space`() {
        assertNull(RuleActionParser.parseToolCall("foo bar(50)"))
    }

    @Test
    fun `parseToolCall returns null for empty input`() {
        assertNull(RuleActionParser.parseToolCall(""))
    }

    // === DOCUMENTED BUGS (assert current behavior) ===

    @Test
    fun `GAP-1 comma inside quoted string stays as one arg - FIXED v4 0 100`() {
        // v4.0.100: Quote-aware splitter — comma inside quotes stays together.
        val parsed = RuleActionParser.parseToolCall("speak(\"hello, world\")")
        assertEquals("hello, world", parsed?.args?.get("0"))
        assertNull(parsed?.args?.get("1"))  // no second arg
    }

    @Test
    fun `GAP-2 quoted numeric string stays as String - FIXED v4 0 100`() {
        // v4.0.100: Only unquoted values are coerced to Int.
        val parsed = RuleActionParser.parseToolCall("speak(\"50\")")
        assertEquals("50", parsed?.args?.get("0"))  // String, not Int
    }

    @Test
    fun `GAP-3 whitespace-only args produce empty arg map - FIXED v4 0 100`() {
        // v4.0.100: Quote-aware splitter returns empty list for whitespace-only args.
        val parsed = RuleActionParser.parseToolCall("foo( )")
        assertTrue(parsed?.args?.isEmpty() == true)
    }

    @Test
    fun `GAP-4 named arg key quotes are stripped - FIXED v4 0 100`() {
        // v4.0.100: Keys are now unquoted.
        val parsed = RuleActionParser.parseToolCall("foo(\"key\"=v)")
        assertEquals("v", parsed?.args?.get("key"))  // not "\"key\""
    }

    @Test
    fun `nested call inside args becomes a garbage string`() {
        // No recursion: the greedy regex swallows the outer paren, so bar(b)
        // lands as one string fragment (minus the final closing paren).
        val parsed = RuleActionParser.parseToolCall("foo(a,bar(b))")
        assertEquals("a", parsed?.args?.get("0"))
        assertEquals("bar(b)", parsed?.args?.get("1"))
    }

    // === END-TO-END RULE SHAPES (the exact rules a user would create) ===

    @Test
    fun `real rule - brightness then speak`() {
        val calls = RuleActionParser.splitToolCalls(
            RuleActionParser.stripPrefix("direct:setBrightness(50); speak(\"good morning\")")
        )
        assertEquals(2, calls.size)
        assertEquals("setBrightness", RuleActionParser.parseToolCall(calls[0])?.toolName)
        assertEquals("speak", RuleActionParser.parseToolCall(calls[1])?.toolName)
        assertEquals("good morning", RuleActionParser.parseToolCall(calls[1])?.args?.get("0"))
    }

    @Test
    fun `real rule - dnd mode toggle`() {
        val parsed = RuleActionParser.parseToolCall("toggleDND(enable=true)")
        assertEquals("toggleDND", parsed?.toolName)
        // GAP-2 applies: quoted or not, "true" stays String
        assertEquals("true", parsed?.args?.get("enable"))
    }

    @Test
    fun `real rule - send whatsapp message with named args`() {
        val parsed = RuleActionParser.parseToolCall(
            "sendWhatsApp(to=\"+919876543210\", msg=\"Reached home\")"
        )
        assertEquals("+919876543210", parsed?.args?.get("to"))
        assertEquals("Reached home", parsed?.args?.get("msg"))
    }

    @Test
    fun `smart rule action is not direct`() {
        assertFalse(RuleActionParser.isDirectRule("Read the notification and reply politely"))
        assertFalse(RuleActionParser.isDirectRule("direct:Read the notification and reply politely"))
    }
}
