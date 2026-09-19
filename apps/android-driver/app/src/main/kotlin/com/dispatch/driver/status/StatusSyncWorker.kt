package com.dispatch.driver.status

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class StatusSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted parameters: WorkerParameters,
    private val engine: StatusSyncEngine,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result =
        when (engine.sync()) {
            SyncPass.Complete -> Result.success()
            SyncPass.Retry -> Result.retry()
        }
}
