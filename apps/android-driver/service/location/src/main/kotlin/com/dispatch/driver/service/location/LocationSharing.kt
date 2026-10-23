package com.dispatch.driver.service.location

import com.dispatch.driver.core.model.location.DemandPolicy
import com.dispatch.driver.core.model.location.LocationFix
import com.dispatch.driver.core.model.location.SharingCadence
import com.dispatch.driver.core.model.location.SharingCadence.Effect
import com.dispatch.driver.core.model.location.SharingCadence.Input
import com.dispatch.driver.core.network.DemandEvent
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** The platform's location provider, delivering fixes at about [intervalMs] while collected. */
fun interface FixSource {
    fun fixes(intervalMs: Long): Flow<LocationFix>
}

/** The outbox, bound to the sharing it serves: stores a fix signed, and sends what is due. */
interface SampleStore {
    /** False when the fix could not be stored (no registered device). */
    suspend fun acquire(fix: LocationFix): Boolean
    suspend fun sync()
}

/** One connection to the demand stream (`DispatchClient.demand`). */
fun interface DemandSource {
    fun connect(): Flow<DemandEvent>
}

/** What a demand event means here. */
sealed interface DemandSignal {
    data class Policy(val policy: DemandPolicy) : DemandSignal

    /** The stream ended or could not be reached: trickle, and reconnect. */
    data object Lost : DemandSignal

    /** The server has no active consent to stream for: sharing is over. */
    data object ConsentEnded : DemandSignal

    companion object {
        fun of(event: DemandEvent): DemandSignal = when {
            event is DemandEvent.Policy -> Policy(event.policy)
            event == DemandEvent.Ended(404, "NO_ACTIVE_CONSENT") -> ConsentEnded
            else -> Lost
        }
    }
}

/** Why a sharing session ended. */
enum class SharingEnd { Revoked, ConsentEnded }

/**
 * A sharing session (Section 25.3, ADR-0014). [SharingCadence] makes every
 * decision; this runs its effects:
 *
 * - [Effect.RequestFixes] and [Effect.StopFixes] become (re)collecting or
 *   cancelling [fixes], whose collection is the provider's registration;
 *   stopping joins it, so the provider has let go before the next effect.
 * - [Effect.Upload] is [SampleStore.sync]: the samples are already stored, so
 *   the cadence decides *when* to send, and the outbox *what*.
 * - [Effect.WakeAt] is a timer delivering [Input.Tick].
 *
 * Revocation closes acquisition first, before the cadence hears of it, so a
 * fix already on its way is not stored (Section 25.3: revocation "prevents
 * acquisition of new samples").
 */
class LocationSharing(
    private val fixes: FixSource,
    private val store: SampleStore,
    private val demand: DemandSource,
    private val clock: () -> Long = System::currentTimeMillis,
    private val reconnectDelayMs: Long = 3_000,
) {
    private sealed interface Event {
        data class Step(val input: Input<LocationFix>) : Event
        data class End(val reason: SharingEnd) : Event
    }

    /** Shares until [revoked] completes or the server ends consent; cancelling releases everything. */
    suspend fun run(revoked: Deferred<Unit>, foreground: Flow<Unit>): SharingEnd = coroutineScope {
        val events = Channel<Event>(Channel.UNLIMITED)
        val acquiring = AtomicBoolean(true)
        val wake = MutableStateFlow<Long?>(null)
        var provider: Job? = null

        // Joined, not merely cancelled: when this returns the provider has
        // let go, before anything after it (the final upload) begins.
        suspend fun release() = provider?.cancelAndJoin().also { provider = null }

        suspend fun request(intervalMs: Long) {
            release()
            provider = launch {
                fixes.fixes(intervalMs).collect { fix ->
                    if (acquiring.get() && store.acquire(fix)) events.send(Event.Step(Input.Acquired(fix)))
                }
            }
        }
        launch {
            wake.filterNotNull().collectLatest { at ->
                delay((at - clock()).coerceAtLeast(0))
                events.send(Event.Step(Input.Tick))
            }
        }
        launch { foreground.collect { events.send(Event.Step(Input.Foreground)) } }
        launch {
            revoked.await()
            acquiring.set(false)
            events.send(Event.End(SharingEnd.Revoked))
        }
        launch {
            do {
                val over = demand.connect().map(DemandSignal::of)
                    .onEach { events.send(it.toEvent()) }
                    .firstOrNull { it == DemandSignal.ConsentEnded } != null
                if (!over) delay(reconnectDelayMs)
            } while (!over)
        }

        suspend fun execute(effects: List<Effect<LocationFix>>) = effects.forEach { effect ->
            when (effect) {
                is Effect.RequestFixes -> request(effect.intervalMs)
                Effect.StopFixes -> release()
                is Effect.Upload -> store.sync()
                is Effect.WakeAt -> wake.value = effect.atMs
            }
        }

        val (initial, started) = SharingCadence.start<LocationFix>()
        execute(started)
        var cadence = initial
        var end: SharingEnd? = null
        while (end == null) {
            val event = events.receive()
            if (event is Event.End) acquiring.set(false)
            val (next, effects) = cadence.step(if (event is Event.Step) event.input else Input.Revoked, clock())
            cadence = next
            execute(effects)
            end = (event as? Event.End)?.reason
        }
        coroutineContext.cancelChildren()
        end
    }

    private fun DemandSignal.toEvent(): Event = when (this) {
        is DemandSignal.Policy -> Event.Step(Input.Policy(policy))
        DemandSignal.Lost -> Event.Step(Input.StreamLost)
        DemandSignal.ConsentEnded -> Event.End(SharingEnd.ConsentEnded)
    }
}
