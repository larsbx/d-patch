package com.dispatch.driver

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.testing.WorkManagerTestInitHelper
import com.dispatch.driver.core.auth.AuthApi
import com.dispatch.driver.core.auth.AuthResult
import com.dispatch.driver.core.auth.AuthSession
import com.dispatch.driver.core.auth.DeviceKeys
import com.dispatch.driver.core.database.PendingState
import com.dispatch.driver.core.database.StatusOutbox
import com.dispatch.driver.core.database.SyncOutcome
import com.dispatch.driver.core.model.capability.CapabilityProvider
import com.dispatch.driver.core.model.capability.CapabilityState
import com.dispatch.driver.core.model.status.StatusTransport
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * The app's own graph against biz-kit's end-to-end stack on the host
 * (`scripts/e2e-stack.sh`, reached as 10.0.2.2): sign-in, the Keystore-sealed
 * device key, and the outbox, across a real offline interval.
 *
 * The one stand-in is the system browser, which follows biz-kit's redirects
 * here; everything from the redirect back to the app on is the app's code.
 * Build with `-Pdispatch.authProvider=driver`, the stack's seeded driver.
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

    @Before
    fun inject() {
        // HiltTestApplication is not DispatchApplication, which configures WorkManager.
        WorkManagerTestInitHelper.initializeTestWorkManager(InstrumentationRegistry.getInstrumentation().targetContext)
        hilt.inject()
    }

    @Test
    fun aDriverSignsInRegistersTheDeviceAndWhatWasQueuedOfflineArrivesSigned() = runBlocking {
        signIn.finish(browse(checkNotNull(signIn.start())))
        assertNull(signIn.message.value)

        // ADR-0009: the document verified against the pinned development key,
        // and the app goes by its features, never its role key (Section 23.2).
        val document = withTimeout(30_000) { capabilities.state.filterIsInstance<CapabilityState.Ready>().first() }.document
        assertTrue(document.offers("status"))

        // ADR-0012: the key sealed under the Keystore, registered with dispatch.
        registration.ensure()
        assertNotNull(keys.current())

        // Offline: declared and signed on the device; nothing reaches the server.
        airplaneMode(true)
        try {
            outbox.declare("AT_PICKUP", null, document.roleAssignmentId)
            delay(2_000)
            outbox.declare("LOADING", null, document.roleAssignmentId)
            assertTrue(outbox.sync(transport) is SyncOutcome.RetryAt)
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

        // ADR-0011: the session lasts until sign-out, and ends on the server with it.
        val token = checkNotNull(auth.accessToken())
        auth.signOut()
        assertTrue(auth.signInRequired.value)
        assertEquals(AuthResult.Refused(401), AuthApi(BuildConfig.AUTH_BASE_URL).principal(token))
    }

    /** The system browser: biz-kit's login, the provider, and the callback back to the app. */
    private tailrec fun browse(url: String): String {
        if (url.startsWith("${BuildConfig.APPLICATION_ID}:")) return url
        val next = browser.newCall(Request.Builder().url(url).build()).execute().use { response ->
            val location = checkNotNull(response.header("Location")) { "no redirect from $url: ${response.code}" }
            if (location.startsWith("${BuildConfig.APPLICATION_ID}:")) location else checkNotNull(response.request.url.resolve(location)).toString()
        }
        return browse(next)
    }

    private val browser = OkHttpClient.Builder()
        .followRedirects(false)
        .cookieJar(object : CookieJar {
            private val jar = mutableListOf<Cookie>()
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                jar.removeAll { kept -> cookies.any { it.name == kept.name } }
                jar += cookies
            }
            override fun loadForRequest(url: HttpUrl): List<Cookie> = jar.filter { it.matches(url) }
        })
        .build()

    private fun airplaneMode(on: Boolean) {
        val command = "cmd connectivity airplane-mode ${if (on) "enable" else "disable"}"
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).close()
    }
}
