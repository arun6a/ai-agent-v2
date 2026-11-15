package com.ai.agent.proactive

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * v4.0.87: ProactiveBus — a lightweight singleton bridge that carries a
 * proactive suggestion from the background [ProactiveAlarmReceiver] to the
 * foreground [com.ai.agent.ui.screens.ChatViewModel].
 *
 * Why: the tick worker runs in a BroadcastReceiver (no ViewModel access).
 * The notification path stays as-is, but when the app is open we ALSO want
 * an inline banner inside the chat. The ViewModel collects this flow and
 * surfaces it as [ChatUiState.proactiveSuggestion].
 *
 * Only the latest suggestion is retained (StateFlow). The UI dismisses it
 * via [clear]; the ViewModel calls [clear] when the user taps "Do it" or
 * "Dismiss".
 */
object ProactiveBus {

    private val _suggestions = MutableStateFlow<ProactiveResult.Suggest?>(null)
    val suggestions: StateFlow<ProactiveResult.Suggest?> = _suggestions.asStateFlow()

    /** Publish a suggestion so the open chat can show it as an inline banner. */
    fun publish(suggestion: ProactiveResult.Suggest) {
        _suggestions.value = suggestion
    }

    /** Clear the current suggestion (called after the user acts on / dismisses it). */
    fun clear() {
        _suggestions.value = null
    }
}
