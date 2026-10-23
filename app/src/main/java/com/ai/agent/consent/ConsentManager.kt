package com.ai.agent.consent

import kotlinx.coroutines.CompletableDeferred

/**
 * v4.0.2: ConsentManager — bridges ToolExecutor (Dispatchers.IO) ↔ UI dialog (Main).
 *
 * Flow:
 * 1. ToolExecutor detects a sensitive tool (sendSMS, deleteFile, etc.)
 * 2. Calls [requestConsent] with a description of the action
 * 3. ChatViewModel observes [pendingRequest] → shows ConsentDialog
 * 4. User taps Approve/Cancel → [resolve] is called with true/false
 * 5. ToolExecutor's [requestConsent] suspend returns the result
 *
 * The dialog is shown in the ChatScreen overlay. Only ONE consent at a time
 * (the agent shouldn't batch sensitive actions — each needs its own approval).
 */
object ConsentManager {

    data class ConsentRequest(
        val toolName: String,
        val description: String,
        val details: String,
    )

    /** The current pending request, or null if none. Observed by ChatViewModel. */
    @Volatile
    var pendingRequest: ConsentRequest? = null
        private set

    private var deferred: CompletableDeferred<Boolean>? = null

    /**
     * Called from ToolExecutor (IO thread). Suspends until user responds.
     * Returns true if approved, false if declined or timed out (30s).
     */
    suspend fun requestConsent(toolName: String, description: String, details: String): Boolean {
        // Cancel any existing request (shouldn't happen, but safety)
        deferred?.complete(false)
        val newDeferred = CompletableDeferred<Boolean>()
        deferred = newDeferred
        pendingRequest = ConsentRequest(toolName, description, details)
        // Wait for user response (with 30s timeout)
        return try {
            kotlinx.coroutines.withTimeoutOrNull(30_000L) { newDeferred.await() } ?: false
        } finally {
            pendingRequest = null
            deferred = null
        }
    }

    /**
     * Called from the ConsentDialog (Main thread). Resolves the pending request.
     */
    fun resolve(approved: Boolean) {
        pendingRequest = null
        deferred?.complete(approved)
        deferred = null
    }

    /** Check if a consent dialog is currently showing. */
    fun isPending(): Boolean = pendingRequest != null
}
