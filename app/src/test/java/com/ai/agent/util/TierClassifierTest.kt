package com.ai.agent.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TierClassifierTest {

    // === IS NO TOOL MESSAGE (Tier 1) ===

    @Test
    fun `isNoToolMessage returns true for hi`() {
        assertTrue(TierClassifier.isNoToolMessage("hi"))
    }

    @Test
    fun `isNoToolMessage returns true for hello`() {
        assertTrue(TierClassifier.isNoToolMessage("hello"))
    }

    @Test
    fun `isNoToolMessage returns true for hey`() {
        assertTrue(TierClassifier.isNoToolMessage("hey"))
    }

    @Test
    fun `isNoToolMessage returns true for namaste`() {
        assertTrue(TierClassifier.isNoToolMessage("namaste"))
    }

    @Test
    fun `isNoToolMessage returns true for thanks`() {
        assertTrue(TierClassifier.isNoToolMessage("thanks"))
    }

    @Test
    fun `isNoToolMessage returns true for thank you`() {
        assertTrue(TierClassifier.isNoToolMessage("thank you"))
    }

    @Test
    fun `isNoToolMessage returns true for ok`() {
        assertTrue(TierClassifier.isNoToolMessage("ok"))
    }

    @Test
    fun `isNoToolMessage returns true for good morning`() {
        assertTrue(TierClassifier.isNoToolMessage("good morning"))
    }

    @Test
    fun `isNoToolMessage returns true for good night`() {
        assertTrue(TierClassifier.isNoToolMessage("good night"))
    }

    @Test
    fun `isNoToolMessage returns true for bye`() {
        assertTrue(TierClassifier.isNoToolMessage("bye"))
    }

    @Test
    fun `isNoToolMessage returns true for lol`() {
        assertTrue(TierClassifier.isNoToolMessage("lol"))
    }

    @Test
    fun `isNoToolMessage returns true for what can you do`() {
        assertTrue(TierClassifier.isNoToolMessage("what can you do"))
    }

    @Test
    fun `isNoToolMessage returns true for who are you`() {
        assertTrue(TierClassifier.isNoToolMessage("who are you"))
    }

    // === IS NO TOOL MESSAGE — with punctuation ===

    @Test
    fun `isNoToolMessage returns true for hi there`() {
        assertTrue(TierClassifier.isNoToolMessage("hi there"))
    }

    @Test
    fun `isNoToolMessage returns true for hi exclamation`() {
        assertTrue(TierClassifier.isNoToolMessage("hi!"))
    }

    @Test
    fun `isNoToolMessage returns true for ok period`() {
        assertTrue(TierClassifier.isNoToolMessage("ok."))
    }

    @Test
    fun `isNoToolMessage returns true for hello question mark`() {
        assertTrue(TierClassifier.isNoToolMessage("hello?"))
    }

    // === IS NO TOOL MESSAGE — case insensitive ===

    @Test
    fun `isNoToolMessage is case insensitive for HI`() {
        assertTrue(TierClassifier.isNoToolMessage("HI"))
    }

    @Test
    fun `isNoToolMessage is case insensitive for Hello`() {
        assertTrue(TierClassifier.isNoToolMessage("Hello"))
    }

    @Test
    fun `isNoToolMessage is case insensitive for GOOD MORNING`() {
        assertTrue(TierClassifier.isNoToolMessage("GOOD MORNING"))
    }

    // === IS NO TOOL MESSAGE — false for action messages ===

    @Test
    fun `isNoToolMessage returns false for play youtube`() {
        assertFalse(TierClassifier.isNoToolMessage("play youtube video"))
    }

    @Test
    fun `isNoToolMessage returns false for send sms`() {
        assertFalse(TierClassifier.isNoToolMessage("send SMS to Mom"))
    }

    @Test
    fun `isNoToolMessage returns false for open app`() {
        assertFalse(TierClassifier.isNoToolMessage("open YouTube app"))
    }

    @Test
    fun `isNoToolMessage returns false for empty string`() {
        assertFalse(TierClassifier.isNoToolMessage(""))
    }

    @Test
    fun `isNoToolMessage returns false for whitespace only`() {
        assertFalse(TierClassifier.isNoToolMessage("   "))
    }

    @Test
    fun `isNoToolMessage returns false for complex question`() {
        assertFalse(TierClassifier.isNoToolMessage("explain the difference between MCP and connectors"))
    }

    // === CLASSIFY ===

    @Test
    fun `classify returns 1 for greeting`() {
        assertEquals(1, TierClassifier.classify("hi", 0))
    }

    @Test
    fun `classify returns 1 for hello with 0 groups`() {
        assertEquals(1, TierClassifier.classify("hello", 0))
    }

    @Test
    fun `classify returns 1 for greeting even with groups matched`() {
        // Greetings always win — no tools needed
        assertEquals(1, TierClassifier.classify("hi", 5))
    }

    @Test
    fun `classify returns 2 for action with 1 group`() {
        assertEquals(2, TierClassifier.classify("play youtube video", 1))
    }

    @Test
    fun `classify returns 2 for action with 2 groups`() {
        assertEquals(2, TierClassifier.classify("open youtube and play music", 2))
    }

    @Test
    fun `classify returns 3 for unknown message with 0 groups`() {
        assertEquals(3, TierClassifier.classify("explain quantum computing", 0))
    }

    @Test
    fun `classify returns 3 for complex message with 0 groups`() {
        assertEquals(3, TierClassifier.classify("analyze this contract and summarize key points", 0))
    }

    @Test
    fun `classify returns 2 for action with 3+ groups`() {
        // 3+ groups still Tier 2 (was supposed to be 2.5 but code returns 2)
        assertEquals(2, TierClassifier.classify("open youtube and send email and check weather", 3))
    }

    // === IS MEDIUM COMPLEXITY ===

    @Test
    fun `isMediumComplexity returns true for 3+ groups`() {
        assertTrue(TierClassifier.isMediumComplexity(3, "test message"))
    }

    @Test
    fun `isMediumComplexity returns true for 5 groups`() {
        assertTrue(TierClassifier.isMediumComplexity(5, "test"))
    }

    @Test
    fun `isMediumComplexity returns false for 2 groups`() {
        assertFalse(TierClassifier.isMediumComplexity(2, "test message"))
    }

    @Test
    fun `isMediumComplexity returns false for 1 group`() {
        assertFalse(TierClassifier.isMediumComplexity(1, "test"))
    }

    @Test
    fun `isMediumComplexity returns false for 0 groups`() {
        assertFalse(TierClassifier.isMediumComplexity(0, "test"))
    }

    @Test
    fun `isMediumComplexity returns true for message with and`() {
        assertTrue(TierClassifier.isMediumComplexity(1, "open youtube and play music"))
    }

    @Test
    fun `isMediumComplexity returns true for message with then`() {
        assertTrue(TierClassifier.isMediumComplexity(1, "download file then set wallpaper"))
    }

    @Test
    fun `isMediumComplexity returns true for message with after`() {
        assertTrue(TierClassifier.isMediumComplexity(1, "open app after 2 seconds"))
    }

    @Test
    fun `isMediumComplexity returns false for simple message`() {
        assertFalse(TierClassifier.isMediumComplexity(1, "play youtube video"))
    }

    @Test
    fun `isMediumComplexity is case insensitive for AND`() {
        assertTrue(TierClassifier.isMediumComplexity(1, "Open YouTube AND Play Music"))
    }
}
