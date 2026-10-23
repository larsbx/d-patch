package com.dispatch.driver.core.model.location

/** One dispatched server-sent event. [id] is the stream's last event ID when it was dispatched. */
data class ServerSentEvent(val event: String, val data: String, val id: String?)

/**
 * An incremental `text/event-stream` reader (WHATWG HTML, "Server-sent events,
 * interpreting an event stream"). Immutable: [feed] returns the reader that has
 * seen the chunk, with the events it completed.
 *
 * Lines end in CRLF, LF or CR. A CR ending one chunk and an LF starting the
 * next are one line ending, which is what makes the result independent of how
 * the network splits the bytes.
 */
class ServerSentEvents private constructor(
    private val partial: String,
    private val afterCr: Boolean,
    private val type: String,
    private val data: List<String>?,
    private val lastEventId: String?,
    /** The stream's reconnection time, once it has sent one. */
    val retryMs: Long?,
) {
    constructor() : this(partial = "", afterCr = false, type = "", data = null, lastEventId = null, retryMs = null)

    fun feed(chunk: String): Pair<ServerSentEvents, List<ServerSentEvent>> {
        if (chunk.isEmpty()) return this to emptyList()
        val text = partial + if (afterCr && chunk.startsWith('\n')) chunk.drop(1) else chunk
        val lines = text.split(LINE_END)
        val start = copy(partial = lines.last(), afterCr = text.endsWith('\r'))
        return lines.dropLast(1).fold(start to emptyList()) { (reader, events), line ->
            reader.line(line).let { (next, event) -> next to (events + listOfNotNull(event)) }
        }
    }

    private fun line(line: String): Pair<ServerSentEvents, ServerSentEvent?> = when {
        line.isEmpty() -> dispatch()
        line.startsWith(':') -> this to null
        else -> {
            val name = line.substringBefore(':')
            val value = if (':' in line) line.substringAfter(':').removePrefix(" ") else ""
            field(name, value) to null
        }
    }

    private fun field(name: String, value: String): ServerSentEvents = when (name) {
        "event" -> copy(type = value)
        "data" -> copy(data = data.orEmpty() + value)
        "id" -> if ('\u0000' in value) this else copy(lastEventId = value)
        "retry" -> if (value.isNotEmpty() && value.all { it in '0'..'9' }) copy(retryMs = value.toLongOrNull()) else this
        else -> this
    }

    private fun dispatch(): Pair<ServerSentEvents, ServerSentEvent?> =
        copy(type = "", data = null) to data?.let { ServerSentEvent(type.ifEmpty { "message" }, it.joinToString("\n"), lastEventId) }

    private fun copy(
        partial: String = this.partial,
        afterCr: Boolean = this.afterCr,
        type: String = this.type,
        data: List<String>? = this.data,
        lastEventId: String? = this.lastEventId,
        retryMs: Long? = this.retryMs,
    ) = ServerSentEvents(partial, afterCr, type, data, lastEventId, retryMs)

    private companion object {
        val LINE_END = Regex("\r\n|\r|\n")
    }
}
