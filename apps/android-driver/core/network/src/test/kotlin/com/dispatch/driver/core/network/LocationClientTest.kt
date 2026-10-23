package com.dispatch.driver.core.network

import com.dispatch.driver.core.model.device.DeviceKey
import com.dispatch.driver.core.model.device.SigningDevice
import com.dispatch.driver.core.model.location.DemandPolicy
import com.dispatch.driver.core.model.location.LocationFix
import com.dispatch.driver.core.model.location.LocationSample
import com.dispatch.driver.core.model.location.SampleVerdict
import com.dispatch.driver.core.model.location.UploadResult
import com.dispatch.driver.core.model.location.ServerSentEvent
import com.dispatch.driver.core.model.session.SessionProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.io.InputStreamReader
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis
import com.dispatch.driver.core.model.location.Sharing

/** Consent, sample upload and the demand stream (Sections 24.2, 25.3; ADR-0014), against a real socket. */
class LocationClientTest {

    private val server = MockWebServer().apply { start() }

    private val session = object : SessionProvider {
        override suspend fun accessToken() = "token-1"
        override fun subject() = "subject-1"
        override fun selectedRoleAssignmentId() = "ra-1"
        override val signInRequired = MutableStateFlow(false)
        override fun rejected(token: String?) = Unit
    }

    private val client = DispatchClient.create(server.url("/").toString(), session)

    private val device = SigningDevice("device-1", DeviceKey.fromSeed(ByteArray(32) { it.toByte() }))
    private fun sample(n: Int) = LocationSample.of(
        LocationFix(51.5, -0.12, 8.0, null, null, "fused", false, Instant.parse("2026-10-23T10:00:00Z")),
        sampleId = "sample-$n",
        sharing = Sharing("trip-1", "grant-1"),
        deviceSequence = n.toLong(),
    ).signedBy(device)

    @After
    fun stop() = server.shutdown()

    @Test
    fun `consent is begun for a trip, under the assignment and an idempotency key`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(201).setBody(
                """{"consent_grant_id":"g-1","assignment_id":"trip-1","granted_at":"2026-10-23T10:00:00Z","expires_at":null,"revoked_at":null}""",
            ),
        )

        val consent = client.beginLocationConsent("ra-1", assignmentId = "trip-1", idempotencyKey = "k-1")

        assertEquals(Fetch.Ok(LocationConsent("g-1", "trip-1", "2026-10-23T10:00:00Z", null, null)), consent)
        val request = server.takeRequest()
        assertEquals("/v1/me/location-consents", request.path)
        assertEquals("k-1", request.getHeader("Idempotency-Key"))
        assertEquals("ra-1", request.getHeader("X-Role-Assignment-ID"))
        assertEquals("""{"assignment_id":"trip-1"}""", request.body.readUtf8())
    }

    @Test
    fun `revoking names the grant`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"consent_grant_id":"g-1","assignment_id":"trip-1","granted_at":"t","expires_at":null,"revoked_at":"u"}""",
            ),
        )

        val revoked = client.revokeLocationConsent("ra-1", consentGrantId = "g-1", idempotencyKey = "k-2")

        assertEquals("u", (revoked as Fetch.Ok).value.revokedAt)
        assertEquals("/v1/me/location-consents/g-1/revoke", server.takeRequest().path)
    }

    @Test
    fun `samples are sent as queued, one result each, keyed by the batch itself`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(207).setBody(
                """{"results":[{"sample_id":"sample-1","result":"ACCEPTED","code":null},""" +
                    """{"sample_id":"sample-2","result":"REJECTED","code":"REJECTED_CONSENT"}]}""",
            ),
        )
        val batch = listOf(sample(1), sample(2))

        val results = client.uploadSamples("ra-1", batch)

        assertEquals(
            Fetch.Ok(listOf(SampleResult("sample-1", "ACCEPTED", null), SampleResult("sample-2", "REJECTED", "REJECTED_CONSENT"))),
            results,
        )
        val request = server.takeRequest()
        assertEquals("/v1/me/location-samples:batch", request.path)
        assertEquals(DispatchClient.batchKey(batch), request.getHeader("Idempotency-Key"))
        assertEquals("""{"samples":[${batch.joinToString(",") { it.toCanonicalJson() }}]}""", request.body.readUtf8())
    }

    // The outbox's port: the same request, answered by sample ID.
    @Test
    fun `as the outbox's transport, an upload answers by sample ID`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(207).setBody(
                """{"results":[{"sample_id":"sample-1","result":"DUPLICATE","code":null},{"sample_id":null,"result":"REJECTED","code":"INVALID_SAMPLE"}]}""",
            ),
        )
        server.enqueue(problemResponse(403, "FORBIDDEN"))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val batch = listOf(sample(1))

        assertEquals(UploadResult.Answered(mapOf("sample-1" to SampleVerdict("DUPLICATE", null))), client.upload("ra-1", batch))
        assertEquals(UploadResult.Problem(403, "FORBIDDEN"), client.upload("ra-1", batch))
        assertEquals(UploadResult.Unreachable, client.upload("ra-1", batch))
    }

    private fun problemResponse(status: Int, code: String) =
        MockResponse().setResponseCode(status).setHeader("content-type", "application/problem+json")
            .setBody("""{"status":$status,"code":"$code","detail":"d"}""")

    @Test
    fun `an unsigned sample is never sent`() {
        val unsigned = sample(1).copy(deviceSignature = null)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { client.uploadSamples("ra-1", listOf(unsigned)) } }
        assertEquals(0, server.requestCount)
    }

    // A retried batch is the same request; a different batch is not.
    @Test
    fun `the batch key is a function of the samples, in order`() {
        val a = listOf(sample(1), sample(2))
        assertEquals(DispatchClient.batchKey(a), DispatchClient.batchKey(listOf(sample(1), sample(2))))
        assertNotEquals(DispatchClient.batchKey(a), DispatchClient.batchKey(a.reversed()))
        assertNotEquals(DispatchClient.batchKey(a), DispatchClient.batchKey(a.take(1)))
        // Not confusable by where the IDs are cut.
        assertNotEquals(
            DispatchClient.batchKey(listOf(sample(1).copy(sampleId = "ab"), sample(2).copy(sampleId = "c"))),
            DispatchClient.batchKey(listOf(sample(1).copy(sampleId = "a"), sample(2).copy(sampleId = "bc"))),
        )
    }

    @Test
    fun `the demand stream yields each policy as it arrives, then how it ended`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("content-type", "text/event-stream")
                .setBody(
                    "retry: 3000\n\n" +
                        "event: location-demand\ndata: {\"mode\":\"trickle\",\"sample_interval_ms\":120000,\"upload_interval_ms\":600000}\n\n" +
                        ": heartbeat\n\n" +
                        // A valid policy under another event's name is not demand.
                        "event: something-else\ndata: {\"mode\":\"realtime\",\"sample_interval_ms\":1,\"upload_interval_ms\":0}\n\n" +
                        "event: location-demand\ndata: {\"mode\":\"warp\"}\n\n" +
                        "event: location-demand\ndata: {\"mode\":\"realtime\",\"sample_interval_ms\":3000,\"upload_interval_ms\":0}\n\n",
                )
                // A few bytes at a time, as a mobile network delivers it.
                .throttleBody(7, 1, TimeUnit.MILLISECONDS),
        )

        val events = client.demand("ra-1").toList()

        assertEquals(
            listOf(
                DemandEvent.Policy(DemandPolicy.TRICKLE),
                DemandEvent.Policy(DemandPolicy(DemandPolicy.Mode.REALTIME, 3_000, 0)),
                DemandEvent.Ended(200, null),
            ),
            events,
        )
        val request = server.takeRequest()
        assertEquals("/v1/me/location-demand", request.path)
        assertEquals("text/event-stream", request.getHeader("Accept"))
        assertEquals("Bearer token-1", request.getHeader("Authorization"))
        assertEquals("ra-1", request.getHeader("X-Role-Assignment-ID"))
    }

    @Test
    fun `no active consent ends the stream with the server's code`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(404).setHeader("content-type", "application/problem+json")
                .setBody("""{"status":404,"code":"NO_ACTIVE_CONSENT","detail":"Nothing is being shared."}"""),
        )
        assertEquals(listOf(DemandEvent.Ended(404, "NO_ACTIVE_CONSENT")), client.demand("ra-1").toList())
    }

    @Test
    fun `an unreachable server ends the stream without a status`() = runTest {
        server.shutdown()
        assertEquals(listOf(DemandEvent.Ended(null, null)), client.demand("ra-1").toList())
    }

    // A quiet stream blocks in a read; stopping sharing must not wait for the
    // next heartbeat (or the read timeout) to let go of the connection.
    @Test
    fun `cancelling the collector closes a quiet stream at once`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("content-type", "text/event-stream")
                .setBody("event: location-demand\ndata: {\"mode\":\"trickle\",\"sample_interval_ms\":120000,\"upload_interval_ms\":600000}\n\n")
                .setSocketPolicy(SocketPolicy.KEEP_OPEN)
                .setHeader("content-length", "100000"),
        )

        val elapsed = measureTimeMillis {
            withTimeout(5_000) { assertEquals(DemandEvent.Policy(DemandPolicy.TRICKLE), client.demand("ra-1").first()) }
        }

        assertTrue("took $elapsed ms", elapsed < 5_000)
    }

    // The stream's own read timeout is what ends a silent stream: with it set
    // short, a stream that stops beating counts as lost, without a status.
    @Test
    fun `a stream silent past its read timeout ends as lost`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("content-type", "text/event-stream")
                .setBody(": heartbeat\n\n").setSocketPolicy(SocketPolicy.KEEP_OPEN).setHeader("content-length", "100000"),
        )
        val impatient = DispatchClient.create(server.url("/").toString(), session, streamReadTimeoutMs = 300)

        withTimeout(5_000) { assertEquals(listOf(DemandEvent.Ended(null, null)), impatient.demand("ra-1").toList()) }
    }

    // OkHttp's default read timeout (10 s) would end every quiet stream between
    // the server's 20 s heartbeats; the stream tolerates two missed beats.
    @Test
    fun `the stream outlasts the server's heartbeat interval`() {
        assertTrue(DispatchClient.STREAM_READ_TIMEOUT_MS >= 2 * 20_000 + 1)
    }

    // Decoding is by character stream, so a multi-byte character split
    // between reads is still one character.
    @Test
    fun `events are decoded as characters, not per read`() = runTest {
        val bytes = "event: location-demand\ndata: é\n\n".encodeToByteArray()
        val trickle = object : InputStream() {
            var at = 0
            override fun read() = if (at < bytes.size) bytes[at++].toInt() and 0xff else -1
            override fun read(b: ByteArray, off: Int, len: Int) = if (at < bytes.size) { b[off] = bytes[at++]; 1 } else -1
        }

        assertEquals(
            listOf(ServerSentEvent("location-demand", "é", null)),
            serverSentEvents(InputStreamReader(trickle, Charsets.UTF_8)).toList(),
        )
    }
}
