package com.dispatch.driver.service.location

import android.content.SharedPreferences
import com.dispatch.driver.core.database.LocationOutbox
import com.dispatch.driver.core.database.SyncOutcome
import com.dispatch.driver.core.model.capability.SyncRequester
import com.dispatch.driver.core.model.location.LocationFix
import com.dispatch.driver.core.model.location.SampleTransport

/**
 * [SharingRecords] in shared preferences. Identifiers only, no position, so
 * nothing here needs sealing; a record missing any field reads as none.
 */
class PrefsSharingRecords(private val prefs: SharedPreferences) : SharingRecords {

    override suspend fun read(): SharingRecord? {
        val assignment = prefs.getString(ASSIGNMENT, null) ?: return null
        val grant = prefs.getString(GRANT, null) ?: return null
        val role = prefs.getString(ROLE, null) ?: return null
        return SharingRecord(assignment, grant, role, prefs.getBoolean(PENDING_REVOKE, false))
    }

    // `commit`, not `apply`: SharingControl orders "on disk" before "stopped".
    override suspend fun write(record: SharingRecord?) {
        prefs.edit().apply {
            if (record == null) {
                clear()
            } else {
                putString(ASSIGNMENT, record.assignmentId)
                putString(GRANT, record.consentGrantId)
                putString(ROLE, record.roleAssignmentId)
                putBoolean(PENDING_REVOKE, record.pendingRevoke)
            }
        }.commit()
    }

    private companion object {
        const val ASSIGNMENT = "assignment_id"
        const val GRANT = "consent_grant_id"
        const val ROLE = "role_assignment_id"
        const val PENDING_REVOKE = "pending_revoke"
    }
}

/** The outbox as one session's [SampleStore]: each fix signed under [record]'s consent. */
class OutboxSampleStore(
    private val outbox: LocationOutbox,
    private val transport: SampleTransport,
    private val record: SharingRecord,
    private val worker: SyncRequester,
) : SampleStore {
    override suspend fun acquire(fix: LocationFix): Boolean = outbox.acquire(fix, record.sharing, record.roleAssignmentId) != null

    // What this leaves pending (no network) is the SyncWorker's to send, so it
    // goes even if the session ends first.
    override suspend fun sync() {
        if (outbox.sync(transport) is SyncOutcome.RetryAt) worker.requestSync()
    }
}
