package com.dispatch.driver.core.model.capability

import com.dispatch.driver.core.model.capability.CapabilityDocumentVerifier.Binding
import com.dispatch.driver.core.model.capability.CapabilityDocumentVerifier.Reason
import com.dispatch.driver.core.model.capability.CapabilityDocumentVerifier.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR-0009's client half. Section 23.2 only means something if a document the
 * server did not sign — or signed for someone else — enables nothing.
 */
class CapabilityDocumentVerifierTest {

    private val verifier = CapabilityDocumentVerifier(TestSigner.developmentPublic)
    private val session = Binding(subject = "subject-1", roleAssignmentId = "assignment-1")

    private fun verify(token: String, binding: Binding = session, now: Long = 1_500) = verifier.verify(token, binding, now)

    private fun rejected(result: Result): Reason = (result as Result.Rejected).reason

    @Test
    fun `a document the server signed verifies, byte for byte from the other implementation`() {
        val result = verifier.verify(
            TestSigner.fixture("server-signed-capability-document.jws"),
            Binding(subject = "fixture-subject", roleAssignmentId = "0192a000-0000-7000-8000-000000000003"),
            nowEpochSeconds = 1_790_000_001,
        )

        val document = (result as Result.Verified).document
        assertEquals(setOf("home", "status"), document.features)
        assertEquals(15, document.statusOptions.size)
        assertTrue(document.statusOption("BREAKDOWN")!!.requiresNote)
        assertFalse(result.stale)
    }

    @Test
    fun `a valid document enables exactly what it lists`() {
        val document = (verify(TestSigner.sign(TestSigner.payload())) as Result.Verified).document

        assertTrue(document.offers(Features.STATUS))
        assertFalse(document.offers("approvals"))
    }

    @Test
    fun `an altered payload fails the signature, whatever it claims`() {
        val (header, _, signature) = TestSigner.sign(TestSigner.payload()).split('.')
        val widened = TestSigner.sign(TestSigner.payload(features = listOf("home", "status", "approvals"))).split('.')[1]

        assertEquals(Reason.BAD_SIGNATURE, rejected(verify("$header.$widened.$signature")))
    }

    @Test
    fun `a document signed by any other key is refused`() {
        val token = TestSigner.sign(TestSigner.payload(), key = TestSigner.otherKeyPair().private)

        assertEquals(Reason.BAD_SIGNATURE, rejected(verify(token)))
    }

    @Test
    fun `a header naming another key, algorithm, or type is refused`() {
        val payload = TestSigner.payload()
        val kid = CapabilityDocumentVerifier.kid(TestSigner.developmentPublic)

        assertEquals(Reason.UNKNOWN_KEY, rejected(verify(TestSigner.sign(payload, header = """{"alg":"ES256","kid":"other","typ":"capability+jws"}"""))))
        assertEquals(Reason.WRONG_ALGORITHM, rejected(verify(TestSigner.sign(payload, header = """{"alg":"none","kid":"$kid","typ":"capability+jws"}"""))))
        assertEquals(Reason.WRONG_TYPE, rejected(verify(TestSigner.sign(payload, header = """{"alg":"ES256","kid":"$kid","typ":"JWT"}"""))))
    }

    @Test
    fun `a document for another login or another role assignment enables nothing`() {
        assertEquals(Reason.WRONG_SUBJECT, rejected(verify(TestSigner.sign(TestSigner.payload(subject = "someone-else")))))
        assertEquals(Reason.WRONG_ASSIGNMENT, rejected(verify(TestSigner.sign(TestSigner.payload(roleAssignmentId = "assignment-2")))))
    }

    @Test
    fun `with no assignment selected yet, the server's resolution is accepted`() {
        val token = TestSigner.sign(TestSigner.payload(roleAssignmentId = "assignment-2"))

        val result = verify(token, session.copy(roleAssignmentId = null))

        assertEquals("assignment-2", (result as Result.Verified).document.roleAssignmentId)
    }

    @Test
    fun `an unknown schema version is refused rather than half-understood`() {
        assertEquals(Reason.UNSUPPORTED_SCHEMA, rejected(verify(TestSigner.sign(TestSigner.payload(schemaVersion = 2)))))
    }

    @Test
    fun `an expired document still verifies, and says it is stale`() {
        val result = verify(TestSigner.sign(TestSigner.payload(exp = 2_000)), now = 2_000)

        assertTrue((result as Result.Verified).stale)
    }

    @Test
    fun `malformed input is refused, never thrown`() {
        listOf("", "a.b", "a.b.c", "...", "!!.@@.##").forEach { token ->
            assertTrue(token, verify(token) is Result.Rejected)
        }
    }

    @Test
    fun `the key identifier matches the server's derivation`() {
        assertEquals("tOkF49ervDH_m_gl", CapabilityDocumentVerifier.kid(TestSigner.developmentPublic))
    }
}
