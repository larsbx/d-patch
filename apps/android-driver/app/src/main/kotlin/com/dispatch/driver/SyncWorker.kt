package com.dispatch.driver

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
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

    override suspend fun doWork(): Result = when (outbox.sync(transport)) {
        SyncOutcome.Drained -> Result.success()
        is SyncOutcome.RetryAt -> Result.retry()
    }

    companion object {
        const val UNIQUE_NAME = "outbox-sync"

        /** "WorkManager constraints require network but not charging" (Section 25.2). */
        fun request(): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresCharging(false).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
    }
}

/**
 * Enqueues [SyncWorker] as unique work. `KEEP` because a run already queued or
 * in flight will pick up the new event: it re-reads what is pending when it
 * finishes and retries rather than succeeding.
 */
class WorkManagerSyncRequester(private val workManager: WorkManager) : SyncRequester {
    override fun requestSync() {
        workManager.enqueueUniqueWork(SyncWorker.UNIQUE_NAME, ExistingWorkPolicy.KEEP, SyncWorker.request())
    }
}
