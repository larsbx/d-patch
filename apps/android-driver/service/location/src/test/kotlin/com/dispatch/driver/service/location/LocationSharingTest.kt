package com.dispatch.driver.service.location

import com.dispatch.driver.core.model.location.DemandPolicy
import com.dispatch.driver.core.model.location.LocationFix
import com.dispatch.driver.core.network.DemandEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The sharing session on a device (Section 25.3, ADR-0014), on virtual time:
 * `SharingCadence` decides, and this runs its effects against the location
 * provider, the outbox and the demand stream, here faked.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocationSharingTest {

    private val realtime = DemandPolicy(DemandPolicy.Mode.REALTIME, 3_000, 0)

    /** The location provider: what interval it was asked for, and whether it is delivering. */
    private class Provider : FixSource {
        val requested = mutableListOf<Long>()
        var active = 0
        val fixes = MutableSharedFlow<LocationFix>()
        override fun fixes(intervalMs: Long): Flow<LocationFix> =
            fixes.onStart { requested += intervalMs; active++ }.onCompletion { active-- }
    }

    /** The outbox: what was stored, and how often it was asked to send. */
    private class Store : SampleStore {
        val acquired = mutableListOf<LocationFix>()
        var syncs = 0
        var onSync: suspend () -> Unit = {}
        override suspend fun acquire(fix: LocationFix) = true.also { acquired += fix }
        override suspend fun sync() {
            syncs++
            onSync()
        }
    }

    /** The demand stream: each connection plays the next script, or stays open quietly. */
    private class Demand(vararg scripts: List<DemandEvent>) : DemandSource {
        val pending = scripts.toMutableList()
        var connections = 0
        override fun connect(): Flow<DemandEvent> = flow {
            connections++
            pending.removeFirstOrNull()?.forEach { emit(it) } ?: awaitCancellation()
        }
    }

    private fun fix(at: Long) = LocationFix(51.5, -0.12, 5.0, null, null, "gps", false, Instant.ofEpochMilli(at))

    private class Session(val provider: Provider, val store: Store, val demand: Demand, val revoke: CompletableDeferred<Unit>, val foreground: MutableSharedFlow<Unit>)

    private fun TestScope.start(
        demand: Demand = Demand(),
        source: DemandSource = demand,
    ): Pair<Session, kotlinx.coroutines.Deferred<SharingEnd>> {
        val session = Session(Provider(), Store(), demand, CompletableDeferred(), MutableSharedFlow())
        val sharing = LocationSharing(session.provider, session.store, source, clock = { currentTime }, reconnectDelayMs = 3_000)
        val ended = async { sharing.run(revoked = session.revoke, foreground = session.foreground) }
        runCurrent()
        return session to ended
    }

    @Test
    fun `sharing starts at the trickle and listens for demand`() = runTest {
        val (s, ended) = start()

        assertEquals(listOf(120_000L), s.provider.requested)
        assertEquals(1, s.demand.connections)
        ended.cancel()
    }

    @Test
    fun `while trickling, samples are stored at once and sent when the oldest is due`() = runTest {
        val (s, ended) = start()

        s.provider.fixes.emit(fix(currentTime))
        runCurrent()
        assertEquals(1, s.store.acquired.size)
        assertEquals(0, s.store.syncs)

        advanceTimeBy(600_000)
        runCurrent()
        assertEquals(1, s.store.syncs)
        ended.cancel()
    }

    @Test
    fun `someone watching makes fixes come every 3 s, each sent at once`() = runTest {
        val (s, ended) = start(Demand(listOf(DemandEvent.Policy(realtime))))

        assertEquals(listOf(120_000L, 3_000L), s.provider.requested)
        assertEquals(1, s.provider.active)

        s.provider.fixes.emit(fix(currentTime))
        runCurrent()
        assertEquals(1, s.store.syncs)
        ended.cancel()
    }

    @Test
    fun `a lost stream means trickle, and a reconnect after the delay`() = runTest {
        val (s, ended) = start(Demand(listOf(DemandEvent.Policy(realtime), DemandEvent.Ended(200, null))))

        // Realtime came and went within one dispatch: the provider is simply
        // left at the trickle, never re-registered for an interval already stale.
        assertEquals(120_000L, s.provider.requested.last())
        assertEquals(1, s.provider.active)
        assertEquals(1, s.demand.connections)

        advanceTimeBy(3_001)
        assertEquals(2, s.demand.connections)
        ended.cancel()
    }

    @Test
    fun `when an established live stream drops, the provider goes back to the trickle`() = runTest {
        val live = Channel<DemandEvent>(Channel.UNLIMITED)
        val (s, ended) = start(Demand(), source = DemandSource { live.receiveAsFlow() })
        live.send(DemandEvent.Policy(realtime))
        runCurrent()
        assertEquals(listOf(120_000L, 3_000L), s.provider.requested)

        live.send(DemandEvent.Ended(null, null))
        runCurrent()

        assertEquals(listOf(120_000L, 3_000L, 120_000L), s.provider.requested)
        assertEquals(1, s.provider.active)
        ended.cancel()
    }

    @Test
    fun `the server saying consent is over ends sharing, sending what is held`() = runTest {
        val (s, ended) = start(Demand(listOf(), listOf(DemandEvent.Ended(404, "NO_ACTIVE_CONSENT"))))
        s.provider.fixes.emit(fix(currentTime))
        runCurrent()

        // The first connection ended quietly; the second says consent is over.
        advanceTimeBy(3_001)

        assertEquals(SharingEnd.ConsentEnded, ended.await())
        assertEquals(0, s.provider.active)
        assertEquals(1, s.store.syncs)
    }

    // Section 25.3: "Revocation cancels location callbacks synchronously, seals
    // the current batch, and prevents acquisition of new samples."
    @Test
    fun `revoking stops fixes, sends the sealed batch, and acquires nothing more`() = runTest {
        val (s, ended) = start()
        s.provider.fixes.emit(fix(currentTime))
        runCurrent()

        s.revoke.complete(Unit)
        // A fix the provider had already delivered when the driver revoked.
        s.provider.fixes.emit(fix(currentTime))
        runCurrent()

        assertEquals(SharingEnd.Revoked, ended.await())
        assertEquals(1, s.store.acquired.size)
        assertEquals(0, s.provider.active)
        assertEquals(1, s.store.syncs)
    }

    // "Synchronously": the provider is released before the sealed batch goes,
    // not after however long the network takes to send it.
    @Test
    fun `fixes stop before the final upload, not after it`() = runTest {
        val (s, ended) = start()
        s.provider.fixes.emit(fix(currentTime))
        runCurrent()
        val activeDuringUpload = CompletableDeferred<Int>()
        s.store.onSync = { activeDuringUpload.complete(s.provider.active); awaitCancellation() }

        s.revoke.complete(Unit)
        runCurrent()

        assertEquals(0, activeDuringUpload.await())
        ended.cancel()
    }

    @Test
    fun `coming to the foreground sends what is held`() = runTest {
        val (s, ended) = start()
        s.provider.fixes.emit(fix(currentTime))
        runCurrent()

        s.foreground.emit(Unit)
        runCurrent()

        assertEquals(1, s.store.syncs)
        ended.cancel()
    }

    @Test
    fun `cancelling the session releases the provider and the stream`() = runTest {
        val (s, ended) = start()
        ended.cancel()
        runCurrent()
        assertEquals(0, s.provider.active)
        assertFalse(ended.isActive)
        assertTrue(ended.isCancelled)
    }

    @Test
    fun `demand events map to the cadence's inputs`() {
        assertEquals(DemandSignal.Policy(realtime), DemandSignal.of(DemandEvent.Policy(realtime)))
        assertEquals(DemandSignal.ConsentEnded, DemandSignal.of(DemandEvent.Ended(404, "NO_ACTIVE_CONSENT")))
        // Anything else is a stream to reconnect, including a 404 for another reason.
        for (end in listOf(DemandEvent.Ended(200, null), DemandEvent.Ended(null, null), DemandEvent.Ended(404, null), DemandEvent.Ended(401, null))) {
            assertEquals("$end", DemandSignal.Lost, DemandSignal.of(end))
        }
    }
}
