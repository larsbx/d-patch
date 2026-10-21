package com.dispatch.driver.core.model.session

import kotlinx.coroutines.flow.StateFlow

/**
 * The authenticated session, as the rest of the app needs it.
 *
 * `core:auth` owns how a session is obtained (OIDC with PKCE, Section 23.1);
 * everything else depends on this port and nothing more, so the outbox and the
 * status screen are tested without an identity provider.
 */
interface SessionProvider {
    /** A current access token, or null when the participant must sign in. */
    suspend fun accessToken(): String?

    /** The verified OIDC subject of the signed-in participant, or null. */
    fun subject(): String?

    /** The role assignment the participant chose to act under, if any. */
    fun selectedRoleAssignmentId(): String?

    /**
     * True when the participant must sign in for anything to reach the
     * server: there is no session, or the server refused the one presented.
     * What works offline keeps working; this is when to offer sign-in.
     */
    val signInRequired: StateFlow<Boolean>

    /**
     * Reports that a request made with [token] was answered `401`. An answer
     * to a token that is no longer current — one replaced by a sign-in made
     * while the request was in flight — changes nothing.
     */
    fun rejected(token: String?)
}
