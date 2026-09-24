package com.dispatch.driver.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface OutboxDao {

    @Query("SELECT COALESCE(MAX(device_sequence), 0) FROM pending_events")
    suspend fun maxDeviceSequence(): Long

    @Insert
    suspend fun insert(event: PendingEventEntity): Long

    @Query("SELECT * FROM pending_events WHERE state = 'PENDING' AND next_attempt_at <= :now ORDER BY device_sequence")
    suspend fun due(now: Long): List<PendingEventEntity>

    @Query("SELECT MIN(next_attempt_at) FROM pending_events WHERE state = 'PENDING'")
    suspend fun earliestPendingAttempt(): Long?

    @Query("UPDATE pending_events SET state = 'ACKNOWLEDGED', rejection_code = NULL, corrective_action = NULL WHERE local_id = :localId")
    suspend fun acknowledge(localId: Long)

    @Query("UPDATE pending_events SET state = 'REJECTED', rejection_code = :code, corrective_action = :action WHERE local_id = :localId")
    suspend fun reject(localId: Long, code: String, action: String)

    @Query(
        "UPDATE pending_events SET attempt_count = attempt_count + 1, next_attempt_at = :nextAttemptAt, " +
            "corrective_action = :reason WHERE local_id = :localId",
    )
    suspend fun defer(localId: Long, nextAttemptAt: Long, reason: String?)

    @Query("SELECT * FROM pending_events ORDER BY device_sequence DESC")
    fun observeEvents(): Flow<List<PendingEventEntity>>

    @Query("SELECT * FROM cached_driver_state WHERE singleton_id = 0")
    suspend fun driverState(): CachedDriverStateEntity?

    @Query("SELECT * FROM cached_driver_state WHERE singleton_id = 0")
    fun observeDriverState(): Flow<CachedDriverStateEntity?>

    @Upsert
    suspend fun putDriverState(state: CachedDriverStateEntity)

    @Query("SELECT * FROM cached_capability_document WHERE singleton_id = 0")
    suspend fun capabilityDocument(): CachedCapabilityDocumentEntity?

    @Upsert
    suspend fun putCapabilityDocument(document: CachedCapabilityDocumentEntity)

    @Query("DELETE FROM cached_capability_document")
    suspend fun clearCapabilityDocument()
}
