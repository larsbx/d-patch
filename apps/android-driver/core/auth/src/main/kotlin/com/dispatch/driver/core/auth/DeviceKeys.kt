package com.dispatch.driver.core.auth

import android.content.SharedPreferences
import com.dispatch.driver.core.model.UuidV7
import com.dispatch.driver.core.model.device.DeviceKey
import com.dispatch.driver.core.model.device.DeviceSigner
import com.dispatch.driver.core.model.device.SigningDevice
import com.dispatch.driver.core.model.session.SessionProvider
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.nullable
import java.io.IOException
import java.security.SecureRandom
import java.util.Base64

/** An installation's key for one person, and its server ID once registered. */
@Serializable
data class DeviceKeyRecord(
    val subject: String,
    val installationId: String,
    /** Base64url Ed25519 seed. Only ever stored sealed. */
    val seed: String,
    val deviceId: String? = null,
)

/**
 * This installation's device key (ADR-0012), sealed in [store] under a
 * Keystore key like the session.
 *
 * One key per person signed in here: when someone else signs in, they get a
 * new installation and key rather than the previous person's. Nothing is
 * signed until the key is registered, and only for the person it belongs to.
 */
class DeviceKeys(
    private val store: Store<DeviceKeyRecord?>,
    private val session: SessionProvider,
    private val random: SecureRandom = SecureRandom(),
) : DeviceSigner {

    @Volatile private var record: DeviceKeyRecord? = store.read()

    /**
     * The signed-in person's key, made on first use. Null when signed out, or
     * when a new key cannot be kept: one that would be lost on restart must
     * never be registered.
     */
    @Synchronized
    fun forSubject(): DeviceKeyRecord? {
        val subject = session.subject() ?: return null
        record?.takeIf { it.subject == subject }?.let { return it }
        val fresh = DeviceKeyRecord(subject, UuidV7.generate().toString(), base64.encodeToString(DeviceKey.generate(random).seed))
        return fresh.takeIf { keep(it) }
    }

    /** Records the server's ID for [held], if it is still the key held. */
    @Synchronized
    fun registered(held: DeviceKeyRecord, deviceId: String) {
        if (record == held) keep(held.copy(deviceId = deviceId))
    }

    /** Gives up [held], whose installation the server says is taken; the next key is fresh. */
    @Synchronized
    fun discard(held: DeviceKeyRecord) {
        if (record == held) keep(null)
    }

    override fun current(): SigningDevice? {
        val held = record ?: return null
        val deviceId = held.deviceId ?: return null
        if (held.subject != session.subject()) return null
        return SigningDevice(deviceId, key(held))
    }

    private fun keep(next: DeviceKeyRecord?): Boolean =
        try {
            store.write(next)
            record = next
            true
        } catch (_: IOException) {
            false
        }

    companion object {
        private val base64 = Base64.getUrlEncoder().withoutPadding()

        /** The record, sealed under `device` in [prefs] beside the session. */
        fun store(prefs: SharedPreferences, sealer: Sealer): Store<DeviceKeyRecord?> =
            SealedPrefs(prefs, "device", DeviceKeyRecord.serializer().nullable, null, sealer)

        fun key(record: DeviceKeyRecord): DeviceKey = DeviceKey.fromSeed(Base64.getUrlDecoder().decode(record.seed))
    }
}
