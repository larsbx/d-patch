package com.dispatch.driver.core.database

import androidx.room.withTransaction
import com.dispatch.driver.core.model.UuidV7
import com.dispatch.driver.core.model.device.DeviceSigner
import com.dispatch.driver.core.model.location.LocationFix
import com.dispatch.driver.core.model.location.LocationSample
import com.dispatch.driver.core.model.location.SampleDisposition
import com.dispatch.driver.core.model.location.SampleTransport
import com.dispatch.driver.core.model.location.SharingCadence
import com.dispatch.driver.core.model.location.Sharing

/**
 * Section 25.3's samples in Section 25.2's outbox (`pending_events`, kind
 * `LOCATION_SAMPLE`).
 *
 * - [acquire] signs a fix and stores it in one transaction, at the next device
 *   sequence of the table the status outbox shares: no sample and declaration
 *   ever share a sequence, and a stored sample is never lost to a crash.
 * - [sync] sends what is due, oldest first, at most [SharingCadence.MAX_BATCH]
 *   a batch, each sample exactly as stored, until the server settles it
 *   ([SampleDisposition]).
 *
 * When to sync is `SharingCadence`'s decision; what to send is this table's.
 */
class LocationOutbox(
    private val database: DispatchDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newSampleId: () -> String = { UuidV7.generate().toString() },
    private val signer: DeviceSigner,
) {
    private val dao = database.outbox()

    /** Stores [fix] signed, or returns null when no device is registered: the server takes only signed samples. */
    suspend fun acquire(fix: LocationFix, sharing: Sharing, roleAssignmentId: String): LocationSample? {
        val device = signer.current() ?: return null
        val now = clock()
        return database.withTransaction {
            val sequence = dao.maxDeviceSequence() + 1
            LocationSample.of(fix, newSampleId(), sharing, sequence).signedBy(device).also { sample ->
                dao.insert(
                    PendingEventEntity(
                        kind = KIND,
                        canonicalJson = sample.toCanonicalJson(),
                        deviceSequence = sequence,
                        createdAt = now,
                        nextAttemptAt = now,
                        state = PendingState.PENDING.name,
                        roleAssignmentId = roleAssignmentId,
                    ),
                )
            }
        }
    }

    /**
     * Sends every due sample, each batch under the assignment it was acquired
     * under. A batch the server said nothing about (no answer, a fault) stops
     * the pass: the next would meet the same network.
     */
    suspend fun sync(transport: SampleTransport): SyncOutcome {
        for (batch in batches(dao.due(KIND, clock()))) {
            val samples = batch.map { LocationSample.fromCanonicalJson(it.canonicalJson) }
            val dispositions = SampleDisposition.of(samples.map { it.sampleId }, transport.upload(batch.first().roleAssignmentId, samples))
            batch.zip(samples).forEach { (row, sample) -> settle(row, dispositions.getValue(sample.sampleId)) }
            if (dispositions.values.all { it is SampleDisposition.RetryLater }) {
                return SyncOutcome.RetryAt(clock() + StatusOutbox.backoffMillis(batch.first().attemptCount + 1))
            }
        }
        return dao.earliestPendingAttempt(KIND)?.let(SyncOutcome::RetryAt) ?: SyncOutcome.Drained
    }

    /** Makes every deferred sample due now, as `StatusOutbox.retryNow` does for declarations. */
    suspend fun retryNow() = dao.makeDue(clock())

    /** How many samples are waiting to be settled. */
    suspend fun pending(): Int = dao.countPending(KIND)

    /** The codes of samples the server refused, oldest first. */
    suspend fun rejected(): List<String> = dao.rejectionCodes(KIND)

    private suspend fun settle(row: PendingEventEntity, disposition: SampleDisposition) = when (disposition) {
        SampleDisposition.Acknowledge -> dao.acknowledge(row.localId)
        is SampleDisposition.Reject -> dao.reject(row.localId, disposition.code, REJECTED_ACTION)
        is SampleDisposition.RetryLater ->
            dao.defer(row.localId, clock() + StatusOutbox.backoffMillis(row.attemptCount + 1), disposition.reason)
    }

    /** Runs of one role assignment, in sequence order, cut at the batch size. */
    private fun batches(rows: List<PendingEventEntity>): List<List<PendingEventEntity>> =
        rows.fold(emptyList<List<PendingEventEntity>>()) { runs, row ->
            val last = runs.lastOrNull()
            if (last != null && last.first().roleAssignmentId == row.roleAssignmentId) runs.dropLast(1) + listOf(last + row)
            else runs + listOf(listOf(row))
        }.flatMap { it.chunked(SharingCadence.MAX_BATCH) }

    private companion object {
        val KIND = PendingKind.LOCATION_SAMPLE.name

        /** A refused sample is final as sent; sharing goes on with the next. */
        const val REJECTED_ACTION = "The server did not accept this location."
    }
}
