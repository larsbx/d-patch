package com.dispatch.driver.core.network

import com.dispatch.driver.core.network.generated.ProblemDetail
import com.dispatch.driver.core.network.generated.StatusApi
import com.dispatch.driver.core.network.generated.StatusEventRequest
import com.dispatch.driver.core.network.generated.StatusEventResponse
import java.io.IOException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory

sealed interface StatusUploadOutcome {
    data class Accepted(val response: StatusEventResponse) : StatusUploadOutcome

    data class Rejected(val problem: ProblemDetail) : StatusUploadOutcome

    data class Retryable(
        val code: String,
        val detail: String,
    ) : StatusUploadOutcome
}

interface StatusTransport {
    suspend fun submit(
        accessToken: String,
        roleAssignmentId: String,
        idempotencyKey: String,
        request: StatusEventRequest,
    ): StatusUploadOutcome
}

class RetrofitStatusTransport(
    private val api: StatusApi,
    private val json: Json,
) : StatusTransport {
    override suspend fun submit(
        accessToken: String,
        roleAssignmentId: String,
        idempotencyKey: String,
        request: StatusEventRequest,
    ): StatusUploadOutcome =
        try {
            val response =
                api.declare(
                    authorization = "Bearer $accessToken",
                    roleAssignmentId = roleAssignmentId,
                    idempotencyKey = idempotencyKey,
                    request = request,
                )

            if (response.code() == 201) {
                val body = response.body()
                if (body == null) {
                    StatusUploadOutcome.Retryable(
                        code = "EMPTY_RESPONSE",
                        detail = "The server accepted the request without a usable response body.",
                    )
                } else {
                    StatusUploadOutcome.Accepted(body)
                }
            } else {
                classifyFailure(response.code(), response.errorBody()?.string())
            }
        } catch (error: IOException) {
            StatusUploadOutcome.Retryable(
                code = "NETWORK_UNAVAILABLE",
                detail = error.message ?: "Network request failed.",
            )
        } catch (error: SerializationException) {
            StatusUploadOutcome.Retryable(
                code = "INVALID_SERVER_RESPONSE",
                detail = error.message ?: "The server response could not be decoded.",
            )
        }

    private fun classifyFailure(httpStatus: Int, rawBody: String?): StatusUploadOutcome {
        val problem =
            rawBody?.let {
                runCatching { json.decodeFromString<ProblemDetail>(it) }.getOrNull()
            }

        if (httpStatus == 409 && problem?.code == "IDEMPOTENCY_KEY_IN_PROGRESS") {
            return StatusUploadOutcome.Retryable(problem.code, problem.detail)
        }

        if (httpStatus == 408 || httpStatus == 425 || httpStatus == 429 || httpStatus >= 500) {
            return StatusUploadOutcome.Retryable(
                code = problem?.code ?: "HTTP_$httpStatus",
                detail = problem?.detail ?: "The server is temporarily unavailable.",
            )
        }

        return if (problem != null) {
            StatusUploadOutcome.Rejected(problem)
        } else {
            StatusUploadOutcome.Rejected(
                ProblemDetail(
                    type = "about:blank",
                    title = "Request rejected",
                    status = httpStatus,
                    detail = rawBody?.take(500) ?: "The server rejected the declaration.",
                    instance = "/v1/me/status-events",
                    code = "HTTP_$httpStatus",
                    correlationId = "unavailable",
                ),
            )
        }
    }
}

fun createStatusApi(
    baseUrl: String,
    client: OkHttpClient,
    json: Json,
): StatusApi =
    Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(StatusApi::class.java)
