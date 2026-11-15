package com.ai.agent.tools.planning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for ConversationStore (v4.0.76) — the agent loop's externalized
 * memory. Pure Kotlin object, tested directly on the JVM (no extraction
 * needed, no Android deps).
 *
 * Covers: iteration add/get, size, the 80-entry cap, reply/result truncation,
 * recent-iteration window, progress summary, clear() and snapshot safety.
 *
 * Documented quirks are asserted with comments (for the other AI):
 *   - QUIRK-1: toolCallsSummary is NOT truncated (brainReply 500,
 *     toolResultsSummary 1000, but tool calls summary is unbounded).
 *   - QUIRK-2: getRecentIterations(negative) throws IllegalArgumentException
 *     (Kotlin takeLast contract) — crash risk if a caller passes a dynamic
 *     negative count.
 *   - QUIRK-3: duplicate step numbers are allowed; getIteration returns the
 *     FIRST match.
 */
class ConversationStoreTest {

    @Before
    fun setUp() {
        ConversationStore.clear()
    }

    private fun longString(len: Int, fill: Char = 'x'): String = String(CharArray(len) { fill })

    // === BASIC ADD / GET ===

    @Test
    fun `addIteration stores entry retrievable by step`() {
        ConversationStore.addIteration(1, "brain said hi", "launchApp(x)", "ok")
        val entry = ConversationStore.getIteration(1)
        assertEquals(1, entry?.step)
        assertEquals("brain said hi", entry?.brainReply)
        assertEquals("launchApp(x)", entry?.toolCallsSummary)
        assertEquals("ok", entry?.toolResultsSummary)
    }

    @Test
    fun `getIteration returns null for unknown step`() {
        ConversationStore.addIteration(1, "a", "b", "c")
        assertNull(ConversationStore.getIteration(99))
    }

    @Test
    fun `getIteration on empty store returns null`() {
        assertNull(ConversationStore.getIteration(1))
    }

    @Test
    fun `size reflects added iterations`() {
        assertEquals(0, ConversationStore.size())
        ConversationStore.addIteration(1, "a", "b", "c")
        ConversationStore.addIteration(2, "a", "b", "c")
        assertEquals(2, ConversationStore.size())
    }

    @Test
    fun `steps do not need to be sequential`() {
        ConversationStore.addIteration(10, "a", "b", "c")
        ConversationStore.addIteration(20, "d", "e", "f")
        assertEquals("d", ConversationStore.getIteration(20)?.brainReply)
        assertNull(ConversationStore.getIteration(15))
    }

    @Test
    fun `empty strings are stored as-is`() {
        ConversationStore.addIteration(1, "", "", "")
        val entry = ConversationStore.getIteration(1)
        assertEquals("", entry?.brainReply)
        assertEquals("", entry?.toolCallsSummary)
        assertEquals("", entry?.toolResultsSummary)
    }

    // === TRUNCATION ===

    @Test
    fun `brainReply truncated to 500 chars`() {
        ConversationStore.addIteration(1, longString(600), "calls", "results")
        assertEquals(500, ConversationStore.getIteration(1)?.brainReply?.length)
    }

    @Test
    fun `brainReply at exactly 500 chars is not cut`() {
        ConversationStore.addIteration(1, longString(500), "c", "r")
        assertEquals(500, ConversationStore.getIteration(1)?.brainReply?.length)
    }

    @Test
    fun `toolResultsSummary truncated to 1000 chars`() {
        ConversationStore.addIteration(1, "brain", "calls", longString(1500))
        assertEquals(1000, ConversationStore.getIteration(1)?.toolResultsSummary?.length)
    }

    @Test
    fun `toolResultsSummary at exactly 1000 chars is not cut`() {
        ConversationStore.addIteration(1, "b", "c", longString(1000))
        assertEquals(1000, ConversationStore.getIteration(1)?.toolResultsSummary?.length)
    }

    @Test
    fun `QUIRK-1 toolCallsSummary is NOT truncated`() {
        // DOCUMENTS QUIRK: only brainReply (500) and toolResultsSummary (1000)
        // are truncated — a huge toolCallsSummary goes in full.
        ConversationStore.addIteration(1, "b", longString(2000), "r")
        assertEquals(2000, ConversationStore.getIteration(1)?.toolCallsSummary?.length)
    }

    @Test
    fun `truncation keeps the prefix not the suffix`() {
        ConversationStore.addIteration(1, "START" + longString(600, 'z'), "c", "r")
        assertTrue(ConversationStore.getIteration(1)?.brainReply?.startsWith("START") == true)
    }

    // === 80-ENTRY CAP ===

    @Test
    fun `cap at 80 - exactly 80 entries all kept`() {
        repeat(80) { i -> ConversationStore.addIteration(i + 1, "b$i", "c", "r") }
        assertEquals(80, ConversationStore.size())
        assertEquals("b0", ConversationStore.getIteration(1)?.brainReply)
        assertEquals("b79", ConversationStore.getIteration(80)?.brainReply)
    }

    @Test
    fun `cap at 80 - oldest entries dropped beyond cap`() {
        repeat(85) { i -> ConversationStore.addIteration(i + 1, "b$i", "c", "r") }
        assertEquals(80, ConversationStore.size())
        // steps 1..5 dropped, step 6 is the first survivor
        assertNull(ConversationStore.getIteration(1))
        assertNull(ConversationStore.getIteration(5))
        assertEquals("b5", ConversationStore.getIteration(6)?.brainReply)
        assertEquals("b84", ConversationStore.getIteration(85)?.brainReply)
    }

    @Test
    fun `cap at 80 - adding far beyond cap keeps only newest window`() {
        repeat(100) { i -> ConversationStore.addIteration(i + 1, "b$i", "c", "r") }
        assertEquals(80, ConversationStore.size())
        assertNull(ConversationStore.getIteration(20))
        assertEquals("b20", ConversationStore.getIteration(21)?.brainReply)
        assertEquals("b99", ConversationStore.getIteration(100)?.brainReply)
    }

    @Test
    fun `entries beyond cap keep newest-first retrieval working`() {
        repeat(90) { i -> ConversationStore.addIteration(i + 1, "b$i", "c", "r") }
        val recent = ConversationStore.getRecentIterations(3)
        assertEquals(listOf(88, 89, 90), recent.map { it.step })
    }

    // === RECENT ITERATIONS WINDOW ===

    @Test
    fun `getRecentIterations returns last N in original order`() {
        ConversationStore.addIteration(1, "a", "c", "r")
        ConversationStore.addIteration(2, "b", "c", "r")
        ConversationStore.addIteration(3, "d", "c", "r")
        val recent = ConversationStore.getRecentIterations(2)
        assertEquals(listOf(2, 3), recent.map { it.step })
    }

    @Test
    fun `getRecentIterations with zero returns empty`() {
        ConversationStore.addIteration(1, "a", "c", "r")
        assertEquals(emptyList<ConversationStore.IterationEntry>(), ConversationStore.getRecentIterations(0))
    }

    @Test
    fun `getRecentIterations larger than size returns all`() {
        ConversationStore.addIteration(1, "a", "c", "r")
        ConversationStore.addIteration(2, "b", "c", "r")
        assertEquals(2, ConversationStore.getRecentIterations(10).size)
    }

    @Test
    fun `getRecentIterations on empty store returns empty`() {
        assertTrue(ConversationStore.getRecentIterations(5).isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `QUIRK-2 getRecentIterations with negative count throws`() {
        // DOCUMENTS CRASH RISK: Kotlin takeLast(n) throws for n < 0.
        ConversationStore.getRecentIterations(-1)
    }

    // === PROGRESS SUMMARY ===

    @Test
    fun `progress summary set and get`() {
        assertNull(ConversationStore.getProgressSummary())
        ConversationStore.setProgressSummary("3 of 7 steps done")
        assertEquals("3 of 7 steps done", ConversationStore.getProgressSummary())
    }

    @Test
    fun `progress summary can be overwritten`() {
        ConversationStore.setProgressSummary("old")
        ConversationStore.setProgressSummary("new")
        assertEquals("new", ConversationStore.getProgressSummary())
    }

    @Test
    fun `progress summary survives iteration cap`() {
        ConversationStore.setProgressSummary("summary")
        repeat(90) { i -> ConversationStore.addIteration(i + 1, "b", "c", "r") }
        assertEquals("summary", ConversationStore.getProgressSummary())
    }

    // === CLEAR / SNAPSHOT SAFETY ===

    @Test
    fun `clear resets everything`() {
        ConversationStore.addIteration(1, "a", "b", "c")
        ConversationStore.setProgressSummary("s")
        ConversationStore.clear()
        assertEquals(0, ConversationStore.size())
        assertNull(ConversationStore.getIteration(1))
        assertNull(ConversationStore.getProgressSummary())
    }

    @Test
    fun `getAllIterations returns an immutable snapshot`() {
        ConversationStore.addIteration(1, "a", "b", "c")
        ConversationStore.addIteration(2, "d", "e", "f")
        val snapshot = ConversationStore.getAllIterations()
        assertEquals(2, snapshot.size)
        ConversationStore.clear()
        // snapshot survives the clear — it is a copy
        assertEquals(2, snapshot.size)
    }

    @Test
    fun `getAllIterations preserves insertion order`() {
        ConversationStore.addIteration(1, "a", "c", "r")
        ConversationStore.addIteration(2, "b", "c", "r")
        ConversationStore.addIteration(3, "e", "c", "r")
        assertEquals(listOf(1, 2, 3), ConversationStore.getAllIterations().map { it.step })
    }

    // === QUIRK-3: DUPLICATE STEPS ===

    @Test
    fun `QUIRK-3 duplicate step numbers return first match`() {
        // DOCUMENTS QUIRK: nothing prevents re-adding the same step.
        ConversationStore.addIteration(5, "first", "c", "r")
        ConversationStore.addIteration(5, "second", "c", "r")
        assertEquals("first", ConversationStore.getIteration(5)?.brainReply)
        assertEquals(2, ConversationStore.size())
    }

    // === AGENT-LOOP SHAPES (realistic usage) ===

    @Test
    fun `realistic agent loop sequence`() {
        // Round 1: open twitter
        ConversationStore.addIteration(1, "Opening Twitter", "launchApp(twitter)", "App opened")
        // Round 2: read screen
        ConversationStore.addIteration(2, "Checking screen", "readScreen()", "Compose button found")
        // Round 3: type + post
        ConversationStore.addIteration(
            3, "Posting tweet",
            "typeText(hello world), tapButton(Post)",
            "Posted successfully"
        )
        ConversationStore.setProgressSummary("3/3 steps done — tweet posted")

        assertEquals(3, ConversationStore.size())
        assertEquals("launchApp(twitter)", ConversationStore.getIteration(1)?.toolCallsSummary)
        assertEquals("Compose button found", ConversationStore.getIteration(2)?.toolResultsSummary)
        assertEquals("3/3 steps done — tweet posted", ConversationStore.getProgressSummary())
        assertEquals(1, ConversationStore.getRecentIterations(1).size)
        assertEquals(3, ConversationStore.getRecentIterations(1).first().step)
    }

    @Test
    fun `entries with same content are data-class equal`() {
        ConversationStore.addIteration(1, "same", "same", "same")
        ConversationStore.addIteration(1, "same", "same", "same")
        val list = ConversationStore.getAllIterations()
        assertEquals(list[0], list[1])
    }
}
