package com.dispatch.driver.status

import androidx.room.Room
import com.dispatch.driver.core.auth.AuthoritySnapshot
import com.dispatch.driver.core.auth.AuthorityStore
import com.dispatch.driver.core.database.DispatchDatabase
import com.dispatch.driver.core.model.DriverStatus
import com.dispatch.driver.core.model.LocalSyncState
import com.dispatch.driver.core.model.STATUS_DECLARE_SELF_CAPABILITY
import com.dispatch.driver.core.model.StatusSubmissionResult
import com.dispatch.driver.core.network.StatusTransport
import com.dispatch.driver.core.network.StatusUploadOutcome
import com.dispatch.driver.core.network.generated.CurrentStatus
import com.dispatch.driver.core.network.generated.ProblemDetail
import com.dispatch.driver.core.network.generated.StatusEventRequest
import com.dispatch.driver.core.network.generated.StatusEventResponse
import com.dispatch.driver.core.network.generated.StoredStatusEvent
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class OfflineStatusSyncTest {
    private lateinit var database: DispatchDatabase
    private lateinit var authority: FakeAuthorityStore
    private lateinit var enqueuer: FakeEnqueuer
    private lateinit var repository: OfflineStatusRepository
    private val json = Json { ignoreUnknownKeys = true }
    private val clock =
        Clock.fixed(Instant.parse("2026-09-19T10:00:00Z"), ZoneOffset.UTC)

    @Before
    fun setUp() {
        database =
            Room.inMemoryDatabaseBuilder(
                RuntimeEnvironment.getApplication(),
                DispatchDatabase::class.java,
            ).allowMainThreadQueries().build()
        authority = FakeAuthorityStore(snapshot(roleAssignmentId = "role-original"))
        enqueuer = FakeEnqueuer()
        repository =
            OfflineStatusRepository(
                database = database,
                dao = database.statusOutboxDao(),
                authorityStore = authority,
                json = json,
                syncEnqueuer = enqueuer,
                clock = clock,
            )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun declarationIsOptimisticAndTerminalRejectionKeepsExactDetail() = runTest {
        val queued = repository.declare(DriverStatus.AT_PICKUP, null)
        assertTrue(queued is StatusSubmissionResult.Queued)
        assertEquals(LocalSyncState.PENDING, repository.currentStatus.first()?.syncState)
        assertEquals(1, enqueuer.calls)

        val detail = "This role assignment cannot declare a status."
        val transport =
            FakeTransport(
                StatusUploadOutcome.Rejected(
                    ProblemDetail(
                        type = "about:blank",
                        title = "Not permitted",
                        status = 403,
                        detail = detail,
                        instance = "/v1/me/status-events",
                        code = "FORBIDDEN",
                        correlationId = "corr",
                    ),
                ),
            )

        val engine = engine(transport)
        assertEquals(SyncPass.Complete, engine.sync())

        val localId = (queued as StatusSubmissionResult.Queued).localId
        val row = database.statusOutboxDao().find(localId)
        assertEquals("REJECTED", row?.state)
        assertEquals(detail, row?.lastErrorDetail)
        assertEquals(detail, repository.latestRejection.first()?.correctiveAction)
        assertEquals(LocalSyncState.REJECTED, repository.currentStatus.first()?.syncState)
    }

    @Test
    fun queuedEventKeepsOriginalAuthorityAndReconcilesToServerCurrentStatus() = runTest {
        val queued = repository.declare(DriverStatus.AT_PICKUP, null)
        assertTrue(queued is StatusSubmissionResult.Queued)

        // Switching the UI's selected assignment after queueing must not retarget
        // this already-created declaration.
        authority.save(snapshot(roleAssignmentId = "role-new"))

        val transport =
            FakeTransport(
                StatusUploadOutcome.Accepted(
                    StatusEventResponse(
                        event =
                            StoredStatusEvent(
                                id = (queued as StatusSubmissionResult.Queued).localId,
                                participantId = "participant",
                                roleAssignmentId = "role-original",
                                status = "AT_PICKUP",
                                source = "PARTICIPANT",
                                occurredAt = "2026-09-19T10:00:00.000Z",
                                recordedAt = "2026-09-19T10:00:01.000Z",
                                verification = "NONE",
                            ),
                        // Simulate a newer declaration already on the server. The
                        // late offline upload is accepted but must not become current.
                        currentStatus =
                            CurrentStatus(
                                value = "AT_DELIVERY",
                                source = "PARTICIPANT",
                                roleKey = "ANY_PROFILE_LABEL",
                                occurredAt = "2026-09-19T10:05:00.000Z",
                            ),
                    ),
                ),
            )

        assertEquals(SyncPass.Complete, engine(transport).sync())
        assertEquals("role-original", transport.lastRoleAssignmentId)
        assertEquals(DriverStatus.AT_DELIVERY, repository.currentStatus.first()?.status)
        assertEquals(LocalSyncState.SENT, repository.currentStatus.first()?.syncState)
    }

    private fun engine(transport: StatusTransport) =
        StatusSyncEngine(
            database = database,
            dao = database.statusOutboxDao(),
            authorityStore = authority,
            transport = transport,
            json = json,
            repository = repository,
            clock = clock,
        )

    private fun snapshot(roleAssignmentId: String) =
        AuthoritySnapshot(
            accessToken = "token",
            roleAssignmentId = roleAssignmentId,
            deviceId = "018f0000-0000-7000-8000-000000000004",
            capabilities = setOf(STATUS_DECLARE_SELF_CAPABILITY),
        )

    private class FakeAuthorityStore(initial: AuthoritySnapshot) : AuthorityStore {
        private val mutable = MutableStateFlow<AuthoritySnapshot?>(initial)
        override val authority: StateFlow<AuthoritySnapshot?> = mutable

        override fun current(): AuthoritySnapshot? = mutable.value

        override fun save(snapshot: AuthoritySnapshot) {
            mutable.value = snapshot
        }

        override fun clear() {
            mutable.value = null
        }
    }

    private class FakeEnqueuer : StatusSyncEnqueuer {
        var calls = 0
        override fun enqueue() {
            calls += 1
        }
    }

    private class FakeTransport(
        private val outcome: StatusUploadOutcome,
    ) : StatusTransport {
        var lastRoleAssignmentId: String? = null

        override suspend fun submit(
            accessToken: String,
            roleAssignmentId: String,
            idempotencyKey: String,
            request: StatusEventRequest,
        ): StatusUploadOutcome {
            lastRoleAssignmentId = roleAssignmentId
            return outcome
        }
    }
}
