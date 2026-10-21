package com.dispatch.driver.core.auth

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/** The app's half of biz-kit's native sign-in: PKCE, the login URL, and reading the redirect. */
class SignInTest {

    private val config = SignInConfig(
        authBaseUrl = "https://auth.example.test/",
        provider = "workos",
        redirectUri = "com.dispatch.driver:/oauth2redirect",
    )
    private val pending = PendingSignIn(verifier = "v".repeat(43), state = "state-1")

    @Test
    fun `the S256 challenge is RFC 7636's`() {
        // RFC 7636 Appendix B.
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }

    @Test
    fun `a fresh sign-in has an unpadded 43-character verifier and its own state`() {
        val random = SecureRandom()
        val a = PendingSignIn.create(random)
        val b = PendingSignIn.create(random)

        for (value in listOf(a.verifier, a.state)) {
            assertEquals(43, value.length)
            assertTrue(value, value.matches(Regex("[A-Za-z0-9_-]+")))
        }
        assertNotEquals(a.verifier, a.state)
        assertNotEquals(a, b)
    }

    @Test
    fun `the login URL names the provider and carries the challenge, never the verifier`() {
        val url = config.authorizationUrl(pending).toHttpUrl()

        assertEquals("/auth/workos/native/login", url.encodedPath)
        assertEquals(config.redirectUri, url.queryParameter("redirect_uri"))
        assertEquals(Pkce.challenge(pending.verifier), url.queryParameter("code_challenge"))
        assertEquals("S256", url.queryParameter("code_challenge_method"))
        assertEquals("state-1", url.queryParameter("state"))
        assertTrue(pending.verifier !in url.toString())
    }

    @Test
    fun `the login URL keeps a base path, with or without its trailing slash`() {
        for (base in listOf("https://example.test/identity", "https://example.test/identity/")) {
            assertEquals(
                "/identity/auth/workos/native/login",
                config.copy(authBaseUrl = base).authorizationUrl(pending).toHttpUrl().encodedPath,
            )
        }
    }

    @Test
    fun `the redirect for this sign-in yields its code`() {
        assertEquals(
            Redirect.Code("c 1"),
            config.read("com.dispatch.driver:/oauth2redirect?code=c%201&state=state-1", pending),
        )
    }

    @Test
    fun `a cancelled or refused sign-in is denied`() {
        assertEquals(
            Redirect.Denied("access_denied"),
            config.read("com.dispatch.driver:/oauth2redirect?error=access_denied&state=state-1", pending),
        )
    }

    @Test
    fun `a redirect this app did not ask for is ignored`() {
        val unsolicited = listOf(
            // Another state: a stale sign-in, or one injected by another app.
            "com.dispatch.driver:/oauth2redirect?code=c&state=state-2" to pending,
            "com.dispatch.driver:/oauth2redirect?code=c" to pending,
            "com.dispatch.driver:/oauth2redirect?error=access_denied&state=state-2" to pending,
            // No sign-in in progress.
            "com.dispatch.driver:/oauth2redirect?code=c&state=state-1" to null,
            // Another path or scheme.
            "com.dispatch.driver:/elsewhere?code=c&state=state-1" to pending,
            "com.dispatch.driver:/oauth2redirect-x?code=c&state=state-1" to pending,
            "https://example.test/oauth2redirect?code=c&state=state-1" to pending,
            // Ours, but carrying neither a code nor an error.
            "com.dispatch.driver:/oauth2redirect?state=state-1" to pending,
        )
        for ((uri, inProgress) in unsolicited) {
            assertEquals(uri, Redirect.Unsolicited, config.read(uri, inProgress))
        }
    }
}
