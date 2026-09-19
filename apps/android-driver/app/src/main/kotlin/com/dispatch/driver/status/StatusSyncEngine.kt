package com.dispatch.driver.status

import androidx.room.withTransaction
import com.dispatch.driver.core.auth.AuthorityStore
import com.dispatch.driver.core.database.DispatchDatabase
import com.dispatch.driver.core.database.StatusOutboxDao
import com.dispatch.driver.core.model.LocalSyncState
import com.dispatch.driver.core.network.StatusTransport
import com.dispatch.driver.core.network.StatusUploadOutcome
import com.dispatch.driver.core.network.generated.StatusEventRequest
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

sealed interface SyncPass {
    data object Complete : SyncPass
    data object Retry : SyncPass
}

@Singleton
class StatusSyncEngine @Inject constructor(
    private val database: DispatchDatabase,
    private val dao: StatusOutboxDao,
    private val authorityStore: AuthorityStore,
    private val transport: StatusTransport,
    private val json: Json,
    private val repository: OfflineStatusRepository,
    private val clock: Clock,
) {
    suspend fun sync(): SyncPass {
        database.withTransaction { dao.recoverInterruptedSends() }

        while (true) {
            val event = dao.oldestUnsettled() ?: return SyncPass.Complete
            val now = clock.millis()
            if (event.nextAttemptAt > now) return SyncPass.Retry

            val authority = authorityStore.current()
            if (authority == null) {
                markRetry(
                    localId = event.localId,
                    attemptCount = event.attemptCount,
                    code = "AUTH_REQUIRED",
                    detail = "Sign in to continue syncing this declaration.",
                    now = now,
                )
                return SyncPass.Retry
            }

            database.withTransaction {
                dao.markSending(event.localId)
                repository.updateCachedIfCurrent(event.localId) {
                    it.copy(syncState = LocalSyncState.SENDING.name)
                }
            }

            val request =
                runCatching { json.decodeFromString<StatusEventRequest>(event.canonicalJson) }
                    .getOrElse {
                        database.withTransaction {
                            dao.markRejected(
                                event.localId,
                                now,
                                "LOCAL_EVENT_INVALID",
                                "This local declaration is unreadable. Submit a corrected status.",
                            )
                            repository.updateCachedIfCurrent(event.localId) {
                                it.copy(
                                    syncState = LocalSyncState.REJECTED.name,
                                    rejectionCode = "LOCAL_EVENT_INVALID",
                                    correctiveAction =
                                        "This local declaration is unreadable. Submit a corrected status.",
                                )
                            }
                        }
                        continue
                    }

            when (
                val outcome =
                    transport.submit(
                        accessToken = authority.accessToken,
                        // The authority context is captured with the event. A later
                        // role switch must not retarget an offline declaration.
                        roleAssignmentId = event.roleAssignmentId,
                        idempotencyKey = event.idempotencyKey,
                        request = request,
                    )
            ) {
                is StatusUploadOutcome.Accepted -> {
                    database.withTransaction {
                        dao.markSent(event.localId, clock.millis())
                        repository.updateCachedIfCurrent(
                            localId = event.localId,
                            serverVersion = outcome.response.event.id,
                        ) {
                            it.copy(
                                // A late offline upload may not be the current server
                                // status, so reconcile to current_status, not the input.
                                status = outcome.response.currentStatus.value,
                                occurredAt = outcome.response.currentStatus.occurredAt,
                                source = outcome.response.currentStatus.source,
                                syncState = LocalSyncState.SENT.name,
                                rejectionCode = null,
                                correctiveAction = null,
                            )
                        }
                    }
                }

                is StatusUploadOutcome.Rejected -> {
                    database.withTransaction {
                        dao.markRejected(
                            event.localId,
                            clock.millis(),
                            outcome.problem.code,
                            outcome.problem.detail,
                        )
                        repository.updateCachedIfCurrent(event.localId) {
                            it.copy(
                                syncState = LocalSyncState.REJECTED.name,
                                rejectionCode = outcome.problem.code,
                                // Section 25.2 requires the server's exact corrective
                                // detail rather than a client-side reinterpretation.
                                correctiveAction = outcome.problem.detail,
                            )
                        }
                    }
                }

                is StatusUploadOutcome.Retryable -> {
                    markRetry(
                        localId = event.localId,
                        attemptCount = event.attemptCount + 1,
                        code = outcome.code,
                        detail = outcome.detail,
                        now = clock.millis(),
                    )
                    return SyncPass.Retry
                }
            }
        }
    }

    private suspend fun markRetry(
        localId: String,
        attemptCount: Int,
        code: String,
        detail: String,
        now: Long,
    ) {
        val exponent = attemptCount.coerceIn(0, 10)
        val delayMillis = (15_000L shl exponent).coerceAtMost(MAX_RETRY_DELAY_MILLIS)
        database.withTransaction {
            dao.markRetry(
                localId = localId,
                nextAttemptAt = now + delayMillis,
                code = code,
                detail = detail,
            )
            repository.updateCachedIfCurrent(localId) {
                it.copy(syncState = LocalSyncState.RETRY.name)
            }
        }
    }

    private companion object {
        const val MAX_RETRY_DELAY_MILLIS = 6L * 60L * 60L * 1_000L
    }
}
