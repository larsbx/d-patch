package com.dispatch.driver

import com.dispatch.driver.core.auth.DeviceKeys
import com.dispatch.driver.core.model.capability.CapabilityProvider
import com.dispatch.driver.core.model.capability.CapabilityState
import com.dispatch.driver.core.model.device.DeviceStatements
import com.dispatch.driver.core.network.DeviceRegistrationRequest
import com.dispatch.driver.core.network.Fetch
import com.dispatch.driver.core.network.RegisteredDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Base64

/**
 * Registers this installation's key after sign-in (Section 23.1, ADR-0012).
 *
 * The statement names the tenant and participant of the verified capability
 * document, so registration waits for one; it runs whenever one arrives, and
 * again when the app returns to the foreground, until the key is registered.
 */
class DeviceRegistration(
    private val keys: DeviceKeys,
    private val capabilities: CapabilityProvider,
    private val register: suspend (roleAssignmentId: String, DeviceRegistrationRequest) -> Fetch<RegisteredDevice>,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val mutex = Mutex()

    /** Registers whenever a verified role document arrives, for the life of the process. */
    fun start() {
        scope.launch { capabilities.state.collect { if (it is CapabilityState.Ready) ensure() } }
    }

    /** One more attempt, for when connectivity may have returned. */
    fun retry() {
        scope.launch { ensure() }
    }

    suspend fun ensure() = mutex.withLock {
        val document = (capabilities.state.value as? CapabilityState.Ready)?.document ?: return@withLock
        val held = keys.forSubject()?.takeIf { it.deviceId == null && it.subject == document.subject } ?: return@withLock

        val key = DeviceKeys.key(held)
        val statement = DeviceStatements.registration(document.tenantId, document.participantId, held.installationId, key.publicKey)
        val request = DeviceRegistrationRequest(held.installationId, base64.encodeToString(key.publicKey), base64.encodeToString(key.sign(statement)))

        when (val result = register(document.roleAssignmentId, request)) {
            is Fetch.Ok -> if (result.value.installationId == held.installationId) keys.registered(held, result.value.deviceId)
            // The installation is someone else's, or holds another key: start afresh.
            is Fetch.Problem -> if (result.httpStatus == 409) keys.discard(held)
            // Offline: the next document, or the next foreground, tries again.
            Fetch.Unreachable -> Unit
        }
    }

    private companion object {
        val base64: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    }
}
