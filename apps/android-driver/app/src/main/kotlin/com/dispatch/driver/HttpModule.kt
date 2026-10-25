package com.dispatch.driver

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient

/** Unscoped: auth and dispatch keep separate pools, as before. */
@Module
@InstallIn(SingletonComponent::class)
object HttpModule {
    @Provides fun http(): OkHttpClient = OkHttpClient()
}
