package com.dispatch.driver

import android.app.Application

class DispatchApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        RuntimeGraph.initialize(this)
    }
}
