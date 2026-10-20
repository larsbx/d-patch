package com.dispatch.driver.core.model.capability

import kotlinx.coroutines.flow.StateFlow

/**
 * Where a feature module learns what the server offers this session.
 *
 * Feature modules depend on this and nothing more, so none of them can reach
 * past the verified document to a role key, a cached row, or the network.
 */
interface CapabilityProvider {
    val state: StateFlow<CapabilityState>

    /** Fetches a fresh document, falling back to the stored one when offline. */
    suspend fun refresh()
}

sealed interface CapabilityState {
    data object Loading : CapabilityState

    /** No authenticated session: nothing is offered until sign-in. */
    data object SignedOut : CapabilityState

    /** No verified document is available; [message] says why, for the participant. */
    data class Unavailable(val message: String) : CapabilityState

    /** A verified document. [stale] when past expiry and not yet refreshed (ADR-0009). */
    data class Ready(val document: CapabilityDocument, val stale: Boolean) : CapabilityState
}

/**
 * Asks for queued events to be sent. Section 25.2 schedules `SyncWorker` on
 * every submission; the port keeps WorkManager out of the feature module.
 */
fun interface SyncRequester {
    fun requestSync()
}
