package com.dispatch.driver.status

import androidx.room.withTransaction
import com.dispatch.driver.core.auth.AuthorityStore
import com.dispatch.driver.core.database.CachedDriverStateEntity
import com.dispatch.driver.core.database.DispatchDatabase
import com.dispatch.driver.core.database.PendingEventEntity
import com.dispatch.driver.core.database.PendingEventStates
import com.dispatch.driver.core.database.StatusOutboxDao
import com.dispatch.driver.core.model.DriverStatus
import com.dispatch.driver.core.model.LocalStatus
import com.dispatch.driver.core.model.LocalSyncState
import com.dispatch.driver.core.model.STATUS_DECLARE_SELF_CAPABILITY
import com.dispatch.driver.core.model.StatusRejection
import com.dispatch.driver.core.model.StatusRepository
import com.dispatch.driver.core.model.StatusSubmissionResult
import com.dispatch.driver.core.network.generated.StatusEventRequest
import java.time.Clock
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class CachedStatusPayload(
    val localId: String,
    val status: String,
    val occurredAt: String,
    val source: String,
    val syncState: String,
    val rejectionCode: String? = null,
    val correctiveAction: String? = null,
)

@Singleton
class OfflineStatusRepository @Inject constructor(
    private val database: DispatchDatabase,
    private val dao: StatusOutboxDao,
    private val authorityStore: AuthorityStore,
    private val json: Json,
    private val syncEnqueuer: StatusSyncEnqueuer,
    private val clock: Clock,
) : StatusRepository {
    private val uuidV7 = UuidV7Generator()
    private val instantFormatter: DateTimeFormatter =
        DateTimeFormatterBuilder().appendInstant(3).toFormatter()

    override val currentStatus: Flow<LocalStatus?> =
        dao.observeDriverState().map { it?.json?.let(::decodeLocalStatus) }

    override val latestRejection: Flow<StatusRejection?> =
        dao.observeLatestRejected().map { event ->
            if (event?.lastErrorCode != null && event.lastErrorDetail != null) {
                StatusRejection(
                    localId = event.localId,
                    code = event.lastErrorCode,
                    correctiveAction = event.lastErrorDetail,
                )
            } else {
                null
            }
        }

    override suspend fun declare(status: DriverStatus, note: String?): StatusSubmissionResult {
        val authority =
            authorityStore.current()
                ?: return StatusSubmissionResult.Blocked("Sign in before declaring a status.")
        val roleAssignmentId =
            authority.roleAssignmentId
                ?: return StatusSubmissionResult.Blocked("Select an active role assignment.")
        val deviceId =
            authority.deviceId
                ?: return StatusSubmissionResult.Blocked(
                    "Register this device before declaring a status.",
                )
        if (STATUS_DECLARE_SELF_CAPABILITY !in authority.capabilities) {
            return StatusSubmissionResult.Blocked(
                "The selected assignment does not grant status declaration.",
            )
        }

        val normalizedNote = note?.trim()?.takeIf(String::isNotEmpty)
        if (status.requiresNote && normalizedNote == null) {
            return StatusSubmissionResult.Blocked("${status.label} requires a note.")
        }

        val nowMillis = clock.millis()
        val occurredAt = instantFormatter.format(Instant.ofEpochMilli(nowMillis))
        val localId = uuidV7.generate(nowMillis)

        database.withTransaction {
            val deviceSequence = dao.nextDeviceSequence(deviceId)
            val request =
                StatusEventRequest(
                    eventId = localId,
                    status = status.name,
                    occurredAt = occurredAt,
                    note = normalizedNote,
                    deviceId = deviceId,
                    deviceSequence = deviceSequence,
                )
            dao.insertPending(
                PendingEventEntity(
                    localId = localId,
                    kind = STATUS_EVENT_KIND,
                    canonicalJson = json.encodeToString(request),
                    deviceId = deviceId,
                    deviceSequence = deviceSequence,
                    roleAssignmentId = roleAssignmentId,
                    idempotencyKey = "status:$localId",
                    createdAt = nowMillis,
                    attemptCount = 0,
                    nextAttemptAt = nowMillis,
                    state = PendingEventStates.PENDING,
                ),
            )
            dao.putDriverState(
                CachedDriverStateEntity(
                    json =
                        json.encodeToString(
                            CachedStatusPayload(
                                localId = localId,
                                status = status.name,
                                occurredAt = occurredAt,
                                source = "PARTICIPANT",
                                syncState = LocalSyncState.PENDING.name,
                            ),
                        ),
                    serverVersion = null,
                    fetchedAt = nowMillis,
                ),
            )
        }

        syncEnqueuer.enqueue()
        return StatusSubmissionResult.Queued(localId)
    }

    override suspend fun retry(localId: String): Boolean {
        val changed =
            database.withTransaction {
                val count = dao.requeueRejected(localId, clock.millis())
                if (count == 1) {
                    updateCachedIfCurrent(localId) {
                        it.copy(
                            syncState = LocalSyncState.PENDING.name,
                            rejectionCode = null,
                            correctiveAction = null,
                        )
                    }
                }
                count
            }
        if (changed == 1) {
            syncEnqueuer.enqueue()
            return true
        }
        return false
    }

    internal suspend fun updateCachedIfCurrent(
        localId: String,
        serverVersion: String? = null,
        transform: (CachedStatusPayload) -> CachedStatusPayload,
    ) {
        val cached = dao.getDriverState() ?: return
        val payload =
            runCatching { json.decodeFromString<CachedStatusPayload>(cached.json) }.getOrNull()
                ?: return
        if (payload.localId != localId) return
        dao.putDriverState(
            cached.copy(
                json = json.encodeToString(transform(payload)),
                serverVersion = serverVersion ?: cached.serverVersion,
                fetchedAt = clock.millis(),
            ),
        )
    }

    private fun decodeLocalStatus(raw: String): LocalStatus? {
        val payload =
            runCatching { json.decodeFromString<CachedStatusPayload>(raw) }.getOrNull()
                ?: return null
        val status = runCatching { DriverStatus.valueOf(payload.status) }.getOrNull() ?: return null
        val syncState =
            runCatching { LocalSyncState.valueOf(payload.syncState) }.getOrNull() ?: return null
        return LocalStatus(
            localId = payload.localId,
            status = status,
            occurredAt = payload.occurredAt,
            source = payload.source,
            syncState = syncState,
            rejectionCode = payload.rejectionCode,
            correctiveAction = payload.correctiveAction,
        )
    }

    private companion object {
        const val STATUS_EVENT_KIND = "participant_status"
    }
}
