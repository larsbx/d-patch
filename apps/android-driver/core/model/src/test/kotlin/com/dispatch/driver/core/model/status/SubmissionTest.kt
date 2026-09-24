package com.dispatch.driver.core.model.status

import com.dispatch.driver.core.model.UuidV7
import com.dispatch.driver.core.model.status.Disposition.Acknowledge
import com.dispatch.driver.core.model.status.Disposition.Reject
import com.dispatch.driver.core.model.status.Disposition.RetryLater
import com.dispatch.driver.core.model.status.SubmitResult.Problem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Section 25.2's line between "rejected" and "still queued". Getting it wrong
 * either way is a lost declaration or an event that retries forever.
 */
class SubmissionTest {

    private fun of(status: Int, code: String? = null, detail: String? = null) = Disposition.of(Problem(status, code, detail))

    @Test
    fun `a stored event is acknowledged with the server's current status`() {
        val current = CurrentStatus("AT_PICKUP", "PARTICIPANT", "2026-09-24T10:00:00Z")

        assertEquals(Acknowledge(current), Disposition.of(SubmitResult.Stored(current)))
    }

    @Test
    fun `no answer, a server fault, throttling, or an in-flight twin keeps the event queued`() {
        assertTrue(Disposition.of(SubmitResult.Unreachable) is RetryLater)
        listOf(500, 502, 503, 504, 408, 429).forEach { assertTrue("$it", of(it) is RetryLater) }
        assertTrue(of(409, "IDEMPOTENCY_KEY_IN_PROGRESS") is RetryLater)
    }

    @Test
    fun `an expired session keeps the event and says how to send it`() {
        val disposition = of(401, "UNAUTHENTICATED")

        assertEquals(RetryLater("Sign in again to send queued updates."), disposition)
    }

    @Test
    fun `every stable rejection code carries a specific corrective action`() {
        listOf(
            409 to "EVENT_ID_CONFLICT",
            409 to "DEVICE_SEQUENCE_CONFLICT",
            409 to "IDEMPOTENCY_KEY_REUSED",
            400 to "MALFORMED_REQUEST",
            403 to "FORBIDDEN",
            403 to "NO_ACTIVE_ROLE_ASSIGNMENT",
            403 to "ROLE_ASSIGNMENT_INVALID",
            403 to "ROLE_ASSIGNMENT_REQUIRED",
        ).forEach { (status, code) ->
            val disposition = of(status, code, detail = "server prose")
            assertTrue(code, disposition is Reject)
            disposition as Reject
            assertEquals(code, disposition.code)
            assertFalse("$code falls back to server prose", disposition.correctiveAction == "server prose")
        }
    }

    @Test
    fun `a validation failure is final and shows the server's reason`() {
        val disposition = of(422, "VALIDATION_FAILED", "note: is required for DELAYED")

        assertEquals(Reject("VALIDATION_FAILED", "note: is required for DELAYED"), disposition)
    }

    @Test
    fun `the canonical JSON omits absent fields and round-trips`() {
        val declaration = StatusDeclaration(
            eventId = "e1",
            status = "AT_PICKUP",
            occurredAt = "2026-09-24T10:00:00Z",
            deviceSequence = 7,
        )

        val json = declaration.toCanonicalJson()

        assertEquals("""{"event_id":"e1","status":"AT_PICKUP","occurred_at":"2026-09-24T10:00:00Z","device_sequence":7}""", json)
        assertEquals(declaration, StatusDeclaration.fromCanonicalJson(json))
    }

    @Test
    fun `event IDs are version 7 and sort by creation time`() {
        val earlier = UuidV7.generate(nowMillis = 1_000)
        val later = UuidV7.generate(nowMillis = 2_000)

        assertEquals(7, earlier.version())
        assertEquals(2, earlier.variant())
        assertTrue(earlier.toString() < later.toString())
        assertEquals(1_000L, earlier.mostSignificantBits ushr 16)
    }
}
