package com.dispatch.driver

import com.dispatch.driver.core.auth.AuthSession
import com.dispatch.driver.core.auth.SignInOutcome
import com.dispatch.driver.core.model.capability.CapabilityProvider
import com.dispatch.driver.core.model.capability.SyncRequester
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Joins sign-in to what waits on it: a new session reloads the role's
 * capabilities and sends anything queued while signed out (Section 25.2).
 */
class SignInFlow(
    private val session: AuthSession,
    private val capabilities: CapabilityProvider,
    private val sync: SyncRequester,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val _message = MutableStateFlow<String?>(null)

    /** Why the last sign-in did not finish, if it did not. */
    val message: StateFlow<String?> = _message.asStateFlow()

    /** The URL to open in the system browser, or null, with [message] set, when sign-in cannot start. */
    fun start(): String? =
        session.begin().also { if (it == null) _message.value = "Sign-in could not start. Free some storage and try again." }

    /**
     * Finishes the sign-in in the process's scope, not the activity's: the code
     * is spent once the exchange starts, so a rotation must not cancel it.
     */
    fun accept(redirect: String) {
        scope.launch { finish(redirect) }
    }

    suspend fun finish(redirect: String) {
        when (val outcome = session.complete(redirect)) {
            SignInOutcome.Ignored -> Unit
            SignInOutcome.Denied -> _message.value = "Sign-in was cancelled."
            is SignInOutcome.Failed ->
                _message.value = if (outcome.reachable) "Sign-in did not complete. Try again." else "Connect to the internet to sign in."
            SignInOutcome.SignedIn -> {
                _message.value = null
                capabilities.refresh()
                sync.requestSyncNow()
            }
        }
    }
}
