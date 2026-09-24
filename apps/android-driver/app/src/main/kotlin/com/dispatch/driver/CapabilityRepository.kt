package com.dispatch.driver

import com.dispatch.driver.core.database.CapabilityStore
import com.dispatch.driver.core.model.capability.CapabilityDocumentVerifier.Binding
import com.dispatch.driver.core.model.capability.CapabilityDocumentVerifier.Result
import com.dispatch.driver.core.model.capability.CapabilityProvider
import com.dispatch.driver.core.model.capability.CapabilityState
import com.dispatch.driver.core.model.session.SessionProvider
import com.dispatch.driver.core.network.Fetch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Fetches, verifies, and keeps the capability document (ADR-0009).
 *
 * Online, the server's answer is authoritative, including a refusal: a revoked
 * assignment must stop offering its surfaces, so a `403` clears the stored
 * document rather than falling back to it. Offline, the stored document is
 * re-verified for this session and used, stale if past expiry.
 */
class CapabilityRepository(
    private val session: SessionProvider,
    private val store: CapabilityStore,
    private val fetch: suspend (roleAssignmentId: String?) -> Fetch<String>,
) : CapabilityProvider {

    private val _state = MutableStateFlow<CapabilityState>(CapabilityState.Loading)
    override val state: StateFlow<CapabilityState> = _state.asStateFlow()

    override suspend fun refresh() {
        val subject = session.subject() ?: return run { _state.value = CapabilityState.SignedOut }
        val binding = Binding(subject, session.selectedRoleAssignmentId())

        _state.value = when (val fetched = fetch(binding.roleAssignmentId)) {
            is Fetch.Ok -> when (val verified = store.accept(fetched.value, binding)) {
                is Result.Verified -> CapabilityState.Ready(verified.document, verified.stale)
                is Result.Rejected -> CapabilityState.Unavailable("Your role's permissions could not be verified. Contact support.")
            }
            is Fetch.Problem -> refused(fetched, binding)
            Fetch.Unreachable -> stored(binding) ?: CapabilityState.Unavailable("Connect to the internet to load your role.")
        }
    }

    private suspend fun refused(problem: Fetch.Problem, binding: Binding): CapabilityState = when {
        problem.httpStatus == 401 -> CapabilityState.SignedOut
        problem.httpStatus == 403 -> {
            store.clear()
            CapabilityState.Unavailable(
                if (problem.code == "ROLE_ASSIGNMENT_REQUIRED") "Choose which role you are working under." else "You have no active role. Contact your dispatcher.",
            )
        }
        // A server fault is not a verdict on the session; keep what we had.
        else -> stored(binding) ?: CapabilityState.Unavailable("Your role could not be loaded. Try again shortly.")
    }

    private suspend fun stored(binding: Binding): CapabilityState? =
        store.current(binding)?.let { CapabilityState.Ready(it.document, it.stale) }
}
