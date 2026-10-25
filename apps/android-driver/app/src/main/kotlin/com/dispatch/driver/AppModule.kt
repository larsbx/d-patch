package com.dispatch.driver

import android.content.Context
import androidx.work.WorkManager
import com.dispatch.driver.core.auth.AesGcmSealer
import com.dispatch.driver.core.auth.AuthApi
import com.dispatch.driver.core.auth.AuthSession
import com.dispatch.driver.core.auth.DeviceKeys
import com.dispatch.driver.core.auth.SealedPrefsVault
import com.dispatch.driver.core.auth.Sealer
import com.dispatch.driver.core.auth.SignInConfig
import com.dispatch.driver.core.database.CapabilityStore
import com.dispatch.driver.core.database.DispatchDatabase
import com.dispatch.driver.core.database.StatusOutbox
import com.dispatch.driver.core.model.capability.CapabilityDocumentVerifier
import com.dispatch.driver.core.model.capability.CapabilityProvider
import com.dispatch.driver.core.model.capability.SyncRequester
import com.dispatch.driver.core.model.session.SessionProvider
import com.dispatch.driver.core.model.status.StatusTransport
import com.dispatch.driver.core.network.DispatchClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import okhttp3.OkHttpClient

/** The composition root. Core modules take plain constructors; only here are they joined. */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun database(@ApplicationContext context: Context): DispatchDatabase = DispatchDatabase.open(context)

    @Provides @Singleton
    fun outbox(database: DispatchDatabase, keys: DeviceKeys): StatusOutbox = StatusOutbox(database, signer = keys)

    /** Section 23.1's Keystore-backed storage, for the session and the device key alike. */
    @Provides @Singleton
    fun sealer(): Sealer = AesGcmSealer.keystore()

    /** ADR-0011: a biz-kit session from native sign-in, sealed by a Keystore key. */
    @Provides @Singleton
    fun authSession(@ApplicationContext context: Context, sealer: Sealer, http: OkHttpClient): AuthSession {
        val config = SignInConfig(BuildConfig.AUTH_BASE_URL, BuildConfig.AUTH_PROVIDER, "${BuildConfig.APPLICATION_ID}:/oauth2redirect")
        val vault = SealedPrefsVault(context.getSharedPreferences("auth", Context.MODE_PRIVATE), sealer)
        return AuthSession(config, AuthApi(config.authBaseUrl, http), vault)
    }

    /** ADR-0012: this installation's Ed25519 key, sealed beside the session. */
    @Provides @Singleton
    fun deviceKeys(@ApplicationContext context: Context, sealer: Sealer, auth: AuthSession): DeviceKeys {
        return DeviceKeys(DeviceKeys.store(context.getSharedPreferences("auth", Context.MODE_PRIVATE), sealer), auth)
    }

    @Provides @Singleton
    fun deviceRegistration(keys: DeviceKeys, capabilities: CapabilityProvider, client: DispatchClient): DeviceRegistration =
        DeviceRegistration(keys, capabilities, client::registerDevice)

    @Provides
    fun session(auth: AuthSession): SessionProvider = auth

    @Provides @Singleton
    fun signIn(auth: AuthSession, capabilities: CapabilityProvider, sync: SyncRequester): SignInFlow = SignInFlow(auth, capabilities, sync)

    @Provides @Singleton
    fun client(session: SessionProvider, http: OkHttpClient): DispatchClient = DispatchClient.create(BuildConfig.API_BASE_URL, session, http)

    @Provides
    fun transport(client: DispatchClient): StatusTransport = client

    /** ADR-0009: the key is pinned at build time, never fetched. */
    @Provides @Singleton
    fun verifier(): CapabilityDocumentVerifier =
        CapabilityDocumentVerifier(CapabilityDocumentVerifier.publicKey(BuildConfig.CAPABILITY_PUBLIC_KEY))

    @Provides @Singleton
    fun capabilities(session: SessionProvider, database: DispatchDatabase, verifier: CapabilityDocumentVerifier, client: DispatchClient): CapabilityProvider =
        CapabilityRepository(session, CapabilityStore(database, verifier), client::capabilityDocument)

    @Provides @Singleton
    fun syncRequester(@ApplicationContext context: Context): SyncRequester = WorkManagerSyncRequester(WorkManager.getInstance(context))
}
