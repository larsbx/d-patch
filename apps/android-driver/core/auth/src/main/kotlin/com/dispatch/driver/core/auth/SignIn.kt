package com.dispatch.driver.core.auth

import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * biz-kit's native sign-in (ADR-0011): RFC 8252 through the system browser,
 * with PKCE (RFC 7636). The app never meets the identity provider; it asks
 * biz-kit, which names none of it back.
 */
object Pkce {
    private val base64Url = Base64.getUrlEncoder().withoutPadding()

    /** S256, the only method biz-kit accepts: `plain` would hand the verifier to the browser. */
    fun challenge(verifier: String): String =
        base64Url.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    /** 32 random bytes, base64url: 43 characters, RFC 7636 §4.1's minimum length at full entropy. */
    internal fun token(random: SecureRandom): String = base64Url.encodeToString(ByteArray(32).also(random::nextBytes))
}

/** A sign-in waiting in the browser. Kept sealed: the verifier is what makes the code usable. */
@Serializable
data class PendingSignIn(val verifier: String, val state: String) {
    companion object {
        fun create(random: SecureRandom): PendingSignIn = PendingSignIn(Pkce.token(random), Pkce.token(random))
    }
}

/**
 * Where this build signs in. [redirectUri] is the app's private-use scheme
 * (RFC 8252 §7.1) and must be on biz-kit's `BIZKIT_NATIVE_REDIRECT_URIS`.
 */
data class SignInConfig(val authBaseUrl: String, val provider: String, val redirectUri: String) {

    fun authorizationUrl(pending: PendingSignIn): String =
        authBaseUrl.toHttpUrl().newBuilder()
            .addPathSegments("auth/$provider/native/login")
            .addQueryParameter("redirect_uri", redirectUri)
            .addQueryParameter("code_challenge", Pkce.challenge(pending.verifier))
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("state", pending.state)
            .build()
            .toString()

    /**
     * Reads what the browser handed back. Only a redirect to [redirectUri]
     * carrying the state of the sign-in in progress counts: any app can open
     * the scheme, so anything else is [Redirect.Unsolicited].
     */
    fun read(uri: String, pending: PendingSignIn?): Redirect {
        val target = uri.substringBefore('#')
        if (pending == null || target.substringBefore('?') != redirectUri) return Redirect.Unsolicited
        // The query is decoded as an HTTP URL's would be; the scheme is not.
        val query = "http://redirect/?${target.substringAfter('?', "")}".toHttpUrlOrNull() ?: return Redirect.Unsolicited
        val state = query.queryParameter("state") ?: return Redirect.Unsolicited
        if (!MessageDigest.isEqual(state.toByteArray(), pending.state.toByteArray())) return Redirect.Unsolicited

        return query.queryParameter("error")?.let(Redirect::Denied)
            ?: query.queryParameter("code")?.let(Redirect::Code)
            ?: Redirect.Unsolicited
    }
}

sealed interface Redirect {
    data class Code(val code: String) : Redirect

    /** The person cancelled, or biz-kit refused them; the reason stays in biz-kit's log. */
    data class Denied(val error: String) : Redirect

    /** Not the answer to the sign-in in progress. Changes nothing. */
    data object Unsolicited : Redirect
}
