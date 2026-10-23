package com.dispatch.driver.core.model.location

import com.dispatch.driver.core.model.location.SampleDisposition.Acknowledge
import com.dispatch.driver.core.model.location.SampleDisposition.Reject
import com.dispatch.driver.core.model.location.SampleDisposition.RetryLater
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

/**
 * What an upload's answer means for each sample it carried (Section 24.2's
 * per-sample `207`). A sample is settled only by an answer about it.
 */
class SampleDispositionTest {

    private val batch = listOf("a", "b", "c", "d")

    @Test
    fun `each sample is settled by its own result`() {
        val answer = UploadResult.Answered(
            mapOf("a" to SampleVerdict("ACCEPTED", null), "b" to SampleVerdict("DUPLICATE", null), "c" to SampleVerdict("REJECTED", "REJECTED_CONSENT")),
        )
        assertEquals(
            mapOf("a" to Acknowledge, "b" to Acknowledge, "c" to Reject("REJECTED_CONSENT"), "d" to RetryLater(null)),
            SampleDisposition.of(batch, answer),
        )
    }

    @Test
    fun `no answer, a refused session, throttling or a server fault keep every sample`() {
        for (
            result in listOf(UploadResult.Unreachable) + listOf(401, 408, 425, 429, 500, 503).map { UploadResult.Problem(it, "X") } +
            UploadResult.Problem(409, "IDEMPOTENCY_KEY_IN_PROGRESS")
        ) {
            assertEquals("$result", batch.associateWith { RetryLater(reasonOf(result)) }, SampleDisposition.of(batch, result))
        }
    }

    @Test
    fun `a request the server refuses outright settles the batch with its code`() {
        assertEquals(batch.associateWith { Reject("FORBIDDEN") }, SampleDisposition.of(batch, UploadResult.Problem(403, "FORBIDDEN")))
        assertEquals(batch.associateWith { Reject("HTTP_400") }, SampleDisposition.of(batch, UploadResult.Problem(400, null)))
    }

    // Total and exact: every sample sent gets one disposition, and nothing
    // the server said about a sample that was not sent is kept.
    @Test
    fun `every sample sent gets exactly one disposition`() {
        val random = Random(2026_10_23)
        val verdicts = listOf(SampleVerdict("ACCEPTED", null), SampleVerdict("DUPLICATE", null), SampleVerdict("REJECTED", "CLOCK_SKEW"))
        repeat(1_000) {
            val sent = List(random.nextInt(0, 26)) { "s$it" }
            val answered = (sent + "stranger").filter { random.nextBoolean() }.associateWith { verdicts.random(random) }
            assertEquals(sent.toSet(), SampleDisposition.of(sent, UploadResult.Answered(answered)).keys)
        }
    }

    private fun reasonOf(result: UploadResult) = when (result) {
        is UploadResult.Problem -> if (result.httpStatus == 401) SampleDisposition.SIGN_IN_AGAIN else null
        else -> null
    }
}
