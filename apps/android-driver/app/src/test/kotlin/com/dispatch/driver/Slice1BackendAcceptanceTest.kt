package com.dispatch.driver

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dispatch.driver.core.auth.NativeSession
import com.dispatch.driver.core.database.DispatchDatabase
import com.dispatch.driver.core.database.PendingState
import com.dispatch.driver.core.database.StatusOutbox
import com.dispatch.driver.core.model.status.StatusDeclaration
import com.dispatch.driver.core.model.status.StatusTransport
import com.dispatch.driver.core.model.status.SubmitResult
import com.dispatch.driver.core.network.DispatchClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/** Real Room + client + Identity + enrolment ledger + Dispatch + PostgreSQL.
 * The provider alone is a deterministic test fixture. No response is mocked.
 * scripts/run-slice1-acceptance.sh starts the pinned sibling backend.
 */
@RunWith(RobolectricTestRunner::class)
class Slice1BackendAcceptanceTest {
    @Test
    fun `sign in enrol queue synchronize replay reject and revoke against biz kit`() = runTest {
        val base = System.getenv("DISPATCH_E2E_URL")
        assumeTrue("Run scripts/run-slice1-acceptance.sh for the actual backend lane", !base.isNullOrBlank())
        val url = requireNotNull(base).trimEnd('/')
        val secret = requireNotNull(System.getenv("DISPATCH_E2E_CONTROL_KEY"))
        val http = OkHttpClient.Builder().followRedirects(false).build()
        fun control(action: String, body: JSONObject? = null): JSONObject {
            val request = Request.Builder().url("$url/__acceptance__/$action").header("X-Acceptance-Key", secret)
            if (body != null) request.post(body.toString().toRequestBody("application/json".toMediaType()))
            return http.newCall(request.build()).execute().use { r ->
                check(r.isSuccessful) { "Control $action: ${r.code}" }; JSONObject(requireNotNull(r.body).string())
            }
        }
        val context: Context = ApplicationProvider.getApplicationContext()
        val prefs = context.getSharedPreferences("backend-acceptance", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val session = NativeSession(prefs, "$url/")
        // Simulate the external browser's redirects and its HttpOnly state cookie.
        val browserStart = session.beginSignIn()
        val (provider, cookie) = http.newCall(Request.Builder().url(browserStart).build()).execute().use { r ->
            assertEquals(302, r.code)
            requireNotNull(r.header("Location")) to requireNotNull(r.header("Set-Cookie")).substringBefore(';')
        }
        val callback = http.newCall(Request.Builder().url(provider).build()).execute().use { r -> requireNotNull(r.header("Location")) }
        val handoff = http.newCall(Request.Builder().url(callback).header("Cookie", cookie).build()).execute().use { r ->
            assertEquals(302, r.code); requireNotNull(r.header("Location"))
        }
        assertFalse(handoff.contains("access_token"))
        session.completeSignIn(Uri.parse(handoff))
        val info = control("context")
        val role = info.getString("role_id")
        assertEquals(info.getString("subject"), session.subject())
        session.chooseRole(role)
        val code = session.requestEnrolment()
        control("approve", JSONObject().put("code", code))
        session.registerDevice()
        val client = DispatchClient.create("$url/", session)
        var now = System.currentTimeMillis()
        val db = Room.inMemoryDatabaseBuilder(context, DispatchDatabase::class.java).allowMainThreadQueries().build()
        val outbox = StatusOutbox(db, clock = { now }, signer = session)
        try {
            outbox.declare("EN_ROUTE_PICKUP", null, role)
            val original = db.outbox().observeEvents().first().single().canonicalJson
            val offline = DispatchClient.create("$url/", session, http.newBuilder()
                .addInterceptor { throw IOException("Acceptance offline interval") }.build())
            outbox.sync(offline)
            assertEquals(0, control("event-count").getInt("count"))
            assertEquals(PendingState.PENDING, outbox.view().first().events.single().state)
            now += 31_000
            // Store on the REAL backend, then lose the response at the client.
            val lostResponse = StatusTransport { d, assignment ->
                assertTrue(client.submit(d, assignment) is SubmitResult.Stored)
                SubmitResult.Unreachable
            }
            outbox.sync(lostResponse)
            assertEquals(1, control("event-count").getInt("count"))
            assertEquals(original, db.outbox().observeEvents().first().single().canonicalJson)
            now += 61_000
            outbox.sync(client)
            assertEquals(PendingState.ACKNOWLEDGED, outbox.view().first().events.single().state)
            assertEquals(1, control("event-count").getInt("count"))
            val queued = StatusDeclaration.fromCanonicalJson(original)
            val bad = client.submit(queued.copy(deviceSignature = "invalid"), role) as SubmitResult.Problem
            assertEquals("DEVICE_REJECTED", bad.code)
            control("revoke-key", JSONObject())
            val revokedDuplicate = client.submit(queued, role) as SubmitResult.Problem
            assertEquals("DEVICE_REJECTED", revokedDuplicate.code)
            outbox.declare("AT_PICKUP", null, role)
            outbox.sync(client)
            assertEquals(PendingState.REJECTED, outbox.view().first().events.first().state)
            control("revoke-role", JSONObject())
            assertEquals(403, (client.submit(queued, role) as SubmitResult.Problem).httpStatus)
            // Logout leaves existing signed history intact and supplies no credential.
            session.signOut()
            assertNull(session.accessToken())
            assertEquals(401, (client.submit(queued, role) as SubmitResult.Problem).httpStatus)
            assertEquals(1, control("event-count").getInt("count"))
        } finally { db.close() }
    }
}
