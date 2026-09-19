package com.dispatch.driver.core.network

import com.dispatch.driver.core.network.generated.StatusEventRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RetrofitStatusTransportTest {
    private lateinit var server: MockWebServer
    private lateinit var transport: StatusTransport
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        transport =
            RetrofitStatusTransport(
                api = createStatusApi(server.url("/").toString(), OkHttpClient(), json),
                json = json,
            )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun sendsAuthorityAndIdempotencyHeadersAndAccepts201() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "event": {
                        "id": "018f0000-0000-7000-8000-000000000001",
                        "participant_id": "018f0000-0000-7000-8000-000000000002",
                        "role_assignment_id": "018f0000-0000-7000-8000-000000000003",
                        "status": "AT_PICKUP",
                        "source": "PARTICIPANT",
                        "occurred_at": "2026-09-19T10:00:00.000Z",
                        "recorded_at": "2026-09-19T10:00:01.000Z",
                        "verification": "NONE"
                      },
                      "current_status": {
                        "value": "AT_PICKUP",
                        "source": "PARTICIPANT",
                        "role_key": "DRIVER",
                        "occurred_at": "2026-09-19T10:00:00.000Z"
                      }
                    }
                    """.trimIndent(),
                ),
        )

        val outcome =
            transport.submit(
                accessToken = "token",
                roleAssignmentId = "role-assignment",
                idempotencyKey = "status:event",
                request = request(),
            )

        assertTrue(outcome is StatusUploadOutcome.Accepted)
        val recorded = server.takeRequest()
        assertEquals("/v1/me/status-events", recorded.path)
        assertEquals("Bearer token", recorded.getHeader("Authorization"))
        assertEquals("role-assignment", recorded.getHeader("X-Role-Assignment-ID"))
        assertEquals("status:event", recorded.getHeader("Idempotency-Key"))
        assertTrue(recorded.body.readUtf8().contains(""device_sequence":7"))
    }

    @Test
    fun preservesProblemDetailAsTerminalRejection() = runTest {
        val detail = "This role assignment cannot declare a status."
        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setHeader("Content-Type", "application/problem+json")
                .setBody(problem(403, "FORBIDDEN", detail)),
        )

        val outcome =
            transport.submit("token", "role-assignment", "status:event", request())

        val rejected = outcome as StatusUploadOutcome.Rejected
        assertEquals("FORBIDDEN", rejected.problem.code)
        assertEquals(detail, rejected.problem.detail)
    }

    @Test
    fun inProgressIdempotencyConflictIsRetryable() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(409)
                .setHeader("Content-Type", "application/problem+json")
                .setBody(problem(409, "IDEMPOTENCY_KEY_IN_PROGRESS", "Retry shortly.")),
        )

        val outcome =
            transport.submit("token", "role-assignment", "status:event", request())

        assertTrue(outcome is StatusUploadOutcome.Retryable)
    }

    private fun request() =
        StatusEventRequest(
            eventId = "018f0000-0000-7000-8000-000000000001",
            status = "AT_PICKUP",
            occurredAt = "2026-09-19T10:00:00.000Z",
            deviceId = "018f0000-0000-7000-8000-000000000004",
            deviceSequence = 7,
        )

    private fun problem(status: Int, code: String, detail: String) =
        """
        {
          "type": "about:blank",
          "title": "Request rejected",
          "status": $status,
          "detail": "$detail",
          "instance": "/v1/me/status-events",
          "code": "$code",
          "correlation_id": "corr-1"
        }
        """.trimIndent()
}
