package com.dispatch.driver

import android.util.Log
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CopyOnWriteArrayList

/** Test-only metadata. No URLs, headers, bodies, identities or exception messages. */
object AcceptanceTrace {
    private val sequence = AtomicInteger()
    val clients = CopyOnWriteArrayList<OkHttpClient>()
    fun record(message: String) { Log.i("DispatchAcceptance", "elapsedMs=${android.os.SystemClock.elapsedRealtime()} $message") }
    fun client(): OkHttpClient = OkHttpClient.Builder().eventListenerFactory {
        val id = sequence.incrementAndGet()
        object : EventListener() {
            private var connected = false
            override fun callStart(call: Call) {
                val operation = when (call.request().url.encodedPath) {
                    "/auth/token" -> "token"
                    "/auth/me" -> "principal"
                    "/auth/logout" -> "logout"
                    "/v1/me/status-events" -> "status"
                    else -> "other"
                }
                record("call=$id operation=$operation start")
            }
            override fun connectStart(call: Call, address: java.net.InetSocketAddress, proxy: java.net.Proxy) { connected = true }
            override fun connectionAcquired(call: Call, connection: Connection) {
                record("call=$id connection reused=${!connected}")
            }
            override fun responseHeadersEnd(call: Call, response: Response) { record("call=$id http=${response.code}") }
            override fun callEnd(call: Call) { record("call=$id end") }
            override fun callFailed(call: Call, ioe: IOException) { record("call=$id failed=${ioe.javaClass.simpleName}") }
        }
    }.build().also { clients += it }
}

@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [HttpModule::class])
object AcceptanceHttpModule {
    @Provides fun http(): OkHttpClient = AcceptanceTrace.client()
}
