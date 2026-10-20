package com.dispatch.driver.core.database

import androidx.room.withTransaction
import com.dispatch.driver.core.model.UuidV7
import com.dispatch.driver.core.model.status.CurrentStatus
import com.dispatch.driver.core.model.status.Disposition
import com.dispatch.driver.core.model.status.StatusDeclaration
import com.dispatch.driver.core.model.status.StatusTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.json.Json
import java.time.Instant

/**
 * Section 25.2's offline outbox for status declarations.
 *
 * "Status submission writes the local event and pending event in one Room
 * transaction, immediately updates the UI, and schedules `SyncWorker`." Here:
 *
 * - [declare] allocates the next device sequence and inserts the pending row
 *   in one transaction, so two declarations can never share a sequence and a
 *   crash cannot leave a sequence allocated with nothing behind it.
 * - The UI updates because [view] is a Flow over that table: the new row is
 *   the optimistic state, with no second copy to keep consistent.
 * - Scheduling the worker is the caller's, through the `SyncRequester` port,
 *   so this class has no WorkManager dependency to fake in tests.
 */
class StatusOutbox(
    private val database: DispatchDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newEventId: () -> String = { UuidV7.generate().toString() },
) {
    private val dao = database.outbox()

    /** Queues a declaration. Validation against the capability document is the caller's. */
    suspend fun declare(status: String, note: String?, roleAssignmentId: String): Long {
        val now = clock()
        return database.withTransaction {
            val sequence = dao.maxDeviceSequence() + 1
            val declaration = StatusDeclaration(
                eventId = newEventId(),
                status = status,
                occurredAt = Instant.ofEpochMilli(now).toString(),
                note = note?.takeIf { it.isNotBlank() },
                deviceSequence = sequence,
            )
            dao.insert(
                PendingEventEntity(
                    kind = PendingKind.STATUS.name,
                    canonicalJson = declaration.toCanonicalJson(),
                    deviceSequence = sequence,
                    createdAt = now,
                    nextAttemptAt = now,
                    state = PendingState.PENDING.name,
                    roleAssignmentId = roleAssignmentId,
                ),
            )
        }
    }

    /**
     * Sends every due event, oldest sequence first, once.
     *
     * A rejection settles its event and the loop moves on: one bad declaration
     * must not hold the rest hostage. An unanswered or transient failure defers
     * its event and stops the pass, because the next one would meet the same
     * network.
     */
    suspend fun sync(transport: StatusTransport): SyncOutcome {
        for (event in dao.due(clock())) {
            val declaration = StatusDeclaration.fromCanonicalJson(event.canonicalJson)
            when (val disposition = Disposition.of(transport.submit(declaration, event.roleAssignmentId))) {
                is Disposition.Acknowledge -> acknowledge(event, disposition.current)
                is Disposition.Reject -> dao.reject(event.localId, disposition.code, disposition.correctiveAction)
                is Disposition.RetryLater -> {
                    dao.defer(event.localId, clock() + backoffMillis(event.attemptCount + 1), disposition.reason)
                    break
                }
            }
        }
        return dao.earliestPendingAttempt()?.let(SyncOutcome::RetryAt) ?: SyncOutcome.Drained
    }

    /** What the status screen renders: the server's view plus everything local. */
    fun view(): Flow<OutboxView> =
        dao.observeDriverState().combine(dao.observeEvents()) { state, events ->
            OutboxView(
                acknowledged = state?.let { json.decodeFromString(CurrentStatus.serializer(), it.json) },
                acknowledgedAt = state?.fetchedAt,
                events = events.map { it.toEntry() },
            )
        }

    private suspend fun acknowledge(event: PendingEventEntity, current: CurrentStatus) =
        database.withTransaction {
            dao.acknowledge(event.localId)
            val cached = dao.driverState()
            if (cached == null || cached.serverVersion < event.deviceSequence) {
                dao.putDriverState(
                    CachedDriverStateEntity(
                        json = json.encodeToString(CurrentStatus.serializer(), current),
                        serverVersion = event.deviceSequence,
                        fetchedAt = clock(),
                    ),
                )
            }
        }

    private fun PendingEventEntity.toEntry(): OutboxEntry {
        val declaration = StatusDeclaration.fromCanonicalJson(canonicalJson)
        return OutboxEntry(
            localId = localId,
            status = declaration.status,
            note = declaration.note,
            occurredAt = declaration.occurredAt,
            state = PendingState.valueOf(state),
            attemptCount = attemptCount,
            rejectionCode = rejectionCode,
            correctiveAction = correctiveAction,
        )
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        private const val BASE_BACKOFF_MILLIS = 30_000L
        private const val MAX_BACKOFF_MILLIS = 30 * 60_000L

        /** 30 s doubling to a 30 min ceiling; WorkManager applies its own on top. */
        fun backoffMillis(attempt: Int): Long =
            (BASE_BACKOFF_MILLIS shl (attempt - 1).coerceIn(0, 16)).coerceAtMost(MAX_BACKOFF_MILLIS)
    }
}

sealed interface SyncOutcome {
    /** Nothing left pending. */
    data object Drained : SyncOutcome

    /** Something is still pending; the earliest may be retried at [epochMillis]. */
    data class RetryAt(val epochMillis: Long) : SyncOutcome
}

/** One queued, sent, or refused declaration, as the screen needs it. */
data class OutboxEntry(
    val localId: Long,
    val status: String,
    val note: String?,
    val occurredAt: String,
    val state: PendingState,
    val attemptCount: Int,
    val rejectionCode: String?,
    val correctiveAction: String?,
)

data class OutboxView(
    val acknowledged: CurrentStatus?,
    val acknowledgedAt: Long?,
    val events: List<OutboxEntry>,
)
