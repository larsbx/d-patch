package com.dispatch.driver.core.network

import com.dispatch.driver.core.model.location.ServerSentEvent
import com.dispatch.driver.core.model.location.ServerSentEvents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.Reader

/** An item of an event stream: an event, or how the stream ended. */
internal sealed interface StreamItem {
    data class Event(val event: ServerSentEvent) : StreamItem

    /** [httpStatus] and [code] are null when there was no answer, or the connection dropped. */
    data class Ended(val httpStatus: Int?, val code: String?) : StreamItem
}

/**
 * Server-sent event streams under [baseUrl] (Section 24.5's `/v1` streams).
 * [http] authenticates, and must not time out between the server's heartbeats.
 */
internal class EventStreams(private val baseUrl: HttpUrl, private val http: OkHttpClient) {

    /**
     * The events of [path], then exactly one [StreamItem.Ended]. Every failure is
     * a value, as in [DispatchClient].
     *
     * The read runs in a child coroutine because it blocks: cancelling the
     * collector reaches [awaitClose] in the parent, whose `call.cancel()` is
     * what interrupts a read waiting on a quiet stream.
     */
    fun open(path: String, roleAssignmentId: String): Flow<StreamItem> = channelFlow {
        val call = http.newCall(
            Request.Builder()
                .url(baseUrl.resolve(path)!!)
                .header("Accept", "text/event-stream")
                .header("X-Role-Assignment-ID", roleAssignmentId)
                .build(),
        )
        launch(Dispatchers.IO) {
            val ended = try {
                call.execute().use { response ->
                    if (response.isSuccessful) {
                        serverSentEvents(response.body!!.charStream()).collect { send(StreamItem.Event(it)) }
                        StreamItem.Ended(response.code, null)
                    } else {
                        StreamItem.Ended(response.code, problemCode(response.body?.string()))
                    }
                }
            } catch (_: IOException) {
                StreamItem.Ended(null, null)
            }
            send(ended)
            channel.close()
        }
        awaitClose { call.cancel() }
    }

    private fun problemCode(body: String?): String? =
        body?.let { runCatching { json.decodeFromString(ProblemBody.serializer(), it).code }.getOrNull() }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}

/**
 * The events read from [reader], as they complete. Read as characters, so a
 * multi-byte character split between network reads is still one character.
 */
internal fun serverSentEvents(reader: Reader): Flow<ServerSentEvent> =
    generateSequence { CharArray(4_096).let { buffer -> reader.read(buffer).takeIf { it >= 0 }?.let { String(buffer, 0, it) } } }
        .asFlow()
        .runningFold(ServerSentEvents() to emptyList<ServerSentEvent>()) { (parser, _), chunk -> parser.feed(chunk) }
        .transform { (_, events) -> events.forEach { emit(it) } }
