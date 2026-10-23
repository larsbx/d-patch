package com.dispatch.driver.service.location

import com.dispatch.driver.core.network.Fetch
import com.dispatch.driver.core.network.Trip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** What the status screen shows about location sharing. */
sealed interface SharingCard {
    /** The role does not share location, and nothing is being shared. */
    data object Hidden : SharingCard
    data object Checking : SharingCard

    /** The trips could not be read: nothing to offer until they can. */
    data object Unavailable : SharingCard
    data object NoTrip : SharingCard
    data class Offer(val trip: Trip) : SharingCard

    /** A session is running: always shown, with `Stop sharing`. */
    data object Sharing : SharingCard

    /** Stopped here; the server has not yet heard. */
    data object Stopping : SharingCard

    companion object {
        /**
         * Pure. A running session comes first (Section 2.2: no hidden
         * collection), whatever the capability or the network says.
         */
        fun of(offered: Boolean, trips: Fetch<List<Trip>>?, record: SharingRecord?, active: Boolean): SharingCard = when {
            active -> Sharing
            record?.pendingRevoke == true -> Stopping
            !offered -> Hidden
            trips == null -> Checking
            trips !is Fetch.Ok -> Unavailable
            else -> offer(trips.value)?.let(::Offer) ?: NoTrip
        }

        /** The trip under way, else the next one. Trips arrive soonest first. */
        private fun offer(trips: List<Trip>): Trip? = trips.firstOrNull { it.status == "ACTIVE" } ?: trips.firstOrNull()
    }
}

/**
 * The status screen's location card and its two buttons, over [SharingControl].
 * [offered] is the role assignment to share under while the capability document
 * offers `location_sharing`, else null.
 */
class TripSharing(
    scope: CoroutineScope,
    private val offered: StateFlow<String?>,
    private val api: ConsentApi,
    private val records: SharingRecords,
    sessions: SharingSessions,
    private val control: SharingControl,
) {
    private val trips = MutableStateFlow<Fetch<List<Trip>>?>(null)
    private val record = MutableStateFlow<SharingRecord?>(null)

    val state: StateFlow<SharingCard> =
        combine(offered, trips, record, sessions.activity) { role, trips, record, active -> SharingCard.of(role != null, trips, record, active) }
            .stateIn(scope, SharingStarted.Eagerly, SharingCard.Checking)

    /** Re-reads the record and the trips. */
    suspend fun refresh() {
        record.value = records.read()
        offered.value?.let { trips.value = api.trips(it) }
    }

    /** Begins sharing for the offered trip; nothing when none is offered. */
    suspend fun share() {
        val role = offered.value ?: return
        val trip = (state.value as? SharingCard.Offer)?.trip ?: return
        control.start(trip, role)
        refresh()
    }

    suspend fun stop() {
        control.stop()
        refresh()
    }

    /** At start-up: §25.3's resumption, then the card. */
    suspend fun resume() {
        control.resume()
        refresh()
    }
}
