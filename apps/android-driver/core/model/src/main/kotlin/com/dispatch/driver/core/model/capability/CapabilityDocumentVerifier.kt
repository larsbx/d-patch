package com.dispatch.driver.core.model.capability

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Verifies a capability document exactly as ADR-0009 specifies.
 *
 * Order matters and is fixed: header, signature, then claims. Nothing in the
 * payload is parsed for meaning until the signature over it has been checked,
 * so a forged document cannot even choose which error it produces.
 *
 * Pure JVM on purpose — `java.security` has ES256 on every supported release
 * (`minSdk 29`) — so the whole check runs in a plain unit test.
 */
class CapabilityDocumentVerifier(private val pinned: PublicKey) {

    private val pinnedKid: String = kid(pinned)

    /** Who the session is: the document must name exactly this. */
    data class Binding(val subject: String, val roleAssignmentId: String?)

    sealed interface Result {
        data class Verified(val document: CapabilityDocument, val stale: Boolean) : Result
        data class Rejected(val reason: Reason) : Result
    }

    enum class Reason { MALFORMED, WRONG_ALGORITHM, WRONG_TYPE, UNKNOWN_KEY, BAD_SIGNATURE, UNSUPPORTED_SCHEMA, WRONG_SUBJECT, WRONG_ASSIGNMENT }

    /**
     * Verifies [token] for [binding] at [nowEpochSeconds].
     *
     * A null [Binding.roleAssignmentId] accepts the assignment the server
     * resolved — the case of a principal holding exactly one — and the caller
     * then adopts the document's assignment as the session's.
     *
     * An expired document is returned as [Result.Verified] with `stale = true`;
     * ADR-0009 keeps it usable while the device cannot refresh.
     */
    fun verify(token: String, binding: Binding, nowEpochSeconds: Long): Result {
        val segments = token.split('.')
        if (segments.size != 3) return reject(Reason.MALFORMED)
        val (headerSegment, payloadSegment, signatureSegment) = segments

        val header = decodeObject(headerSegment) ?: return reject(Reason.MALFORMED)
        if (header.string("alg") != "ES256") return reject(Reason.WRONG_ALGORITHM)
        if (header.string("typ") != "capability+jws") return reject(Reason.WRONG_TYPE)
        if (header.string("kid") != pinnedKid) return reject(Reason.UNKNOWN_KEY)

        val signature = decode(signatureSegment)?.let(::rawToDer) ?: return reject(Reason.BAD_SIGNATURE)
        val signedInput = "$headerSegment.$payloadSegment".toByteArray(Charsets.US_ASCII)
        if (!verifies(signedInput, signature)) return reject(Reason.BAD_SIGNATURE)

        val document = decode(payloadSegment)
            ?.let { runCatching { json.decodeFromString<CapabilityDocument>(it.decodeToString()) }.getOrNull() }
            ?: return reject(Reason.MALFORMED)

        return when {
            document.schemaVersion != CapabilityDocument.SCHEMA_VERSION -> reject(Reason.UNSUPPORTED_SCHEMA)
            document.subject != binding.subject -> reject(Reason.WRONG_SUBJECT)
            binding.roleAssignmentId != null && document.roleAssignmentId != binding.roleAssignmentId ->
                reject(Reason.WRONG_ASSIGNMENT)
            else -> Result.Verified(document, stale = document.isStale(nowEpochSeconds))
        }
    }

    private fun verifies(input: ByteArray, der: ByteArray): Boolean =
        runCatching {
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(pinned)
                update(input)
                verify(der)
            }
        }.getOrDefault(false)

    private fun reject(reason: Reason) = Result.Rejected(reason)

    private fun decodeObject(segment: String): JsonObject? =
        decode(segment)?.let { runCatching { json.parseToJsonElement(it.decodeToString()) as? JsonObject }.getOrNull() }

    private fun JsonObject.string(name: String): String? =
        runCatching { this[name]?.jsonPrimitive?.content }.getOrNull()

    companion object {
        private const val COORDINATE_BYTES = 32

        private val json = Json { ignoreUnknownKeys = true }

        /** Parses a PEM `PUBLIC KEY` (SubjectPublicKeyInfo) into an EC key. */
        fun publicKeyFromPem(pem: String): PublicKey {
            val body = pem.lineSequence().filterNot { it.startsWith("-----") }.joinToString("")
            return KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(body)))
        }

        /**
         * ADR-0009's key identifier: the first 16 base64url characters of the
         * SHA-256 of the DER SubjectPublicKeyInfo. The server derives the same.
         */
        fun kid(key: PublicKey): String =
            Base64.getUrlEncoder().withoutPadding()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest(key.encoded))
                .take(16)

        private fun decode(segment: String): ByteArray? =
            runCatching { Base64.getUrlDecoder().decode(segment) }.getOrNull()

        /** RFC 7518 §3.4 carries R || S; `java.security` wants DER. */
        private fun rawToDer(raw: ByteArray): ByteArray? {
            if (raw.size != 2 * COORDINATE_BYTES) return null
            fun integer(bytes: ByteArray): ByteArray {
                val value = BigInteger(1, bytes).toByteArray()
                return byteArrayOf(0x02, value.size.toByte()) + value
            }
            val body = integer(raw.copyOfRange(0, COORDINATE_BYTES)) + integer(raw.copyOfRange(COORDINATE_BYTES, raw.size))
            return byteArrayOf(0x30, body.size.toByte()) + body
        }
    }
}
