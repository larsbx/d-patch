package com.dispatch.driver.core.model.capability

import com.dispatch.driver.core.model.capability.CapabilityDocumentVerifier.Binding
import com.dispatch.driver.core.model.capability.CapabilityDocumentVerifier.Reason
import com.dispatch.driver.core.model.capability.CapabilityDocumentVerifier.Result
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR-0009's client half. Section 23.2 only means something if a document the
 * server did not sign — or signed for someone else — enables nothing.
 */
class CapabilityDocumentVerifierTest {

    private val verifier = CapabilityDocumentVerifier(TestSigner.testKey.public)
    private val session = Binding(subject = "subject-1", roleAssignmentId = "assignment-1")

    private fun verify(signed: SignedDocument, binding: Binding = session, now: Long = 1_500) = verifier.verify(signed, binding, now)

    private fun rejected(result: Result): Reason = (result as Result.Rejected).reason

    @Test
    fun `a document biz-kit signed verifies against the pinned development key`() {
        val signed = Json.decodeFromString(SignedDocument.serializer(), TestSigner.fixture("server-signed-capability-document.json"))

        val result = CapabilityDocumentVerifier(TestSigner.developmentPublic).verify(
            signed,
            Binding(subject = "fixture-subject", roleAssignmentId = "0192a000-0000-7000-8000-000000000003"),
            nowEpochSeconds = 1_790_000_001,
        )

        val document = (result as Result.Verified).document
        assertEquals(CapabilityDocument.SCHEMA, document.schema)
        assertEquals(setOf("home", "status"), document.features)
        assertEquals(15, document.statusOptions.size)
        assertTrue(document.statusOption("BREAKDOWN")!!.requiresNote)
        assertFalse(result.stale)
    }

    @Test
    fun `a valid document enables exactly what it lists`() {
        val document = (verify(TestSigner.sign(TestSigner.document())) as Result.Verified).document

        assertTrue(document.offers(Features.STATUS))
        assertFalse(document.offers("approvals"))
    }

    @Test
    fun `altered claims fail the signature, whatever they claim`() {
        val signed = TestSigner.sign(TestSigner.document())
        val widened = TestSigner.sign(TestSigner.document(features = setOf("home", "status", "approvals")))

        assertEquals(Reason.BAD_SIGNATURE, rejected(verify(signed.copy(document = widened.document))))
    }

    @Test
    fun `a document signed by another key is refused, whatever key it names`() {
        val other = TestSigner.generate()
        val pinnedId = CapabilityDocumentVerifier.keyId(TestSigner.testKey.public)

        assertEquals(Reason.UNKNOWN_KEY, rejected(verify(TestSigner.sign(TestSigner.document(), key = other))))
        assertEquals(Reason.BAD_SIGNATURE, rejected(verify(TestSigner.sign(TestSigner.document(), key = other, keyId = pinnedId))))
    }

    @Test
    fun `signed bytes of another schema are not a capability document`() {
        // An enrolment statement or ledger envelope signed by the same key must
        // not enable anything here.
        assertEquals(Reason.WRONG_SCHEMA, rejected(verify(TestSigner.sign(TestSigner.document(schema = "CoopDeviceEnrolmentV1")))))
    }

    @Test
    fun `a document for another login or another role assignment enables nothing`() {
        assertEquals(Reason.WRONG_SUBJECT, rejected(verify(TestSigner.sign(TestSigner.document(subject = "someone-else")))))
        assertEquals(Reason.WRONG_ASSIGNMENT, rejected(verify(TestSigner.sign(TestSigner.document(roleAssignmentId = "assignment-2")))))
    }

    @Test
    fun `with no assignment selected yet, the server's resolution is accepted`() {
        val result = verify(TestSigner.sign(TestSigner.document(roleAssignmentId = "assignment-2")), session.copy(roleAssignmentId = null))

        assertEquals("assignment-2", (result as Result.Verified).document.roleAssignmentId)
    }

    @Test
    fun `an unknown schema version is refused rather than half-understood`() {
        assertEquals(Reason.UNSUPPORTED_SCHEMA, rejected(verify(TestSigner.sign(TestSigner.document(schemaVersion = 2)))))
    }

    @Test
    fun `an expired document still verifies, and says it is stale`() {
        val result = verify(TestSigner.sign(TestSigner.document(exp = 2_000)), now = 2_000)

        assertTrue((result as Result.Verified).stale)
    }

    @Test
    fun `malformed input is refused, never thrown`() {
        val pinnedId = CapabilityDocumentVerifier.keyId(TestSigner.testKey.public)
        val good = TestSigner.sign(TestSigner.document())

        listOf(
            good.copy(document = "!!"),
            good.copy(signature = "!!"),
            good.copy(signature = good.signature.take(10)),
            TestSigner.signBytes("not cbor".toByteArray(), keyId = pinnedId),
        ).forEach { assertTrue(it.toString(), verify(it) is Result.Rejected) }
    }

    @Test
    fun `the key identifier follows the ledger's k- convention`() {
        assertEquals("k-2fd0e33d", CapabilityDocumentVerifier.keyId(TestSigner.developmentPublic))
    }
}
