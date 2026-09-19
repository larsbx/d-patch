package com.dispatch.driver.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

@Serializable
data class StatusEventRequest(
    @SerialName("event_id") val eventId: String,
    @SerialName("assignment_id") val assignmentId: String? = null,
    val status: String,
    @SerialName("occurred_at") val occurredAt: String,
    val note: String? = null,
    @SerialName("device_id") val deviceId: String,
    @SerialName("device_sequence") val deviceSequence: Long,
    @SerialName("supersedes_event_id") val supersedesEventId: String? = null,
)

@Serializable data class StoredStatusEvent(val id: String)
@Serializable data class StatusEventResponse(val event: StoredStatusEvent)
@Serializable data class Problem(val type: String? = null, val title: String? = null,
    val detail: String? = null, val code: String? = null)

interface StatusApi {
    @POST("v1/me/status-events")
    suspend fun create(
        @Header("Authorization") authorization: String,
        @Header("X-Role-Assignment-ID") roleAssignmentId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body request: StatusEventRequest,
    ): Response<StatusEventResponse>
}

sealed interface UploadOutcome {
    data object Accepted : UploadOutcome
    data class Retry(val delayMillis: Long) : UploadOutcome
    data class Rejected(val code: String, val correctiveAction: String) : UploadOutcome
}

object StatusResponsePolicy {
    fun classify(code: Int, problem: Problem?): UploadOutcome = when {
        code in 200..299 -> UploadOutcome.Accepted
        code == 408 || code == 425 || code == 429 || code >= 500 ->
            UploadOutcome.Retry(30_000)
        code == 401 -> UploadOutcome.Rejected("AUTHENTICATION_REQUIRED", "Sign in again, then resubmit.")
        code == 403 -> UploadOutcome.Rejected("ROLE_ASSIGNMENT_FORBIDDEN",
            "Select an active role assignment with status declaration access.")
        code == 409 && problem?.code == "IDEMPOTENCY_KEY_IN_PROGRESS" ->
            UploadOutcome.Retry(5_000)
        else -> UploadOutcome.Rejected(problem?.code ?: "DECLARATION_REJECTED",
            problem?.detail?.takeIf { it.isNotBlank() } ?: "Review the declaration and submit a corrected status.")
    }
}
