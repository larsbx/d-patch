package com.dispatch.driver.core.model

enum class ParticipantStatus(val label: String, val requiresNote: Boolean = false) {
    AVAILABLE("Available"), OFF_DUTY("Off duty"), PRE_TRIP("Pre-trip"),
    DEADHEAD("Deadheading"), EN_ROUTE_PICKUP("En route to pickup"),
    AT_PICKUP("At pickup"), LOADING("Loading"),
    EN_ROUTE_DELIVERY("En route to delivery"), AT_DELIVERY("At delivery"),
    UNLOADING("Unloading"), DELAYED("Delayed", true),
    BREAKDOWN("Breakdown", true), RESTING("Resting"),
    COMPLETE("Complete"), EMERGENCY("Emergency")
}

enum class DeliveryState { PENDING, SENDING, SYNCED, REJECTED }

data class RoleContext(
    val roleAssignmentId: String,
    val capabilities: Set<String>,
    val expiresAtEpochMillis: Long? = null,
) {
    fun canDeclareStatus(nowEpochMillis: Long): Boolean =
        "status.declare.self" in capabilities &&
            (expiresAtEpochMillis == null || nowEpochMillis < expiresAtEpochMillis)
}

data class StatusDraft(
    val status: ParticipantStatus,
    val note: String?,
    val assignmentId: String?,
    val supersedesEventId: String? = null,
)

data class LocalStatusEvent(
    val localId: String,
    val eventId: String,
    val roleAssignmentId: String,
    val status: ParticipantStatus,
    val note: String?,
    val occurredAt: String,
    val deviceSequence: Long,
    val state: DeliveryState,
    val rejectionCode: String? = null,
    val correctiveAction: String? = null,
)

fun StatusDraft.validationError(): String? = when {
    note.orEmpty().codePointCount(0, note.orEmpty().length) > 1_000 ->
        "Note must be 1,000 characters or fewer."
    status.requiresNote && note.isNullOrBlank() ->
        "Add a note describing the delay or breakdown."
    else -> null
}
