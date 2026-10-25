package com.dispatch.driver.core.auth

import com.dispatch.driver.core.model.session.SessionProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
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

    /** A newer intent won; this one-shot cleanup result never implies local sign-in. */
    data class Superseded(val remoteLogout: AuthResult<Unit>) : SignInOutcome
}

/**
 * The session the app runs under: a biz-kit session obtained by native sign-in
 * (ADR-0011), kept sealed in [vault]. It is sent until the person signs out:
 * biz-kit's native sessions have no time limit. Only the server ends it early
 * (revocation, unbinding), answering `401`, which turns [signInRequired] on
 * while queued work waits. A session the server did limit is not sent past its
 * expiry. The subject is kept until sign-out, so the stored capability
 * document still verifies offline.
 */
class AuthSession(
    private val config: SignInConfig,
    private val api: AuthApi,
    private val vault: Vault,
    private val clock: Clock = Clock.systemUTC(),
    private val random: SecureRandom = SecureRandom(),
) : SessionProvider {

    @Volatile private var record: AuthRecord = vault.read()

    // Identity, rather than a wrapping counter. Read/rotate/admit under this monitor.
    private var intent = Any()
    private data class Completion(val read: Redirect, val pending: PendingSignIn, val intent: Any)

    private val _signInRequired = MutableStateFlow(record.session == null)
    override val signInRequired: StateFlow<Boolean> = _signInRequired.asStateFlow()

    /**
     * Starts a sign-in; returns the URL to open in the system browser, or null
     * when it cannot be kept on disk — its verifier would not survive the app
     * being stopped while the browser is open.
     */
    @Synchronized
    fun begin(): String? {
        val pending = PendingSignIn.create(random)
        if (!keep(record.copy(pending = pending))) return null
        intent = Any()
        return config.authorizationUrl(pending)
    }

    /** Finishes the sign-in the browser has returned from. */
    suspend fun complete(redirect: String): SignInOutcome {
        val (read, pending, startedIntent) = consume(redirect) ?: return SignInOutcome.Ignored
        val code = when (read) {
            is Redirect.Code -> read.code
            else -> return SignInOutcome.Denied
        }

        val grant = when (val exchanged = api.exchange(code, pending.verifier, config.redirectUri)) {
            is AuthResult.Ok -> exchanged.value
            else -> return failed(exchanged)
        }
        if (!isCurrent(startedIntent)) return SignInOutcome.Superseded(api.logout(grant.accessToken))
        val subject = when (val principal = api.principal(grant.accessToken)) {
            is AuthResult.Ok -> principal.value
            else -> return if (!isCurrent(startedIntent)) SignInOutcome.Superseded(api.logout(grant.accessToken)) else failed(principal)
        }
        val signedIn = SignedIn(grant.accessToken, subject, grant.expiresInSeconds?.let { clock.millis() + it * 1000 })
        return admit(startedIntent, signedIn)
            ?: SignInOutcome.Superseded(api.logout(grant.accessToken))
    }

    /**
     * Forgets the session here at once, then attempts remote logout once.
     * The result reports delivery separately from local removal; null means
     * there was no local session. Unreachable never confirms revocation.
     */
    suspend fun signOut(): AuthResult<Unit>? {
        val ended = endLocal()
        return ended?.let { api.logout(it.accessToken) }
    }

    override suspend fun accessToken(): String? = current()

    override fun subject(): String? = record.session?.subject

    /** Section 4.3's role switcher is not built; the server picks the only active assignment. */
    override fun selectedRoleAssignmentId(): String? = null

    override fun rejected(token: String?) {
        if (token == current()) _signInRequired.value = true
    }

    private fun current(): String? =
        record.session?.takeIf { session ->
            session.expiresAtEpochMs.let { it == null || clock.millis() < it - EXPIRY_MARGIN.toMillis() }
        }?.accessToken

    private fun failed(result: AuthResult<*>) = SignInOutcome.Failed(reachable = result !is AuthResult.Unreachable)

    /**
     * Reads [redirect] against the sign-in in progress and, if it answers it,
     * spends that sign-in — in one step, so a redirect delivered twice is
     * exchanged once. Spent whatever the exchange answers: biz-kit spends the
     * code too.
     */
    @Synchronized
    private fun consume(redirect: String): Completion? {
        val pending = record.pending ?: return null
        val read = config.read(redirect, pending)
        if (read == Redirect.Unsolicited) return null
        forget { it.copy(pending = null) }
        return Completion(read, pending, intent)
    }

    @Synchronized
    private fun isCurrent(startedIntent: Any): Boolean = startedIntent === intent

    /** Admission and local sign-out are atomic relative to each other and begin. */
    @Synchronized
    private fun admit(startedIntent: Any, signedIn: SignedIn): SignInOutcome? {
        if (startedIntent !== intent) return null
        if (!keep(AuthRecord(session = signedIn))) return SignInOutcome.Failed(reachable = true)
        _signInRequired.value = false
        return SignInOutcome.SignedIn
    }

    @Synchronized
    private fun endLocal(): SignedIn? {
        intent = Any()
        val ended = forget { AuthRecord() }.session
        _signInRequired.value = true
        return ended
    }

    /** Stores [next]; false, with nothing changed, when the disk refuses it. */
    @Synchronized
    private fun keep(next: AuthRecord): Boolean =
        try {
            vault.write(next)
            record = next
            true
        } catch (_: IOException) {
            false
        }

    /**
     * Forgetting takes effect here at once, and on disk if it can: a forgotten
     * verifier or session must not stay usable because storage was full.
     * Returns what was forgotten.
     */
    @Synchronized
    private fun forget(change: (AuthRecord) -> AuthRecord): AuthRecord {
        val previous = record
        record = change(previous)
        try {
            vault.write(record)
        } catch (_: IOException) {
            // Kept in memory; the next successful write replaces what is on disk.
        }
        return previous
    }

    companion object {
        /** A token this close to expiry is not sent: it could lapse in flight. */
        val EXPIRY_MARGIN: Duration = Duration.ofSeconds(30)
    }
}
