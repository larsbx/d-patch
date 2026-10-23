package com.dispatch.driver.service.location

import android.app.Application
import android.app.Notification
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import com.dispatch.driver.core.model.location.LocationFix
import com.dispatch.driver.core.model.location.Sharing
import com.dispatch.driver.core.network.DemandEvent
import com.dispatch.driver.core.network.Fetch
import com.dispatch.driver.core.network.LocationConsent
import com.dispatch.driver.core.network.Trip
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

private val granted: java.time.Instant = java.time.Instant.parse("2026-10-23T10:00:00Z")

/**
 * Section 25.3's foreground service: sharing is visible for as long as it
 * runs, with `Stop sharing` one tap away, and it ends when consent does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LocationSharingServiceTest.Host::class)
class LocationSharingServiceTest {

    /** The app, as the service sees it, with every dependency faked. */
    class Host : Application(), SharingHost {
        val demand = Channel<DemandEvent>(Channel.UNLIMITED)
        val log = mutableListOf<String>()
        var revokeAnswer: Fetch<LocationConsent> = Fetch.Ok(LocationConsent("g-1", "trip-1", "2026-10-23T10:00:00Z", null, "now"))
        private var record: SharingRecord? = null

        override val sessions = SharingSessions()
        override val foreground = MutableSharedFlow<Unit>()
        override val sharingControl: SharingControl by lazy {
            SharingControl(
                api = object : ConsentApi {
                    override suspend fun begin(roleAssignmentId: String, assignmentId: String, idempotencyKey: String) =
                        Fetch.Ok(LocationConsent("g-1", assignmentId, "2026-10-23T10:00:00Z", null, null))
                    override suspend fun revoke(roleAssignmentId: String, consentGrantId: String, idempotencyKey: String) =
                        revokeAnswer.also { log += "revoke $consentGrantId" }
                    override suspend fun trips(roleAssignmentId: String): Fetch<List<Trip>> = Fetch.Unreachable.also { log += "trips" }
                },
                records = object : SharingRecords {
                    override suspend fun read() = record
                    override suspend fun write(record: SharingRecord?) { this@Host.record = record }
                },
                runner = ServiceSharingRunner(this, sessions),
            )
        }

        override fun sharingSession(record: SharingRecord) = LocationSharing(
            fixes = {
                flow {
                    log += "fixes"
                    emit(LocationFix(51.5, -0.12, 5.0, null, null, "gps", false, java.time.Instant.parse("2026-10-23T10:00:00Z")))
                    awaitCancellation()
                }
            },
            store = object : SampleStore {
                override suspend fun acquire(fix: LocationFix) = true
                override suspend fun sync() { log += "sync" }
            },
            demand = { demand.receiveAsFlow() },
        )

        suspend fun remembered() = record

        suspend fun remember(record: SharingRecord) { this.record = record }

        override fun onCreate() {
            super.onCreate()
            SharingHost.install(this)
        }
    }

    private val host get() = ApplicationProvider.getApplicationContext<Host>()
    private val record = SharingRecord("trip-1", "g-1", granted, "ra-1")

    @Before
    fun clear() = host.log.clear()

    private fun started() =
        Robolectric.buildService(LocationSharingService::class.java, LocationSharingService.start(host, record)).create().startCommand(0, 1)

    @Test
    fun `sharing runs in the foreground, for location, with Stop sharing on its notification`() {
        val service = started().get()
        shadowOf(android.os.Looper.getMainLooper()).idle()

        val shadow = shadowOf(service)
        val notification = shadow.lastForegroundNotification
        assertNotNull(notification)
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(listOf("Stop sharing"), notification.actions.map { it.title.toString() })
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION, service.foregroundServiceType)
        assertTrue("collecting", "fixes" in host.log)
        assertTrue(host.sessions.active)
    }

    @Test
    fun `Stop sharing stops collection here, then revokes at the server, and the service ends`() = kotlinx.coroutines.test.runTest {
        host.sharingControl.start(Trip("trip-1", "load-1", "ACTIVE", "t", null, LocationConsent("g-1", "trip-1", "2026-10-23T10:00:00Z", null, null)), "ra-1")
        val controller = started()
        shadowOf(android.os.Looper.getMainLooper()).idle()

        val stop = shadowOf(controller.get()).lastForegroundNotification.actions.single().actionIntent
        val stopIntent = shadowOf(stop).savedIntent
        controller.withIntent(stopIntent).startCommand(0, 2)
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertFalse(host.sessions.active)
        assertEquals("revoke g-1", host.log.last { it.startsWith("revoke") })
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
    }

    @Test
    fun `the server ending consent ends the service and forgets the consent`() = kotlinx.coroutines.test.runTest {
        host.sharingControl.start(Trip("trip-1", "load-1", "ACTIVE", "t", null, LocationConsent("g-1", "trip-1", "2026-10-23T10:00:00Z", null, null)), "ra-1")
        val controller = started()
        shadowOf(android.os.Looper.getMainLooper()).idle()

        host.demand.send(DemandEvent.Ended(404, "NO_ACTIVE_CONSENT"))
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
        assertEquals(null, host.remembered())
    }

    // Section 25.3: the app coming to the foreground sends what is held.
    @Test
    fun `the app coming to the foreground reaches the running session`() = kotlinx.coroutines.test.runTest {
        started()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        host.log.clear()

        host.foreground.emit(Unit)
        shadowOf(android.os.Looper.getMainLooper()).idle()

        // The trickle held the fix; coming to the foreground sends it.
        assertEquals(listOf("sync"), host.log)
    }

    // Section 25.3: "Reboot receiver resumes only if the persisted consent is
    // valid and the active assignment still exists after server confirmation."
    @Test
    fun `after a reboot the remembered consent is checked with the server`() = kotlinx.coroutines.test.runTest {
        host.remember(record)

        SharingBootReceiver().onReceive(host, Intent(Intent.ACTION_BOOT_COMPLETED))
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertEquals(listOf("trips"), host.log)
        // Unconfirmed (no answer): nothing started.
        assertFalse(host.sessions.active)
    }

    @Test
    fun `the boot receiver ignores anything but a boot`() = kotlinx.coroutines.test.runTest {
        host.remember(record)
        SharingBootReceiver().onReceive(host, Intent(Intent.ACTION_TIME_CHANGED))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(emptyList<String>(), host.log)
    }

    @Test
    fun `a start without a consent is refused, not run`() {
        val service = Robolectric.buildService(LocationSharingService::class.java, Intent(LocationSharingService.ACTION_START)).create().startCommand(0, 1).get()
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertFalse(host.sessions.active)
    }

    @Test
    fun `the runner starts the service with the consent it shares under`() {
        ServiceSharingRunner(host, host.sessions).start(Sharing("trip-1", "g-1", granted), "ra-1")
        val intent = shadowOf(host).nextStartedService
        assertEquals(LocationSharingService.ACTION_START, intent.action)
        assertEquals(record, LocationSharingService.recordOf(intent))
    }
}
