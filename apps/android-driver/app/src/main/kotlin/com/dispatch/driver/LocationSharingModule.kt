package com.dispatch.driver

import android.content.Context
import android.location.LocationManager
import com.dispatch.driver.core.auth.DeviceKeys
import com.dispatch.driver.core.database.DispatchDatabase
import com.dispatch.driver.core.database.LocationOutbox
import com.dispatch.driver.core.model.capability.CapabilityProvider
import com.dispatch.driver.core.model.capability.CapabilityState
import com.dispatch.driver.core.model.capability.Features
import com.dispatch.driver.core.model.location.SampleTransport
import com.dispatch.driver.core.network.DispatchClient
import com.dispatch.driver.service.location.ConsentApi
import com.dispatch.driver.service.location.DemandSource
import com.dispatch.driver.service.location.LocationSharing
import com.dispatch.driver.service.location.OutboxSampleStore
import com.dispatch.driver.service.location.PlatformFixSource
import com.dispatch.driver.service.location.PrefsSharingRecords
import com.dispatch.driver.service.location.ServiceSharingRunner
import com.dispatch.driver.service.location.SharingControl
import com.dispatch.driver.service.location.SharingHost
import com.dispatch.driver.service.location.SharingRecord
import com.dispatch.driver.service.location.SharingRecords
import com.dispatch.driver.service.location.SharingSessions
import com.dispatch.driver.service.location.TripSharing
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/** Section 25.3's sharing, joined: the outbox, the client, the service and the status screen's card. */
@Module
@InstallIn(SingletonComponent::class)
object LocationSharingModule {

    @Provides @Singleton
    fun locationOutbox(database: DispatchDatabase, keys: DeviceKeys): LocationOutbox = LocationOutbox(database, signer = keys)

    @Provides
    fun sampleTransport(client: DispatchClient): SampleTransport = client

    @Provides @Singleton
    fun sessions(): SharingSessions = SharingSessions()

    @Provides @Singleton
    fun records(@ApplicationContext context: Context): SharingRecords =
        PrefsSharingRecords(context.getSharedPreferences("location-sharing", Context.MODE_PRIVATE))

    @Provides
    fun consentApi(client: DispatchClient): ConsentApi = object : ConsentApi {
        override suspend fun begin(roleAssignmentId: String, assignmentId: String, idempotencyKey: String) =
            client.beginLocationConsent(roleAssignmentId, assignmentId, idempotencyKey)
        override suspend fun revoke(roleAssignmentId: String, consentGrantId: String, idempotencyKey: String) =
            client.revokeLocationConsent(roleAssignmentId, consentGrantId, idempotencyKey)
        override suspend fun trips(roleAssignmentId: String) = client.assignments(roleAssignmentId)
    }

    @Provides @Singleton
    fun control(@ApplicationContext context: Context, api: ConsentApi, records: SharingRecords, sessions: SharingSessions): SharingControl =
        SharingControl(api, records, ServiceSharingRunner(context, sessions))

    /** The card: offered while the verified capability document offers `location_sharing`. */
    @Provides @Singleton
    fun tripSharing(
        capabilities: CapabilityProvider,
        api: ConsentApi,
        records: SharingRecords,
        sessions: SharingSessions,
        control: SharingControl,
    ): TripSharing {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val offered = capabilities.state
            .map { state -> (state as? CapabilityState.Ready)?.document?.takeIf { it.offers(Features.LOCATION_SHARING) }?.roleAssignmentId }
            .stateIn(scope, SharingStarted.Eagerly, null)
        return TripSharing(scope, offered, api, records, sessions, control)
    }
}

/** What LocationSharingService needs, from the app's graph (`SharingHost`). */
class AppSharingHost @Inject constructor(
    @ApplicationContext private val context: Context,
    override val sessions: SharingSessions,
    override val sharingControl: SharingControl,
    private val outbox: LocationOutbox,
    private val client: DispatchClient,
) : SharingHost {
    override fun sharingSession(record: SharingRecord): LocationSharing = LocationSharing(
        fixes = PlatformFixSource(context.getSystemService(LocationManager::class.java)),
        store = OutboxSampleStore(outbox, client, record),
        demand = DemandSource { client.demand(record.roleAssignmentId) },
    )
}
