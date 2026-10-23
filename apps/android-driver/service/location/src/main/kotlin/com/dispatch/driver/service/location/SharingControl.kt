package com.dispatch.driver.service.location

import com.dispatch.driver.core.model.location.Sharing
import com.dispatch.driver.core.network.Fetch
import com.dispatch.driver.core.network.LocationConsent
import com.dispatch.driver.core.network.Trip
import java.time.Instant
import java.util.UUID

/**
 * What this device remembers having begun: the consent it shares under, and
 * whether it still owes the server a revocation.
 */
data class SharingRecord(
    val assignmentId: String,
    val consentGrantId: String,
    val grantedAt: Instant,
    val roleAssignmentId: String,
    val pendingRevoke: Boolean = false,
) {
    val sharing: Sharing get() = Sharing(assignmentId, consentGrantId, grantedAt)
}

/** Where the record survives a restart. */
interface SharingRecords {
    suspend fun read(): SharingRecord?
    suspend fun write(record: SharingRecord?)
}

/** The server's side of consent (`DispatchClient`), as this needs it. */
interface ConsentApi {
    suspend fun begin(roleAssignmentId: String, assignmentId: String, idempotencyKey: String): Fetch<LocationConsent>
    suspend fun revoke(roleAssignmentId: String, consentGrantId: String, idempotencyKey: String): Fetch<LocationConsent>
    suspend fun trips(roleAssignmentId: String): Fetch<List<Trip>>
}

/** The visible sharing session: the foreground service, or a fake. */
interface SharingRunner {
    fun start(sharing: Sharing, roleAssignmentId: String)

    /** Stops collecting now; does not wait for the network. */
    fun stop()
}

/**
 * What to do with a remembered consent after a restart (Section 25.3: resume
 * "only if the persisted consent is valid and the active assignment still
 * exists after server confirmation").
 */
sealed interface Resumption {
    data class Resume(val sharing: Sharing, val roleAssignmentId: String) : Resumption

    /** The server no longer has it: forget it here. */
    data object Forget : Resumption

    /** No confirmation either way: keep the record, share nothing. */
    data object Wait : Resumption

    /** A stop the server has not yet heard: say it again, never resume. */
    data class RetryRevoke(val consentGrantId: String) : Resumption

    data object Idle : Resumption

    companion object {
        fun of(record: SharingRecord?, trips: Fetch<List<Trip>>): Resumption = when {
            record == null -> Idle
            record.pendingRevoke -> RetryRevoke(record.consentGrantId)
            trips !is Fetch.Ok -> Wait
            confirms(trips.value, record) -> Resume(record.sharing, record.roleAssignmentId)
            else -> Forget
        }

        private fun confirms(trips: List<Trip>, record: SharingRecord): Boolean =
            trips.any { trip ->
                trip.assignmentId == record.assignmentId &&
                    trip.locationConsent?.let { it.consentGrantId == record.consentGrantId && it.revokedAt == null } == true
            }
    }
}

enum class StartResult { Sharing, Refused, Unreachable }

/**
 * Begins, stops and resumes sharing. Each step is ordered so that what the
 * driver sees is never ahead of what is true:
 *
 * - **start**: consent at the server, then the record, then the visible
 *   session. Nothing is collected under a consent the server has not granted.
 * - **stop**: the intent to revoke on disk, then the session stopped here,
 *   then the server told. Collection ends before any network call, and a
 *   revocation the server did not hear is repeated at the next [resume].
 */
class SharingControl(
    private val api: ConsentApi,
    private val records: SharingRecords,
    private val runner: SharingRunner,
    private val newKey: () -> String = { UUID.randomUUID().toString() },
) {
    suspend fun start(trip: Trip, roleAssignmentId: String): StartResult {
        val consent = trip.locationConsent?.takeIf { it.revokedAt == null }
            ?: when (val begun = api.begin(roleAssignmentId, trip.assignmentId, newKey())) {
                is Fetch.Ok -> begun.value
                is Fetch.Problem -> return StartResult.Refused
                Fetch.Unreachable -> return StartResult.Unreachable
            }
        val record = SharingRecord(trip.assignmentId, consent.consentGrantId, Instant.parse(consent.grantedAt), roleAssignmentId)
        records.write(record)
        runner.start(record.sharing, roleAssignmentId)
        return StartResult.Sharing
    }

    suspend fun stop() {
        val record = records.read() ?: return runner.stop()
        records.write(record.copy(pendingRevoke = true))
        runner.stop()
        revoke(record)
    }

    /** At start-up: resume, forget, or finish a stop, as [Resumption] decides. */
    suspend fun resume() {
        val record = records.read() ?: return
        val trips = if (record.pendingRevoke) Fetch.Unreachable else api.trips(record.roleAssignmentId)
        when (val resumption = Resumption.of(record, trips)) {
            is Resumption.Resume -> runner.start(resumption.sharing, resumption.roleAssignmentId)
            Resumption.Forget -> records.write(null)
            is Resumption.RetryRevoke -> revoke(record)
            Resumption.Wait, Resumption.Idle -> Unit
        }
    }

    /** The server ended consent (the demand stream's `NO_ACTIVE_CONSENT`): nothing to revoke. */
    suspend fun ended() = records.write(null)

    // Revoked, or already gone (`404`): either way the server holds no consent.
    private suspend fun revoke(record: SharingRecord) {
        when (val answer = api.revoke(record.roleAssignmentId, record.consentGrantId, newKey())) {
            is Fetch.Ok -> records.write(null)
            is Fetch.Problem -> if (answer.httpStatus == 404) records.write(null)
            Fetch.Unreachable -> Unit
        }
    }
}
