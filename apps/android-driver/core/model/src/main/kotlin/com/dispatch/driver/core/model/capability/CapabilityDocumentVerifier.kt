package com.dispatch.driver.core.model.capability

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.util.Base64

/**
 * Verifies a capability document as ADR-0009 specifies: Ed25519 over the
 * exact `CoopEventCanonicalV1` bytes served, against a key pinned at build
 * time — the platform's one signature scheme, the same the ledger uses.
 *
 * Order matters and is fixed: key, signature, then claims. Nothing in the
 * document is decoded until the signature over its bytes has been checked,
 * so a forged document cannot even choose which error it produces.
 *
 * Pure JVM (BouncyCastle, because `java.security` has no Ed25519 below API 33
 * and `minSdk` is 29), so the whole check runs in a plain unit test.
 */
@OptIn(ExperimentalSerializationApi::class)
class CapabilityDocumentVerifier(pinnedPublicKey: ByteArray) {

    private val pinned = Ed25519PublicKeyParameters(pinnedPublicKey.also { require(it.size == 32) { "Ed25519 keys are 32 bytes" } }, 0)
    private val pinnedKeyId = keyId(pinnedPublicKey)

    /** Who the session is: the document must name exactly this. */
    data class Binding(val subject: String, val roleAssignmentId: String?)

    sealed interface Result {
        data class Verified(val document: CapabilityDocument, val stale: Boolean) : Result
        data class Rejected(val reason: Reason) : Result
    }

    enum class Reason { MALFORMED, UNKNOWN_KEY, BAD_SIGNATURE, WRONG_SCHEMA, UNSUPPORTED_SCHEMA, WRONG_SUBJECT, WRONG_ASSIGNMENT }

    /**
     * Verifies [signed] for [binding] at [nowEpochSeconds].
     *
     * A null [Binding.roleAssignmentId] accepts the assignment the server
     * resolved — the case of a principal holding exactly one — and the caller
     * then adopts the document's assignment as the session's.
     *
     * An expired document is returned as [Result.Verified] with `stale = true`;
     * ADR-0009 keeps it usable while the device cannot refresh.
     */
    fun verify(signed: SignedDocument, binding: Binding, nowEpochSeconds: Long): Result {
        if (signed.keyId != pinnedKeyId) return reject(Reason.UNKNOWN_KEY)
        val bytes = decode(signed.document) ?: return reject(Reason.MALFORMED)
        val signature = decode(signed.signature)?.takeIf { it.size == 64 } ?: return reject(Reason.BAD_SIGNATURE)

        val valid = Ed25519Signer().run {
            init(false, pinned)
            update(bytes, 0, bytes.size)
            verifySignature(signature)
        }
        if (!valid) return reject(Reason.BAD_SIGNATURE)

        val document = runCatching { cbor.decodeFromByteArray(CapabilityDocument.serializer(), bytes) }.getOrNull()
            ?: return reject(Reason.MALFORMED)

        return when {
            document.schema != CapabilityDocument.SCHEMA -> reject(Reason.WRONG_SCHEMA)
            document.schemaVersion != CapabilityDocument.SCHEMA_VERSION -> reject(Reason.UNSUPPORTED_SCHEMA)
            document.subject != binding.subject -> reject(Reason.WRONG_SUBJECT)
            binding.roleAssignmentId != null && document.roleAssignmentId != binding.roleAssignmentId ->
                reject(Reason.WRONG_ASSIGNMENT)
            else -> Result.Verified(document, stale = document.isStale(nowEpochSeconds))
        }
    }

    private fun reject(reason: Reason) = Result.Rejected(reason)

    companion object {
        private val cbor = Cbor { ignoreUnknownKeys = true }

        /** Decodes a base64url public key, as pinned in `BuildConfig`. */
        fun publicKey(base64Url: String): ByteArray = Base64.getUrlDecoder().decode(base64Url.trim())

        /** The ledger's key identifier: `k-` and the key's first four bytes in hex. */
        fun keyId(publicKey: ByteArray): String = "k-" + publicKey.take(4).joinToString("") { "%02x".format(it) }

        private fun decode(segment: String): ByteArray? =
            runCatching { Base64.getUrlDecoder().decode(segment) }.getOrNull()
    }
}
