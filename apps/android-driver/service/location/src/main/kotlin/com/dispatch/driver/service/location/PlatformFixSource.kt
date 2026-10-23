package com.dispatch.driver.service.location

import android.Manifest
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import androidx.annotation.RequiresPermission
import com.dispatch.driver.core.model.location.LocationFix
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.time.Instant

/**
 * Fixes from the platform's own [LocationManager]: no vendor SDK, as Section
 * 28.4 keeps those to `infra:*`. Registered while collected, released when the
 * collection ends, which is what `LocationSharing` cancels and joins.
 */
class PlatformFixSource(
    private val manager: LocationManager,
    private val provider: String = bestProvider(manager),
    private val looper: Looper = Looper.getMainLooper(),
) : FixSource {

    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    override fun fixes(intervalMs: Long): Flow<LocationFix> = callbackFlow {
        val listener = LocationListener { location -> location.toFix()?.let(::trySend) }
        manager.requestLocationUpdates(provider, intervalMs, 0f, listener, looper)
        awaitClose { manager.removeUpdates(listener) }
    }

    companion object {
        /** The platform's fused provider where it has one (API 31), else GPS. */
        fun bestProvider(manager: LocationManager): String =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && manager.hasProvider(LocationManager.FUSED_PROVIDER)) {
                LocationManager.FUSED_PROVIDER
            } else {
                LocationManager.GPS_PROVIDER
            }
    }
}

/** This location in the app's units, or null without an accuracy: Section 6.1 shows none without one. */
internal fun Location.toFix(): LocationFix? = if (!hasAccuracy()) null else LocationFix(
    lat = latitude,
    lng = longitude,
    accuracyM = accuracy.toDouble(),
    speedMps = if (hasSpeed()) speed.toDouble() else null,
    bearingDeg = if (hasBearing()) bearing.toDouble() else null,
    provider = provider ?: "unknown",
    isMock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) isMock else @Suppress("DEPRECATION") isFromMockProvider,
    occurredAt = Instant.ofEpochMilli(time),
)
