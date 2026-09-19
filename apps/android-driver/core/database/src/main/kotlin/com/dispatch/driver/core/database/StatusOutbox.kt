package com.dispatch.driver.core.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "local_status_events")
data class LocalStatusEntity(
    @PrimaryKey @ColumnInfo(name = "local_id") val localId: String,
    @ColumnInfo(name = "event_id") val eventId: String,
    @ColumnInfo(name = "role_assignment_id") val roleAssignmentId: String,
    val status: String, val note: String?,
    @ColumnInfo(name = "assignment_id") val assignmentId: String?,
    @ColumnInfo(name = "occurred_at") val occurredAt: String,
    @ColumnInfo(name = "device_sequence") val deviceSequence: Long,
    val state: String,
    @ColumnInfo(name = "rejection_code") val rejectionCode: String? = null,
    @ColumnInfo(name = "corrective_action") val correctiveAction: String? = null,
)
@Entity(tableName = "pending_events")
data class PendingEventEntity(
    @PrimaryKey @ColumnInfo(name = "local_id") val localId: String,
    val kind: String, @ColumnInfo(name = "canonical_json") val canonicalJson: String,
    @ColumnInfo(name = "device_sequence") val deviceSequence: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "attempt_count") val attemptCount: Int = 0,
    @ColumnInfo(name = "next_attempt_at") val nextAttemptAt: Long,
    val state: String = "PENDING",
)
@Entity(tableName = "device_sequences")
data class DeviceSequenceEntity(@PrimaryKey val deviceId: String, val lastSequence: Long)
@Entity(tableName = "cached_driver_state")
data class CachedDriverStateEntity(@PrimaryKey @ColumnInfo(name = "singleton_id") val singletonId: Int = 1,
    val json: String, @ColumnInfo(name = "server_version") val serverVersion: String?,
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long)
@Entity(tableName = "cached_assignment")
data class CachedAssignmentEntity(@PrimaryKey val id: String, val json: String,
    @ColumnInfo(name = "server_version") val serverVersion: String?,
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long)

@Dao interface StatusOutboxDao {
    @Query("SELECT * FROM local_status_events ORDER BY occurred_at DESC")
    fun observeEvents(): Flow<List<LocalStatusEntity>>
    @Query("SELECT * FROM pending_events WHERE state = 'PENDING' AND next_attempt_at <= :now ORDER BY device_sequence LIMIT :limit")
    suspend fun ready(now: Long, limit: Int = 25): List<PendingEventEntity>
    @Query("UPDATE pending_events SET state = 'PENDING' WHERE state = 'SENDING'")
    suspend fun reclaimInterruptedClaims(): Int
    @Query("SELECT role_assignment_id FROM local_status_events WHERE local_id = :id")
    suspend fun roleAssignmentId(id: String): String?
    @Query("SELECT lastSequence FROM device_sequences WHERE deviceId = :deviceId")
    suspend fun sequence(deviceId: String): Long?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putSequence(value: DeviceSequenceEntity)
    @Insert suspend fun insertLocal(value: LocalStatusEntity)
    @Insert suspend fun insertPending(value: PendingEventEntity)
    @Query("UPDATE pending_events SET state = 'SENDING', attempt_count = attempt_count + 1 WHERE local_id = :id AND state = 'PENDING'")
    suspend fun claim(id: String): Int
    @Query("DELETE FROM pending_events WHERE local_id = :id") suspend fun deletePending(id: String)
    @Query("UPDATE pending_events SET state = 'PENDING', next_attempt_at = :nextAt WHERE local_id = :id")
    suspend fun retry(id: String, nextAt: Long)
    @Query("UPDATE local_status_events SET state = 'SYNCED', rejection_code = NULL, corrective_action = NULL WHERE local_id = :id")
    suspend fun synced(id: String)
    @Query("UPDATE local_status_events SET state = 'REJECTED', rejection_code = :code, corrective_action = :action WHERE local_id = :id")
    suspend fun rejected(id: String, code: String, action: String)
}
@Database(entities = [LocalStatusEntity::class, PendingEventEntity::class,
    DeviceSequenceEntity::class, CachedDriverStateEntity::class, CachedAssignmentEntity::class],
    version = 1, exportSchema = true)
abstract class DispatchDatabase : RoomDatabase() { abstract fun statusOutbox(): StatusOutboxDao }
data class EnqueuedStatus(val localId: String, val deviceSequence: Long)
class StatusOutbox(private val db: DispatchDatabase) {
    fun observeEvents(): Flow<List<LocalStatusEntity>> = db.statusOutbox().observeEvents()
    suspend fun enqueue(deviceId: String, localId: String, eventId: String, roleAssignmentId: String,
        status: String, note: String?, assignmentId: String?, occurredAt: String,
        canonicalJson: (Long) -> String, now: Long): EnqueuedStatus = db.withTransaction {
        val dao = db.statusOutbox()
        val sequence = (dao.sequence(deviceId) ?: 0L) + 1L
        dao.putSequence(DeviceSequenceEntity(deviceId, sequence))
        dao.insertLocal(LocalStatusEntity(localId, eventId, roleAssignmentId, status, note,
            assignmentId, occurredAt, sequence, "PENDING"))
        dao.insertPending(PendingEventEntity(localId, "STATUS", canonicalJson(sequence),
            sequence, now, nextAttemptAt = now))
        EnqueuedStatus(localId, sequence)
    }
}
