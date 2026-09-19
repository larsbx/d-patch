package com.dispatch.driver.di

import android.content.Context
import androidx.room.Room
import androidx.work.WorkManager
import com.dispatch.driver.BuildConfig
import com.dispatch.driver.core.auth.AuthorityStore
import com.dispatch.driver.core.auth.EncryptedAuthorityStore
import com.dispatch.driver.core.database.DispatchDatabase
import com.dispatch.driver.core.database.StatusOutboxDao
import com.dispatch.driver.core.model.StatusRepository
import com.dispatch.driver.core.network.RetrofitStatusTransport
import com.dispatch.driver.core.network.StatusTransport
import com.dispatch.driver.core.network.createStatusApi
import com.dispatch.driver.status.OfflineStatusRepository
import com.dispatch.driver.status.StatusSyncEnqueuer
import com.dispatch.driver.status.WorkManagerStatusSyncEnqueuer
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object DispatchProvidesModule {
    @Provides @Singleton
    fun json(): Json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = false
        }

    @Provides @Singleton
    fun database(@ApplicationContext context: Context): DispatchDatabase =
        Room.databaseBuilder(context, DispatchDatabase::class.java, "dispatch-driver.db").build()

    @Provides
    fun outboxDao(database: DispatchDatabase): StatusOutboxDao = database.statusOutboxDao()

    @Provides @Singleton
    fun authorityStore(@ApplicationContext context: Context): AuthorityStore =
        EncryptedAuthorityStore(context)

    @Provides @Singleton
    fun okHttpClient(): OkHttpClient = OkHttpClient.Builder().build()

    @Provides @Singleton
    fun statusTransport(client: OkHttpClient, json: Json): StatusTransport =
        RetrofitStatusTransport(
            api = createStatusApi(BuildConfig.API_BASE_URL, client, json),
            json = json,
        )

    @Provides @Singleton
    fun workManager(@ApplicationContext context: Context): WorkManager =
        WorkManager.getInstance(context)

    @Provides @Singleton
    fun clock(): Clock = Clock.systemUTC()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DispatchBindingsModule {
    @Binds @Singleton
    abstract fun statusRepository(repository: OfflineStatusRepository): StatusRepository

    @Binds @Singleton
    abstract fun statusSyncEnqueuer(enqueuer: WorkManagerStatusSyncEnqueuer): StatusSyncEnqueuer
}
