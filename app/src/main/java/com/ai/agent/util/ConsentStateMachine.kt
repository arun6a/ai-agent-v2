package com.ai.agent.util

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * v4.0.99-collab4: ConsentStateMachine — the pure, JVM-testable state logic
 * of ConsentManager (v4.0.2 / v4.0.17 / v4.0.86) with the overlay and
 * Android Context parts removed. ConsentManager should delegate here
 * (wiring GAP below).
 *
 * Flow (mirrors ConsentManager.requestConsentWithContext with context=null):
 *   1. begin() — cancels any previous request (its waiter gets false),
 *      publishes the new request on a StateFlow (so Compose can observe it)
 *      and returns the CompletableDeferred the UI must complete.
 *   2. UI calls resolve(true/false) — publishes null and completes the waiter.
 *   3. awaitResponse() — suspends until resolve or timeout (30s in
 *      ConsentManager); timeout returns false.
 *   4. finish() — clears state (ConsentManager does this in a finally block,
 *      also hiding the overlay there).
 *
 * This extraction makes the race the team wants to verify on-device
 * (tomorrow's device test #1: overlay show/hide + double-request) testable
 * on the JVM: the "new request cancels old one" semantics are asserted here.
 *
 * GAP-1 (MEDIUM — real bug family): begin() completes the OLD deferred with
 *     false but does NOT null it out before installing the new one. That is
 *     correct here, but ConsentManager has the same pattern PLUS the overlay
 *     — the on-device race (old overlay visible while a new request begins)
 *     cannot be reproduced on the JVM because ConsentOverlay is not part of
 *     this state machine. After wiring, the overlay hide/show must stay
 *     outside this class exactly like here.
 *
 * GAP-2 (LOW): resolve() when nothing is pending is a silent no-op (no log,
 *     no error). A late double-tap on Approve/Deny in the UI is invisible.
 */
class ConsentStateMachine {

    data class ConsentRequest(
        val toolName: String,
        val description: String,
        val details: String,
    )

    private val _pendingRequest = MutableStateFlow<ConsentRequest?>(null)
    val pendingRequestFlow = _pendingRequest.asStateFlow()

    /** Backward-compat direct read (ChatScreen reads this). */
    val pendingRequest: ConsentRequest? get() = _pendingRequest.value

    private var deferred: CompletableDeferred<Boolean>? = null

    /**
     * Start a consent request. Any previous pending request is cancelled —
     * its waiter receives false. Returns the deferred that the UI must
     * complete via [resolve].
     */
    fun begin(toolName: String, description: String, details: String): CompletableDeferred<Boolean> {
        deferred?.complete(false)  // cancel any existing request (safety)
        val newDeferred = CompletableDeferred<Boolean>()
        deferred = newDeferred
        _pendingRequest.value = ConsentRequest(toolName, description, details)
        return newDeferred
    }

    /** Suspend until [resolve] completes [d], or timeout (default 30s) → false. */
    suspend fun awaitResponse(
        d: CompletableDeferred<Boolean>,
        timeoutMs: Long = 30_000L,
    ): Boolean = withTimeoutOrNull(timeoutMs) { d.await() } ?: false

    /** UI approved/denied. Clears the pending request and completes the waiter. */
    fun resolve(approved: Boolean) {
        _pendingRequest.value = null
        deferred?.complete(approved)
        deferred = null
    }

    /** Clear state without completing anything (ConsentManager's finally path). */
    fun finish() {
        _pendingRequest.value = null
        deferred = null
    }

    /** True while a consent dialog should be showing. */
    fun isPending(): Boolean = _pendingRequest.value != null
}
