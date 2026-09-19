package com.dispatch.driver.status

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

interface StatusSyncEnqueuer {
    fun enqueue()
}

@Singleton
class WorkManagerStatusSyncEnqueuer @Inject constructor(
    private val workManager: WorkManager,
) : StatusSyncEnqueuer {
    override fun enqueue() {
        val constraints =
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresCharging(false)
                .build()
        val request =
            OneTimeWorkRequestBuilder<StatusSyncWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .build()

        // Appending prevents the narrow drain/complete race from discarding a
        // declaration queued while the previous unique worker is still running.
        workManager.enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        )
    }

    private companion object {
        const val UNIQUE_WORK_NAME = "participant-status-sync"
    }
}
