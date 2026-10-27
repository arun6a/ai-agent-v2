package com.ai.agent.consent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * v4.0.2: ConsentManager — bridges ToolExecutor (Dispatchers.IO) ↔ UI dialog (Main).
 * v4.0.17: Fixed — uses MutableStateFlow so Compose observes changes.
 *
 * Flow:
 * 1. ToolExecutor detects a sensitive tool (sendSMS, deleteFile, etc.)
 * 2. Calls [requestConsent] with a description of the action
 * 3. ChatScreen observes [pendingRequestFlow] → shows ConsentDialog
 * 4. User taps Approve/Cancel → [resolve] is called with true/false
 * 5. ToolExecutor's [requestConsent] suspend returns the result
 *
 * The dialog is shown in the ChatScreen overlay. Only ONE consent at a time.
 */
object ConsentManager {

    data class ConsentRequest(
        val toolName: String,
        val description: String,
        val details: String,
    )

    // v4.0.17: Use MutableStateFlow instead of @Volatile var.
    // Compose can't observe plain vars — StateFlow triggers recomposition.
    private val _pendingRequest = MutableStateFlow<ConsentRequest?>(null)
    val pendingRequestFlow = _pendingRequest.asStateFlow()

    // For backward compat — ChatScreen reads this directly.
    val pendingRequest: ConsentRequest? get() = _pendingRequest.value

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
        _pendingRequest.value = ConsentRequest(toolName, description, details)
        // Wait for user response (with 30s timeout)
        return try {
            kotlinx.coroutines.withTimeoutOrNull(30_000L) { newDeferred.await() } ?: false
        } finally {
            _pendingRequest.value = null
            deferred = null
        }
    }

    /**
     * Called from the ConsentDialog (Main thread). Resolves the pending request.
     */
    fun resolve(approved: Boolean) {
        _pendingRequest.value = null
        deferred?.complete(approved)
        deferred = null
    }

    /** Check if a consent dialog is currently showing. */
    fun isPending(): Boolean = _pendingRequest.value != null
}
