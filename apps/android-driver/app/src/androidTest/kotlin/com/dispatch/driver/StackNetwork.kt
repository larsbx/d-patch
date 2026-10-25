package com.dispatch.driver

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Read-only probes through both existing pools and a fresh pool; no tokens. */
object StackNetwork {
    private val fresh = OkHttpClient()

    suspend fun ready(online: Boolean): Boolean = withContext(Dispatchers.IO) {
        val clients = AcceptanceTrace.clients.toList() + fresh
        val targets = listOf(BuildConfig.API_BASE_URL + "health/live", BuildConfig.AUTH_BASE_URL + "auth/me")
        clients.mapIndexed { pool, client ->
            targets.mapIndexed { endpoint, url ->
                val code = try {
                    client.newBuilder().callTimeout(1, TimeUnit.SECONDS).build()
                        .newCall(Request.Builder().url(url).build()).execute().use { it.code }
                } catch (_: IOException) { null }
                AcceptanceTrace.record("probe pool=$pool endpoint=$endpoint http=${code ?: "unreachable"}")
                if (online) code == (if (endpoint == 0) 200 else 401) else code == null
            }.all { it }
        }.all { it }
    }
}
