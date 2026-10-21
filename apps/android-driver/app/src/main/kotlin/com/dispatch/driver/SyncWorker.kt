package com.dispatch.driver

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.dispatch.driver.core.database.StatusOutbox
import com.dispatch.driver.core.database.SyncOutcome
import com.dispatch.driver.core.model.capability.SyncRequester
import com.dispatch.driver.core.model.status.StatusTransport
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Section 25.2's `SyncWorker`: drains the outbox once per run.
 *
 * Success only when nothing is left pending. Anything still queued — deferred
 * by backoff, or declared while this run was in flight — is a retry, so no
 * event waits for the next declaration to be sent.
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val outbox: StatusOutbox,
    private val transport: StatusTransport,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (inputData.getBoolean(RETRY_NOW_KEY, false)) outbox.retryNow()
        return when (outbox.sync(transport)) {
            SyncOutcome.Drained -> Result.success()
            is SyncOutcome.RetryAt -> Result.retry()
        }
    }

    companion object {
        const val UNIQUE_NAME = "outbox-sync"
        private const val RETRY_NOW_KEY = "retry_now"

        /** Input for a run that first makes every deferred event due. */
        val RETRY_NOW: Data = workDataOf(RETRY_NOW_KEY to true)

        /** "WorkManager constraints require network but not charging" (Section 25.2). */
        fun request(input: Data = Data.EMPTY): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setInputData(input)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresCharging(false).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
    }
}

/**
 * Enqueues [SyncWorker] as unique work with `APPEND_OR_REPLACE`.
 *
 * `KEEP` would lose a request made while a run is finishing: an event declared
 * after that run's last look at the outbox, but before it reaches `SUCCEEDED`,
 * would see its enqueue ignored and wait until the next declaration or launch.
 * Appending chains a run after the current one, so every request made during a
 * run gets a run that starts after it; a failed or cancelled chain is replaced
 * rather than inherited. A chained run with nothing due drains at once.
 */
class WorkManagerSyncRequester(private val workManager: WorkManager) : SyncRequester {
    override fun requestSync() {
        workManager.enqueueUniqueWork(SyncWorker.UNIQUE_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, SyncWorker.request())
    }

    /**
     * `REPLACE`: a chain sitting out WorkManager's backoff would hold an
     * appended run behind it. Cancelling a run mid-send is safe, because each
     * event is sent under its event ID as the idempotency key.
     */
    override fun requestSyncNow() {
        workManager.enqueueUniqueWork(SyncWorker.UNIQUE_NAME, ExistingWorkPolicy.REPLACE, SyncWorker.request(SyncWorker.RETRY_NOW))
    }
}
