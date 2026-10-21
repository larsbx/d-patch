package com.dispatch.driver

import com.dispatch.driver.core.auth.AuthApi
import com.dispatch.driver.core.auth.AuthRecord
import com.dispatch.driver.core.auth.AuthSession
import com.dispatch.driver.core.auth.SignInConfig
import com.dispatch.driver.core.auth.Vault
import com.dispatch.driver.core.model.capability.CapabilityProvider
import com.dispatch.driver.core.model.capability.CapabilityState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What a finished sign-in sets going, and what the participant is told when it does not finish. */
class SignInFlowTest {

    private val server = MockWebServer().apply { start() }
    private val config = SignInConfig(server.url("/").toString(), "workos", "com.dispatch.driver:/oauth2redirect")
    private val session = AuthSession(config, AuthApi(config.authBaseUrl), object : Vault {
        private var record = AuthRecord()
        override fun read() = record
        override fun write(record: AuthRecord) {
            this.record = record
        }
    })

    private var refreshes = 0
    private var syncs = 0
    private val capabilities = object : CapabilityProvider {
        override val state = MutableStateFlow<CapabilityState>(CapabilityState.SignedOut)
        override suspend fun refresh() {
            refreshes++
        }
    }
    private val flow = SignInFlow(session, capabilities, sync = { syncs++ })

    @After
    fun stop() = server.shutdown()

    private fun back(url: String, result: String) =
        "${config.redirectUri}?$result&state=${url.toHttpUrl().queryParameter("state")}"

    @Test
    fun `a session reloads the role and sends what waited for it`() = runTest {
        server.enqueue(MockResponse().setBody("""{"access_token":"s-1","token_type":"Bearer","expires_in":3600}"""))
        server.enqueue(MockResponse().setBody("""{"principal":"prn_1"}"""))

        flow.finish(back(flow.start(), "code=c-1"))

        assertEquals(1, refreshes)
        assertEquals(1, syncs)
        assertNull(flow.message.value)
    }

    @Test
    fun `a sign-in that does not finish says why, and sets nothing going`() = runTest {
        flow.finish(back(flow.start(), "error=access_denied"))
        assertEquals("Sign-in was cancelled.", flow.message.value)

        server.enqueue(MockResponse().setResponseCode(400))
        flow.finish(back(flow.start(), "code=c-1"))
        assertEquals("Sign-in did not complete. Try again.", flow.message.value)

        val url = flow.start()
        server.shutdown()
        flow.finish(back(url, "code=c-2"))
        assertEquals("Connect to the internet to sign in.", flow.message.value)

        assertEquals(0, refreshes)
        assertEquals(0, syncs)
    }

    @Test
    fun `an unsolicited redirect is ignored silently`() = runTest {
        flow.start()
        flow.finish("${config.redirectUri}?code=x&state=forged")

        assertNull(flow.message.value)
        assertEquals(0, refreshes)
    }
}
