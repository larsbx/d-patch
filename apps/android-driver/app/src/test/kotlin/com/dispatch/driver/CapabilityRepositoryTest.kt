package com.dispatch.driver

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dispatch.driver.core.database.CapabilityStore
import com.dispatch.driver.core.database.DispatchDatabase
import com.dispatch.driver.core.model.capability.CapabilityDocumentVerifier
import com.dispatch.driver.core.model.capability.CapabilityState
import com.dispatch.driver.core.model.session.SessionProvider
import com.dispatch.driver.core.network.Fetch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ADR-0009's lifecycle for the document: the server's answer wins when there
 * is one — including a refusal — and the stored copy serves only when there
 * is none.
 */
@RunWith(RobolectricTestRunner::class)
class CapabilityRepositoryTest {

    private val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), DispatchDatabase::class.java)
        .allowMainThreadQueries()
        .build()

    private val verifier = CapabilityDocumentVerifier(CapabilityDocumentVerifier.publicKeyFromPem(BuildConfig.CAPABILITY_PUBLIC_KEY_PEM))
    private val store = CapabilityStore(database, verifier, clock = { 1_790_000_001_000 })
    private val token = checkNotNull(javaClass.classLoader?.getResource("server-signed-capability-document.jws")).readText().trim()

    private var subject: String? = "fixture-subject"
    private val session = object : SessionProvider {
        override suspend fun accessToken() = "t"
        override fun subject() = subject
        override fun selectedRoleAssignmentId() = "0192a000-0000-7000-8000-000000000003"
    }

    private var answer: Fetch<String> = Fetch.Ok(token)
    private val repository = CapabilityRepository(session, store) { answer }

    @After
    fun close() = database.close()

    private suspend fun refreshed(): CapabilityState = repository.run { refresh(); state.value }

    @Test
    fun `the debug build pins the development key the fixture was signed with`() {
        assertEquals("tOkF49ervDH_m_gl", CapabilityDocumentVerifier.kid(CapabilityDocumentVerifier.publicKeyFromPem(BuildConfig.CAPABILITY_PUBLIC_KEY_PEM)))
    }

    @Test
    fun `a fetched document that verifies is ready`() = runTest {
        val state = refreshed()

        assertTrue((state as CapabilityState.Ready).document.offers("status"))
    }

    @Test
    fun `offline, the stored document is used`() = runTest {
        refreshed()
        answer = Fetch.Unreachable

        assertTrue(refreshed() is CapabilityState.Ready)
    }

    @Test
    fun `offline with nothing stored, nothing is offered`() = runTest {
        answer = Fetch.Unreachable

        assertTrue(refreshed() is CapabilityState.Unavailable)
    }

    @Test
    fun `a revoked assignment stops offering surfaces, even with a stored document`() = runTest {
        refreshed()
        answer = Fetch.Problem(403, "NO_ACTIVE_ROLE_ASSIGNMENT", null)

        assertTrue(refreshed() is CapabilityState.Unavailable)

        answer = Fetch.Unreachable
        assertTrue("the stored document was discarded", refreshed() is CapabilityState.Unavailable)
    }

    @Test
    fun `a document that does not verify is never offered`() = runTest {
        answer = Fetch.Ok(token.dropLast(4) + "AAAA")

        assertTrue(refreshed() is CapabilityState.Unavailable)
    }

    @Test
    fun `without a session nothing is fetched or offered`() = runTest {
        subject = null
        answer = Fetch.Ok("should not be used")

        assertEquals(CapabilityState.SignedOut, refreshed())
    }
}
