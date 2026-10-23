package com.dispatch.driver.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dispatch.driver.core.model.device.DeviceKey
import com.dispatch.driver.core.model.device.SigningDevice
import com.dispatch.driver.core.model.location.LocationFix
import com.dispatch.driver.core.model.location.LocationSample
import com.dispatch.driver.core.model.location.SampleTransport
import com.dispatch.driver.core.model.location.SampleVerdict
import com.dispatch.driver.core.model.location.Sharing
import com.dispatch.driver.core.model.location.UploadResult
import com.dispatch.driver.core.model.status.CurrentStatus
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
import java.time.Instant
import kotlin.random.Random

/**
 * Section 25.3's samples in Section 25.2's outbox: stored signed when acquired,
 * sent until the server settles each one, in the device-sequence space the
 * status outbox uses.
 */
@RunWith(RobolectricTestRunner::class)
class LocationOutboxTest {

    private val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), DispatchDatabase::class.java)
        .allowMainThreadQueries()
        .build()

    private var now = 1_000_000L
    private var ids = 0
    private val device = SigningDevice("device-1", DeviceKey.fromSeed(ByteArray(32) { 7 }))
    private val outbox = LocationOutbox(database, clock = { now }, newSampleId = { "sample-${++ids}" }, signer = { device })
    private val sharing = Sharing(assignmentId = "trip-1", consentGrantId = "grant-1")

    private fun fix() = LocationFix(51.5, -0.12, 8.0, null, null, "gps", false, Instant.ofEpochMilli(now))

    @After
    fun close() = database.close()

    /** A server that records each batch and answers from a script. */
    private class Server(var answer: (List<LocationSample>) -> UploadResult) : SampleTransport {
        val batches = mutableListOf<Pair<String, List<LocationSample>>>()
        override suspend fun upload(roleAssignmentId: String, samples: List<LocationSample>): UploadResult {
            batches += roleAssignmentId to samples
            return answer(samples)
        }
    }

    private fun accepting() = Server { batch -> UploadResult.Answered(batch.associate { it.sampleId to SampleVerdict("ACCEPTED", null) }) }

    @Test
    fun `a sample is stored signed, at the next device sequence after any declaration`() = runTest {
        StatusOutbox(database, clock = { now }).declare("AT_PICKUP", null, "ra-1")

        val sample = outbox.acquire(fix(), sharing, "ra-1")!!

        assertEquals(2L, sample.deviceSequence)
        assertEquals("device-1", sample.deviceId)
        assertEquals(1, outbox.pending())
    }

    @Test
    fun `without a registered device nothing is acquired`() = runTest {
        val unregistered = LocationOutbox(database, clock = { now }, signer = { null })
        assertNull(unregistered.acquire(fix(), sharing, "ra-1"))
        assertEquals(0, unregistered.pending())
    }

    @Test
    fun `sync sends what is due, oldest first, at most 25 a batch, as stored`() = runTest {
        val acquired = List(30) { outbox.acquire(fix(), sharing, "ra-1")!!.also { now += 1_000 } }
        val server = accepting()

        assertEquals(SyncOutcome.Drained, outbox.sync(server))

        assertEquals(listOf(25, 5), server.batches.map { it.second.size })
        assertEquals(acquired, server.batches.flatMap { it.second })
        assertTrue(server.batches.all { it.first == "ra-1" })
        assertEquals(0, outbox.pending())
    }

    // Section 24.6: a queued item goes under the authority it was made under,
    // so a batch never mixes assignments, and order is kept across them.
    @Test
    fun `a batch carries one assignment's samples`() = runTest {
        val order = listOf("ra-1", "ra-2", "ra-2", "ra-1").map { ra -> outbox.acquire(fix(), sharing, ra)!! to ra }
        val server = accepting()

        outbox.sync(server)

        assertEquals(listOf("ra-1", "ra-2", "ra-1"), server.batches.map { it.first })
        assertEquals(order.map { it.first }, server.batches.flatMap { it.second })
    }

    @Test
    fun `each sample is settled by its own result`() = runTest {
        val (a, b, c) = List(3) { outbox.acquire(fix(), sharing, "ra-1")!! }
        val server = Server {
            UploadResult.Answered(mapOf(a.sampleId to SampleVerdict("DUPLICATE", null), b.sampleId to SampleVerdict("REJECTED", "REJECTED_CONSENT")))
        }

        assertTrue(outbox.sync(server) is SyncOutcome.RetryAt)

        // c had no answer: kept, and sent again once due, unchanged.
        val retry = accepting()
        now += StatusOutbox.backoffMillis(1)
        assertEquals(SyncOutcome.Drained, outbox.sync(retry))
        assertEquals(listOf(c), retry.batches.single().second)
        assertEquals(listOf("REJECTED_CONSENT"), outbox.rejected())
    }

    @Test
    fun `an unreachable server keeps everything and stops the pass`() = runTest {
        repeat(30) { outbox.acquire(fix(), sharing, "ra-1") }
        val server = Server { UploadResult.Unreachable }

        assertEquals(SyncOutcome.RetryAt(now + StatusOutbox.backoffMillis(1)), outbox.sync(server))

        assertEquals(1, server.batches.size)
        assertEquals(30, outbox.pending())
    }

    @Test
    fun `the status outbox neither sends nor shows samples`() = runTest {
        val status = StatusOutbox(database, clock = { now })
        outbox.acquire(fix(), sharing, "ra-1")
        status.declare("LOADING", null, "ra-1")

        val received = mutableListOf<String>()
        status.sync { declaration, _ ->
            received += declaration.status
            SubmitResult.Stored(CurrentStatus(declaration.status, "PARTICIPANT", declaration.occurredAt))
        }

        assertEquals(listOf("LOADING"), received)
        assertEquals(listOf("LOADING"), status.view().first().events.map { it.status })
        assertEquals(1, outbox.pending())
    }

    // Conservation through the outbox: whatever the server answers, every
    // sample acquired is eventually settled exactly once, every send of it is
    // the same bytes, and only the server's word settles it.
    @Test
    fun `whatever the network does, each sample is settled once and sent unchanged`() = runTest {
        val random = Random(2026_10_23)
        repeat(40) {
            database.clearAllTables()
            val outcomes = listOf("ACCEPTED", "DUPLICATE", "REJECTED", "silent", "unreachable", "500")
            val server = Server { batch ->
                when (val pick = outcomes.random(random)) {
                    "unreachable" -> UploadResult.Unreachable
                    "500" -> UploadResult.Problem(500, null)
                    else -> UploadResult.Answered(
                        batch.filter { random.nextBoolean() || pick != "silent" }
                            .associate { it.sampleId to SampleVerdict(outcomes.take(3).random(random), "CLOCK_SKEW") },
                    )
                }
            }
            val acquired = List(random.nextInt(1, 60)) {
                now += random.nextLong(0, 5_000)
                if (random.nextInt(5) == 0) outbox.sync(server)
                outbox.acquire(fix(), sharing, "ra-1")!!
            }

            // Then the network recovers and time passes.
            server.answer = { batch -> UploadResult.Answered(batch.associate { it.sampleId to SampleVerdict("ACCEPTED", null) }) }
            now += 365L * 24 * 3_600_000
            assertEquals(SyncOutcome.Drained, outbox.sync(server))

            val sent = server.batches.flatMap { it.second }
            assertEquals(acquired.map { it.sampleId }.toSet(), sent.map { it.sampleId }.toSet())
            sent.groupBy { it.sampleId }.values.forEach { copies -> assertEquals(1, copies.toSet().size) }
            assertEquals(0, outbox.pending())
            server.batches.clear()
        }
    }
}
