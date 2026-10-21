package com.dispatch.driver.core.model.device

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.io.ByteArrayOutputStream
import java.security.SecureRandom

/**
 * `CoopEventCanonicalV1`, the platform's canonical form (ADR-0009): RFC 8949
 * deterministic CBOR — shortest heads, definite lengths, map keys sorted by
 * their encoded bytes — over integers, text, byte strings, booleans, null,
 * lists and maps with text keys. Anything else, floats included, has no
 * canonical form and is refused.
 */
object Canonical {

    fun encode(value: Any?): ByteArray = ByteArrayOutputStream().also { write(it, value) }.toByteArray()

    private fun write(out: ByteArrayOutputStream, value: Any?) {
        when (value) {
            null -> out.write(0xf6)
            true -> out.write(0xf5)
            false -> out.write(0xf4)
            is Long -> integer(out, value)
            is Int -> integer(out, value.toLong())
            is String -> value.encodeToByteArray().let { head(out, 3, it.size.toLong()); out.write(it) }
            is ByteArray -> head(out, 2, value.size.toLong()).also { out.write(value) }
            is List<*> -> head(out, 4, value.size.toLong()).also { value.forEach { write(out, it) } }
            is Map<*, *> -> {
                val pairs = value.map { (key, item) ->
                    require(key is String) { "Canonical map keys are text" }
                    encode(key) to encode(item)
                }.sortedWith { a, b -> compare(a.first, b.first) }
                head(out, 5, pairs.size.toLong())
                pairs.forEach { (key, item) -> out.write(key); out.write(item) }
            }
            else -> throw IllegalArgumentException("No canonical form for ${value::class.simpleName}")
        }
    }

    private fun integer(out: ByteArrayOutputStream, value: Long) =
        if (value >= 0) head(out, 0, value) else head(out, 1, -1 - value)

    /** [argument] is never negative: `-1 - Long.MIN_VALUE` is `Long.MAX_VALUE`. */
    private fun head(out: ByteArrayOutputStream, major: Int, argument: Long) {
        val type = major shl 5
        val (info, size) = when {
            argument < 24 -> argument.toInt() to 0
            argument <= 0xff -> 24 to 1
            argument <= 0xffff -> 25 to 2
            argument <= 0xffff_ffffL -> 26 to 4
            else -> 27 to 8
        }
        out.write(type or info)
        for (shift in (size - 1) downTo 0) out.write((argument ushr (8 * shift)).toInt() and 0xff)
    }

    /** Bytewise, unsigned; a prefix sorts first. */
    private fun compare(a: ByteArray, b: ByteArray): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val order = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
            if (order != 0) return order
        }
        return a.size - b.size
    }
}

/**
 * What a device signs (biz-kit `Dispatch.Accounts.DeviceKeys`). Each is a
 * canonical map with a `schema` and no `type`, so neither can pass for the
 * other, or for a ledger command.
 */
object DeviceStatements {
    const val REGISTRATION = "DispatchDeviceRegistrationV1"
    const val EVENT = "DispatchStatusEventV1"

    /** Proves this installation holds [publicKey], for the participant the capability document names. */
    fun registration(tenantId: String, participantId: String, installationId: String, publicKey: ByteArray): ByteArray =
        Canonical.encode(
            mapOf(
                "schema" to REGISTRATION,
                "tenant_id" to tenantId,
                "participant_id" to participantId,
                "installation_id" to installationId,
                "public_key" to publicKey,
            ),
        )

    /** [fields]: the body exactly as it will be sent, without `device_signature`. */
    fun event(fields: JsonObject): ByteArray = Canonical.encode(term(fields) as Map<*, *> + ("schema" to EVENT))

    private fun term(json: JsonElement): Any? = when (json) {
        JsonNull -> null
        is JsonObject -> json.mapValues { (_, value) -> term(value) }
        is JsonArray -> json.map(::term)
        is JsonPrimitive -> when {
            json.isString -> json.content
            else -> json.longOrNull ?: json.booleanOrNull ?: throw IllegalArgumentException("No canonical form for ${json.content}")
        }
    }
}

/** The registered device that signs declarations: its server ID and its key. */
class SigningDevice(val id: String, private val key: DeviceKey) {
    fun sign(message: ByteArray): ByteArray = key.sign(message)
}

/**
 * Where declarations find the device to sign with. Null until the
 * installation's key is registered; declarations made before then are queued
 * unsigned, and the server records them as such.
 */
fun interface DeviceSigner {
    fun current(): SigningDevice?
}

/**
 * The installation's Ed25519 key (ADR-0012). BouncyCastle, as for capability
 * documents: `java.security` has no Ed25519 below API 33. The seed is kept
 * sealed by `core:auth`; this class only uses it.
 */
class DeviceKey private constructor(private val key: Ed25519PrivateKeyParameters) {

    val publicKey: ByteArray get() = key.generatePublicKey().encoded

    val seed: ByteArray get() = key.encoded

    fun sign(message: ByteArray): ByteArray = Ed25519Signer().run {
        init(true, key)
        update(message, 0, message.size)
        generateSignature()
    }

    companion object {
        fun fromSeed(seed: ByteArray): DeviceKey =
            DeviceKey(Ed25519PrivateKeyParameters(seed.also { require(it.size == 32) { "Ed25519 seeds are 32 bytes" } }, 0))

        fun generate(random: SecureRandom): DeviceKey = fromSeed(ByteArray(32).also(random::nextBytes))
    }
}
