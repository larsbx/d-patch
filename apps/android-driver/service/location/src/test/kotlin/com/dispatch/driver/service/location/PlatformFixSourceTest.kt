package com.dispatch.driver.service.location

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import com.dispatch.driver.core.model.location.LocationFix
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.time.Instant

/** The platform's provider, as the sharing session sees it: fixes in the app's own units. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class PlatformFixSourceTest {

    private val manager = ApplicationProvider.getApplicationContext<Context>().getSystemService(LocationManager::class.java)
    private val source = PlatformFixSource(manager, provider = LocationManager.GPS_PROVIDER)

    init {
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
    }

    private fun location(configure: Location.() -> Unit) = Location(LocationManager.GPS_PROVIDER).apply {
        latitude = 51.5074123
        longitude = -0.1278456
        time = Instant.parse("2026-10-23T10:00:00Z").toEpochMilli()
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        configure()
    }

    @Test
    fun `fixes arrive in the app's units while collected, and the provider is released after`() = runTest(UnconfinedTestDispatcher()) {
        val fixes = mutableListOf<LocationFix>()
        val collecting = launch { source.fixes(3_000).toList(fixes) }

        shadowOf(manager).simulateLocation(location { accuracy = 12.5f; speed = 4f; bearing = 270f })
        // The provider honours the requested interval, as a real one does.
        ShadowSystemClock.advanceBy(Duration.ofMillis(3_000))
        shadowOf(manager).simulateLocation(location { accuracy = 30f; isMock = true })
        ShadowLooper.idleMainLooper()
        collecting.cancel()
        ShadowLooper.idleMainLooper()

        assertEquals(
            listOf(
                LocationFix(51.5074123, -0.1278456, 12.5, 4.0, 270.0, "gps", false, Instant.parse("2026-10-23T10:00:00Z")),
                LocationFix(51.5074123, -0.1278456, 30.0, null, null, "gps", true, Instant.parse("2026-10-23T10:00:00Z")),
            ),
            fixes,
        )
        assertEquals(emptyList<Any>(), shadowOf(manager).locationUpdateListeners)
    }

    // A position without its accuracy cannot be shown as Section 6.1 requires.
    @Test
    fun `a fix without accuracy is dropped`() = runTest(UnconfinedTestDispatcher()) {
        val fixes = mutableListOf<LocationFix>()
        val collecting = launch { source.fixes(3_000).toList(fixes) }

        shadowOf(manager).simulateLocation(location { removeAccuracy() })
        ShadowLooper.idleMainLooper()
        collecting.cancel()

        assertEquals(emptyList<LocationFix>(), fixes)
    }

    @Test
    fun `the provider is asked for the session's interval`() = runTest(UnconfinedTestDispatcher()) {
        val collecting = launch { source.fixes(120_000).toList(mutableListOf()) }
        ShadowLooper.idleMainLooper()

        assertEquals(120_000L, shadowOf(manager).getLocationRequests(LocationManager.GPS_PROVIDER).single().intervalMillis)
        collecting.cancel()
    }

    // minSdk 29: no `Location.isMock` there; the older flag is read instead.
    @Test
    @Config(sdk = [29])
    fun `on API 29 a fix converts just the same`() = runTest(UnconfinedTestDispatcher()) {
        val fixes = mutableListOf<LocationFix>()
        val collecting = launch { source.fixes(3_000).toList(fixes) }

        shadowOf(manager).simulateLocation(location { accuracy = 8f })
        ShadowLooper.idleMainLooper()
        collecting.cancel()

        assertEquals(listOf(LocationFix(51.5074123, -0.1278456, 8.0, null, null, "gps", false, Instant.parse("2026-10-23T10:00:00Z"))), fixes)
    }
}
