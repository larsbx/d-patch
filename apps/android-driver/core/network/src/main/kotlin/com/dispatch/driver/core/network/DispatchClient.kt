package com.dispatch.driver.core.network

import com.dispatch.driver.core.model.capability.SignedDocument
import com.dispatch.driver.core.model.location.DemandPolicy
import com.dispatch.driver.core.model.location.LocationSample
import com.dispatch.driver.core.model.session.SessionProvider
import com.dispatch.driver.core.model.status.CurrentStatus
import com.dispatch.driver.core.model.status.StatusDeclaration
import com.dispatch.driver.core.model.status.StatusTransport
import com.dispatch.driver.core.model.status.SubmitResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.Retrofit
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * The machine API client (Sections 24.1, 24.6).
 *
 * Every failure is a value. Section 25.2 decides what a failure *means*
 * (`Disposition`); this class only reports faithfully which one it was, and
 * never turns "no answer" into "refused" or the reverse.
 */
class DispatchClient internal constructor(
    private val api: DispatchApi,
    private val streams: EventStreams,
) : StatusTransport {

    override suspend fun submit(declaration: StatusDeclaration, roleAssignmentId: String): SubmitResult =
        call(
            request = {
                api.declareStatus(
                    roleAssignmentId = roleAssignmentId,
                    // The event ID is already the declaration's permanent,
                    // client-chosen identity, so it is the natural key.
                    idempotencyKey = declaration.eventId,
                    body = declaration.toCanonicalJson().toRequestBody(JSON),
                )
            },
            onSuccess = { SubmitResult.Stored(json.decodeFromString(StoredBody.serializer(), it).currentStatus) },
            onProblem = { status, problem -> SubmitResult.Problem(status, problem?.code, problem?.detail) },
            onUnreachable = { SubmitResult.Unreachable },
        )

    /** `GET /v1/role-capabilities`: the signed document, unverified — the caller verifies. */
    suspend fun capabilityDocument(roleAssignmentId: String?): Fetch<SignedDocument> =
        fetch { api.roleCapabilities(roleAssignmentId) }

    /** `GET /v1/me/role-assignments`: the choices for Section 4.3's switcher. */
    suspend fun roleAssignments(): Fetch<List<RoleAssignmentChoice>> =
        call(
            request = { api.roleAssignments() },
            onSuccess = { Fetch.Ok(json.decodeFromString(AssignmentsBody.serializer(), it).roleAssignments) },
            onProblem = { status, problem -> Fetch.Problem(status, problem?.code, problem?.detail) },
            onUnreachable = { Fetch.Unreachable },
        )

    /**
     * `POST /v1/me/devices` (ADR-0012): registers this installation's key. A
     * `200` (already registered) and a `201` both carry the device ID.
     */
    suspend fun registerDevice(roleAssignmentId: String, registration: DeviceRegistrationRequest): Fetch<RegisteredDevice> =
        fetch {
            api.registerDevice(
                roleAssignmentId = roleAssignmentId,
                body = json.encodeToString(DeviceRegistrationRequest.serializer(), registration).toRequestBody(JSON),
            )
        }

    /** `POST /v1/me/location-consents` (Section 25.3): sharing begins for one of the caller's own trips. */
    suspend fun beginLocationConsent(roleAssignmentId: String, assignmentId: String, idempotencyKey: String): Fetch<LocationConsent> =
        fetch {
            api.beginLocationConsent(
                roleAssignmentId = roleAssignmentId,
                idempotencyKey = idempotencyKey,
                body = json.encodeToString(LocationConsentRequest.serializer(), LocationConsentRequest(assignmentId)).toRequestBody(JSON),
            )
        }

    suspend fun revokeLocationConsent(roleAssignmentId: String, consentGrantId: String, idempotencyKey: String): Fetch<LocationConsent> =
        fetch { api.revokeLocationConsent(roleAssignmentId, idempotencyKey, consentGrantId) }

    /**
     * `POST /v1/me/location-samples:batch` (Section 24.2): each sample exactly as
     * queued, keyed by the batch itself so a retry is the same request. Only
     * signed samples are sent; the server would refuse the rest anyway.
     */
    suspend fun uploadSamples(roleAssignmentId: String, samples: List<LocationSample>): Fetch<List<SampleResult>> {
        require(samples.all { it.deviceId != null && it.deviceSignature != null }) { "Only signed samples are sent" }
        val body = samples.joinToString(",", prefix = """{"samples":[""", postfix = "]}") { it.toCanonicalJson() }
        return call(
            request = { api.uploadSamples(roleAssignmentId, batchKey(samples), body.toRequestBody(JSON)) },
            onSuccess = { Fetch.Ok(json.decodeFromString(SampleResultsBody.serializer(), it).results) },
            onProblem = { status, problem -> Fetch.Problem(status, problem?.code, problem?.detail) },
            onUnreachable = { Fetch.Unreachable },
        )
    }

    /**
     * `GET /v1/me/location-demand` (ADR-0014): each policy as the server sends
     * it, then exactly one [DemandEvent.Ended] saying how the stream ended.
     * Cancelling the collector closes the connection.
     */
    fun demand(roleAssignmentId: String): Flow<DemandEvent> =
        streams.open("v1/me/location-demand", roleAssignmentId).mapNotNull { item ->
            when (item) {
                is StreamItem.Event ->
                    item.event.takeIf { it.event == "location-demand" }?.let { DemandPolicy.parse(it.data) }?.let(DemandEvent::Policy)
                is StreamItem.Ended -> DemandEvent.Ended(item.httpStatus, item.code)
            }
        }

    private suspend inline fun <reified T> fetch(noinline request: suspend () -> Response<ResponseBody>): Fetch<T> =
        call(
            request = request,
            onSuccess = { Fetch.Ok(json.decodeFromString(serializer<T>(), it)) },
            onProblem = { status, problem -> Fetch.Problem(status, problem?.code, problem?.detail) },
            onUnreachable = { Fetch.Unreachable },
        )

    private suspend fun <T> call(
        request: suspend () -> Response<ResponseBody>,
        onSuccess: (String) -> T,
        onProblem: (Int, ProblemBody?) -> T,
        onUnreachable: () -> T,
    ): T {
        val response = try {
            request()
        } catch (_: IOException) {
            return onUnreachable()
        }
        return if (response.isSuccessful) {
            // A 2xx whose body does not parse is the server misbehaving, not a
            // verdict on the request; treating it as unreachable keeps the event.
            response.body()?.string()?.let { runCatching { onSuccess(it) }.getOrNull() } ?: onUnreachable()
        } else {
            onProblem(response.code(), response.errorBody()?.string()?.let { runCatching { json.decodeFromString(ProblemBody.serializer(), it) }.getOrNull() })
        }
    }

    companion object {
        /**
         * Retrofit refuses a base URL without a trailing `/`, and it would do so
         * when Hilt first builds this client — a crash on launch for a release
         * configured with the conventional `https://api.example.com`. The `/v1`
         * paths are relative, so the slash is always what was meant.
         */
        internal fun retrofitBaseUrl(baseUrl: String): String = baseUrl.trim().let { if (it.endsWith("/")) it else "$it/" }

        private val JSON = "application/json".toMediaType()
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Builds a client for [baseUrl] that authenticates as [session].
         *
         * A request made with no token goes out without one and comes back
         * `401`, which `Disposition` keeps queued: signing in is what sends it.
         * Every `401` is reported to [session], so the app can offer sign-in.
         */
        fun create(
            baseUrl: String,
            session: SessionProvider,
            http: OkHttpClient = OkHttpClient(),
            streamReadTimeoutMs: Long = STREAM_READ_TIMEOUT_MS,
        ): DispatchClient {
            val authenticated = http.newBuilder()
                .addInterceptor(Interceptor { chain ->
                    val token = runBlocking { session.accessToken() }
                    chain.proceed(
                        chain.request().newBuilder()
                            .apply { if (token != null) header("Authorization", "Bearer $token") }
                            .apply { if (chain.request().header("Accept") == null) header("Accept", "application/json") }
                            .build(),
                    ).also { if (it.code == 401) session.rejected(token) }
                })
                .build()
            val base = retrofitBaseUrl(baseUrl)
            val retrofit = Retrofit.Builder().baseUrl(base).client(authenticated).build()
            val streaming = authenticated.newBuilder().readTimeout(streamReadTimeoutMs, TimeUnit.MILLISECONDS).build()
            return DispatchClient(retrofit.create(DispatchApi::class.java), EventStreams(base.toHttpUrl(), streaming))
        }

        /**
         * How long a stream may be silent before it counts as lost. The server
         * beats every 20 s (Section 24.5); this tolerates two missed beats,
         * where OkHttp's default 10 s would end every quiet stream.
         */
        const val STREAM_READ_TIMEOUT_MS = 45_000L

        /**
         * A batch's `Idempotency-Key`: a name-based UUID of its sample IDs, in
         * order and length-prefixed, so a retried batch is the same request and
         * no two different batches share a key by where their IDs are cut.
         */
        fun batchKey(samples: List<LocationSample>): String =
            UUID.nameUUIDFromBytes(samples.joinToString("") { "${it.sampleId.length}:${it.sampleId}" }.encodeToByteArray()).toString()
    }
}

/** The outcome of a read, in the same three shapes as a submission. */
sealed interface Fetch<out T> {
    data class Ok<T>(val value: T) : Fetch<T>
    data class Problem(val httpStatus: Int, val code: String?, val detail: String?) : Fetch<Nothing>
    data object Unreachable : Fetch<Nothing>
}

@Serializable
data class RoleAssignmentChoice(val id: String, val label: String)

/** The body of `POST /v1/me/devices`; the key and proof are base64url. */
@Serializable
data class DeviceRegistrationRequest(
    @SerialName("installation_id") val installationId: String,
    @SerialName("public_key") val publicKey: String,
    val proof: String,
)

@Serializable
data class RegisteredDevice(
    @SerialName("device_id") val deviceId: String,
    @SerialName("installation_id") val installationId: String,
)

@Serializable
internal data class StoredBody(@SerialName("current_status") val currentStatus: CurrentStatus)

@Serializable
internal data class AssignmentsBody(@SerialName("role_assignments") val roleAssignments: List<RoleAssignmentChoice>)

/** What the demand stream reports (ADR-0014): each policy, then how the stream ended. */
sealed interface DemandEvent {
    data class Policy(val policy: DemandPolicy) : DemandEvent

    /** [httpStatus] is null when the server could not be reached or the connection dropped. */
    data class Ended(val httpStatus: Int?, val code: String?) : DemandEvent
}

@Serializable
internal data class LocationConsentRequest(@SerialName("assignment_id") val assignmentId: String)

@Serializable
data class LocationConsent(
    @SerialName("consent_grant_id") val consentGrantId: String,
    @SerialName("assignment_id") val assignmentId: String,
    @SerialName("granted_at") val grantedAt: String,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("revoked_at") val revokedAt: String? = null,
)

/** One sample's answer: `ACCEPTED`, `DUPLICATE` or `REJECTED` with its code (Section 24.2). */
@Serializable
data class SampleResult(
    @SerialName("sample_id") val sampleId: String? = null,
    val result: String,
    val code: String? = null,
)

@Serializable
internal data class SampleBatchBody(val samples: List<LocationSample>)

@Serializable
internal data class SampleResultsBody(val results: List<SampleResult>)

/** RFC 9457 as `DispatchWeb.Problem` writes it; only the stable code and detail are used. */
@Serializable
internal data class ProblemBody(val code: String? = null, val detail: String? = null)
