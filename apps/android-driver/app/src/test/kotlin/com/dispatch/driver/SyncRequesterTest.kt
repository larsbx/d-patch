package com.dispatch.driver

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A sync requested while one is queued or running must get a run of its own
 * after it. Under `KEEP` the second request vanished, stranding an event
 * declared while the running worker was finishing.
 */
@RunWith(RobolectricTestRunner::class)
class SyncRequesterTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var workManager: WorkManager

    @Before
    fun initWorkManager() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        workManager = WorkManager.getInstance(context)
    }

    private fun chain() = workManager.getWorkInfosForUniqueWork(SyncWorker.UNIQUE_NAME).get()

    @Test
    fun `a request made while a sync is pending is chained after it, not dropped`() {
        val requester = WorkManagerSyncRequester(workManager)

        // The network constraint holds the first run, as a run in progress would.
        requester.requestSync()
        requester.requestSync()

        val runs = chain()
        assertEquals(2, runs.size)
        assertTrue(runs.any { it.state == WorkInfo.State.BLOCKED })
    }
}
