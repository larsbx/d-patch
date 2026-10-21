package com.dispatch.driver.core.auth

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.security.MessageDigest
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
class NativeSessionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs = context.getSharedPreferences("native-session-test", Context.MODE_PRIVATE)
    private val server = MockWebServer()
    @Before fun start() { prefs.edit().clear().commit(); server.start() }
    @After fun stop() { server.shutdown() }
    @Test fun `PKCE state is checked before exchange and session survives process recreation`() = runTest {
        val session = NativeSession(prefs, server.url("/").toString())
        server.enqueue(MockResponse().setBody(JSONObject().put("authorization_url", server.url("/v1/mobile/login")).toString()))
        session.beginSignIn()
        val begin = server.takeRequest()
        val challenge = JSONObject(begin.body.readUtf8()).getString("code_challenge")
        val verifier = requireNotNull(prefs.getString("pkce", null))
        assertEquals(Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())), challenge)
        val bad = runCatching { session.completeSignIn(Uri.parse("com.dispatch.driver://signin?state=wrong&code=one-time")) }
        assertTrue(bad.isFailure)
        assertNull(session.subject())
        val state = prefs.getString("state", null)
        server.enqueue(MockResponse().setBody("""{"access_token":"local-session","token_type":"BizKit","subject":"account","principal":"prn_test","expires_at":9999999999999}"""))
        session.completeSignIn(Uri.parse("com.dispatch.driver://signin?state=$state&code=one-time"))
        val exchange = server.takeRequest()
        assertEquals(verifier, JSONObject(exchange.body.readUtf8()).getString("code_verifier"))
        val restarted = NativeSession(prefs, server.url("/").toString())
        assertEquals("account", restarted.subject())
        assertEquals("local-session", restarted.accessToken())
        assertEquals("BizKit", restarted.authorizationScheme())
        assertNull(prefs.getString("pkce", null))
        prefs.edit().putLong("expires", 0).commit()
        assertNull(restarted.accessToken())
        assertEquals("account", restarted.subject()) // offline declarations retain their owner
    }
    @Test fun `signing requires an enrolled device and keeps the original role and key`() = runTest {
        prefs.edit().putString("subject", "account").putString("role", "role-a").commit()
        val session = NativeSession(prefs, server.url("/").toString())
        val declaration = com.dispatch.driver.core.model.status.StatusDeclaration("event", "AT_PICKUP", "2026-10-21T04:16:00Z", deviceSequence = 1)
        assertTrue(runCatching { session.sign(declaration, "role-a") }.isFailure)
        prefs.edit().putString("key:account:device", "device-a").commit()
        val first = session.sign(declaration, "role-a")
        val restarted = NativeSession(prefs, server.url("/").toString())
        assertEquals(first, restarted.sign(declaration, "role-a"))
        restarted.chooseRole("role-b")
        assertTrue(runCatching { restarted.sign(declaration, "role-a") }.isFailure)
        assertTrue(restarted.deviceReady())
    }
    @Test fun `logout clears session and role even when the server cannot revoke it`() = runTest {
        prefs.edit().putString("token", "token").putLong("expires", 9999999999999).putString("subject", "account").putString("role", "role").commit()
        val session = NativeSession(prefs, server.url("/").toString())
        server.enqueue(MockResponse().setResponseCode(503))
        assertTrue(runCatching { session.signOut() }.isFailure)
        assertNull(session.accessToken()); assertNull(session.subject()); assertNull(session.selectedRoleAssignmentId())
    }
    @Test fun `a server statement outside the device enrolment schema is never signed`() = runTest {
        prefs.edit().putString("token", "token").putLong("expires", 9999999999999)
            .putString("subject", "account").putString("principal", "principal").putString("role", "role").commit()
        val session = NativeSession(prefs, server.url("/").toString())
        server.enqueue(MockResponse().setBody("""{"id":"challenge","statement":"AA"}"""))
        assertTrue(runCatching { session.requestEnrolment() }.isFailure)
        assertEquals(1, server.requestCount) // request only; no proof signature is submitted
    }

}
