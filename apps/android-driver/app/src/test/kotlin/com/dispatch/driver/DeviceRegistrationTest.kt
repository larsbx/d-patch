package com.dispatch.driver

import com.dispatch.driver.core.auth.DeviceKeyRecord
import com.dispatch.driver.core.auth.DeviceKeys
import com.dispatch.driver.core.auth.Store
import com.dispatch.driver.core.model.capability.CapabilityDocument
import com.dispatch.driver.core.model.capability.CapabilityProvider
import com.dispatch.driver.core.model.capability.CapabilityState
import com.dispatch.driver.core.model.capability.RoleSummary
import com.dispatch.driver.core.model.device.DeviceStatements
import com.dispatch.driver.core.model.session.SessionProvider
import com.dispatch.driver.core.network.DeviceRegistrationRequest
import com.dispatch.driver.core.network.Fetch
import com.dispatch.driver.core.network.RegisteredDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** The installation registers its key once there is a verified role to register it under (ADR-0012). */
class DeviceRegistrationTest {

    private val document = CapabilityDocument(
        schema = CapabilityDocument.SCHEMA,
        schemaVersion = CapabilityDocument.SCHEMA_VERSION,
        subject = "prn_1",
        tenantId = "tenant-1",
        participantId = "participant-1",
        roleAssignmentId = "ra-1",
        role = RoleSummary("DRIVER", "Driver", 1),
        capabilities = emptyList(),
        features = emptySet(),
        statusOptions = emptyList(),
        issuedAtEpochSeconds = 0,
        expiresAtEpochSeconds = Long.MAX_VALUE,
    )

    private val session = object : SessionProvider {
        override suspend fun accessToken() = "t"
        override fun subject() = "prn_1"
        override fun selectedRoleAssignmentId() = null
        override val signInRequired = MutableStateFlow(false)
        override fun rejected(token: String?) = Unit
    }

    private val store = object : Store<DeviceKeyRecord?> {
        var value: DeviceKeyRecord? = null
        override fun read() = value
        override fun write(record: DeviceKeyRecord?) {
            value = record
        }
    }
    private val keys = DeviceKeys(store, session)

    private val capabilities = object : CapabilityProvider {
        override val state = MutableStateFlow<CapabilityState>(CapabilityState.Loading)
        override suspend fun refresh() = Unit
    }

    private val requests = mutableListOf<Pair<String, DeviceRegistrationRequest>>()
    private var answer: (DeviceRegistrationRequest) -> Fetch<RegisteredDevice> = { Fetch.Ok(RegisteredDevice("device-1", it.installationId)) }

    private fun TestScope.registration() =
        DeviceRegistration(keys, capabilities, { ra, request -> requests += ra to request; answer(request) }, backgroundScope)

    private fun b64(value: String) = Base64.getUrlDecoder().decode(value)

    @Test
    fun `a verified role registers the key, with a proof biz-kit checks, and the device then signs`() = runTest(UnconfinedTestDispatcher()) {
        registration().start()
        assertTrue("nothing to register under yet", requests.isEmpty())

        capabilities.state.value = CapabilityState.Ready(document, stale = false)

        val (roleAssignmentId, request) = requests.single()
        assertEquals("ra-1", roleAssignmentId)
        val statement = DeviceStatements.registration("tenant-1", "participant-1", request.installationId, b64(request.publicKey))
        assertTrue(Ed25519Signer().run {
            init(false, Ed25519PublicKeyParameters(b64(request.publicKey), 0))
            update(statement, 0, statement.size)
            verifySignature(b64(request.proof))
        })
        assertEquals("device-1", keys.current()?.id)
    }

    @Test
    fun `a registered key is not registered again`() = runTest {
        capabilities.state.value = CapabilityState.Ready(document, stale = false)
        val registration = registration()

        registration.ensure()
        registration.ensure()

        assertEquals(1, requests.size)
    }

    @Test
    fun `offline, nothing is recorded, and the next attempt registers`() = runTest {
        capabilities.state.value = CapabilityState.Ready(document, stale = false)
        val registration = registration()
        answer = { Fetch.Unreachable }

        registration.ensure()
        assertNull(keys.current())

        answer = { Fetch.Ok(RegisteredDevice("device-1", it.installationId)) }
        registration.ensure()
        assertEquals("device-1", keys.current()?.id)
        assertEquals("the same key", 1, requests.map { it.second.publicKey }.distinct().size)
    }

    @Test
    fun `a taken installation is given up for a fresh one`() = runTest {
        capabilities.state.value = CapabilityState.Ready(document, stale = false)
        val registration = registration()
        answer = { Fetch.Problem(409, "INSTALLATION_CONFLICT", null) }

        registration.ensure()
        answer = { Fetch.Ok(RegisteredDevice("device-2", it.installationId)) }
        registration.ensure()

        val (first, second) = requests.map { it.second }
        assertNotEquals(first.installationId, second.installationId)
        assertNotEquals(first.publicKey, second.publicKey)
        assertEquals("device-2", keys.current()?.id)
    }

    @Test
    fun `an answer for another installation is not recorded`() = runTest {
        capabilities.state.value = CapabilityState.Ready(document, stale = false)
        answer = { Fetch.Ok(RegisteredDevice("device-1", "someone-else")) }

        registration().ensure()

        assertNull(keys.current())
    }
}
