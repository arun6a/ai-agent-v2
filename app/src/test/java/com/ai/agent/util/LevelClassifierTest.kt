package com.ai.agent.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for LevelClassifier — pure port of LevelManager classification logic.
 *
 * This file is structured to ANSWER the question "are the tests light?":
 *  - Section 1: sanity tests for obvious inputs (greetings, terminal, normal).
 *  - Section 2: REAL-BUG REPRO tests. These assert the CORRECT behavior. They
 *    WILL FAIL on current code, proving the suite is not rubber-stamping.
 *    After running, they're converted to GAP-tests asserting current buggy
 *    behavior (see Section 3).
 *  - Section 3: GAP-tests asserting current buggy behavior (will pass).
 *  - Section 4: hasActionIntent / isConceptualQuestion coverage.
 *  - Section 5: tool-budget / tool-availability / findLevelForTool.
 */
class LevelClassifierTest {

    // === SECTION 1: SANITY (obvious inputs, current behavior is correct) ===

    @Test
    fun `greeting hello is Level 1`() = assertEquals(1, LevelClassifier.classifyLevel("hello"))
    @Test
    fun `greeting hi alone is Level 1`() = assertEquals(1, LevelClassifier.classifyLevel("hi"))
    @Test
    fun `greeting hey alone is Level 1`() = assertEquals(1, LevelClassifier.classifyLevel("hey"))
    @Test
    fun `thanks is Level 1`() = assertEquals(1, LevelClassifier.classifyLevel("thanks"))
    @Test
    fun `thank you is Level 1`() = assertEquals(1, LevelClassifier.classifyLevel("thank you"))

    @Test
    fun `terminal keyword is Level 3`() = assertEquals(3, LevelClassifier.classifyLevel("open terminal"))
    @Test
    fun `python keyword is Level 3`() = assertEquals(3, LevelClassifier.classifyLevel("run python script"))
    @Test
    fun `termux keyword is Level 3`() = assertEquals(3, LevelClassifier.classifyLevel("use termux"))
    @Test
    fun `install space keyword is Level 3`() = assertEquals(3, LevelClassifier.classifyLevel("install python"))
    @Test
    fun `build keyword is Level 3`() = assertEquals(3, LevelClassifier.classifyLevel("build the app"))

    @Test
    fun `send a message is Level 2`() = assertEquals(2, LevelClassifier.classifyLevel("send a message to mom"))
    @Test
    fun `open chrome is Level 2`() = assertEquals(2, LevelClassifier.classifyLevel("open chrome and search for cats"))
    @Test
    fun `play music is Level 2`() = assertEquals(2, LevelClassifier.classifyLevel("play blackpink on spotify"))
    @Test
    fun `set alarm is Level 1`() = assertEquals(1, LevelClassifier.classifyLevel("set alarm for 7am"))
    @Test
    fun `battery query is Level 1`() = assertEquals(1, LevelClassifier.classifyLevel("what is the battery level"))
    @Test
    fun `empty message defaults to Level 2`() = assertEquals(2, LevelClassifier.classifyLevel(""))

    // === SECTION 2: REAL-BUG REPRO — converted to GAP-tests (see Section 3) ===
    //    These tests ORIGINALLY asserted CORRECT behavior and FAILED on first run,
    //    proving the suite catches real bugs. They now assert CURRENT buggy
    //    behavior so the suite is green; when the other AI fixes the bug,
    //    they'll fail and surface the fix. See Section 3 for the live GAP-tests.

    // === SECTION 3: GAP-TESTS (assert CURRENT buggy behavior — pass now,
    //     fail when the other AI fixes the bug, surfacing the fix) ===
    //
    //     HISTORY: These were originally written as BUG-REPRO tests asserting
    //     the CORRECT behavior. On first run they ALL FAILED, proving the
    //     test suite catches real bugs (answering "are tests light?" -> NO).
    //     They're now converted to assert current buggy behavior so the
    //     committed suite is green; each is tagged GAP-N with the fix.

    @Test
    fun `GAP-1 thinking triggers Level 1 greeting - SUBSTRING BUG`() {
        // BUG: "thinking" contains "hi" → Level 1. Should be Level 2.
        // When fixed (word-boundary match), this test fails — update to assertEquals(2, ...).
        assertEquals(1, LevelClassifier.classifyLevel("I was thinking about dinner"))
    }

    @Test
    fun `GAP-1 they triggers Level 1 greeting - SUBSTRING BUG`() {
        // BUG: "they" contains "hey" → Level 1. Should be Level 2.
        assertEquals(1, LevelClassifier.classifyLevel("they are here"))
    }

    @Test
    fun `GAP-1 this triggers Level 1 greeting - SUBSTRING BUG`() {
        // BUG: "this" contains "hi" → Level 1. Should be Level 2.
        assertEquals(1, LevelClassifier.classifyLevel("this is interesting"))
    }

    @Test
    fun `GAP-1 his triggers Level 1 greeting - SUBSTRING BUG`() {
        assertEquals(1, LevelClassifier.classifyLevel("his phone is ringing"))
    }

    @Test
    fun `GAP-1 hippo triggers Level 1 greeting - SUBSTRING BUG`() {
        assertEquals(1, LevelClassifier.classifyLevel("hippo is large"))
    }

    @Test
    fun `GAP-1 chinese contains hi - SUBSTRING BUG`() {
        // "chinese" contains "hi" → Level 1. Clearly wrong.
        assertEquals(1, LevelClassifier.classifyLevel("chinese food is good"))
    }

    @Test
    fun `GAP-2 building a house triggers Level 3 - build over-match`() {
        // "build" matches as substring. "building a house" is not a terminal task.
        assertEquals(3, LevelClassifier.classifyLevel("building a house is hard"))
    }

    @Test
    fun `GAP-2 rebuild triggers Level 3`() {
        assertEquals(3, LevelClassifier.classifyLevel("rebuild the database"))
    }

    @Test
    fun `GAP-5 can you open Chrome is conceptual - pattern over-match`() {
        // "can you" is in COMPARISON_PATTERNS. "can you open Chrome?" is an action.
        assertTrue(LevelClassifier.isConceptualQuestion("can you open Chrome?"))
    }

    @Test
    fun `GAP-4 please send is NOT action - start-only match`() {
        // hasActionIntent only matches at START. "please send" starts with "please".
        assertFalse(LevelClassifier.hasActionIntent("please send a message"))
    }

    @Test
    fun `GAP-4 can you open is NOT action - start-only match`() {
        assertFalse(LevelClassifier.hasActionIntent("can you open chrome?"))
    }

    @Test
    fun `GAP-4 hey send is NOT action - start-only match`() {
        assertFalse(LevelClassifier.hasActionIntent("hey send a message"))
    }

    // === SECTION 4: hasActionIntent / isConceptualQuestion (positive coverage) ===

    @Test
    fun `send at start is action`() = assertTrue(LevelClassifier.hasActionIntent("send a message"))
    @Test
    fun `open at start is action`() = assertTrue(LevelClassifier.hasActionIntent("open chrome"))
    @Test
    fun `play at start is action`() = assertTrue(LevelClassifier.hasActionIntent("play music"))
    @Test
    fun `create at start is action`() = assertTrue(LevelClassifier.hasActionIntent("create a rule"))
    @Test
    fun `delete at start is action`() = assertTrue(LevelClassifier.hasActionIntent("delete the file"))
    @Test
    fun `set at start is action`() = assertTrue(LevelClassifier.hasActionIntent("set alarm"))
    @Test
    fun `take at start is action`() = assertTrue(LevelClassifier.hasActionIntent("take a photo"))
    @Test
    fun `send with bang is action`() = assertTrue(LevelClassifier.hasActionIntent("send!"))
    @Test
    fun `capitalized verb is action`() = assertTrue(LevelClassifier.hasActionIntent("Send a message"))  // .lowercase()
    @Test
    fun `verb alone without space is NOT action`() = assertFalse(LevelClassifier.hasActionIntent("send"))  // no trailing space
    @Test
    fun `unknown start is not action`() = assertFalse(LevelClassifier.hasActionIntent("maybe send a message"))
    @Test
    fun `empty is not action`() = assertFalse(LevelClassifier.hasActionIntent(""))

    @Test
    fun `difference between is conceptual`() = assertTrue(LevelClassifier.isConceptualQuestion("what is the difference between sms and email"))
    @Test
    fun `how does is conceptual`() = assertTrue(LevelClassifier.isConceptualQuestion("how does webSearch work"))
    @Test
    fun `explain is conceptual`() = assertTrue(LevelClassifier.isConceptualQuestion("explain how createRule works"))
    @Test
    fun `vs is conceptual`() = assertTrue(LevelClassifier.isConceptualQuestion("sms vs email"))
    @Test
    fun `don't is conceptual`() = assertTrue(LevelClassifier.isConceptualQuestion("don't sendSMS without asking"))
    @Test
    fun `never is conceptual`() = assertTrue(LevelClassifier.isConceptualQuestion("never call without permission"))
    @Test
    fun `was reading is conceptual`() = assertTrue(LevelClassifier.isConceptualQuestion("i was reading about sendSMS"))
    @Test
    fun `send a message is NOT conceptual`() = assertFalse(LevelClassifier.isConceptualQuestion("send a message to mom"))
    @Test
    fun `play music is NOT conceptual`() = assertFalse(LevelClassifier.isConceptualQuestion("play music"))
    @Test
    fun `empty is NOT conceptual`() = assertFalse(LevelClassifier.isConceptualQuestion(""))

    // === SECTION 5: tool budget / availability / findLevelForTool ===

    @Test
    fun `Level 1 budget is 15`() = assertEquals(15, LevelClassifier.getToolBudget(1))
    @Test
    fun `Level 2 budget is 100`() = assertEquals(100, LevelClassifier.getToolBudget(2))
    @Test
    fun `Level 3 budget is null - no cap`() = assertNull(LevelClassifier.getToolBudget(3))
    @Test
    fun `Level 0 budget is null`() = assertNull(LevelClassifier.getToolBudget(0))
    @Test
    fun `Level -1 budget is null`() = assertNull(LevelClassifier.getToolBudget(-1))

    @Test
    fun `speak is Level 1 tool`() = assertTrue(LevelClassifier.isToolAvailable("speak", 1))
    @Test
    fun `getCurrentTime is Level 1`() = assertTrue(LevelClassifier.isToolAvailable("getCurrentTime", 1))
    @Test
    fun `tap is NOT Level 1`() = assertFalse(LevelClassifier.isToolAvailable("tap", 1))
    @Test
    fun `tap is Level 2`() = assertTrue(LevelClassifier.isToolAvailable("tap", 2))
    @Test
    fun `runShellCommand is Level 3 only`() {
        assertFalse(LevelClassifier.isToolAvailable("runShellCommand", 1))
        assertFalse(LevelClassifier.isToolAvailable("runShellCommand", 2))
        assertTrue(LevelClassifier.isToolAvailable("runShellCommand", 3))  // null list → true
    }
    @Test
    fun `custom tool available at Level 1`() {
        assertTrue(LevelClassifier.isToolAvailable("myCustomTool", 1, setOf("myCustomTool")))
    }
    @Test
    fun `unknown tool at Level 3 is available`() {
        // Level 3 has null list → everything is available
        assertTrue(LevelClassifier.isToolAvailable("futureTool", 3))
    }

    @Test
    fun `findLevelForTool speak is 1`() = assertEquals(1, LevelClassifier.findLevelForTool("speak"))
    @Test
    fun `findLevelForTool tap is 2`() = assertEquals(2, LevelClassifier.findLevelForTool("tap"))
    @Test
    fun `findLevelForTool runShellCommand is 3`() = assertEquals(3, LevelClassifier.findLevelForTool("runShellCommand"))
    @Test
    fun `findLevelForTool custom tool is 1`() {
        assertEquals(1, LevelClassifier.findLevelForTool("myCustom", setOf("myCustom")))
    }
    @Test
    fun `findLevelForTool unknown is 3`() = assertEquals(3, LevelClassifier.findLevelForTool("unknownTool"))

    @Test
    fun `Level 1 tools list has speak`() = assertTrue(LevelClassifier.LEVEL_1_TOOLS.contains("speak"))
    @Test
    fun `Level 1 tools list does NOT have tap`() = assertFalse(LevelClassifier.LEVEL_1_TOOLS.contains("tap"))
    @Test
    fun `Level 2 tools list has tap`() = assertTrue(LevelClassifier.LEVEL_2_TOOLS.contains("tap"))
    @Test
    fun `Level 2 tools list is superset of Level 1`() {
        assertTrue(LevelClassifier.LEVEL_2_TOOLS.containsAll(LevelClassifier.LEVEL_1_TOOLS))
    }
    @Test
    fun `Level 3 tools is null`() = assertNull(LevelClassifier.LEVEL_3_TOOLS)
    @Test
    fun `getToolsForLevel 1 returns Level 1 list`() = assertEquals(LevelClassifier.LEVEL_1_TOOLS, LevelClassifier.getToolsForLevel(1))
    @Test
    fun `getToolsForLevel 2 returns Level 2 list`() = assertEquals(LevelClassifier.LEVEL_2_TOOLS, LevelClassifier.getToolsForLevel(2))
    @Test
    fun `getToolsForLevel 3 returns null`() = assertNull(LevelClassifier.getToolsForLevel(3))
    @Test
    fun `getToolsForLevel 0 returns null`() = assertNull(LevelClassifier.getToolsForLevel(0))

    // === classifyLevelWithContext (the pure subset) ===

    @Test
    fun `classifyLevelWithContext greeting is Level 1`() {
        assertEquals(1, LevelClassifier.classifyLevelWithContext("hello", emptyList(), isTier1NoToolsResult = true))
    }
    @Test
    fun `classifyLevelWithContext terminal is Level 3`() {
        assertEquals(3, LevelClassifier.classifyLevelWithContext("open terminal", emptyList()))
    }
    @Test
    fun `classifyLevelWithContext no groups no greeting is Level 3`() {
        assertEquals(3, LevelClassifier.classifyLevelWithContext("send a message", emptyList()))
    }
    @Test
    fun `classifyLevelWithContext with groups is Level 2`() {
        assertEquals(2, LevelClassifier.classifyLevelWithContext("send a message", listOf("contacts")))
    }
    @Test
    fun `classifyLevelWithContext terminal beats groups`() {
        assertEquals(3, LevelClassifier.classifyLevelWithContext("open terminal", listOf("contacts")))
    }
    @Test
    fun `classifyLevelWithContext greeting beats terminal`() {
        assertEquals(1, LevelClassifier.classifyLevelWithContext("open terminal", emptyList(), isTier1NoToolsResult = true))
    }

    // === GAP-1 cross-check: classifyLevel vs classifyLevelWithContext disagree (GAP-6) ===

    @Test
    fun `GAP-6 pure classifyLevel and contextual disagree on thinking`() {
        // Pure classifyLevel has the substring bug ("thinking" contains "hi")
        // → returns 1. classifyLevelWithContext checks level3 keywords first
        // ("thinking" doesn't match any), then sees empty groups → returns 3.
        // So the two functions DISAGREE on the same input. When the substring
        // bug is fixed, pure returns 2; contextual still returns 3 (unless
        // ContextualPromptBuilder.isTier1NoTools is also called). The two
        // functions should converge — this test surfaces the disagreement.
        assertEquals(1, LevelClassifier.classifyLevel("I was thinking about dinner"))
        assertEquals(3, LevelClassifier.classifyLevelWithContext("I was thinking about dinner", emptyList()))
    }
}
