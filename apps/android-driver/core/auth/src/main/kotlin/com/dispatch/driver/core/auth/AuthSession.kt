package com.dispatch.driver.core.auth

import com.dispatch.driver.core.model.session.SessionProvider
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration

sealed interface SignInOutcome {
    data object SignedIn : SignInOutcome
    data object Denied : SignInOutcome

    /** No session; [reachable] says whether biz-kit answered at all. */
    data class Failed(val reachable: Boolean) : SignInOutcome

    /** The redirect was not for the sign-in in progress; nothing changed. */
    data object Ignored : SignInOutcome
}

/**
 * The session the app runs under: a biz-kit session obtained by native sign-in
 * (ADR-0011), kept sealed in [vault].
 *
 * An expired session sends no token, so dispatch answers `401` and queued work
 * waits for the next sign-in. Its subject is kept until sign-out, so the stored
 * capability document still verifies offline.
 */
class AuthSession(
    private val config: SignInConfig,
    private val api: AuthApi,
    private val vault: Vault,
    private val clock: Clock = Clock.systemUTC(),
    private val random: SecureRandom = SecureRandom(),
) : SessionProvider {

    @Volatile private var record: AuthRecord = vault.read()

    /** Starts a sign-in; returns the URL to open in the system browser. */
    fun begin(): String {
        val pending = PendingSignIn.create(random)
        save(record.copy(pending = pending))
        return config.authorizationUrl(pending)
    }

    /** Finishes the sign-in the browser has returned from. */
    suspend fun complete(redirect: String): SignInOutcome {
        val pending = record.pending
        val code = when (val read = config.read(redirect, pending)) {
            Redirect.Unsolicited -> return SignInOutcome.Ignored
            is Redirect.Denied -> null
            is Redirect.Code -> read.code
        }
        // Spent before the exchange, whatever it answers: biz-kit spends the code too.
        save(record.copy(pending = null))
        code ?: return SignInOutcome.Denied

        val grant = when (val exchanged = api.exchange(code, checkNotNull(pending).verifier, config.redirectUri)) {
            is AuthResult.Ok -> exchanged.value
            else -> return failed(exchanged)
        }
        val subject = when (val principal = api.principal(grant.accessToken)) {
            is AuthResult.Ok -> principal.value
            else -> return failed(principal)
        }
        save(AuthRecord(session = SignedIn(grant.accessToken, subject, clock.millis() + grant.expiresInSeconds * 1000)))
        return SignInOutcome.SignedIn
    }

    /** Forgets the session here at once, then ends it on biz-kit if reachable. */
    suspend fun signOut() {
        val ended = record.session
        save(AuthRecord())
        ended?.let { api.logout(it.accessToken) }
    }

    override suspend fun accessToken(): String? =
        record.session?.takeIf { clock.millis() < it.expiresAtEpochMs - EXPIRY_MARGIN.toMillis() }?.accessToken

    override fun subject(): String? = record.session?.subject

    /** Section 4.3's role switcher is not built; the server picks the only active assignment. */
    override fun selectedRoleAssignmentId(): String? = null

    private fun failed(result: AuthResult<*>) = SignInOutcome.Failed(reachable = result !is AuthResult.Unreachable)

    @Synchronized
    private fun save(next: AuthRecord) {
        vault.write(next)
        record = next
    }

    companion object {
        /** A token this close to expiry is not sent: it could lapse in flight. */
        val EXPIRY_MARGIN: Duration = Duration.ofSeconds(30)
    }
}
