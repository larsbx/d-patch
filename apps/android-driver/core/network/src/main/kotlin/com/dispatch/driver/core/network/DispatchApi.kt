package com.dispatch.driver.core.network

import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST

/**
 * The `/v1` routes this client uses, from `contracts/openapi.json`.
 *
 * Bodies travel as raw bytes in both directions. A queued declaration is sent
 * exactly as it was written to the outbox, so a retry is byte-identical, and
 * responses are decoded by the callers that know which status means what.
 * `ContractConformanceTest` holds these paths and field names to the contract.
 */
internal interface DispatchApi {

    @POST("v1/me/status-events")
    suspend fun declareStatus(
        @Header("X-Role-Assignment-ID") roleAssignmentId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body body: RequestBody,
    ): Response<ResponseBody>

    @GET("v1/role-capabilities")
    suspend fun roleCapabilities(
        @Header("X-Role-Assignment-ID") roleAssignmentId: String?,
    ): Response<ResponseBody>

    @GET("v1/me/role-assignments")
    suspend fun roleAssignments(): Response<ResponseBody>
}
