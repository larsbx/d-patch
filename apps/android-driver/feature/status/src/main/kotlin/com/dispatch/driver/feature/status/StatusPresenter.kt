package com.dispatch.driver.feature.status

import com.dispatch.driver.core.database.OutboxEntry
import com.dispatch.driver.core.database.OutboxView
import com.dispatch.driver.core.database.PendingState
import com.dispatch.driver.core.model.capability.CapabilityDocument
import com.dispatch.driver.core.model.capability.CapabilityState
import com.dispatch.driver.core.model.capability.Features
import com.dispatch.driver.core.model.capability.StatusOption
import com.dispatch.driver.core.model.status.StatusDeclaration
import java.time.Instant

/**
 * Everything the status screen shows, derived — never stored — from the
 * capability document and the outbox.
 *
 * Pure, so the rules Section 25.2 and Section 1 impose on this screen are
 * tested without a device: what is offered comes only from the document, and
 * the current status always says whose declaration it is and how fresh.
 */
sealed interface StatusUiState {
    data object Loading : StatusUiState

    /** The status surface is not offered; [message] says why. */
    data class NotOffered(val message: String) : StatusUiState

    data class Ready(
        val options: List<StatusOption>,
        val current: DisplayedStatus?,
        val attention: List<OutboxEntry>,
        val roleAssignmentId: String,
        val capabilitiesStale: Boolean,
    ) : StatusUiState
}

/**
 * The status shown as current, with Section 1's provenance.
 *
 * [sourceLabel] is built from the role's presentation label — "Driver-reported
 * status" under the seeded `DRIVER` profile (Section 5.2) — so a renamed or new
 * role reads correctly without the client knowing any role key.
 */
data class DisplayedStatus(
    val label: String,
    val sourceLabel: String,
    val occurredAt: Instant,
    val freshness: Freshness,
)

sealed interface Freshness {
    /** Declared on this device, not yet accepted by the server. */
    data object NotYetSent : Freshness

    /** Accepted by the server; [confirmedAt] is when this device learned so. */
    data class Confirmed(val confirmedAt: Instant) : Freshness
}

object StatusPresenter {

    fun present(capabilities: CapabilityState, outbox: OutboxView): StatusUiState = when (capabilities) {
        CapabilityState.Loading -> StatusUiState.Loading
        CapabilityState.SignedOut -> StatusUiState.NotOffered("Sign in to declare your status.")
        is CapabilityState.Unavailable -> StatusUiState.NotOffered(capabilities.message)
        is CapabilityState.Ready -> ready(capabilities.document, capabilities.stale, outbox)
    }

    private fun ready(document: CapabilityDocument, stale: Boolean, outbox: OutboxView): StatusUiState {
        if (!document.offers(Features.STATUS) || document.statusOptions.isEmpty()) {
            return StatusUiState.NotOffered("Your current role does not declare a status.")
        }
        return StatusUiState.Ready(
            options = document.statusOptions,
            current = current(document, outbox),
            attention = outbox.events.filter { it.state == PendingState.REJECTED || it.correctiveAction != null },
            roleAssignmentId = document.roleAssignmentId,
            capabilitiesStale = stale,
        )
    }

    /**
     * The newest declaration by the time it was *made*, local or confirmed.
     * An offline declaration uploaded late does not displace a newer one, the
     * same rule the server applies to `current_status`.
     */
    private fun current(document: CapabilityDocument, outbox: OutboxView): DisplayedStatus? {
        val sourceLabel = "${document.role.label}-reported status"
        fun label(value: String) = document.statusOption(value)?.label ?: value

        val pending = outbox.events
            .filter { it.state == PendingState.PENDING }
            .map { DisplayedStatus(label(it.status), sourceLabel, Instant.parse(it.occurredAt), Freshness.NotYetSent) }

        val confirmed = outbox.acknowledged?.let { ack ->
            runCatching { Instant.parse(ack.occurredAt) }.getOrNull()?.let { occurredAt ->
                DisplayedStatus(label(ack.value), sourceLabel, occurredAt, Freshness.Confirmed(Instant.ofEpochMilli(outbox.acknowledgedAt ?: 0)))
            }
        }

        return (pending + listOfNotNull(confirmed)).maxByOrNull { it.occurredAt }
    }

    /**
     * Section 24.1's rules, checked before queuing so a declaration that could
     * never be accepted is not made to wait for a server to say so. Returns the
     * message to show, or null when the declaration may be queued.
     */
    fun validate(document: CapabilityDocument, value: String, note: String?): String? {
        val option = document.statusOption(value) ?: return "That status is not available to your role."
        val trimmed = note?.trim().orEmpty()
        return when {
            option.requiresNote && trimmed.isEmpty() -> "${option.label} needs a note saying what is happening."
            trimmed.codePointCount(0, trimmed.length) > StatusDeclaration.NOTE_MAX_CODE_POINTS ->
                "Notes can be at most ${StatusDeclaration.NOTE_MAX_CODE_POINTS} characters."
            else -> null
        }
    }
}
