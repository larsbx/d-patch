package com.dispatch.driver

import com.dispatch.driver.core.auth.AuthApi
import com.dispatch.driver.core.auth.AuthRecord
import com.dispatch.driver.core.auth.AuthSession
import com.dispatch.driver.core.auth.SignInConfig
import com.dispatch.driver.core.auth.Vault
import com.dispatch.driver.core.model.capability.CapabilityProvider
import com.dispatch.driver.core.model.capability.CapabilityState
import com.dispatch.driver.core.model.capability.SyncRequester
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** What a finished sign-in sets going, and what the participant is told when it does not finish. */
class SignInFlowTest {

    private val server = MockWebServer().apply { start() }
    private val config = SignInConfig(server.url("/").toString(), "workos", "com.dispatch.driver:/oauth2redirect")
    private var diskFull = false
    private val session = AuthSession(config, AuthApi(config.authBaseUrl), object : Vault {
        private var record = AuthRecord()
        override fun read() = record
        override fun write(record: AuthRecord) {
            if (diskFull) throw IOException("full")
            this.record = record
        }
    })

    private var refreshes = 0
    private var syncs = 0
    private var syncsNow = 0
    private val capabilities = object : CapabilityProvider {
        override val state = MutableStateFlow<CapabilityState>(CapabilityState.SignedOut)
        override suspend fun refresh() {
            refreshes++
        }
    }
    private val flow = SignInFlow(session, capabilities, object : SyncRequester {
        override fun requestSync() {
            syncs++
        }
        override fun requestSyncNow() {
            syncsNow++
        }
    })

    private fun SignInFlow.started(): String = checkNotNull(start())

    @After
    fun stop() = server.shutdown()

    private fun back(url: String, result: String) =
        "${config.redirectUri}?$result&state=${url.toHttpUrl().queryParameter("state")}"

    @Test
    fun `a session reloads the role and sends what waited for it`() = runTest {
        server.enqueue(MockResponse().setBody("""{"access_token":"s-1","token_type":"Bearer","expires_in":3600}"""))
        server.enqueue(MockResponse().setBody("""{"principal":"prn_1"}"""))

        flow.finish(back(flow.started(), "code=c-1"))

        assertEquals(1, refreshes)
        // Now, not after the backoff the refused session earned.
        assertEquals(1, syncsNow)
        assertEquals(0, syncs)
        assertNull(flow.message.value)
    }

    @Test
    fun `a superseded response never refreshes capabilities or starts synchronization`() = runBlocking {
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/auth/token" -> MockResponse().setBody("""{"access_token":"late","token_type":"Bearer"}""")
                "/auth/me" -> {
                    arrived.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    MockResponse().setBody("""{"principal":"principal"}""")
                }
                "/auth/logout" -> MockResponse().setResponseCode(204)
                else -> MockResponse().setResponseCode(404)
            }
        }
        val completion = async(Dispatchers.IO) { flow.finish(back(flow.started(), "code=c-late")) }
        try {
            withContext(Dispatchers.IO) { check(arrived.await(10, TimeUnit.SECONDS)) }
            session.signOut()
        } finally {
            release.countDown()
        }
        completion.await()
        assertNull(session.accessToken())
        assertEquals(0, refreshes)
        assertEquals(0, syncs + syncsNow)
        assertNull(flow.message.value)
    }

    @Test
    fun `a sign-in that does not finish says why, and sets nothing going`() = runTest {
        flow.finish(back(flow.started(), "error=access_denied"))
        assertEquals("Sign-in was cancelled.", flow.message.value)

        server.enqueue(MockResponse().setResponseCode(400))
        flow.finish(back(flow.started(), "code=c-1"))
        assertEquals("Sign-in did not complete. Try again.", flow.message.value)

        val url = flow.started()
        server.shutdown()
        flow.finish(back(url, "code=c-2"))
        assertEquals("Connect to the internet to sign in.", flow.message.value)

        assertEquals(0, refreshes)
        assertEquals(0, syncs + syncsNow)
    }

    @Test
    fun `a sign-in that cannot be kept does not open the browser, and says why`() {
        diskFull = true

        assertNull(flow.start())
        assertEquals("Sign-in could not start. Free some storage and try again.", flow.message.value)
    }

    @Test
    fun `an unsolicited redirect is ignored silently`() = runTest {
        flow.started()
        flow.finish("${config.redirectUri}?code=x&state=forged")

        assertNull(flow.message.value)
        assertEquals(0, refreshes)
    }
}
