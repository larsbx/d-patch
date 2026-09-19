package com.dispatch.driver.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

object PendingEventStates {
    const val PENDING = "PENDING"
    const val RETRY = "RETRY"
    const val SENDING = "SENDING"
    const val SENT = "SENT"
    const val REJECTED = "REJECTED"
}

@Entity(
    tableName = "pending_events",
    indices = [
        Index(value = ["device_id", "device_sequence"], unique = true),
        Index(value = ["state", "device_sequence"]),
    ],
)
data class PendingEventEntity(
    @PrimaryKey
    @ColumnInfo(name = "local_id")
    val localId: String,
    val kind: String,
    @ColumnInfo(name = "canonical_json")
    val canonicalJson: String,
    @ColumnInfo(name = "device_id")
    val deviceId: String,
    @ColumnInfo(name = "device_sequence")
    val deviceSequence: Long,
    @ColumnInfo(name = "role_assignment_id")
    val roleAssignmentId: String,
    @ColumnInfo(name = "idempotency_key")
    val idempotencyKey: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "attempt_count")
    val attemptCount: Int,
    @ColumnInfo(name = "next_attempt_at")
    val nextAttemptAt: Long,
    val state: String,
    @ColumnInfo(name = "last_error_code")
    val lastErrorCode: String? = null,
    @ColumnInfo(name = "last_error_detail")
    val lastErrorDetail: String? = null,
)

@Entity(tableName = "cached_driver_state")
data class CachedDriverStateEntity(
    @PrimaryKey
    @ColumnInfo(name = "singleton_id")
    val singletonId: Int = SINGLETON_ID,
    val json: String,
    @ColumnInfo(name = "server_version")
    val serverVersion: String?,
    @ColumnInfo(name = "fetched_at")
    val fetchedAt: Long,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}

@Entity(tableName = "cached_assignment")
data class CachedAssignmentEntity(
    @PrimaryKey
    val id: String,
    val json: String,
    @ColumnInfo(name = "server_version")
    val serverVersion: String?,
    @ColumnInfo(name = "fetched_at")
    val fetchedAt: Long,
)
