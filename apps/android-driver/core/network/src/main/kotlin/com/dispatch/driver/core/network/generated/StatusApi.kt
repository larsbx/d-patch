package com.dispatch.driver.core.network.generated

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

// Generated projection of contracts/openapi.json for the Slice 1 status surface.
// Keep transport names and enum strings exactly aligned with OpenAPI; domain
// meaning stays in core:model.

@Serializable
data class StatusEventRequest(
    @SerialName("event_id")
    val eventId: String,
    val status: String,
    @SerialName("occurred_at")
    val occurredAt: String,
    val note: String? = null,
    @SerialName("assignment_id")
    val assignmentId: String? = null,
    @SerialName("location_sample_id")
    val locationSampleId: String? = null,
    @SerialName("device_id")
    val deviceId: String,
    @SerialName("device_sequence")
    val deviceSequence: Long,
    @SerialName("supersedes_event_id")
    val supersedesEventId: String? = null,
)

@Serializable
data class StoredStatusEvent(
    val id: String,
    @SerialName("participant_id")
    val participantId: String,
    @SerialName("role_assignment_id")
    val roleAssignmentId: String,
    @SerialName("assignment_id")
    val assignmentId: String? = null,
    val status: String,
    val source: String,
    @SerialName("occurred_at")
    val occurredAt: String,
    @SerialName("recorded_at")
    val recordedAt: String,
    val note: String? = null,
    @SerialName("location_sample_id")
    val locationSampleId: String? = null,
    val verification: String,
    @SerialName("supersedes_event_id")
    val supersedesEventId: String? = null,
    @SerialName("device_id")
    val deviceId: String? = null,
    @SerialName("device_sequence")
    val deviceSequence: Long? = null,
)

@Serializable
data class CurrentStatus(
    val value: String,
    val source: String,
    @SerialName("role_key")
    val roleKey: String,
    @SerialName("occurred_at")
    val occurredAt: String,
)

@Serializable
data class StatusEventResponse(
    val event: StoredStatusEvent,
    @SerialName("current_status")
    val currentStatus: CurrentStatus,
)

@Serializable
data class ProblemDetail(
    val type: String,
    val title: String,
    val status: Int,
    val detail: String,
    val instance: String,
    val code: String,
    @SerialName("correlation_id")
    val correlationId: String,
)

interface StatusApi {
    @POST("v1/me/status-events")
    suspend fun declare(
        @Header("Authorization") authorization: String,
        @Header("X-Role-Assignment-ID") roleAssignmentId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body request: StatusEventRequest,
    ): Response<StatusEventResponse>
}
