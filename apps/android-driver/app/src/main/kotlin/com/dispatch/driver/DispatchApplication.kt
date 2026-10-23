package com.dispatch.driver

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.dispatch.driver.service.location.SharingHost
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Hilt's root, WorkManager's worker factory so `SyncWorker` is injected, the
 * start of device registration, which lives as long as the process, and the
 * location service's host.
 */
@HiltAndroidApp
class DispatchApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var sharingHost: AppSharingHost

    @Inject lateinit var deviceRegistration: DeviceRegistration

    override fun onCreate() {
        super.onCreate()
        // Before any service of this process can start (LocationSharingService).
        SharingHost.install(sharingHost)
        deviceRegistration.start()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()
}
