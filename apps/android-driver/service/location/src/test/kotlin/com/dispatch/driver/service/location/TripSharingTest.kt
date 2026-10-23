package com.dispatch.driver.service.location

import com.dispatch.driver.core.model.location.Sharing
import com.dispatch.driver.core.network.Fetch
import com.dispatch.driver.core.network.LocationConsent
import com.dispatch.driver.core.network.Trip
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

private val granted: java.time.Instant = java.time.Instant.parse("2026-10-23T10:00:00Z")

/** What the status screen offers about location, and what its buttons do. */
@OptIn(ExperimentalCoroutinesApi::class)
class TripSharingTest {

    private fun trip(id: String, status: String = "PLANNED", consent: LocationConsent? = null) = Trip(id, "load-$id", status, "t", null, consent)
    private val record = SharingRecord("trip-1", "g-1", granted, "ra-1")

    @Test
    fun `the card follows the capability, the trips and the session`() {
        val trips = Fetch.Ok(listOf(trip("trip-1"), trip("trip-2", "ACTIVE")))
        assertEquals(SharingCard.Hidden, SharingCard.of(offered = false, trips, null, active = false))
        assertEquals(SharingCard.Checking, SharingCard.of(true, null, null, false))
        assertEquals(SharingCard.Unavailable, SharingCard.of(true, Fetch.Unreachable, null, false))
        assertEquals(SharingCard.NoTrip, SharingCard.of(true, Fetch.Ok(emptyList()), null, false))
        // The trip under way is offered before one still to come.
        assertEquals(SharingCard.Offer(trip("trip-2", "ACTIVE")), SharingCard.of(true, trips, null, false))
        assertEquals(SharingCard.Sharing, SharingCard.of(true, trips, record, active = true))
        assertEquals(SharingCard.Stopping, SharingCard.of(true, trips, record.copy(pendingRevoke = true), false))
    }

    // Section 2.2: no hidden collection. Whatever else is true, a running
    // session shows itself with Stop sharing, even if the role no longer offers
    // the feature or the trips cannot be read.
    @Test
    fun `a running session is never hidden`() {
        val random = Random(2026_10_23)
        val answers = listOf(null, Fetch.Unreachable, Fetch.Problem(403, null, null), Fetch.Ok(emptyList()), Fetch.Ok(listOf(trip("trip-1"))))
        repeat(1_000) {
            val card = SharingCard.of(random.nextBoolean(), answers.random(random), if (random.nextBoolean()) record else null, active = true)
            assertEquals(SharingCard.Sharing, card)
        }
    }

    private class Fakes {
        val trips = MutableStateFlow<Fetch<List<Trip>>>(Fetch.Ok(listOf(Trip("trip-1", "load-trip-1", "ACTIVE", "t", null, null))))
        var stored: SharingRecord? = null
        val sessions = SharingSessions()
        var revokeAnswer: Fetch<LocationConsent> = Fetch.Ok(LocationConsent("g-1", "trip-1", "2026-10-23T10:00:00Z", null, "now"))
        val api = object : ConsentApi {
            override suspend fun begin(roleAssignmentId: String, assignmentId: String, idempotencyKey: String) =
                Fetch.Ok(LocationConsent("g-1", assignmentId, "2026-10-23T10:00:00Z", null, null))
            override suspend fun revoke(roleAssignmentId: String, consentGrantId: String, idempotencyKey: String) = revokeAnswer
            override suspend fun trips(roleAssignmentId: String) = trips.value
        }
        val records = object : SharingRecords {
            override suspend fun read() = stored
            override suspend fun write(record: SharingRecord?) { stored = record }
        }
        val runner = object : SharingRunner {
            override fun start(sharing: Sharing, roleAssignmentId: String) { sessions.open() }
            override fun stop() = sessions.stop()
        }
        val offered = MutableStateFlow<String?>("ra-1")
        val control = SharingControl(api, records, runner)
    }

    @Test
    fun `share consents for the offered trip, and stop stops it`() = runTest {
        val f = Fakes()
        val model = TripSharing(backgroundScope, f.offered, f.api, f.records, f.sessions, f.control)
        model.refresh()
        runCurrent()
        assertEquals(SharingCard.Offer(trip("trip-1", "ACTIVE")), model.state.value)

        model.share()
        runCurrent()
        assertEquals(SharingCard.Sharing, model.state.value)
        assertEquals(SharingRecord("trip-1", "g-1", granted, "ra-1"), f.stored)

        model.stop()
        runCurrent()
        assertTrue(!f.sessions.active)
        assertEquals(null, f.stored)
        assertTrue(model.state.value is SharingCard.Offer)
    }

    // Stopped here but not yet at the server: say so, and do not offer to
    // share again over a consent the server still holds.
    @Test
    fun `a stop the server has not heard shows as stopping`() = runTest {
        val f = Fakes().apply { revokeAnswer = Fetch.Unreachable }
        val model = TripSharing(backgroundScope, f.offered, f.api, f.records, f.sessions, f.control)
        model.refresh()
        runCurrent()
        model.share()
        runCurrent()

        model.stop()
        runCurrent()

        assertEquals(SharingCard.Stopping, model.state.value)
    }

    // First sign-in: the screen opens while the capability document is still
    // loading. When the feature arrives, the trips are read without anyone asking.
    @Test
    fun `trips are read when the capability arrives`() = runTest {
        val f = Fakes().apply { offered.value = null }
        val model = TripSharing(backgroundScope, f.offered, f.api, f.records, f.sessions, f.control)
        model.refresh()
        runCurrent()
        assertEquals(SharingCard.Hidden, model.state.value)

        f.offered.value = "ra-1"
        runCurrent()

        assertEquals(SharingCard.Offer(trip("trip-1", "ACTIVE")), model.state.value)
    }

    @Test
    fun `without the feature there is nothing to show, and nothing to share`() = runTest {
        val f = Fakes().apply { offered.value = null }
        val model = TripSharing(backgroundScope, f.offered, f.api, f.records, f.sessions, f.control)
        model.refresh()
        model.share()
        runCurrent()
        assertEquals(SharingCard.Hidden, model.state.value)
        assertEquals(null, f.stored)
    }
}
