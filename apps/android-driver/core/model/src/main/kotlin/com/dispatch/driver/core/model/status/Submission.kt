package com.dispatch.driver.core.model.status

/**
 * The transport port for status submission. `core:network` implements it; the
 * outbox depends only on this, so its retry and rejection rules are tested
 * without a socket.
 */
fun interface StatusTransport {
    suspend fun submit(declaration: StatusDeclaration, roleAssignmentId: String): SubmitResult
}

/** What came back, before anyone decides what it means. */
sealed interface SubmitResult {
    /** `201`: stored, or recognised as a replay of what was stored. */
    data class Stored(val current: CurrentStatus) : SubmitResult

    /** An RFC 9457 problem (Section 19.2) with its stable reason code. */
    data class Problem(val httpStatus: Int, val code: String?, val detail: String?) : SubmitResult

    /** No answer: offline, timed out, or the connection failed mid-request. */
    data object Unreachable : SubmitResult
}

/**
 * What the outbox does with a [SubmitResult].
 *
 * Section 25.2: "Server rejection marks the local item `REJECTED` and displays
 * the exact corrective action." The line that matters is between a rejection —
 * this declaration will never be accepted as sent — and everything else, which
 * keeps the event queued. Misfiling a transient failure as a rejection loses a
 * declaration; misfiling a rejection as transient retries it forever. So the
 * rejections are an explicit list and the default is to keep the event.
 */
sealed interface Disposition {
    data class Acknowledge(val current: CurrentStatus) : Disposition
    data class Reject(val code: String, val correctiveAction: String) : Disposition

    /** Keep queued. [reason] is shown beside the pending item, if anything is. */
    data class RetryLater(val reason: String?) : Disposition

    companion object {
        fun of(result: SubmitResult): Disposition = when (result) {
            is SubmitResult.Stored -> Acknowledge(result.current)
            SubmitResult.Unreachable -> RetryLater(null)
            is SubmitResult.Problem -> ofProblem(result)
        }

        private fun ofProblem(problem: SubmitResult.Problem): Disposition {
            val code = problem.code
            val action = code?.let(correctiveActions::get)
            return when {
                code in retryableCodes -> RetryLater(null)
                code != null && action != null -> Reject(code, action)
                // Unauthenticated is the session's fault, not the declaration's:
                // signing in again sends it unchanged.
                problem.httpStatus == 401 -> RetryLater("Sign in again to send queued updates.")
                // A validation failure we have no specific advice for is still
                // final — resending the same bytes cannot succeed — so the
                // server's own detail is the corrective action.
                problem.httpStatus == 422 -> Reject(code ?: "VALIDATION_FAILED", problem.detail ?: "Correct the update and send it again.")
                problem.httpStatus in 400..499 && problem.httpStatus !in retryable4xx ->
                    Reject(code ?: "HTTP_${problem.httpStatus}", problem.detail ?: "This update was refused. Declare your status again.")
                else -> RetryLater(null)
            }
        }

        /** Request timeout, too-early and throttling resolve by waiting. */
        private val retryable4xx = setOf(408, 425, 429)

        /** A `409` that is not a conflict: an identical request is still running. */
        private val retryableCodes = setOf("IDEMPOTENCY_KEY_IN_PROGRESS")

        /**
         * The stable codes of `DispatchWeb.StatusEventController` and
         * `DispatchWeb.Plugs.ResolveActor`, each with what the participant can
         * do about it.
         */
        private val correctiveActions = mapOf(
            "EVENT_ID_CONFLICT" to "This update clashed with another one already sent. Declare your status again.",
            "DEVICE_SEQUENCE_CONFLICT" to "This device sent a different update under the same number. Declare your status again.",
            "IDEMPOTENCY_KEY_REUSED" to "This update clashed with another one already sent. Declare your status again.",
            "MALFORMED_REQUEST" to "This update could not be read by the server. Declare your status again.",
            "FORBIDDEN" to "Your current role cannot declare a status. Choose a different role or contact your dispatcher.",
            "NO_ACTIVE_ROLE_ASSIGNMENT" to "You have no active role. Contact your dispatcher.",
            "ROLE_ASSIGNMENT_INVALID" to "The role this was sent under is no longer active. Choose your role again and redeclare.",
            "ROLE_ASSIGNMENT_REQUIRED" to "Choose which role you are working under, then declare your status again.",
        )
    }
}
