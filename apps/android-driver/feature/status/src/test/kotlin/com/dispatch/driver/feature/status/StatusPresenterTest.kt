package com.dispatch.driver.feature.status

import com.dispatch.driver.core.database.OutboxEntry
import com.dispatch.driver.core.database.OutboxView
import com.dispatch.driver.core.database.PendingState
import com.dispatch.driver.core.model.capability.CapabilityState
import com.dispatch.driver.core.model.status.CurrentStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The screen's rules without the screen: Section 23.2's "features from the
 * document, never a role key", and Section 1's source-and-freshness on the one
 * fact this screen exists to show.
 */
class StatusPresenterTest {

    private val empty = OutboxView(acknowledged = null, acknowledgedAt = null, events = emptyList())

    private fun entry(id: Long, status: String, at: String, state: PendingState = PendingState.PENDING, action: String? = null) =
        OutboxEntry(id, status, null, at, state, attemptCount = 0, rejectionCode = null, correctiveAction = action)

    private fun ready(outbox: OutboxView = empty, stale: Boolean = false) =
        StatusPresenter.present(CapabilityState.Ready(document(), stale), outbox) as StatusUiState.Ready

    @Test
    fun `without a verified document nothing is offered`() {
        assertEquals(StatusUiState.Loading, StatusPresenter.present(CapabilityState.Loading, empty))
        assertTrue(StatusPresenter.present(CapabilityState.SignedOut, empty) is StatusUiState.NotOffered)
        assertEquals(StatusUiState.NotOffered("offline"), StatusPresenter.present(CapabilityState.Unavailable("offline"), empty))
    }

    @Test
    fun `a document without the status feature offers no status surface`() {
        val state = StatusPresenter.present(CapabilityState.Ready(document(features = setOf("home")), false), empty)

        assertTrue(state is StatusUiState.NotOffered)
    }

    @Test
    fun `the choices are the document's, in its order`() {
        assertEquals(listOf("AT_PICKUP", "DELAYED"), ready().options.map { it.value })
    }

    @Test
    fun `a queued declaration shows at once, as the role's own report, not yet sent`() {
        val current = ready(empty.copy(events = listOf(entry(1, "AT_PICKUP", "2026-09-24T10:00:00Z")))).current!!

        assertEquals("At pickup", current.label)
        assertEquals("Courier-reported status", current.sourceLabel)
        assertEquals(Freshness.NotYetSent, current.freshness)
    }

    @Test
    fun `a confirmed status says when the server confirmed it`() {
        val outbox = OutboxView(
            acknowledged = CurrentStatus("DELAYED", "PARTICIPANT", "2026-09-24T10:00:00Z"),
            acknowledgedAt = 1_790_000_000_000,
            events = listOf(entry(1, "DELAYED", "2026-09-24T10:00:00Z", PendingState.ACKNOWLEDGED)),
        )

        val current = ready(outbox).current!!

        assertEquals("Delayed", current.label)
        assertEquals(Freshness.Confirmed(Instant.ofEpochMilli(1_790_000_000_000)), current.freshness)
    }

    @Test
    fun `the newest declaration by when it was made wins, not by when it was sent`() {
        val outbox = OutboxView(
            acknowledged = CurrentStatus("DELAYED", "PARTICIPANT", "2026-09-24T11:00:00Z"),
            acknowledgedAt = 0,
            events = listOf(entry(1, "AT_PICKUP", "2026-09-24T10:00:00Z")),
        )

        assertEquals("Delayed", ready(outbox).current!!.label)
    }

    @Test
    fun `a rejected declaration never shows as current, and asks for attention`() {
        val rejected = entry(1, "AT_PICKUP", "2026-09-24T10:00:00Z", PendingState.REJECTED, "Choose your role again")

        val state = ready(empty.copy(events = listOf(rejected)))

        assertNull(state.current)
        assertEquals(listOf(rejected), state.attention)
    }

    @Test
    fun `a stale document is still used, and says so`() {
        assertTrue(ready(stale = true).capabilitiesStale)
    }

    @Test
    fun `Section 24_1's note rules are checked before queuing`() {
        val doc = document()

        assertNull(StatusPresenter.validate(doc, "AT_PICKUP", null))
        assertEquals("Delayed needs a note saying what is happening.", StatusPresenter.validate(doc, "DELAYED", "  "))
        assertNull(StatusPresenter.validate(doc, "DELAYED", "Road closed"))
        assertTrue(StatusPresenter.validate(doc, "EMERGENCY", null)!!.contains("not available"))
        // Code points, not UTF-16 units: 1,000 emoji are 2,000 chars but a legal note.
        assertNull(StatusPresenter.validate(doc, "DELAYED", "🚚".repeat(1_000)))
        assertTrue(StatusPresenter.validate(doc, "DELAYED", "a".repeat(1_001))!!.contains("at most"))
    }
}
