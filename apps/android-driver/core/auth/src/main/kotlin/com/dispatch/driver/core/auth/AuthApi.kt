package com.dispatch.driver.core.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** A biz-kit session: the bearer token dispatch accepts. */
data class Grant(val accessToken: String, val expiresInSeconds: Long)

/** Every outcome is a value, and "no answer" is never mistaken for "refused". */
sealed interface AuthResult<out T> {
    data class Ok<T>(val value: T) : AuthResult<T>
    data class Refused(val httpStatus: Int) : AuthResult<Nothing>
    data object Unreachable : AuthResult<Nothing>
}

/**
 * biz-kit's `/auth` endpoints for a native app. Nothing here logs: the
 * requests carry a live code, its verifier, or a session.
 */
class AuthApi(authBaseUrl: String, private val http: OkHttpClient = OkHttpClient()) {

    private val base: HttpUrl = authBaseUrl.toHttpUrl()

    /** RFC 6749 §4.1.3 with the PKCE verifier. */
    suspend fun exchange(code: String, verifier: String, redirectUri: String): AuthResult<Grant> =
        call(
            post(
                "auth/token",
                FormBody.Builder()
                    .add("grant_type", "authorization_code")
                    .add("code", code)
                    .add("code_verifier", verifier)
                    .add("redirect_uri", redirectUri)
                    .build(),
            ),
        ) { body ->
            json.decodeFromString(TokenBody.serializer(), body)
                .takeIf { it.tokenType.equals("Bearer", ignoreCase = true) && it.expiresIn > 0 }
                ?.let { Grant(it.accessToken, it.expiresIn) }
        }

    /** The principal the session belongs to: the `sub` capability documents are bound to. */
    suspend fun principal(token: String): AuthResult<String> =
        call(request("auth/me").bearer(token).get().build()) { json.decodeFromString(MeBody.serializer(), it).principal }

    suspend fun logout(token: String): AuthResult<Unit> =
        call(post("auth/logout", ByteArray(0).toRequestBody(), token)) { }

    private fun request(path: String) = Request.Builder().url(base.newBuilder().addPathSegments(path).build())

    private fun post(path: String, body: okhttp3.RequestBody, token: String? = null) =
        request(path).apply { if (token != null) bearer(token) }.post(body).build()

    private fun Request.Builder.bearer(token: String) = header("Authorization", "Bearer $token")

    /** A 2xx whose body is not what biz-kit sends is refused: nothing in it can be trusted. */
    private suspend fun <T> call(request: Request, parse: (String) -> T?): AuthResult<T> = withContext(Dispatchers.IO) {
        try {
            http.newCall(request).execute().use { response ->
                val value = if (response.isSuccessful) runCatching { parse(response.body?.string().orEmpty()) }.getOrNull() else null
                value?.let { AuthResult.Ok(it) } ?: AuthResult.Refused(response.code)
            }
        } catch (_: IOException) {
            AuthResult.Unreachable
        }
    }

    @Serializable
    private data class TokenBody(
        @SerialName("access_token") val accessToken: String,
        @SerialName("token_type") val tokenType: String,
        @SerialName("expires_in") val expiresIn: Long,
    )

    @Serializable
    private data class MeBody(val principal: String)

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
