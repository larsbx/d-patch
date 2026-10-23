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

    // Every read below is of one `kind`: the table is shared so device
    // sequences never repeat, but each outbox decodes only its own rows.

    @Query("SELECT * FROM pending_events WHERE kind = :kind AND state = 'PENDING' AND next_attempt_at <= :now ORDER BY device_sequence")
    suspend fun due(kind: String, now: Long): List<PendingEventEntity>

    @Query("SELECT MIN(next_attempt_at) FROM pending_events WHERE kind = :kind AND state = 'PENDING'")
    suspend fun earliestPendingAttempt(kind: String): Long?

    @Query("SELECT COUNT(*) FROM pending_events WHERE kind = :kind AND state = 'PENDING'")
    suspend fun countPending(kind: String): Int

    @Query("SELECT rejection_code FROM pending_events WHERE kind = :kind AND state = 'REJECTED' ORDER BY device_sequence")
    suspend fun rejectionCodes(kind: String): List<String>

    @Query("UPDATE pending_events SET state = 'ACKNOWLEDGED', rejection_code = NULL, corrective_action = NULL WHERE local_id = :localId")
    suspend fun acknowledge(localId: Long)

    @Query("UPDATE pending_events SET state = 'REJECTED', rejection_code = :code, corrective_action = :action WHERE local_id = :localId")
    suspend fun reject(localId: Long, code: String, action: String)

    @Query(
        "UPDATE pending_events SET attempt_count = attempt_count + 1, next_attempt_at = :nextAttemptAt, " +
            "corrective_action = :reason WHERE local_id = :localId",
    )
    suspend fun defer(localId: Long, nextAttemptAt: Long, reason: String?)

    @Query("UPDATE pending_events SET next_attempt_at = :now, corrective_action = NULL WHERE state = 'PENDING' AND next_attempt_at > :now")
    suspend fun makeDue(now: Long)

    @Query("SELECT * FROM pending_events WHERE kind = :kind ORDER BY device_sequence DESC")
    fun observeEvents(kind: String): Flow<List<PendingEventEntity>>

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
