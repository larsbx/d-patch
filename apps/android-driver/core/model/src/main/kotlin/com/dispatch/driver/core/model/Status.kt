package com.dispatch.driver.core.model

import kotlinx.coroutines.flow.Flow

const val STATUS_DECLARE_SELF_CAPABILITY = "status.declare.self"

enum class DriverStatus(
    val label: String,
    val meaning: String,
    val requiresNote: Boolean = false,
) {
    AVAILABLE("Available", "Ready for dispatch consideration; not a legal HOS assertion."),
    OFF_DUTY("Off duty", "Operationally unavailable."),
    PRE_TRIP("Pre-trip", "Preparing vehicle or load."),
    DEADHEAD("Deadheading", "Traveling without the assigned freight."),
    EN_ROUTE_PICKUP("En route to pickup", "Traveling to shipper."),
    AT_PICKUP("At pickup", "Driver declares arrival at shipper."),
    LOADING("Loading", "Freight is being loaded."),
    EN_ROUTE_DELIVERY("En route to delivery", "Traveling to consignee."),
    AT_DELIVERY("At delivery", "Driver declares arrival at consignee."),
    UNLOADING("Unloading", "Freight is being unloaded."),
    DELAYED("Delayed", "Delay exists; reason and revised estimate requested.", requiresNote = true),
    BREAKDOWN("Breakdown", "Vehicle problem; description and safe location requested.", requiresNote = true),
    RESTING("Resting", "Operationally unavailable while resting; not an ELD duty-status record."),
    COMPLETE("Complete", "Operational completion declared."),
    EMERGENCY(
        "Emergency",
        "Driver-originated safety state. It grants no inbound routing or priority privilege.",
    ),
}

enum class LocalSyncState { PENDING, RETRY, SENDING, SENT, REJECTED }

data class LocalStatus(
    val localId: String,
    val status: DriverStatus,
    val occurredAt: String,
    val source: String,
    val syncState: LocalSyncState,
    val rejectionCode: String? = null,
    val correctiveAction: String? = null,
)

data class StatusRejection(
    val localId: String,
    val code: String,
    val correctiveAction: String,
)

sealed interface StatusSubmissionResult {
    data class Queued(val localId: String) : StatusSubmissionResult
    data class Blocked(val reason: String) : StatusSubmissionResult
}

interface StatusRepository {
    val currentStatus: Flow<LocalStatus?>
    val latestRejection: Flow<StatusRejection?>

    suspend fun declare(status: DriverStatus, note: String?): StatusSubmissionResult

    suspend fun retry(localId: String): Boolean
}
