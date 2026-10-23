package com.dispatch.driver.service.location

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dispatch.driver.core.database.DispatchDatabase
import com.dispatch.driver.core.database.LocationOutbox
import com.dispatch.driver.core.model.device.DeviceKey
import com.dispatch.driver.core.model.device.SigningDevice
import com.dispatch.driver.core.model.location.LocationFix
import com.dispatch.driver.core.model.location.LocationSample
import com.dispatch.driver.core.model.location.SampleTransport
import com.dispatch.driver.core.model.location.SampleVerdict
import com.dispatch.driver.core.model.location.UploadResult
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

private val granted: java.time.Instant = java.time.Instant.parse("2026-10-23T10:00:00Z")

/** The record on disk, and the outbox as a session's store. */
@RunWith(RobolectricTestRunner::class)
class AdaptersTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val database = Room.inMemoryDatabaseBuilder(context, DispatchDatabase::class.java).allowMainThreadQueries().build()

    private var syncRequests = 0

    @After
    fun close() = database.close()

    @Test
    fun `the record survives as written, pending revoke included, and clears`() = runTest {
        val prefs = context.getSharedPreferences("sharing-test", Context.MODE_PRIVATE)
        val records = PrefsSharingRecords(prefs)
        assertNull(records.read())

        val pending = SharingRecord("trip-1", "g-1", granted, "ra-1", pendingRevoke = true)
        records.write(pending)
        assertEquals(pending, PrefsSharingRecords(prefs).read())

        records.write(null)
        assertNull(PrefsSharingRecords(prefs).read())
    }

    @Test
    fun `a record missing a field reads as none, not a half-consent`() = runTest {
        val prefs = context.getSharedPreferences("sharing-partial", Context.MODE_PRIVATE)
        prefs.edit().putString("assignment_id", "trip-1").putString("role_assignment_id", "ra-1").commit()
        assertNull(PrefsSharingRecords(prefs).read())
    }

    @Test
    fun `a session's store signs under its consent and sends through the transport`() = runTest {
        val sent = mutableListOf<Pair<String, List<LocationSample>>>()
        val transport = SampleTransport { role, batch ->
            sent += role to batch
            UploadResult.Answered(batch.associate { it.sampleId to SampleVerdict("ACCEPTED", null) })
        }
        val device = SigningDevice("device-1", DeviceKey.fromSeed(ByteArray(32) { 1 }))
        val store = OutboxSampleStore(LocationOutbox(database, signer = { device }), transport, SharingRecord("trip-1", "g-1", granted, "ra-1")) { syncRequests++ }

        assertTrue(store.acquire(LocationFix(51.5, -0.12, 5.0, null, null, "gps", false, Instant.parse("2026-10-23T10:00:00Z"))))
        store.sync()

        val (role, batch) = sent.single()
        assertEquals("ra-1", role)
        assertEquals("trip-1" to "g-1", batch.single().assignmentId to batch.single().consentGrantId)
    }

    @Test
    fun `without a registered device the store acquires nothing`() = runTest {
        val store = OutboxSampleStore(LocationOutbox(database, signer = { null }), { _, _ -> UploadResult.Unreachable }, SharingRecord("trip-1", "g-1", granted, "ra-1")) { syncRequests++ }
        assertFalse(store.acquire(LocationFix(51.5, -0.12, 5.0, null, null, "gps", false, Instant.parse("2026-10-23T10:00:00Z"))))
    }

    // A session that ends with samples unsent leaves them to WorkManager,
    // not to whatever happens to sync next.
    @Test
    fun `what a session cannot send is handed to the sync worker`() = runTest {
        val device = SigningDevice("device-1", DeviceKey.fromSeed(ByteArray(32) { 1 }))
        val outbox = LocationOutbox(database, signer = { device })
        val fix = LocationFix(51.5, -0.12, 5.0, null, null, "gps", false, Instant.parse("2026-10-23T10:00:00Z"))
        val offline = OutboxSampleStore(outbox, { _, _ -> UploadResult.Unreachable }, SharingRecord("trip-1", "g-1", granted, "ra-1")) { syncRequests++ }

        offline.acquire(fix)
        offline.sync()
        assertEquals(1, syncRequests)

        val online = OutboxSampleStore(
            outbox,
            { _, batch -> UploadResult.Answered(batch.associate { it.sampleId to SampleVerdict("ACCEPTED", null) }) },
            SharingRecord("trip-1", "g-1", granted, "ra-1"),
        ) { syncRequests++ }
        outbox.retryNow()
        online.sync()
        assertEquals(1, syncRequests)
    }
}
