package com.dispatch.driver.core.database

import com.dispatch.driver.core.model.capability.CapabilityDocumentVerifier
import com.dispatch.driver.core.model.capability.CapabilityDocumentVerifier.Binding
import com.dispatch.driver.core.model.capability.CapabilityDocumentVerifier.Result

/**
 * Keeps the last capability document for offline use (ADR-0009).
 *
 * Only a document that verifies is stored, and a stored one is verified again
 * on every read: the database is not a trust boundary, and the session it is
 * read for may not be the one it was fetched for.
 */
class CapabilityStore(
    database: DispatchDatabase,
    private val verifier: CapabilityDocumentVerifier,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dao = database.outbox()

    /** Verifies [token] for [binding] and keeps it if it passes. */
    suspend fun accept(token: String, binding: Binding): Result =
        verifier.verify(token, binding, clock() / 1_000).also {
            if (it is Result.Verified) dao.putCapabilityDocument(CachedCapabilityDocumentEntity(token = token, fetchedAt = clock()))
        }

    /** The stored document, if one exists and still verifies for [binding]. */
    suspend fun current(binding: Binding): Result.Verified? =
        dao.capabilityDocument()?.let { verifier.verify(it.token, binding, clock() / 1_000) as? Result.Verified }

    /** Signing out or switching role discards it. */
    suspend fun clear() = dao.clearCapabilityDocument()
}
