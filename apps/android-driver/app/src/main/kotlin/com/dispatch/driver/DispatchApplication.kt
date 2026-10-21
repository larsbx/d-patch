package com.dispatch.driver

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Hilt's root, WorkManager's worker factory so `SyncWorker` is injected, and
 * the start of device registration, which lives as long as the process.
 */
@HiltAndroidApp
class DispatchApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var deviceRegistration: DeviceRegistration

    override fun onCreate() {
        super.onCreate()
        deviceRegistration.start()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()
}
