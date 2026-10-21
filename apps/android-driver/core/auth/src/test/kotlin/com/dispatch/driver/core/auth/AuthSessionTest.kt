package com.dispatch.driver.core.auth

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors

/**
 * The session the rest of the app sees (`SessionProvider`), from the browser
 * round trip to expiry and sign-out.
 */
class AuthSessionTest {

    private val server = MockWebServer().apply { start() }
    private val config = SignInConfig(server.url("/").toString(), "workos", "com.dispatch.driver:/oauth2redirect")
    private val vault = MemoryVault()
    private val clock = MutableClock(Instant.parse("2026-10-21T10:00:00Z"))

    private fun session() = AuthSession(config, AuthApi(config.authBaseUrl), vault, clock)

    @After
    fun stop() = server.shutdown()

    private fun granted(expiresIn: Long = 3600) {
        server.enqueue(MockResponse().setBody("""{"access_token":"s-1","token_type":"Bearer","expires_in":$expiresIn}"""))
        server.enqueue(MockResponse().setBody("""{"principal":"prn_1"}"""))
    }

    private fun AuthSession.started(): String = checkNotNull(begin())

    /** What the system browser hands back after biz-kit's callback. */
    private fun redirect(url: String, vararg params: Pair<String, String>): String {
        val state = url.toHttpUrl().queryParameter("state")
        return "com.dispatch.driver:/oauth2redirect?" +
            (params.toList() + ("state" to state!!)).joinToString("&") { (k, v) -> "$k=$v" }
    }

    @Test
    fun `a completed sign-in yields a token and the principal as subject`() = runTest {
        val session = session()
        val url = session.started()
        granted()

        assertEquals(SignInOutcome.SignedIn, session.complete(redirect(url, "code" to "c-1")))

        assertEquals("s-1", session.accessToken())
        assertEquals("prn_1", session.subject())
        val exchange = server.takeRequest().body.readUtf8()
        val verifier = exchange.substringAfter("code_verifier=").substringBefore('&')
        // The verifier sent is the one whose challenge went to the browser.
        assertEquals(url.toHttpUrl().queryParameter("code_challenge"), Pkce.challenge(verifier))
        assertNull("the verifier is spent", vault.read().pending)
    }

    @Test
    fun `before any sign-in there is no session`() = runTest {
        val session = session()

        assertNull(session.accessToken())
        assertNull(session.subject())
    }

    @Test
    fun `a sign-in survives the process, including one still in the browser`() = runTest {
        val url = session().started()
        granted()

        // The app was killed while the browser was open.
        val restarted = session()
        assertEquals(SignInOutcome.SignedIn, restarted.complete(redirect(url, "code" to "c-1")))

        assertEquals("s-1", session().accessToken())
        assertEquals("prn_1", session().subject())
    }

    @Test
    fun `an unsolicited redirect changes nothing, and the real one still completes`() = runTest {
        val session = session()
        val url = session.started()

        val forged = "com.dispatch.driver:/oauth2redirect?code=attacker&state=guess"
        assertEquals(SignInOutcome.Ignored, session.complete(forged))
        assertEquals(0, server.requestCount)

        granted()
        assertEquals(SignInOutcome.SignedIn, session.complete(redirect(url, "code" to "c-1")))
    }

    @Test
    fun `a redirect is used once`() = runTest {
        val session = session()
        val url = session.started()
        granted()
        val back = redirect(url, "code" to "c-1")

        assertEquals(SignInOutcome.SignedIn, session.complete(back))
        assertEquals(SignInOutcome.Ignored, session.complete(back))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a denied sign-in ends the attempt and leaves the previous session alone`() = runTest {
        val session = session()
        session.complete(redirect(session.started().also { granted() }, "code" to "c-1"))

        val url = session.started()
        assertEquals(SignInOutcome.Denied, session.complete(redirect(url, "error" to "access_denied")))

        assertNull(vault.read().pending)
        assertEquals("s-1", session.accessToken())
    }

    @Test
    fun `a refused or unanswered exchange fails without a session`() = runTest {
        val session = session()

        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}"""))
        assertEquals(SignInOutcome.Failed(reachable = true), session.complete(redirect(session.started(), "code" to "c-1")))

        val url = session.started()
        server.shutdown()
        assertEquals(SignInOutcome.Failed(reachable = false), session.complete(redirect(url, "code" to "c-2")))

        assertNull(session.accessToken())
        assertNull(session.subject())
    }

    @Test
    fun `a token whose principal cannot be read is not kept`() = runTest {
        val session = session()
        server.enqueue(MockResponse().setBody("""{"access_token":"s-1","token_type":"Bearer","expires_in":3600}"""))
        server.enqueue(MockResponse().setResponseCode(401))

        assertEquals(SignInOutcome.Failed(reachable = true), session.complete(redirect(session.started(), "code" to "c-1")))
        assertNull(session.accessToken())
    }

    @Test
    fun `an expired session sends no token but keeps its subject for offline work`() = runTest {
        val session = session()
        granted(expiresIn = 3600)
        session.complete(redirect(session.started(), "code" to "c-1"))

        clock.now = clock.now.plusSeconds(3600 - AuthSession.EXPIRY_MARGIN.seconds - 1)
        assertEquals("s-1", session.accessToken())

        clock.now = clock.now.plusSeconds(1)
        assertNull(session.accessToken())
        assertEquals("prn_1", session.subject())
    }

    @Test
    fun `signing out forgets the session here and ends it on the server`() = runTest {
        val session = session()
        granted()
        session.complete(redirect(session.started(), "code" to "c-1"))
        server.takeRequest()
        server.takeRequest()
        server.enqueue(MockResponse().setResponseCode(204))

        session.signOut()

        assertNull(session.accessToken())
        assertNull(session.subject())
        assertEquals(AuthRecord(), vault.read())
        val logout = server.takeRequest()
        assertEquals("/auth/logout", logout.path)
        assertEquals("Bearer s-1", logout.getHeader("Authorization"))
    }

    @Test
    fun `signing out offline still signs out here`() = runTest {
        val session = session()
        granted()
        session.complete(redirect(session.started(), "code" to "c-1"))
        server.shutdown()

        session.signOut()

        assertNull(session.subject())
        assertEquals(AuthRecord(), vault.read())
    }

    @Test
    fun `a sign-in whose verifier cannot be kept does not start`() {
        vault.refusing = true

        assertNull(session().begin())
    }

    @Test
    fun `a session that cannot be kept is not used`() = runTest {
        val session = session()
        val url = session.started()
        granted()
        vault.refusing = true

        assertEquals(SignInOutcome.Failed(reachable = true), session.complete(redirect(url, "code" to "c-1")))
        assertNull(session.accessToken())
        assertNull(session.subject())
    }

    @Test
    fun `a redirect delivered twice at once is exchanged once`() {
        // Every exchange is refused at once, so each round costs one request
        // per exchange made; two threads released together race for the code.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(400)
        }
        val session = session()
        val rounds = 300
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(rounds) {
                val back = redirect(session.started(), "code" to "c-$it")
                val barrier = CyclicBarrier(2)
                val outcomes = List(2) { pool.submit<SignInOutcome> { barrier.await(); runBlocking { session.complete(back) } } }.map { it.get() }
                assertEquals(setOf(SignInOutcome.Failed(reachable = true), SignInOutcome.Ignored), outcomes.toSet())
            }
        } finally {
            pool.shutdown()
        }
        assertEquals(rounds, server.requestCount)
    }

    @Test
    fun `sign-in is required without a session, and again once the server refuses the current one`() = runTest {
        val session = session()
        assertTrue(session.signInRequired.value)

        granted()
        session.complete(redirect(session.started(), "code" to "c-1"))
        assertFalse(session.signInRequired.value)

        // An answer to a token that is no longer current changes nothing.
        session.rejected("s-0")
        assertFalse(session.signInRequired.value)

        session.rejected("s-1")
        assertTrue(session.signInRequired.value)
    }

    @Test
    fun `an expired session is refused as no token at all`() = runTest {
        val session = session()
        granted()
        session.complete(redirect(session.started(), "code" to "c-1"))

        session.rejected(null)
        assertFalse("a tokenless request made before sign-in", session.signInRequired.value)

        clock.now = clock.now.plusSeconds(3600)
        session.rejected(session.accessToken())
        assertTrue(session.signInRequired.value)
    }

    @Test
    fun `signing out requires sign-in`() = runTest {
        val session = session()
        granted()
        session.complete(redirect(session.started(), "code" to "c-1"))
        server.shutdown()

        session.signOut()

        assertTrue(session.signInRequired.value)
    }

    private class MemoryVault : Vault {
        var refusing = false
        private var record = AuthRecord()
        override fun read() = record
        override fun write(record: AuthRecord) {
            if (refusing) throw IOException("full")
            this.record = record
        }
    }

    private class MutableClock(var now: Instant) : Clock() {
        override fun instant(): Instant = now
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
    }
}
