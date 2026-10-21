package com.dispatch.driver.core.auth

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** biz-kit's `/auth/token`, `/auth/me` and `/auth/logout`, against a real socket. */
class AuthApiTest {

    private val server = MockWebServer().apply { start() }
    private val api = AuthApi(server.url("/").toString())

    @After
    fun stop() = server.shutdown()

    @Test
    fun `a code and its verifier are exchanged for a bearer session`() = runTest {
        server.enqueue(MockResponse().setBody("""{"access_token":"s-1","token_type":"Bearer","expires_in":3600}"""))

        assertEquals(AuthResult.Ok(Grant("s-1", 3600)), api.exchange("c/1", "verifier", "com.dispatch.driver:/oauth2redirect"))

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/auth/token", request.path)
        assertEquals("application/x-www-form-urlencoded", request.getHeader("Content-Type")?.substringBefore(';'))
        assertEquals(
            "grant_type=authorization_code&code=c%2F1&code_verifier=verifier&redirect_uri=com.dispatch.driver%3A%2Foauth2redirect",
            request.body.readUtf8(),
        )
    }

    @Test
    fun `a grant without expires_in lasts until signed out`() = runTest {
        server.enqueue(MockResponse().setBody("""{"access_token":"s-1","token_type":"Bearer"}"""))

        assertEquals(AuthResult.Ok(Grant("s-1", expiresInSeconds = null)), api.exchange("c", "v", "r"))
    }

    @Test
    fun `a refused exchange, or a grant that is not a bearer session, is refused`() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}"""))
        server.enqueue(MockResponse().setBody("""{"access_token":"s-1","token_type":"MAC","expires_in":3600}"""))
        server.enqueue(MockResponse().setBody("""{"access_token":"s-1","token_type":"Bearer","expires_in":0}"""))
        server.enqueue(MockResponse().setBody("not json"))

        assertEquals(AuthResult.Refused(400), api.exchange("c", "v", "r"))
        repeat(3) { assertEquals(AuthResult.Refused(200), api.exchange("c", "v", "r")) }
    }

    @Test
    fun `no answer is unreachable, not refused`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        assertEquals(AuthResult.Unreachable, api.exchange("c", "v", "r"))
    }

    @Test
    fun `the session's principal is the subject capability documents are bound to`() = runTest {
        server.enqueue(MockResponse().setBody("""{"principal":"prn_1"}"""))
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"unauthenticated"}"""))

        assertEquals(AuthResult.Ok("prn_1"), api.principal("s-1"))
        assertEquals(AuthResult.Refused(401), api.principal("s-2"))

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/auth/me", request.path)
        assertEquals("Bearer s-1", request.getHeader("Authorization"))
    }

    @Test
    fun `logging out ends the bearer session on the server`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))

        assertEquals(AuthResult.Ok(Unit), api.logout("s-1"))

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/auth/logout", request.path)
        assertEquals("Bearer s-1", request.getHeader("Authorization"))
    }
}
