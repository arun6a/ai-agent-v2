package com.ai.agent.util

import com.ai.agent.llm.LLMClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v4.0.99-collab5: Focused regression coverage for the `speak` positional
 * mapping in ToolCallNormalizer.
 *
 * WHY THIS FILE EXISTS:
 *   Mutation test #2 (delete the `"speak" to listOf("text")` line from
 *   ToolCallNormalizer.positionalOrder) was caught by only ONE test
 *   (`speak positional arg becomes text - v4_0_96 log fix`). That's a weak
 *   spot — the v4.0.96 production bug-fix line is guarded by a single test.
 *   This file adds 12 more speak-specific tests so any accidental removal
 *   of the speak entry (or change to its signature) is caught by 13 tests
 *   instead of 1.
 *
 * HISTORY: The `speak(0="text")` failure was a real production bug (logged
 * in v4.0.96 log analysis). The fix added "speak" to the positional map.
 * If the other AI ever refactors that map and forgets speak, the AI's
 * `speak(0="hello there")` calls will silently lose their text arg again.
 * These tests prevent that regression.
 */
class ToolCallNormalizerSpeakRegressionTest {

    private fun n(vararg args: Pair<String, Any>) =
        ToolCallNormalizer.normalize(LLMClient.ToolCall("speak", args.toMap()))

    // === The core regression: speak(0="...") MUST become speak(text="...") ===

    @Test
    fun `speak positional 0 becomes text - simple`() {
        assertEquals(mapOf("text" to "hello"), n("0" to "hello").args)
    }

    @Test
    fun `speak positional 0 becomes text - long text`() {
        val longText = "This is a longer piece of text that the AI might want to speak aloud. " +
            "It has multiple sentences. It should still map correctly to the text parameter."
        assertEquals(mapOf("text" to longText), n("0" to longText).args)
    }

    @Test
    fun `speak positional 0 becomes text - with punctuation`() {
        assertEquals(mapOf("text" to "Hello, world!"), n("0" to "Hello, world!").args)
    }

    @Test
    fun `speak positional 0 becomes text - with unicode`() {
        assertEquals(mapOf("text" to "Bonjour café"), n("0" to "Bonjour café").args)
    }

    @Test
    fun `speak positional 0 becomes text - empty string`() {
        assertEquals(mapOf("text" to ""), n("0" to "").args)
    }

    @Test
    fun `speak positional 0 becomes text - numeric value coerced`() {
        // If the AI passes speak(0=42), the value is an Int 42 — should map to text.
        assertEquals(mapOf("text" to 42), n("0" to 42).args)
    }

    @Test
    fun `speak positional 0 becomes text - boolean value`() {
        assertEquals(mapOf("text" to true), n("0" to true).args)
    }

    // === Named arg is untouched (no positional mapping needed) ===

    @Test
    fun `speak named text arg passes through`() {
        assertEquals(mapOf("text" to "hi"), n("text" to "hi").args)
    }

    @Test
    fun `speak named text arg passes through - long`() {
        val longText = "A longer named text argument."
        assertEquals(mapOf("text" to longText), n("text" to longText).args)
    }

    // === Named text wins over positional 0 (the GAP-1 priority rule) ===

    @Test
    fun `speak named text wins over positional 0`() {
        val args = n("text" to "from-named", "0" to "from-positional").args
        assertEquals("from-named", args["text"])
        // Only "text" should be in the result (positional is dropped in favor of named)
        assertEquals(1, args.size)
    }

    // === Extra positional args are dropped (speak has only 1 positional slot) ===

    @Test
    fun `speak positional 1 is dropped - only slot 0 maps`() {
        val args = n("0" to "hello", "1" to "world").args
        assertEquals(mapOf("text" to "hello"), args)
    }

    @Test
    fun `speak positional 2 is dropped`() {
        val args = n("0" to "hello", "2" to "extra").args
        assertEquals(mapOf("text" to "hello"), args)
    }

    // === Tool name is preserved ===

    @Test
    fun `speak tool name preserved with positional`() {
        assertEquals("speak", n("0" to "hi").name)
    }

    @Test
    fun `speak tool name preserved with named`() {
        assertEquals("speak", n("text" to "hi").name)
    }

    // === Realistic shapes from production logs ===

    @Test
    fun `real shape - AI calls speak with positional greeting`() {
        // "speak(0=\"hello there\")" after text parsing
        val result = n("0" to "hello there")
        assertEquals(LLMClient.ToolCall("speak", mapOf("text" to "hello there")), result)
    }

    @Test
    fun `real shape - AI calls speak with positional question`() {
        val result = n("0" to "what time is it?")
        assertEquals("what time is it?", result.args["text"])
    }

    @Test
    fun `real shape - AI calls speak with named arg correctly`() {
        // Well-formed call — normalizer should be a no-op
        val result = n("text" to "the battery is at 80 percent")
        assertEquals("the battery is at 80 percent", result.args["text"])
    }
}
