package com.dispatch.driver.core.model.capability

import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/**
 * Signs documents the way `Dispatch.Access.CapabilitySigner` does, so tests can
 * produce every variation the verifier must reject. A second implementation of
 * the format is only trustworthy next to a fixture from the first, which is why
 * `server-signed-capability-document.jws` exists too.
 */
object TestSigner {

    private val repositoryRoot: File =
        generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "infra/capability-signing").isDirectory }

    /** ADR-0009's published development key, the one the debug build pins. */
    val developmentPublic: PublicKey =
        CapabilityDocumentVerifier.publicKeyFromPem(File(repositoryRoot, "infra/capability-signing/development.pub.pem").readText())

    val developmentPrivate: PrivateKey = run {
        val pem = File(repositoryRoot, "infra/capability-signing/development.pem").readText()
        val body = pem.lineSequence().filterNot { it.startsWith("-----") }.joinToString("")
        KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)))
    }

    fun otherKeyPair() = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResource(name)) { "missing fixture $name" }.readText().trim()

    fun sign(
        payload: String,
        key: PrivateKey = developmentPrivate,
        header: String = """{"alg":"ES256","kid":"${CapabilityDocumentVerifier.kid(developmentPublic)}","typ":"capability+jws"}""",
    ): String {
        val input = "${encode(header.toByteArray())}.${encode(payload.toByteArray())}"
        val der = Signature.getInstance("SHA256withECDSA").run {
            initSign(key)
            update(input.toByteArray(Charsets.US_ASCII))
            sign()
        }
        return "$input.${encode(derToRaw(der))}"
    }

    fun payload(
        subject: String = "subject-1",
        roleAssignmentId: String = "assignment-1",
        features: List<String> = listOf("home", "status"),
        schemaVersion: Int = 1,
        exp: Long = 2_000,
    ): String =
        """
        {"schema_version":$schemaVersion,"sub":"$subject","tenant_id":"t","participant_id":"p",
         "role_assignment_id":"$roleAssignmentId","role":{"key":"COURIER","label":"Courier","version":0},
         "capabilities":[{"key":"status.declare.self","constraints":["self_only"]}],
         "features":[${features.joinToString(",") { "\"$it\"" }}],
         "status_options":[{"value":"AT_PICKUP","label":"At pickup","requires_note":false},
                           {"value":"DELAYED","label":"Delayed","requires_note":true}],
         "iat":1000,"exp":$exp}
        """.trimIndent()

    private fun encode(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun derToRaw(der: ByteArray): ByteArray {
        // SEQUENCE { INTEGER r, INTEGER s }, short-form lengths for P-256.
        var offset = 2
        fun next(): ByteArray {
            val length = der[offset + 1].toInt()
            val value = der.copyOfRange(offset + 2, offset + 2 + length)
            offset += 2 + length
            return BigInteger(1, value).toByteArray().takeLast(32).toByteArray().let { ByteArray(32 - it.size) + it }
        }
        return next() + next()
    }
}
