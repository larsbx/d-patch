package com.dispatch.driver.core.model.capability

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.io.File
import java.security.SecureRandom
import java.util.Base64

/**
 * Signs documents in the server's format so tests can produce every variation
 * the verifier must reject. A second implementation of the format is only
 * trustworthy next to a fixture from the first, which is why
 * `server-signed-capability-document.json` — signed by biz-kit with the
 * published development seed — exists too.
 */
@OptIn(ExperimentalSerializationApi::class)
object TestSigner {

    private val repositoryRoot: File =
        generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "infra/capability-signing").isDirectory }

    /** The development public key the debug build pins (biz-kit's published seed). */
    val developmentPublic: ByteArray =
        CapabilityDocumentVerifier.publicKey(File(repositoryRoot, "infra/capability-signing/development.pub").readText())

    class KeyPair(val private: Ed25519PrivateKeyParameters) {
        val public: ByteArray = private.generatePublicKey().encoded
    }

    /** A key the tests hold the private half of. */
    val testKey: KeyPair = generate()

    fun generate(): KeyPair = KeyPair(
        Ed25519KeyPairGenerator().run {
            init(Ed25519KeyGenerationParameters(SecureRandom()))
            generateKeyPair().private as Ed25519PrivateKeyParameters
        },
    )

    fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResource(name)) { "missing fixture $name" }.readText()

    fun sign(document: CapabilityDocument, key: KeyPair = testKey, keyId: String = CapabilityDocumentVerifier.keyId(key.public)): SignedDocument =
        signBytes(Cbor.encodeToByteArray(CapabilityDocument.serializer(), document), key, keyId)

    fun signBytes(bytes: ByteArray, key: KeyPair = testKey, keyId: String = CapabilityDocumentVerifier.keyId(key.public)): SignedDocument {
        val signature = Ed25519Signer().run {
            init(true, key.private)
            update(bytes, 0, bytes.size)
            generateSignature()
        }
        return SignedDocument(encode(bytes), encode(signature), keyId)
    }

    fun document(
        subject: String = "subject-1",
        roleAssignmentId: String = "assignment-1",
        features: Set<String> = setOf("home", "status"),
        schema: String = CapabilityDocument.SCHEMA,
        schemaVersion: Int = 1,
        exp: Long = 2_000,
    ) = CapabilityDocument(
        schema = schema,
        schemaVersion = schemaVersion,
        subject = subject,
        tenantId = "t",
        participantId = "p",
        roleAssignmentId = roleAssignmentId,
        role = RoleSummary("COURIER", "Courier", 0),
        capabilities = listOf(Capability("status.declare.self", listOf("self_only"))),
        features = features,
        statusOptions = listOf(StatusOption("AT_PICKUP", "At pickup", false), StatusOption("DELAYED", "Delayed", true)),
        issuedAtEpochSeconds = 1_000,
        expiresAtEpochSeconds = exp,
    )

    private fun encode(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
