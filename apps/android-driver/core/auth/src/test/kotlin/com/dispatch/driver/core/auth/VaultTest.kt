package com.dispatch.driver.core.auth

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import javax.crypto.KeyGenerator

/**
 * The session and the in-flight verifier are kept sealed. On a device the key
 * lives in the Android Keystore; here a software key stands in for it, since
 * Robolectric has no Keystore — the sealing itself is the same code.
 */
@RunWith(RobolectricTestRunner::class)
class VaultTest {

    private fun key() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val sealer = AesGcmSealer(key())
    private val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("vault-test", Context.MODE_PRIVATE)

    private val record = AuthRecord(
        session = SignedIn(accessToken = "s-1", subject = "prn_1", expiresAtEpochMs = 1_000),
        pending = PendingSignIn(verifier = "v".repeat(43), state = "state-1"),
    )

    @Test
    fun `sealing round-trips, with a fresh nonce each time`() {
        val plain = "secret".toByteArray()
        val once = sealer.seal(plain)
        val twice = sealer.seal(plain)

        assertArrayEquals(plain, sealer.open(once))
        assertFalse(once.contentEquals(twice))
    }

    @Test
    fun `a tampered or foreign ciphertext does not open`() {
        val sealed = sealer.seal("secret".toByteArray())
        sealed[sealed.size - 1] = (sealed.last() + 1).toByte()

        assertThrows(Exception::class.java) { sealer.open(sealed) }
        assertThrows(Exception::class.java) { AesGcmSealer(key()).open(sealer.seal("secret".toByteArray())) }
    }

    @Test
    fun `the record survives a restart, and nothing is stored in the clear`() {
        SealedPrefsVault(prefs, sealer).write(record)

        assertEquals(record, SealedPrefsVault(prefs, sealer).read())
        val stored = prefs.all.values.joinToString()
        for (secret in listOf("s-1", "prn_1", record.pending!!.verifier)) assertFalse(secret in stored)
    }

    @Test
    fun `a write the disk refuses is an error, not a silent loss`() {
        val refusing = mockk<SharedPreferences> {
            every { edit() } returns mockk {
                every { putString(any(), any()) } returns this
                every { commit() } returns false
            }
        }

        assertThrows(IOException::class.java) { SealedPrefsVault(refusing, sealer).write(record) }
    }

    @Test
    fun `a record that no longer opens is signed out, not a crash`() {
        SealedPrefsVault(prefs, sealer).write(record)

        // The Keystore key is gone (a restore, a cleared lock screen).
        assertEquals(AuthRecord(), SealedPrefsVault(prefs, AesGcmSealer(key())).read())
        assertEquals(AuthRecord(), SealedPrefsVault(prefs.also { it.edit().putString(SealedPrefsVault.KEY, "%%").commit() }, sealer).read())
    }
}
