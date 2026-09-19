package com.dispatch.driver

import com.dispatch.driver.core.auth.SessionProvider
import com.dispatch.driver.core.database.PendingEventEntity
import com.dispatch.driver.core.database.StatusOutboxDao
import com.dispatch.driver.core.model.RoleContext
import com.dispatch.driver.core.network.StatusApi
import com.dispatch.driver.core.network.StatusEventRequest
import com.dispatch.driver.core.network.StatusEventResponse
import com.dispatch.driver.core.network.StoredStatusEvent
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.Response

class StatusSyncRunnerTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun drainsRowsBeyondTheFirstTwentyFiveAndKeepsCapturedRoleContext() = runTest {
        val dao = mockk<StatusOutboxDao>()
        val api = mockk<StatusApi>()
        val firstPage = (1..25).map(::pending)
        val secondPage = listOf(pending(26))

        coEvery { dao.reclaimInterruptedClaims() } returns 0
        coEvery { dao.ready(1_000L, 25) } returnsMany
            listOf(firstPage, secondPage, emptyList())
        coEvery { dao.claim(any()) } returns 1
        coEvery { dao.roleAssignmentId(any()) } returns "captured-role"
        coEvery { dao.synced(any()) } returns Unit
        coEvery { dao.deletePending(any()) } returns Unit
        coEvery {
            api.create("Bearer token", "captured-role", any(), any())
        } returns Response.success(StatusEventResponse(StoredStatusEvent("stored")))

        val result = StatusSyncRunner(
            dao = dao,
            sessions = FakeSessionProvider("token"),
            api = api,
            json = json,
            nowMillis = { 1_000L },
        ).run()

        assertEquals(SyncRunResult.SUCCESS, result)
        coVerify(exactly = 3) { dao.ready(1_000L, 25) }
        coVerify(exactly = 26) {
            api.create("Bearer token", "captured-role", any(), any())
        }
    }

    @Test fun reclaimsInterruptedSendingRowsBeforeWaitingForAuthentication() = runTest {
        val dao = mockk<StatusOutboxDao>()
        val api = mockk<StatusApi>()

        coEvery { dao.reclaimInterruptedClaims() } returns 1

        val result = StatusSyncRunner(
            dao = dao,
            sessions = FakeSessionProvider(null),
            api = api,
            json = json,
            nowMillis = { 1_000L },
        ).run()

        assertEquals(SyncRunResult.RETRY, result)
        coVerify(exactly = 1) { dao.reclaimInterruptedClaims() }
        coVerify(exactly = 0) { dao.ready(any(), any()) }
    }

    private fun pending(index: Int): PendingEventEntity {
        val request = StatusEventRequest(
            eventId = "event-$index",
            status = "AVAILABLE",
            occurredAt = "2026-09-19T00:00:00Z",
            deviceId = "device",
            deviceSequence = index.toLong(),
        )
        return PendingEventEntity(
            localId = "local-$index",
            kind = "STATUS",
            canonicalJson = json.encodeToString(request),
            deviceSequence = index.toLong(),
            createdAt = 0L,
            nextAttemptAt = 0L,
        )
    }

    private class FakeSessionProvider(private val token: String?) : SessionProvider {
        override suspend fun bearerToken(): String? = token
        override fun activeRole(): RoleContext? = RoleContext("current-role", emptySet())
        override fun deviceId(): String = "device"
    }
}
