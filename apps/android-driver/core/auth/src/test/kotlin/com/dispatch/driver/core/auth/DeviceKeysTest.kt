package com.dispatch.driver.core.auth

import com.dispatch.driver.core.model.session.SessionProvider
import kotlinx.coroutines.flow.MutableStateFlow
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Base64

/** The installation's key (ADR-0012): one per person signed in here, kept sealed, used once registered. */
class DeviceKeysTest {

    private var subject: String? = "prn_1"
    private val session = object : SessionProvider {
        override suspend fun accessToken() = "t"
        override fun subject() = subject
        override fun selectedRoleAssignmentId() = null
        override val signInRequired = MutableStateFlow(false)
        override fun rejected(token: String?) = Unit
    }

    private class MemoryStore : Store<DeviceKeyRecord?> {
        var refusing = false
        var value: DeviceKeyRecord? = null
        override fun read() = value
        override fun write(record: DeviceKeyRecord?) {
            if (refusing) throw IOException("full")
            value = record
        }
    }

    private val store = MemoryStore()
    private fun keys() = DeviceKeys(store, session)

    @Test
    fun `signed out there is no key, and nothing signs`() {
        subject = null

        assertNull(keys().forSubject())
        assertNull(keys().current())
    }

    @Test
    fun `a key is made once per person, and survives a restart`() {
        val first = checkNotNull(keys().forSubject())

        assertEquals(first, keys().forSubject())
        assertEquals("prn_1", first.subject)
        assertEquals(32, Base64.getUrlDecoder().decode(first.seed).size)
    }

    @Test
    fun `nothing is signed until the key is registered, then the device signs as itself`() {
        val keys = keys()
        val held = checkNotNull(keys.forSubject())
        assertNull(keys.current())

        keys.registered(held, "device-1")

        val device = checkNotNull(keys().current())
        assertEquals("device-1", device.id)
        val message = "statement".toByteArray()
        val publicKey = DeviceKeys.key(held).publicKey
        assertTrue(Ed25519Signer().run {
            init(false, Ed25519PublicKeyParameters(publicKey, 0))
            update(message, 0, message.size)
            verifySignature(device.sign(message))
        })
    }

    @Test
    fun `someone else signing in gets their own installation and key, and nothing signs for them yet`() {
        val keys = keys()
        val mine = checkNotNull(keys.forSubject())
        keys.registered(mine, "device-1")

        subject = "prn_2"
        assertNull("not prn_2's device", keys.current())
        val theirs = checkNotNull(keys.forSubject())

        assertNotEquals(mine.installationId, theirs.installationId)
        assertNotEquals(mine.seed, theirs.seed)
        assertNull(theirs.deviceId)
    }

    @Test
    fun `a registration for a key no longer held is not recorded`() {
        val keys = keys()
        val old = checkNotNull(keys.forSubject())
        subject = "prn_2"
        keys.forSubject()

        keys.registered(old, "device-1")

        assertNull(store.value?.deviceId)
    }

    @Test
    fun `a taken installation is given up, and the next one is fresh`() {
        val keys = keys()
        val taken = checkNotNull(keys.forSubject())

        keys.discard(taken)

        assertNotEquals(taken.installationId, checkNotNull(keys.forSubject()).installationId)
    }

    @Test
    fun `a key that cannot be kept is not used`() {
        store.refusing = true

        assertNull(keys().forSubject())
        assertNull(store.value)
    }

    @Test
    fun `a registration that cannot be kept leaves the key unregistered, to try again`() {
        val keys = keys()
        val held = checkNotNull(keys.forSubject())
        store.refusing = true

        keys.registered(held, "device-1")

        assertNull(keys.current())
        assertNotNull(keys.forSubject())
    }
}
