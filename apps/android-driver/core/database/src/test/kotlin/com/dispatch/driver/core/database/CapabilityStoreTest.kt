package com.dispatch.driver.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dispatch.driver.core.model.capability.CapabilityDocumentVerifier
import com.dispatch.driver.core.model.capability.CapabilityDocumentVerifier.Binding
import com.dispatch.driver.core.model.capability.CapabilityDocumentVerifier.Result
import com.dispatch.driver.core.model.capability.SignedDocument
import kotlinx.serialization.json.Json
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** ADR-0009: storage is not a trust boundary, so what is read back is verified again. */
@RunWith(RobolectricTestRunner::class)
class CapabilityStoreTest {

    private val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), DispatchDatabase::class.java)
        .allowMainThreadQueries()
        .build()

    private val developmentKey = CapabilityDocumentVerifier.publicKey(
        generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "infra/capability-signing/development.pub") }
            .first { it.isFile }
            .readText(),
    )

    private val store = CapabilityStore(database, CapabilityDocumentVerifier(developmentKey), clock = { 1_790_000_001_000 })

    private val token: SignedDocument = Json.decodeFromString(
        SignedDocument.serializer(),
        checkNotNull(javaClass.classLoader?.getResource("server-signed-capability-document.json")).readText(),
    )
    private val owner = Binding(subject = "fixture-subject", roleAssignmentId = "0192a000-0000-7000-8000-000000000003")

    @After
    fun close() = database.close()

    @Test
    fun `a verified document is kept and read back for its own session`() = runTest {
        assertTrue(store.accept(token, owner) is Result.Verified)

        assertEquals(setOf("home", "status"), store.current(owner)?.document?.features)
    }

    @Test
    fun `a document that fails verification is not kept`() = runTest {
        assertTrue(store.accept(token.copy(signature = token.signature.reversed()), owner) is Result.Rejected)

        assertNull(database.outbox().capabilityDocument())
    }

    @Test
    fun `a stored document is not read back for another session or after tampering`() = runTest {
        store.accept(token, owner)

        assertNull(store.current(owner.copy(subject = "someone-else")))
        assertNull(store.current(owner.copy(roleAssignmentId = "another-assignment")))

        val tampered = token.copy(signature = token.signature.reversed())
        database.outbox().putCapabilityDocument(
            CachedCapabilityDocumentEntity(document = tampered.document, signature = tampered.signature, keyId = tampered.keyId, fetchedAt = 0),
        )
        assertNull(store.current(owner))
    }

    @Test
    fun `clearing discards it`() = runTest {
        store.accept(token, owner)
        assertNotNull(store.current(owner))

        store.clear()

        assertNull(store.current(owner))
    }
}
