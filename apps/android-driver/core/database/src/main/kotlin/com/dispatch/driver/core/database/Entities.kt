package com.dispatch.driver.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Section 25.2's `pending_events`, with three columns the specification's
 * list leaves implicit:
 *
 * - `role_assignment_id` — the assignment the participant declared under.
 *   A queued event is sent under *that* authority context (Section 24.6), not
 *   whichever role happens to be selected when connectivity returns.
 * - `rejection_code`, `corrective_action` — Section 25.2's "marks the local
 *   item `REJECTED` and displays the exact corrective action" needs somewhere
 *   to keep both. While `PENDING`, `corrective_action` may carry what would get
 *   it sent (for example, signing in again).
 *
 * Rows are kept after acknowledgement: `device_sequence` must never repeat on
 * this installation (Section 22.3), and the next value is derived from the
 * maximum here. Pruning, when it comes, must retain the highest row.
 */
@Entity(
    tableName = "pending_events",
    indices = [Index(value = ["device_sequence"], unique = true), Index(value = ["state", "next_attempt_at"])],
)
data class PendingEventEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "local_id") val localId: Long = 0,
    val kind: String,
    @ColumnInfo(name = "canonical_json") val canonicalJson: String,
    @ColumnInfo(name = "device_sequence") val deviceSequence: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "attempt_count") val attemptCount: Int = 0,
    @ColumnInfo(name = "next_attempt_at") val nextAttemptAt: Long,
    val state: String,
    @ColumnInfo(name = "role_assignment_id") val roleAssignmentId: String,
    @ColumnInfo(name = "rejection_code") val rejectionCode: String? = null,
    @ColumnInfo(name = "corrective_action") val correctiveAction: String? = null,
)

/**
 * Section 25.2's `cached_driver_state`: the server's last word on the
 * participant's status. What the screen shows is derived from this *and* the
 * outbox, never written back into it, so an optimistic value can never be
 * mistaken for an acknowledged one.
 *
 * `server_version` is the device sequence of the acknowledgement that produced
 * `json`; an older acknowledgement arriving late cannot overwrite a newer one.
 */
@Entity(tableName = "cached_driver_state")
data class CachedDriverStateEntity(
    @PrimaryKey @ColumnInfo(name = "singleton_id") val singletonId: Int = SINGLETON,
    val json: String,
    @ColumnInfo(name = "server_version") val serverVersion: Long,
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long,
) {
    companion object {
        const val SINGLETON = 0
    }
}

/**
 * The last capability document received, as the signed token.
 *
 * Stored signed and verified again on every read (ADR-0009): the database is
 * not a trust boundary, so a row edited on a rooted device enables nothing.
 */
@Entity(tableName = "cached_capability_document")
data class CachedCapabilityDocumentEntity(
    @PrimaryKey @ColumnInfo(name = "singleton_id") val singletonId: Int = CachedDriverStateEntity.SINGLETON,
    val token: String,
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long,
)

/** `pending_events.state`. Text in the table so a row reads without the enum. */
enum class PendingState { PENDING, ACKNOWLEDGED, REJECTED }

/** `pending_events.kind`. Location samples and approvals join this later. */
enum class PendingKind { STATUS }
