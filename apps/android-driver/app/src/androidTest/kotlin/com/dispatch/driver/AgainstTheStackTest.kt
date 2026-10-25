package com.dispatch.driver

import android.net.ConnectivityManager
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.dispatch.driver.core.auth.AuthApi
import com.dispatch.driver.core.auth.AuthResult
import com.dispatch.driver.core.auth.AuthSession
import com.dispatch.driver.core.auth.DeviceKeys
import com.dispatch.driver.core.database.DispatchDatabase
import com.dispatch.driver.core.database.PendingState
import com.dispatch.driver.core.database.StatusOutbox
import com.dispatch.driver.core.database.SyncOutcome
import com.dispatch.driver.core.model.capability.CapabilityProvider
import com.dispatch.driver.core.model.capability.CapabilityState
import com.dispatch.driver.core.model.status.StatusDeclaration
import com.dispatch.driver.core.model.status.SubmitResult
import com.dispatch.driver.core.model.status.StatusTransport
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject
import java.util.UUID

/**
 * The app's own graph against biz-kit's end-to-end stack on the host
 * (`scripts/e2e-stack.sh`, reached as 10.0.2.2): sign-in, the Keystore-sealed
 * device key, and the outbox, across a real offline interval.
 *
 * The one stand-in is the system browser, which follows biz-kit's redirects
 * here; everything from the redirect back to the app on is the app's code.
 * Build with `-Pdispatch.authProvider=driver` or `=courier`: either seeded
 * field role, through the same code (scenario 14).
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class AgainstTheStackTest {

    @get:Rule val hilt = HiltAndroidRule(this)

    @Inject lateinit var signIn: SignInFlow
    @Inject lateinit var auth: AuthSession
    @Inject lateinit var capabilities: CapabilityProvider
    @Inject lateinit var registration: DeviceRegistration
    @Inject lateinit var keys: DeviceKeys
    @Inject lateinit var outbox: StatusOutbox
    @Inject lateinit var transport: StatusTransport
    @Inject lateinit var database: DispatchDatabase

    @Before
    fun inject() {
        // HiltTestApplication is not DispatchApplication, which configures WorkManager.
        WorkManagerTestInitHelper.initializeTestWorkManager(InstrumentationRegistry.getInstrumentation().targetContext)
        hilt.inject()
    }

    @Test
    fun aFieldRoleSignsInRegistersTheDeviceAndWhatWasQueuedOfflineArrivesSigned() = runBlocking {
        signIn.finish(StackBrowser.follow(checkNotNull(signIn.start())))
        assertNull(signIn.message.value)

        // ADR-0009: the document verified against the pinned development key,
        // and the app goes by its features, never its role key (Section 23.2).
        val document = withTimeout(30_000) { capabilities.state.filterIsInstance<CapabilityState.Ready>().first() }.document
        assertTrue(document.offers("status"))

        // ADR-0012: the key sealed under the Keystore, registered with dispatch.
        registration.ensure()
        assertNotNull(keys.current())

        // Offline: declared and signed on the device; nothing reaches the server.
        // Inside the try: a wait for offline that times out still restores the network.
        val interval = UUID.randomUUID().toString()
        val queued = mutableListOf<Pair<StatusDeclaration, String>>()
        try {
            airplaneMode(true)
            observeServer("START", interval, 0)
            outbox.declare("AT_PICKUP", null, document.roleAssignmentId)
            delay(2_000)
            outbox.declare("LOADING", null, document.roleAssignmentId)
            queued += database.outbox().due("STATUS", Long.MAX_VALUE).map {
                StatusDeclaration.fromCanonicalJson(it.canonicalJson) to it.roleAssignmentId
            }
            assertEquals(2, queued.size)
            val workers = WorkManager.getInstance(InstrumentationRegistry.getInstrumentation().targetContext)
                .getWorkInfosForUniqueWork(SyncWorker.UNIQUE_NAME).get().map { it.state }
            AcceptanceTrace.record("workers=$workers")
            val offline = outbox.sync(transport)
            AcceptanceTrace.record("offline outcome=$offline states=${outbox.view().first().events.map { it.state }}")
            observeServer("OFFLINE", interval, 0)
            assertTrue("offline outcome=$offline", offline is SyncOutcome.RetryAt)
            assertEquals(listOf(PendingState.PENDING, PendingState.PENDING), outbox.view().first().events.map { it.state })
        } finally {
            airplaneMode(false)
        }

        // Back online: sent unchanged, oldest first, and accepted as signed. A
        // signature that did not verify would be refused, not acknowledged.
        withTimeout(60_000) {
            while (true) {
                outbox.retryNow()
                if (outbox.sync(transport) == SyncOutcome.Drained) break
                delay(2_000)
            }
        }
        val view = outbox.view().first()
        assertEquals(listOf(PendingState.ACKNOWLEDGED, PendingState.ACKNOWLEDGED), view.events.map { it.state })
        assertEquals("LOADING", view.acknowledged?.value)

        // Replaying the very same signed events cannot create another effect.
        for ((declaration, role) in queued) {
            assertTrue("replayed signed event", transport.submit(declaration, role) is SubmitResult.Stored)
        }
        observeServer("REPLAYED", interval, 2)

        // ADR-0011: local removal and confirmed remote logout are separate facts.
        val token = checkNotNull(auth.accessToken())
        val logout = auth.signOut()
        AcceptanceTrace.record("logout outcome=$logout localRemoved=${auth.accessToken() == null}")
        assertTrue(auth.signInRequired.value)
        assertNull(auth.accessToken())
        assertEquals("remote logout must be confirmed", AuthResult.Ok(Unit), logout)
        val probe = AuthApi(BuildConfig.AUTH_BASE_URL, AcceptanceTrace.client()).principal(token)
        // Keep the exact 401 requirement without leaking a principal on failure.
        assertTrue("prior session probe=${probe.javaClass.simpleName}", probe == AuthResult.Refused(401))
    }

    /**
     * Offline means no network, not a command sent: the shell command returns
     * before it has run, and the network drops some time after it has. Wi-Fi
     * goes too, since Android 13 may keep it on in airplane mode.
     */
    private suspend fun airplaneMode(on: Boolean) {
        shell("cmd connectivity airplane-mode ${if (on) "enable" else "disable"}")
        shell("cmd wifi set-wifi-enabled ${if (on) "disabled" else "enabled"}")
        AcceptanceTrace.record("network command offline=$on active=${connectivity.activeNetwork != null}")
        withTimeout(60_000) {
            while ((connectivity.activeNetwork != null) == on || !StackNetwork.ready(online = !on)) delay(200)
        }
        AcceptanceTrace.record("network settled offline=$on active=${connectivity.activeNetwork != null}")
    }

    /** The host reads aggregate counts, then acknowledges over adb while the network stays put. */
    private suspend fun observeServer(operation: String, interval: String, expected: Int) {
        if (InstrumentationRegistry.getArguments().getString("serverObserver") != "true") return
        AcceptanceTrace.record("observer $operation $interval")
        val file = "/data/local/tmp/dispatch-acceptance-$interval-$operation"
        val result = withTimeout(30_000) {
            var read = ""
            while (read.isEmpty()) {
                read = shell("cat $file 2>/dev/null").toString(Charsets.UTF_8).trim()
                if (read.isEmpty()) delay(200)
            }
            read
        }
        assertEquals("server effects at $operation", expected.toString(), result)
    }

    // Read to the end: that, not closing the descriptor, waits for the command.
    private fun shell(command: String) =
        ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command))
            .use { it.readBytes() }

    private val connectivity get() =
        InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(ConnectivityManager::class.java)
}
