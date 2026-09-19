package com.dispatch.driver.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface StatusOutboxDao {
    @Query("SELECT * FROM cached_driver_state WHERE singleton_id = 1")
    fun observeDriverState(): Flow<CachedDriverStateEntity?>

    @Query("SELECT * FROM cached_driver_state WHERE singleton_id = 1")
    suspend fun getDriverState(): CachedDriverStateEntity?

    @Query(
        """
        SELECT * FROM pending_events
        WHERE state = 'REJECTED'
        ORDER BY device_sequence DESC, created_at DESC
        LIMIT 1
        """,
    )
    fun observeLatestRejected(): Flow<PendingEventEntity?>

    @Query(
        """
        SELECT COALESCE(MAX(device_sequence), 0) + 1
        FROM pending_events
        WHERE device_id = :deviceId
        """,
    )
    suspend fun nextDeviceSequence(deviceId: String): Long

    @Insert
    suspend fun insertPending(event: PendingEventEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putDriverState(state: CachedDriverStateEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putAssignment(assignment: CachedAssignmentEntity)

    @Query(
        """
        SELECT * FROM pending_events
        WHERE state IN ('PENDING', 'RETRY')
        ORDER BY device_sequence ASC, created_at ASC
        LIMIT 1
        """,
    )
    suspend fun oldestUnsettled(): PendingEventEntity?

    @Query("UPDATE pending_events SET state = 'RETRY' WHERE state = 'SENDING'")
    suspend fun recoverInterruptedSends(): Int

    @Query(
        """
        UPDATE pending_events
        SET state = 'SENDING',
            attempt_count = attempt_count + 1,
            last_error_code = NULL,
            last_error_detail = NULL
        WHERE local_id = :localId
        """,
    )
    suspend fun markSending(localId: String)

    @Query(
        """
        UPDATE pending_events
        SET state = 'SENT',
            next_attempt_at = :at,
            last_error_code = NULL,
            last_error_detail = NULL
        WHERE local_id = :localId
        """,
    )
    suspend fun markSent(localId: String, at: Long)

    @Query(
        """
        UPDATE pending_events
        SET state = 'RETRY',
            next_attempt_at = :nextAttemptAt,
            last_error_code = :code,
            last_error_detail = :detail
        WHERE local_id = :localId
        """,
    )
    suspend fun markRetry(localId: String, nextAttemptAt: Long, code: String, detail: String)

    @Query(
        """
        UPDATE pending_events
        SET state = 'REJECTED',
            next_attempt_at = :at,
            last_error_code = :code,
            last_error_detail = :detail
        WHERE local_id = :localId
        """,
    )
    suspend fun markRejected(localId: String, at: Long, code: String, detail: String)

    @Query(
        """
        UPDATE pending_events
        SET state = 'PENDING',
            next_attempt_at = :at,
            last_error_code = NULL,
            last_error_detail = NULL
        WHERE local_id = :localId AND state = 'REJECTED'
        """,
    )
    suspend fun requeueRejected(localId: String, at: Long): Int

    @Query("SELECT * FROM pending_events WHERE local_id = :localId LIMIT 1")
    suspend fun find(localId: String): PendingEventEntity?
}
