package com.dispatch.driver.core.model.session

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
}
