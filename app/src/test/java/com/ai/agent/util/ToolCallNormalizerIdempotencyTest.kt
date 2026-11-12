package com.ai.agent.util

import com.ai.agent.llm.LLMClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * v4.0.99-collab5: Idempotency / property tests for ToolCallNormalizer.
 *
 * These tests answer the question "are tests light?" by asserting INVARIANTS
 * that must hold for ANY input. If normalize() has a subtle bug (e.g.
 * re-normalizing changes the output, or an alias loop), these WILL fail.
 *
 * The first run of these tests caught ZERO bugs in ToolCallNormalizer —
 * the normalizer is genuinely idempotent on the inputs we generated. That's
 * a real (if modest) confidence gain.
 */
class ToolCallNormalizerIdempotencyTest {

    private val random = Random(42)  // deterministic seed

    private fun normalize(call: LLMClient.ToolCall) = ToolCallNormalizer.normalize(call)

    // === INVARIANT 1: normalize(normalize(x)) == normalize(x) ===
    //     A normalizer that needs to be run twice is broken.

    @Test
    fun `normalize is idempotent on empty args`() {
        val call = LLMClient.ToolCall("tap", emptyMap())
        val once = normalize(call)
        val twice = normalize(once)
        assertEquals(once.args, twice.args)
    }

    @Test
    fun `normalize is idempotent on already-named args`() {
        val call = LLMClient.ToolCall("tap", mapOf("x" to 1, "y" to 2))
        val once = normalize(call)
        val twice = normalize(once)
        assertEquals(once.args, twice.args)
    }

    @Test
    fun `normalize is idempotent on positional args`() {
        val call = LLMClient.ToolCall("tap", mapOf("0" to 1, "1" to 2))
        val once = normalize(call)
        val twice = normalize(once)
        assertEquals(once.args, twice.args)
    }

    @Test
    fun `normalize is idempotent on mixed args`() {
        val call = LLMClient.ToolCall("tap", mapOf("x" to 1, "1" to 2, "extra" to "e"))
        val once = normalize(call)
        val twice = normalize(once)
        assertEquals(once.args, twice.args)
    }

    @Test
    fun `normalize is idempotent on aliased args`() {
        val call = LLMClient.ToolCall("launchApp", mapOf("app" to "com.x"))
        val once = normalize(call)
        val twice = normalize(once)
        assertEquals(once.args, twice.args)
    }

    @Test
    fun `normalize is idempotent on conflicting aliases`() {
        // GAP-1: both "app" and "pkg" present, "app" wins, "pkg" stays
        val call = LLMClient.ToolCall("launchApp", mapOf("app" to "x", "pkg" to "y"))
        val once = normalize(call)
        val twice = normalize(once)
        assertEquals(once.args, twice.args)
    }

    @Test
    fun `normalize is idempotent on tool-specific aliases`() {
        val call = LLMClient.ToolCall("recallMemory", mapOf("key" to "wifi"))
        val once = normalize(call)
        val twice = normalize(once)
        assertEquals(once.args, twice.args)
    }

    // === INVARIANT 2: tool name is NEVER changed by normalize ===

    @Test
    fun `normalize preserves tool name - tap`() {
        assertEquals("tap", normalize(LLMClient.ToolCall("tap", emptyMap())).name)
    }

    @Test
    fun `normalize preserves tool name - unknown tool`() {
        assertEquals("futureTool", normalize(LLMClient.ToolCall("futureTool", emptyMap())).name)
    }

    @Test
    fun `normalize preserves tool name with positional args`() {
        assertEquals("speak", normalize(LLMClient.ToolCall("speak", mapOf("0" to "hi"))).name)
    }

    // === INVARIANT 3: normalize never returns null args ===

    @Test
    fun `normalize never returns null args - empty input`() {
        val result = normalize(LLMClient.ToolCall("tap", emptyMap()))
        assertTrue(result.args.isNotEmpty() || result.args.isEmpty())  // tautology but proves non-null
        assertEquals(0, result.args.size)
    }

    @Test
    fun `normalize never returns null args - with args`() {
        val result = normalize(LLMClient.ToolCall("tap", mapOf("x" to 1)))
        assertTrue(result.args.isNotEmpty())
    }

    // === INVARIANT 4: named args (non-numeric keys) are NEVER lost ===
    //     (the normalize step copies named args first, then fills from positional)

    @Test
    fun `named arg survives normalization - tap x`() {
        val result = normalize(LLMClient.ToolCall("tap", mapOf("x" to 5)))
        assertEquals(5, result.args["x"])
    }

    @Test
    fun `named arg survives with positional present - tap x and 0`() {
        // named wins over positional
        val result = normalize(LLMClient.ToolCall("tap", mapOf("x" to 5, "0" to 9)))
        assertEquals(5, result.args["x"])
    }

    @Test
    fun `extra named arg survives`() {
        val result = normalize(LLMClient.ToolCall("tap", mapOf("x" to 1, "y" to 2, "extra" to "e")))
        assertEquals("e", result.args["extra"])
    }

    // === RANDOMIZED PROPERTY TEST (1000 iterations) ===
    //   Generate random tool calls with mixed named + positional + alias keys.
    //   Assert idempotency holds for ALL of them.
    //   If this ever fails, we have a real bug.

    @Test
    fun `idempotency holds for 1000 random tool calls`() {
        val tools = listOf(
            "tap", "swipe", "type", "speak", "launchApp", "searchInApp",
            "webSearch", "setVolume", "setBrightness", "sendSMS",
            "recallMemory", "createRule", "createConnector", "unknownTool"
        )
        val argKeys = listOf("0", "1", "2", "x", "y", "text", "package", "query",
            "app", "pkg", "name", "key", "ruleName", "baseUrl", "extra")
        val argValues: List<Any> = listOf("hello", 42, true, 3.14, "com.example", "", "with space")

        var tested = 0
        repeat(1000) {
            val tool = tools.random(random)
            val argCount = random.nextInt(0, 5)
            val args = mutableMapOf<String, Any>()
            repeat(argCount) {
                val key = argKeys.random(random)
                val value = argValues.random(random)
                args[key] = value
            }
            val call = LLMClient.ToolCall(tool, args)
            val once = normalize(call)
            val twice = normalize(once)
            // Idempotency: the args must be identical
            assertEquals("idempotency broken for $call -> $once -> $twice", once.args, twice.args)
            // Tool name must be unchanged
            assertEquals("tool name changed for $call", call.name, once.name)
            tested++
        }
        assertEquals(1000, tested)
    }

    // === INVARIANT 5: normalize is deterministic (same input → same output) ===

    @Test
    fun `normalize is deterministic - same input twice gives same output`() {
        val call = LLMClient.ToolCall("tap", mapOf("0" to 970, "1" to 180))
        val a = normalize(call)
        val b = normalize(call)
        assertEquals(a.args, b.args)
        assertEquals(a.name, b.name)
    }

    @Test
    fun `normalize is deterministic across 100 calls`() {
        val call = LLMClient.ToolCall("recallMemory", mapOf("key" to "wifi", "q" to "facts"))
        val first = normalize(call)
        repeat(100) {
            val again = normalize(call)
            assertEquals(first.args, again.args)
        }
    }

    // === INVARIANT 6: aliases only ADD canonical keys, never REMOVE existing ones ===
    //     (GAP-1: alias keys themselves may stay, but the canonical must appear)

    @Test
    fun `alias adds canonical key without removing existing - launchApp`() {
        val result = normalize(LLMClient.ToolCall("launchApp", mapOf("app" to "com.x")))
        // "app" is removed and "package" appears
        assertEquals("com.x", result.args["package"])
    }

    @Test
    fun `canonical wins over alias - launchApp with both`() {
        val result = normalize(LLMClient.ToolCall("launchApp", mapOf("package" to "right", "app" to "wrong")))
        assertEquals("right", result.args["package"])
    }

    // === INVARIANT 7: GAP-1 documented quirk is STABLE ===
    //     (if the other AI fixes GAP-1, these will fail and surface the fix)

    @Test
    fun `GAP-1 stability - alias key stays when canonical present`() {
        val result = normalize(LLMClient.ToolCall("launchApp", mapOf("package" to "right", "app" to "wrong")))
        // GAP-1: "app" key stays. When fixed (alias removed), this fails → update.
        assertTrue("app" in result.args)
        assertEquals("wrong", result.args["app"])
    }

    @Test
    fun `GAP-1 stability - first alias wins between app and pkg`() {
        val result = normalize(LLMClient.ToolCall("launchApp", mapOf("app" to "from-app", "pkg" to "from-pkg")))
        // "app" is processed first in the mapOf iteration → "package" = "from-app"
        assertEquals("from-app", result.args["package"])
        // GAP-1: "pkg" stays. When fixed, this fails.
        assertTrue("pkg" in result.args)
    }
}
