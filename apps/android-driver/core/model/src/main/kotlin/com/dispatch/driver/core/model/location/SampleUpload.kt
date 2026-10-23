package com.dispatch.driver.core.model.location

import com.dispatch.driver.core.model.status.Disposition

/**
 * Sends a batch of signed samples (Section 24.2). The outbox depends only on
 * this, as the status outbox does on `StatusTransport`.
 */
fun interface SampleTransport {
    suspend fun upload(roleAssignmentId: String, samples: List<LocationSample>): UploadResult
}

/** One sample's result: `ACCEPTED`, `DUPLICATE` or `REJECTED`, with the rejection's code. */
data class SampleVerdict(val result: String, val code: String?)

sealed interface UploadResult {
    /** The server's `207`, by sample ID. */
    data class Answered(val verdicts: Map<String, SampleVerdict>) : UploadResult
    data class Problem(val httpStatus: Int, val code: String?) : UploadResult
    data object Unreachable : UploadResult
}

/**
 * What the outbox does with each sample of a batch, given the answer.
 *
 * The line is the status outbox's ([Disposition]): only an answer that this
 * sample, as sent, will never be accepted settles it as rejected; everything
 * else keeps it. `DUPLICATE` is the server already holding it, so it is
 * acknowledged. A sample the answer does not mention is kept.
 */
sealed interface SampleDisposition {
    data object Acknowledge : SampleDisposition
    data class Reject(val code: String) : SampleDisposition
    data class RetryLater(val reason: String?) : SampleDisposition

    companion object {
        const val SIGN_IN_AGAIN = "Sign in again to send queued locations."

        /** One disposition for each of [sampleIds], and only for those. */
        fun of(sampleIds: List<String>, result: UploadResult): Map<String, SampleDisposition> = when (result) {
            is UploadResult.Answered -> sampleIds.associateWith { id -> result.verdicts[id]?.let(::of) ?: RetryLater(null) }
            is UploadResult.Problem -> sampleIds.associateWith { ofProblem(result) }
            UploadResult.Unreachable -> sampleIds.associateWith { RetryLater(null) }
        }

        private fun of(verdict: SampleVerdict): SampleDisposition = when (verdict.result) {
            "ACCEPTED", "DUPLICATE" -> Acknowledge
            "REJECTED" -> Reject(verdict.code ?: "REJECTED")
            else -> RetryLater(null)
        }

        private fun ofProblem(problem: UploadResult.Problem): SampleDisposition = when {
            problem.code in Disposition.retryableCodes -> RetryLater(null)
            problem.httpStatus == 401 -> RetryLater(SIGN_IN_AGAIN)
            problem.httpStatus in 400..499 && problem.httpStatus !in Disposition.retryable4xx ->
                Reject(problem.code ?: "HTTP_${problem.httpStatus}")
            else -> RetryLater(null)
        }
    }
}
