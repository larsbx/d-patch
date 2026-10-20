package com.dispatch.driver.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dispatch.driver.core.model.status.CurrentStatus
import com.dispatch.driver.core.model.status.StatusDeclaration
import com.dispatch.driver.core.model.status.StatusTransport
import com.dispatch.driver.core.model.status.SubmitResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Section 25.2 against a real Room database. Section 35 requires "Android
 * offline and retry behavior is tested", and Slice 1's exit criterion asks for
 * the status flow to pass "after an offline interval" — which is the scenario
 * the last test here walks through end to end.
 */
@RunWith(RobolectricTestRunner::class)
class StatusOutboxTest {

    private val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), DispatchDatabase::class.java)
        .allowMainThreadQueries()
        .build()

    private var now = 1_000_000L
    private var ids = 0
    private val outbox = StatusOutbox(database, clock = { now }, newEventId = { "event-${++ids}" })

    @After
    fun close() = database.close()

    /** A server that records what it received and answers from a script. */
    private class Server(var answer: (StatusDeclaration) -> SubmitResult) : StatusTransport {
        val received = mutableListOf<Pair<StatusDeclaration, String>>()
        override suspend fun submit(declaration: StatusDeclaration, roleAssignmentId: String): SubmitResult {
            received += declaration to roleAssignmentId
            return answer(declaration)
        }
    }

    private fun stored(declaration: StatusDeclaration) =
        SubmitResult.Stored(CurrentStatus(declaration.status, "PARTICIPANT", declaration.occurredAt))

    private suspend fun events() = outbox.view().first().events

    @Test
    fun `a declaration is queued at once with the next device sequence`() = runTest {
        outbox.declare("AT_PICKUP", note = null, roleAssignmentId = "ra-1")
        outbox.declare("LOADING", note = " ", roleAssignmentId = "ra-1")

        val queued = events()
        assertEquals(listOf("LOADING", "AT_PICKUP"), queued.map { it.status })
        assertTrue(queued.all { it.state == PendingState.PENDING })
        // A blank note is no note: Section 24.1 wants nonblank where one is required.
        assertNull(queued.first().note)
        assertEquals(listOf(2L, 1L), database.outbox().observeEvents().first().map { it.deviceSequence })
    }

    @Test
    fun `device sequences never repeat, across restarts and after acknowledgement`() = runTest {
        outbox.declare("AT_PICKUP", null, "ra-1")
        outbox.sync(Server(::stored))

        val restarted = StatusOutbox(database, clock = { now }, newEventId = { "after-restart" })
        restarted.declare("LOADING", null, "ra-1")

        assertEquals(listOf(2L, 1L), database.outbox().observeEvents().first().map { it.deviceSequence })
    }

    @Test
    fun `an acknowledged event records the server's current status`() = runTest {
        outbox.declare("AT_PICKUP", null, "ra-1")

        assertEquals(SyncOutcome.Drained, outbox.sync(Server(::stored)))

        val view = outbox.view().first()
        assertEquals(PendingState.ACKNOWLEDGED, view.events.single().state)
        assertEquals("AT_PICKUP", view.acknowledged?.value)
        assertEquals("PARTICIPANT", view.acknowledged?.source)
    }

    @Test
    fun `each event is sent under the assignment it was declared under, byte for byte`() = runTest {
        outbox.declare("AT_PICKUP", null, "ra-1")
        outbox.declare("LOADING", null, "ra-2")
        val server = Server(::stored)

        outbox.sync(server)

        assertEquals(listOf("ra-1", "ra-2"), server.received.map { it.second })
        assertEquals(listOf("event-1", "event-2"), server.received.map { it.first.eventId })
    }

    @Test
    fun `a rejection shows its corrective action and does not block later events`() = runTest {
        outbox.declare("DELAYED", "Traffic", "ra-revoked")
        outbox.declare("LOADING", null, "ra-1")
        val server = Server { d ->
            if (d.status == "DELAYED") SubmitResult.Problem(403, "ROLE_ASSIGNMENT_INVALID", null) else stored(d)
        }

        assertEquals(SyncOutcome.Drained, outbox.sync(server))

        val (loading, delayed) = events()
        assertEquals(PendingState.ACKNOWLEDGED, loading.state)
        assertEquals(PendingState.REJECTED, delayed.state)
        assertEquals("ROLE_ASSIGNMENT_INVALID", delayed.rejectionCode)
        assertTrue(delayed.correctiveAction!!.contains("Choose your role again"))
    }

    @Test
    fun `an unreachable server defers the event with growing backoff and stops the pass`() = runTest {
        outbox.declare("AT_PICKUP", null, "ra-1")
        outbox.declare("LOADING", null, "ra-1")
        val server = Server { SubmitResult.Unreachable }

        // One attempt, then the pass stops: LOADING would meet the same network.
        assertEquals(SyncOutcome.RetryAt(now), outbox.sync(server))
        assertEquals(listOf("AT_PICKUP"), server.received.map { it.first.status })

        // AT_PICKUP is backing off, so only LOADING is due.
        server.received.clear()
        outbox.sync(server)
        assertEquals(listOf("LOADING"), server.received.map { it.first.status })

        assertEquals(listOf(1, 1), events().map { it.attemptCount })
        assertEquals(30_000L, StatusOutbox.backoffMillis(1))
        assertEquals(60_000L, StatusOutbox.backoffMillis(2))
        assertEquals(30 * 60_000L, StatusOutbox.backoffMillis(50))
    }

    @Test
    fun `an expired session keeps events queued and says how to send them`() = runTest {
        outbox.declare("AT_PICKUP", null, "ra-1")

        outbox.sync(Server { SubmitResult.Problem(401, "UNAUTHENTICATED", null) })

        val event = events().single()
        assertEquals(PendingState.PENDING, event.state)
        assertEquals(1, event.attemptCount)
        assertEquals("Sign in again to send queued updates.", event.correctiveAction)
    }

    @Test
    fun `a late acknowledgement cannot overwrite a newer one`() = runTest {
        outbox.declare("AT_PICKUP", null, "ra-1")
        outbox.declare("LOADING", null, "ra-1")

        // AT_PICKUP fails and backs off; LOADING goes through meanwhile.
        var first = true
        outbox.sync(Server { d -> if (first) SubmitResult.Unreachable.also { first = false } else stored(d) })
        outbox.sync(Server(::stored))
        assertEquals("LOADING", outbox.view().first().acknowledged?.value)

        // AT_PICKUP is acknowledged later, carrying its own older answer.
        now += StatusOutbox.backoffMillis(1)
        outbox.sync(Server(::stored))

        assertTrue(events().all { it.state == PendingState.ACKNOWLEDGED })
        assertEquals("LOADING", outbox.view().first().acknowledged?.value)
    }

    @Test
    fun `offline interval - declarations queue while offline and drain in order once online`() = runTest {
        var online = false
        val server = Server { d -> if (online) stored(d) else SubmitResult.Unreachable }

        outbox.declare("EN_ROUTE_PICKUP", null, "ra-1")
        now += 60_000
        outbox.declare("AT_PICKUP", null, "ra-1")
        now += 60_000
        outbox.declare("LOADING", null, "ra-1")

        // Offline: the first attempt fails and nothing is lost or reordered.
        assertTrue(outbox.sync(server) is SyncOutcome.RetryAt)
        assertTrue(events().all { it.state == PendingState.PENDING })

        // Back online after the backoff: everything drains, oldest first, and
        // each declaration keeps the time it was made, not the time it was sent.
        online = true
        now += StatusOutbox.backoffMillis(1)
        server.received.clear()

        assertEquals(SyncOutcome.Drained, outbox.sync(server))

        assertEquals(listOf("EN_ROUTE_PICKUP", "AT_PICKUP", "LOADING"), server.received.map { it.first.status })
        assertEquals(
            listOf(1_000_000L, 1_060_000L, 1_120_000L).map { java.time.Instant.ofEpochMilli(it).toString() },
            server.received.map { it.first.occurredAt },
        )
        assertEquals(listOf(1L, 2L, 3L), server.received.map { it.first.deviceSequence })
        assertEquals("LOADING", outbox.view().first().acknowledged?.value)
    }
}
