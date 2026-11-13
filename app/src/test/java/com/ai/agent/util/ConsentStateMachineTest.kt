package com.ai.agent.util

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for ConsentStateMachine — the pure state logic of ConsentManager
 * (v4.0.2/v4.0.17/v4.0.86) without the Android overlay.
 *
 * This is the JVM-testable half of tomorrow's device test #1 (consent
 * overlay show/hide race): the double-request cancellation semantics and
 * the resolve/timeout paths are proven here.
 */
class ConsentStateMachineTest {

    private fun sm() = ConsentStateMachine()

    // === BEGIN ===

    @Test
    fun `begin publishes the request on the StateFlow`() {
        val s = sm()
        s.begin("sendSMS", "Send a text", "to +919876543210")
        val req = s.pendingRequest
        assertEquals("sendSMS", req?.toolName)
        assertEquals("Send a text", req?.description)
        assertEquals("to +919876543210", req?.details)
    }

    @Test
    fun `begin makes isPending true`() {
        val s = sm()
        assertFalse(s.isPending())
        s.begin("deleteFile", "Delete a file", "/sdcard/x.txt")
        assertTrue(s.isPending())
    }

    @Test
    fun `pendingRequestFlow exposes the same request`() {
        val s = sm()
        s.begin("toggleDND", "Enable DND", "")
        assertEquals("toggleDND", s.pendingRequestFlow.value?.toolName)
    }

    @Test
    fun `initial state is clean`() {
        val s = sm()
        assertNull(s.pendingRequest)
        assertFalse(s.isPending())
        assertNull(s.pendingRequestFlow.value)
    }

    // === RESOLVE — APPROVE ===

    @Test
    fun `resolve true completes the waiter with true`() = runTest {
        val s = sm()
        val d = s.begin("sendSMS", "Send a text", "hi")
        s.resolve(true)
        assertEquals(true, s.awaitResponse(d, timeoutMs = 1000))
    }

    // === RESOLVE — DENY ===

    @Test
    fun `resolve false completes the waiter with false`() = runTest {
        val s = sm()
        val d = s.begin("deleteFile", "Delete a file", "x.txt")
        s.resolve(false)
        assertEquals(false, s.awaitResponse(d, timeoutMs = 1000))
    }

    @Test
    fun `resolve clears the pending request`() {
        val s = sm()
        s.begin("sendSMS", "d", "x")
        s.resolve(true)
        assertNull(s.pendingRequest)
        assertFalse(s.isPending())
        assertNull(s.pendingRequestFlow.value)
    }

    // === TIMEOUT ===

    @Test
    fun `no response within timeout returns false`() = runTest {
        val s = sm()
        val d = s.begin("sendSMS", "d", "x")
        // 50ms virtual timeout, nobody resolves → false (user ignored dialog)
        assertEquals(false, s.awaitResponse(d, timeoutMs = 50))
    }

    @Test
    fun `timeout with default 30s returns false when unresolved`() = runTest {
        val s = sm()
        val d = s.begin("sendSMS", "d", "x")
        assertEquals(false, s.awaitResponse(d))  // default timeout, virtual time
    }

    // === DOUBLE REQUEST (the race) ===

    @Test
    fun `second begin cancels the first request with false`() = runTest {
        val s = sm()
        val first = s.begin("sendSMS", "first", "x")
        val second = s.begin("deleteFile", "second", "y")
        // The first waiter is completed with false immediately
        assertEquals(false, s.awaitResponse(first, timeoutMs = 1000))
        // The pending request is now the SECOND one
        assertEquals("second", s.pendingRequest?.description)
        assertEquals("deleteFile", s.pendingRequest?.toolName)
        // And the second waiter is still unresolved
        s.resolve(true)
        assertEquals(true, s.awaitResponse(second, timeoutMs = 1000))
    }

    @Test
    fun `triple begin leaves only the last request valid`() = runTest {
        val s = sm()
        val d1 = s.begin("a", "one", "x")
        val d2 = s.begin("b", "two", "x")
        val d3 = s.begin("c", "three", "x")
        assertEquals(false, s.awaitResponse(d1, timeoutMs = 1000))
        assertEquals(false, s.awaitResponse(d2, timeoutMs = 1000))
        assertEquals("three", s.pendingRequestFlow.value?.description)
        s.resolve(true)
        assertEquals(true, s.awaitResponse(d3, timeoutMs = 1000))
        assertNull(s.pendingRequestFlow.value)  // resolve() cleared it
    }

    @Test
    fun `resolve only completes the CURRENT deferred`() = runTest {
        val s = sm()
        val first = s.begin("a", "one", "x")
        val second = s.begin("b", "two", "x")
        s.resolve(false)  // denies the SECOND request
        assertEquals(true, second.isCompleted)
        assertEquals(false, second.await())
        assertEquals(false, first.await())  // cancelled earlier
    }

    // === FINISH (ConsentManager's finally path) ===

    @Test
    fun `finish clears state without completing the waiter`() = runTest {
        val s = sm()
        val d = s.begin("sendSMS", "d", "x")
        s.finish()
        assertFalse(s.isPending())
        assertNull(s.pendingRequest)
        // The waiter is NOT completed — await times out with false
        assertEquals(false, s.awaitResponse(d, timeoutMs = 50))
    }

    // === EDGE CASES ===

    @Test
    fun `resolve with nothing pending is a safe no-op - GAP-2`() {
        val s = sm()
        s.resolve(true)  // must not throw
        assertFalse(s.isPending())
    }

    @Test
    fun `begin after finish works normally`() = runTest {
        val s = sm()
        s.begin("a", "one", "x")
        s.finish()
        val d = s.begin("b", "two", "x")
        assertEquals("two", s.pendingRequestFlow.value?.description)
        s.resolve(true)
        assertEquals(true, s.awaitResponse(d, timeoutMs = 1000))
    }

    @Test
    fun `full consent lifecycle sendSMS`() = runTest {
        val s = sm()
        // ToolExecutor asks for consent on IO thread
        val d: CompletableDeferred<Boolean> = s.begin(
            "sendSMS", "Send SMS to Amma",
            "Message: Reached home safely"
        )
        // ChatScreen observes the flow and shows the dialog
        assertEquals("sendSMS", s.pendingRequestFlow.value?.toolName)
        // User taps Approve
        s.resolve(true)
        // ToolExecutor resumes with the answer
        assertEquals(true, s.awaitResponse(d, timeoutMs = 1000))
        // State is clean for the next request
        assertFalse(s.isPending())
    }

    @Test
    fun `full consent lifecycle with denial`() = runTest {
        val s = sm()
        val d = s.begin("deleteFile", "Delete /sdcard/old.txt", "Cannot be undone")
        s.resolve(false)
        assertEquals(false, s.awaitResponse(d, timeoutMs = 1000))
        assertFalse(s.isPending())
    }
}
