package com.dispatch.driver

import android.Manifest
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.testing.WorkManagerTestInitHelper
import com.dispatch.driver.core.database.LocationOutbox
import com.dispatch.driver.core.database.PendingState
import com.dispatch.driver.core.database.StatusOutbox
import com.dispatch.driver.core.database.SyncOutcome
import com.dispatch.driver.core.model.capability.CapabilityProvider
import com.dispatch.driver.core.model.capability.CapabilityState
import com.dispatch.driver.core.model.capability.Features
import com.dispatch.driver.core.model.location.SampleTransport
import com.dispatch.driver.core.model.status.StatusTransport
import com.dispatch.driver.core.network.DispatchClient
import com.dispatch.driver.core.network.Fetch
import com.dispatch.driver.service.location.PlatformFixSource
import com.dispatch.driver.service.location.SharingCard
import com.dispatch.driver.service.location.SharingHost
import com.dispatch.driver.service.location.TripSharing
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import javax.inject.Inject

/**
 * Section 33.4's scenario 1 on the device, against biz-kit's stack: the driver
 * signs in, starts assignment sharing, a fix from the platform provider is
 * stored signed and accepted, `EN_ROUTE_PICKUP` is declared, and stopping
 * revokes the consent at the server. The portal half is Playwright's
 * (`location.spec.js`, `slice1.spec.js`).
 *
 * The provider is the emulator's own, fed through a test provider; the
 * courier build has no location capability and skips (scenario 14).
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class SharingAgainstTheStackTest {

    @get:Rule val hilt = HiltAndroidRule(this)

    @Inject lateinit var signIn: SignInFlow
    @Inject lateinit var capabilities: CapabilityProvider
    @Inject lateinit var registration: DeviceRegistration
    @Inject lateinit var host: AppSharingHost
    @Inject lateinit var trips: TripSharing
    @Inject lateinit var samples: LocationOutbox
    @Inject lateinit var sampleTransport: SampleTransport
    @Inject lateinit var statuses: StatusOutbox
    @Inject lateinit var statusTransport: StatusTransport
    @Inject lateinit var client: DispatchClient

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Before
    fun prepare() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        hilt.inject()
        // HiltTestApplication is not DispatchApplication, which installs this.
        SharingHost.install(host)
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.POST_NOTIFICATIONS)
            .forEach { instrumentation.uiAutomation.grantRuntimePermission(context.packageName, it) }
        shell("appops set ${context.packageName} android:mock_location allow")
    }

    @Test
    fun scenario1TheDriverStartsSharingDeclaresAndStops() = runBlocking {
        signIn.finish(StackBrowser.follow(checkNotNull(signIn.start())))
        val document = withTimeout(30_000) { capabilities.state.filterIsInstance<CapabilityState.Ready>().first() }.document
        assumeTrue("this role does not share location", document.offers(Features.LOCATION_SHARING))
        registration.ensure()

        // In the foreground, where a location service may start.
        ActivityScenario.launch(MainActivity::class.java).use {
            trips.refresh()
            withTimeout(30_000) { trips.state.first { it is SharingCard.Offer } }

            trips.share()
            withTimeout(30_000) { trips.state.first { it == SharingCard.Sharing } }

            // A fix from the platform provider is stored, signed, under the consent.
            // Stamped no earlier than the grant: the client rightly drops fixes
            // from before consent, and an emulator's clock can trail the
            // server's by minutes where a phone on network time does not.
            val grantedAt = (client.assignments(document.roleAssignmentId) as Fetch.Ok).value
                .firstNotNullOf { it.locationConsent }.grantedAt.let(Instant::parse).toEpochMilli()
            val provider = feed()
            withTimeout(60_000) {
                while (samples.pending() == 0) {
                    fix(provider, maxOf(System.currentTimeMillis(), grantedAt))
                    delay(1_000)
                }
            }
            assertEquals(SyncOutcome.Drained, samples.sync(sampleTransport))
            assertEquals(emptyList<String>(), samples.rejected())

            statuses.declare("EN_ROUTE_PICKUP", null, document.roleAssignmentId)
            assertEquals(SyncOutcome.Drained, statuses.sync(statusTransport))
            assertEquals(PendingState.ACKNOWLEDGED, statuses.view().first().events.first().state)

            // Stop sharing: collection ends here, and the server holds no consent.
            trips.stop()
            withTimeout(30_000) { trips.state.first { it is SharingCard.Offer } }
            val after = (client.assignments(document.roleAssignmentId) as Fetch.Ok).value
            assertNull(after.firstNotNullOfOrNull { it.locationConsent })
        }
    }

    private val locations get() = context.getSystemService(LocationManager::class.java)

    private fun feed(): String = PlatformFixSource.bestProvider(locations).also { provider ->
        locations.addTestProvider(
            provider,
            ProviderProperties.Builder().setAccuracy(ProviderProperties.ACCURACY_FINE).setPowerUsage(ProviderProperties.POWER_USAGE_LOW).build(),
        )
        locations.setTestProviderEnabled(provider, true)
    }

    private fun fix(provider: String, at: Long) = locations.setTestProviderLocation(
        provider,
        Location(provider).apply {
            latitude = 51.5074
            longitude = -0.1278
            accuracy = 5f
            time = at
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        },
    )

    private fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).close()
}
