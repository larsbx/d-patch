package com.dispatch.driver

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.dispatch.driver.core.database.DispatchDatabase
import com.dispatch.driver.core.database.LocationOutbox
import com.dispatch.driver.core.database.StatusOutbox
import com.dispatch.driver.core.model.device.DeviceKey
import com.dispatch.driver.core.model.device.SigningDevice
import com.dispatch.driver.core.model.location.LocationFix
import com.dispatch.driver.core.model.location.SampleTransport
import com.dispatch.driver.core.model.location.SampleVerdict
import com.dispatch.driver.core.model.location.Sharing
import com.dispatch.driver.core.model.location.UploadResult
import com.dispatch.driver.core.model.status.CurrentStatus
import com.dispatch.driver.core.model.status.StatusTransport
import com.dispatch.driver.core.model.status.SubmitResult
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

private val granted: java.time.Instant = java.time.Instant.parse("2026-10-23T10:00:00Z")

@RunWith(RobolectricTestRunner::class)
class SyncWorkerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val database = Room.inMemoryDatabaseBuilder(context, DispatchDatabase::class.java).allowMainThreadQueries().build()
    private val outbox = StatusOutbox(database)
    private val device = SigningDevice("device-1", DeviceKey.fromSeed(ByteArray(32) { 3 }))
    private val samples = LocationOutbox(database, signer = { device })
    private var sampleTransport = SampleTransport { _, batch -> UploadResult.Answered(batch.associate { it.sampleId to SampleVerdict("ACCEPTED", null) }) }
    private val fix = LocationFix(51.5, -0.12, 5.0, null, null, "gps", false, Instant.parse("2026-10-23T10:00:00Z"))

    @After
    fun close() = database.close()

    private fun worker(input: Data = Data.EMPTY, transport: StatusTransport) =
        TestListenableWorkerBuilder<SyncWorker>(context)
            .setInputData(input)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                    SyncWorker(appContext, workerParameters, outbox, transport, samples, sampleTransport)
            })
            .build()

    @Test
    fun `Section 25_2's constraints - network required, charging not`() {
        val constraints = SyncWorker.request().workSpec.constraints

        assertEquals(NetworkType.CONNECTED, constraints.requiredNetworkType)
        assertFalse(constraints.requiresCharging())
    }

    @Test
    fun `a drained outbox succeeds`() = runTest {
        outbox.declare("AT_PICKUP", null, "ra-1")

        val result = worker { d, _ -> SubmitResult.Stored(CurrentStatus(d.status, "PARTICIPANT", d.occurredAt)) }.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
    }

    @Test
    fun `a sync requested after sign-in sends what the refused session deferred, at once`() = runTest {
        outbox.declare("AT_PICKUP", null, "ra-1")
        outbox.sync { _, _ -> SubmitResult.Problem(401, "UNAUTHENTICATED", null) }
        val stored = StatusTransport { d, _ -> SubmitResult.Stored(CurrentStatus(d.status, "PARTICIPANT", d.occurredAt)) }

        assertEquals("deferred: nothing due yet", ListenableWorker.Result.retry(), worker(transport = stored).doWork())
        assertEquals(ListenableWorker.Result.success(), worker(SyncWorker.RETRY_NOW, stored).doWork())
    }

    @Test
    fun `anything still pending is a retry, so it is not stranded`() = runTest {
        outbox.declare("AT_PICKUP", null, "ra-1")

        assertEquals(ListenableWorker.Result.retry(), worker { _, _ -> SubmitResult.Unreachable }.doWork())
    }

    // Samples left when a session ended offline go with the next sync, not
    // with the next session.
    @Test
    fun `queued location samples drain with the declarations`() = runTest {
        samples.acquire(fix, Sharing("trip-1", "g-1", granted), "ra-1")
        val stored = StatusTransport { d, _ -> SubmitResult.Stored(CurrentStatus(d.status, "PARTICIPANT", d.occurredAt)) }

        assertEquals(ListenableWorker.Result.success(), worker(transport = stored).doWork())
        assertEquals(0, samples.pending())
    }

    @Test
    fun `a sample still pending is a retry, even with no declaration left`() = runTest {
        samples.acquire(fix, Sharing("trip-1", "g-1", granted), "ra-1")
        sampleTransport = SampleTransport { _, _ -> UploadResult.Unreachable }
        val stored = StatusTransport { d, _ -> SubmitResult.Stored(CurrentStatus(d.status, "PARTICIPANT", d.occurredAt)) }

        assertEquals(ListenableWorker.Result.retry(), worker(transport = stored).doWork())
    }
}
