package com.dispatch.driver

import com.dispatch.driver.core.model.session.SessionProvider

/**
 * The session before `core:auth` exists.
 *
 * Section 23.1's OIDC sign-in with PKCE is `core:auth`'s, and it is not built
 * yet. Until it is, there is no session and the app says so everywhere: no
 * surface is offered, and nothing is declared under an identity the app does
 * not have. This is the absence of a session, stated — not a stand-in for one.
 */
object SignedOutSession : SessionProvider {
    override suspend fun accessToken(): String? = null
    override fun subject(): String? = null
    override fun selectedRoleAssignmentId(): String? = null
}
