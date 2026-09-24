package com.dispatch.driver.core.network

import com.dispatch.driver.core.model.session.SessionProvider
import com.dispatch.driver.core.model.status.StatusDeclaration
import com.dispatch.driver.core.model.status.SubmitResult
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The wire behaviour Sections 24.1 and 24.6 require of the client, against a real socket. */
class DispatchClientTest {

    private val server = MockWebServer().apply { start() }
    private var token: String? = "token-1"

    private val session = object : SessionProvider {
        override suspend fun accessToken() = token
        override fun subject() = "subject-1"
        override fun selectedRoleAssignmentId() = "ra-1"
    }

    private val client = DispatchClient.create(server.url("/").toString(), session)

    private val declaration = StatusDeclaration(
        eventId = "018f0000-0000-7000-8000-000000000001",
        status = "DELAYED",
        occurredAt = "2026-09-24T10:00:00Z",
        note = "Road closed",
        deviceSequence = 12,
    )

    @After
    fun stop() = server.shutdown()

    private fun problem(status: Int, code: String, detail: String = "detail") =
        MockResponse().setResponseCode(status).setHeader("content-type", "application/problem+json")
            .setBody("""{"type":"about:blank","title":"t","status":$status,"code":"$code","detail":"$detail"}""")

    @Test
    fun `a declaration is sent as queued, under its assignment, keyed by its event ID`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(201).setBody(
                """{"event":{},"current_status":{"value":"DELAYED","source":"PARTICIPANT","role_key":"COURIER","occurred_at":"2026-09-24T10:00:00Z"}}""",
            ),
        )

        val result = client.submit(declaration, roleAssignmentId = "ra-7")

        assertEquals("DELAYED", (result as SubmitResult.Stored).current.value)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/me/status-events", request.path)
        assertEquals("Bearer token-1", request.getHeader("Authorization"))
        assertEquals("ra-7", request.getHeader("X-Role-Assignment-ID"))
        assertEquals(declaration.eventId, request.getHeader("Idempotency-Key"))
        assertEquals(declaration.toCanonicalJson(), request.body.readUtf8())
    }

    @Test
    fun `a problem is reported with its stable code and detail`() = runTest {
        server.enqueue(problem(422, "VALIDATION_FAILED", "note is required"))

        assertEquals(SubmitResult.Problem(422, "VALIDATION_FAILED", "note is required"), client.submit(declaration, "ra-1"))
    }

    @Test
    fun `an error without a problem body still reports its status`() = runTest {
        server.enqueue(MockResponse().setResponseCode(502).setBody("<html>bad gateway</html>"))

        assertEquals(SubmitResult.Problem(502, null, null), client.submit(declaration, "ra-1"))
    }

    @Test
    fun `a dropped connection is unreachable, not a rejection`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        assertEquals(SubmitResult.Unreachable, client.submit(declaration, "ra-1"))
    }

    @Test
    fun `a success whose body cannot be read keeps the event rather than settling it`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setBody("{}"))

        assertEquals(SubmitResult.Unreachable, client.submit(declaration, "ra-1"))
    }

    @Test
    fun `no session means no Authorization header, and the server's 401 comes back`() = runTest {
        token = null
        server.enqueue(problem(401, "UNAUTHENTICATED"))

        assertEquals(401, (client.submit(declaration, "ra-1") as SubmitResult.Problem).httpStatus)
        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `the capability document is fetched for the selected assignment`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"document":"a.b.c"}"""))

        assertEquals(Fetch.Ok("a.b.c"), client.capabilityDocument("ra-3"))
        val request = server.takeRequest()
        assertEquals("/v1/role-capabilities", request.path)
        assertEquals("ra-3", request.getHeader("X-Role-Assignment-ID"))
    }

    @Test
    fun `with no assignment chosen the header is omitted, letting the server resolve or refuse`() = runTest {
        server.enqueue(problem(403, "ROLE_ASSIGNMENT_REQUIRED"))

        assertEquals(Fetch.Problem(403, "ROLE_ASSIGNMENT_REQUIRED", "detail"), client.capabilityDocument(null))
        assertNull(server.takeRequest().getHeader("X-Role-Assignment-ID"))
    }

    @Test
    fun `role assignments list identifiers and labels`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"role_assignments":[{"id":"ra-1","label":"Driver"}]}"""))

        assertEquals(Fetch.Ok(listOf(RoleAssignmentChoice("ra-1", "Driver"))), client.roleAssignments())
        assertEquals("/v1/me/role-assignments", server.takeRequest().path)
    }
}
