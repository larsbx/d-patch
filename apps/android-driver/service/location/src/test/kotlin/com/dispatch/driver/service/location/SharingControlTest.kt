package com.dispatch.driver.service.location

import com.dispatch.driver.core.model.location.Sharing
import com.dispatch.driver.core.network.Fetch
import com.dispatch.driver.core.network.LocationConsent
import com.dispatch.driver.core.network.Trip
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

private val granted: java.time.Instant = java.time.Instant.parse("2026-10-23T10:00:00Z")

/**
 * Beginning, stopping and resuming sharing (Section 25.3): consent first, a
 * visible session, and after a restart "resumes only if the persisted consent
 * is valid and the active assignment still exists after server confirmation".
 */
class SharingControlTest {

    private fun consent(id: String, trip: String, revoked: String? = null) = LocationConsent(id, trip, "2026-10-23T10:00:00Z", null, revoked)
    private fun trip(id: String, consent: LocationConsent? = null) = Trip(id, "load-$id", "ACTIVE", "t", null, consent)
    private val record = SharingRecord(assignmentId = "trip-1", consentGrantId = "g-1", roleAssignmentId = "ra-1")

    // ---- The resumption rule, as a function -------------------------------

    @Test
    fun `a confirmed consent resumes, anything else is forgotten or waited on`() {
        val confirmed = Fetch.Ok(listOf(trip("trip-1", consent("g-1", "trip-1"))))
        assertEquals(Resumption.Resume(Sharing("trip-1", "g-1", granted), "ra-1"), Resumption.of(record, confirmed))

        // The grant's start is the server's word, not anything kept here.
        val later = LocationConsent("g-1", "trip-1", "2026-10-23T11:30:00.123456Z", null, null)
        assertEquals(
            Resumption.Resume(Sharing("trip-1", "g-1", java.time.Instant.parse("2026-10-23T11:30:00.123456Z")), "ra-1"),
            Resumption.of(record, Fetch.Ok(listOf(trip("trip-1", later)))),
        )

        assertEquals(Resumption.Forget, Resumption.of(record, Fetch.Ok(emptyList())))
        assertEquals(Resumption.Forget, Resumption.of(record, Fetch.Ok(listOf(trip("trip-1")))))
        assertEquals(Resumption.Forget, Resumption.of(record, Fetch.Ok(listOf(trip("trip-1", consent("g-2", "trip-1"))))))
        assertEquals(Resumption.Wait, Resumption.of(record, Fetch.Unreachable))
        assertEquals(Resumption.Wait, Resumption.of(record, Fetch.Problem(401, null, null)))
        assertEquals(Resumption.Idle, Resumption.of(null, confirmed))
        assertEquals(Resumption.RetryRevoke("g-1"), Resumption.of(record.copy(pendingRevoke = true), confirmed))
    }

    // The property: resuming implies the server named this device's consent,
    // for this trip, unrevoked — and a pending revoke is never resumed.
    @Test
    fun `nothing resumes without the server confirming exactly what was persisted`() {
        val random = Random(2026_10_23)
        val ids = listOf("trip-1", "trip-2")
        val grants = listOf("g-1", "g-2")
        repeat(5_000) {
            val persisted = if (random.nextInt(5) == 0) null else SharingRecord(ids.random(random), grants.random(random), "ra-1", random.nextInt(4) == 0)
            val answer: Fetch<List<Trip>> = when (random.nextInt(4)) {
                0 -> Fetch.Unreachable
                1 -> Fetch.Problem(listOf(401, 403, 500).random(random), null, null)
                else -> Fetch.Ok(
                    ids.filter { random.nextBoolean() }.map { id ->
                        trip(id, if (random.nextBoolean()) consent(grants.random(random), id, if (random.nextBoolean()) "r" else null) else null)
                    },
                )
            }

            val resumption = Resumption.of(persisted, answer)

            if (resumption is Resumption.Resume) {
                val trips = (answer as Fetch.Ok).value
                val named = trips.single { it.assignmentId == persisted!!.assignmentId }.locationConsent!!
                assertEquals(persisted!!.consentGrantId, named.consentGrantId)
                assertNull(named.revokedAt)
                assertTrue(!persisted.pendingRevoke)
            }
            if (answer !is Fetch.Ok && persisted != null && !persisted.pendingRevoke) assertEquals(Resumption.Wait, resumption)
        }
    }

    // ---- The controller, against fakes -------------------------------------

    private class Fakes {
        val log = mutableListOf<String>()
        var stored: SharingRecord? = null
        var revokeAnswer: Fetch<LocationConsent> = Fetch.Ok(LocationConsent("g-1", "trip-1", "2026-10-23T10:00:00Z", null, "now"))
        var tripsAnswer: Fetch<List<Trip>> = Fetch.Unreachable
        var running: Pair<Sharing, String>? = null

        val records = object : SharingRecords {
            override suspend fun read() = stored
            override suspend fun write(record: SharingRecord?) { log += "write ${record?.let { if (it.pendingRevoke) "pending" else "sharing" } ?: "none"}"; stored = record }
        }
        val api = object : ConsentApi {
            override suspend fun begin(roleAssignmentId: String, assignmentId: String, idempotencyKey: String): Fetch<LocationConsent> {
                log += "begin $assignmentId"
                return Fetch.Ok(LocationConsent("g-1", assignmentId, "2026-10-23T10:00:00Z", null, null))
            }
            override suspend fun revoke(roleAssignmentId: String, consentGrantId: String, idempotencyKey: String): Fetch<LocationConsent> {
                log += "revoke $consentGrantId"
                return revokeAnswer
            }
            override suspend fun trips(roleAssignmentId: String) = tripsAnswer
        }
        val runner = object : SharingRunner {
            override fun start(sharing: Sharing, roleAssignmentId: String) { log += "start ${sharing.consentGrantId}"; running = sharing to roleAssignmentId }
            override fun stop() { log += "stop"; running = null }
        }
        val control = SharingControl(api, records, runner, newKey = { "key" })
    }

    @Test
    fun `starting consents, remembers, then shares`() = runTest {
        val f = Fakes()

        assertEquals(StartResult.Sharing, f.control.start(trip("trip-1"), "ra-1"))

        assertEquals(listOf("begin trip-1", "write sharing", "start g-1"), f.log)
        assertEquals(record, f.stored)
    }

    @Test
    fun `a trip already covered shares under that consent without asking again`() = runTest {
        val f = Fakes()
        f.control.start(trip("trip-1", consent("g-1", "trip-1")), "ra-1")
        assertEquals(listOf("write sharing", "start g-1"), f.log)
    }

    @Test
    fun `a revoked consent is never shared under, a new one is asked for`() = runTest {
        val f = Fakes()
        f.control.start(trip("trip-1", consent("g-0", "trip-1", revoked = "r")), "ra-1")
        assertEquals(listOf("begin trip-1", "write sharing", "start g-1"), f.log)
    }

    // Section 25.3: revocation cancels collection synchronously. The local stop
    // comes before any network, and the intent to revoke is on disk first.
    @Test
    fun `stopping stops here first, then tells the server`() = runTest {
        val f = Fakes()
        f.control.start(trip("trip-1"), "ra-1")
        f.log.clear()

        f.control.stop()

        assertEquals(listOf("write pending", "stop", "revoke g-1", "write none"), f.log)
        assertNull(f.running)
    }

    @Test
    fun `a revoke the server did not hear is retried, and sharing is never resumed meanwhile`() = runTest {
        val f = Fakes()
        f.control.start(trip("trip-1"), "ra-1")
        f.revokeAnswer = Fetch.Unreachable
        f.control.stop()
        assertTrue(f.stored!!.pendingRevoke)

        // A restart, with the server still showing the consent as active.
        f.tripsAnswer = Fetch.Ok(listOf(trip("trip-1", consent("g-1", "trip-1"))))
        f.revokeAnswer = Fetch.Ok(consent("g-1", "trip-1", revoked = "now"))
        f.log.clear()
        f.control.resume()

        assertEquals(listOf("revoke g-1", "write none"), f.log)
        assertNull(f.running)
    }

    @Test
    fun `a consent the server no longer has is gone, not an error`() = runTest {
        val f = Fakes()
        f.control.start(trip("trip-1"), "ra-1")
        f.revokeAnswer = Fetch.Problem(404, "NOT_FOUND", null)
        f.control.stop()
        assertNull(f.stored)
    }

    @Test
    fun `after a restart, a confirmed consent resumes and a stale one is forgotten`() = runTest {
        val f = Fakes().apply { stored = record; tripsAnswer = Fetch.Ok(listOf(trip("trip-1", consent("g-1", "trip-1")))) }
        f.control.resume()
        assertEquals(Sharing("trip-1", "g-1", granted) to "ra-1", f.running)

        val g = Fakes().apply { stored = record; tripsAnswer = Fetch.Ok(listOf(trip("trip-1"))) }
        g.control.resume()
        assertNull(g.running)
        assertNull(g.stored)

        val h = Fakes().apply { stored = record; tripsAnswer = Fetch.Unreachable }
        h.control.resume()
        assertNull(h.running)
        assertEquals(record, h.stored)
    }

    @Test
    fun `the server ending consent forgets it here`() = runTest {
        val f = Fakes()
        f.control.start(trip("trip-1"), "ra-1")
        f.control.ended()
        assertNull(f.stored)
    }

    // One session per process: a new one stops the last, and a stale session
    // closing cannot clear the one after it.
    @Test
    fun `sessions hold one live signal`() {
        val sessions = SharingSessions()
        val first = sessions.open()
        val second = sessions.open()

        assertTrue(first.isCompleted)
        assertTrue(sessions.active)
        sessions.close(first)
        assertTrue(sessions.active)

        sessions.stop()
        assertTrue(second.isCompleted)
        assertTrue(!sessions.active)
    }
}
