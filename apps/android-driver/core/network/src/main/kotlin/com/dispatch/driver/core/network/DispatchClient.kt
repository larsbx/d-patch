package com.dispatch.driver.core.network

import com.dispatch.driver.core.model.session.SessionProvider
import com.dispatch.driver.core.model.status.CurrentStatus
import com.dispatch.driver.core.model.status.StatusDeclaration
import com.dispatch.driver.core.model.status.StatusTransport
import com.dispatch.driver.core.model.status.SubmitResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.Retrofit
import java.io.IOException

/**
 * The machine API client (Sections 24.1, 24.6).
 *
 * Every failure is a value. Section 25.2 decides what a failure *means*
 * (`Disposition`); this class only reports faithfully which one it was, and
 * never turns "no answer" into "refused" or the reverse.
 */
class DispatchClient internal constructor(private val api: DispatchApi) : StatusTransport {

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

    /** `GET /v1/role-capabilities`: the signed token, unverified — the caller verifies. */
    suspend fun capabilityDocument(roleAssignmentId: String?): Fetch<String> =
        call(
            request = { api.roleCapabilities(roleAssignmentId) },
            onSuccess = { Fetch.Ok(json.decodeFromString(DocumentBody.serializer(), it).document) },
            onProblem = { status, problem -> Fetch.Problem(status, problem?.code, problem?.detail) },
            onUnreachable = { Fetch.Unreachable },
        )

    /** `GET /v1/me/role-assignments`: the choices for Section 4.3's switcher. */
    suspend fun roleAssignments(): Fetch<List<RoleAssignmentChoice>> =
        call(
            request = { api.roleAssignments() },
            onSuccess = { Fetch.Ok(json.decodeFromString(AssignmentsBody.serializer(), it).roleAssignments) },
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
        private val JSON = "application/json".toMediaType()
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Builds a client for [baseUrl] that authenticates as [session].
         *
         * A request made with no token goes out without one and comes back
         * `401`, which `Disposition` keeps queued: signing in is what sends it.
         */
        fun create(baseUrl: String, session: SessionProvider, http: OkHttpClient = OkHttpClient()): DispatchClient {
            val authenticated = http.newBuilder()
                .addInterceptor(Interceptor { chain ->
                    val token = runBlocking { session.accessToken() }
                    chain.proceed(
                        chain.request().newBuilder()
                            .apply { if (token != null) header("Authorization", "Bearer $token") }
                            .header("Accept", "application/json")
                            .build(),
                    )
                })
                .build()
            val retrofit = Retrofit.Builder().baseUrl(baseUrl).client(authenticated).build()
            return DispatchClient(retrofit.create(DispatchApi::class.java))
        }
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

@Serializable
internal data class StoredBody(@SerialName("current_status") val currentStatus: CurrentStatus)

@Serializable
internal data class DocumentBody(val document: String)

@Serializable
internal data class AssignmentsBody(@SerialName("role_assignments") val roleAssignments: List<RoleAssignmentChoice>)

/** RFC 9457 as `DispatchWeb.Problem` writes it; only the stable code and detail are used. */
@Serializable
internal data class ProblemBody(val code: String? = null, val detail: String? = null)
