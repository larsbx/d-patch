package com.dispatch.driver.core.model.location

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

/**
 * The `text/event-stream` grammar (WHATWG HTML, "Server-sent events"), read
 * incrementally. The property that matters on a phone: what is parsed does not
 * depend on where the network happened to split the bytes.
 */
class ServerSentEventsTest {

    private fun parse(vararg chunks: String): List<ServerSentEvent> =
        chunks.fold(ServerSentEvents() to emptyList<ServerSentEvent>()) { (parser, seen), chunk ->
            parser.feed(chunk).let { (next, events) -> next to seen + events }
        }.second

    @Test
    fun `an event is its fields up to a blank line`() {
        assertEquals(
            listOf(ServerSentEvent("location-demand", """{"mode":"trickle"}""", id = null)),
            parse("event: location-demand\ndata: {\"mode\":\"trickle\"}\n\n"),
        )
    }

    @Test
    fun `comments, retry hints and unfinished events produce nothing`() {
        assertEquals(emptyList<ServerSentEvent>(), parse(": heartbeat\n\n", "retry: 3000\n\n", "data: half"))
    }

    @Test
    fun `retry is remembered, and only when it is all digits`() {
        val (parser, _) = ServerSentEvents().feed("retry: 3000\n\nretry: 3s\n\n")
        assertEquals(3000L, parser.retryMs)
    }

    @Test
    fun `data lines join with newlines, and one leading space is the separator's`() {
        assertEquals(listOf(ServerSentEvent("message", "a\n b", null)), parse("data:a\ndata:  b\n\n"))
    }

    @Test
    fun `an event with no data is not dispatched, and does not leak its type into the next`() {
        assertEquals(listOf(ServerSentEvent("message", "x", null)), parse("event: lost\n\ndata: x\n\n"))
    }

    @Test
    fun `CR, LF and CRLF all end lines, even when a CRLF is split across chunks`() {
        val expected = listOf(ServerSentEvent("e", "d", "7"))
        assertEquals(expected, parse("event: e\r\ndata: d\rid: 7\n\n"))
        assertEquals(expected, parse("event: e\r", "\ndata: d\r", "\nid: 7\r", "\r"))
    }

    // The property: for any events, any line endings, and any split of the
    // bytes into chunks, the parsed events are the events written.
    @Test
    fun `parsing is independent of chunking`() {
        val random = Random(2026_10_23)
        repeat(2_000) { round ->
            val events = List(random.nextInt(0, 6)) { event(random) }
            val stream = buildString {
                // A line ending, never "\n" straight after "\r": that pair is one CRLF.
                var last = ""
                fun eol() = listOf("\n", "\r", "\r\n").filterNot { last == "\r" && it.startsWith("\n") }
                    .random(random).also { last = it }
                fun line(text: String) = append(text).append(eol())

                if (random.nextBoolean()) line(": heartbeat").append(eol())
                for (e in events) {
                    line("event: ${e.event}")
                    if (e.id != null) line("id: ${e.id}")
                    for (data in e.data.split("\n")) line("data: $data")
                    append(eol())
                }
            }

            // The last event ID persists until another `id` replaces it.
            val expected = events.runningFold(null as ServerSentEvent?) { last, e -> e.copy(id = e.id ?: last?.id) }.drop(1)
            assertEquals("round $round", expected, parse(*split(stream, random)))
        }
    }

    private fun event(random: Random): ServerSentEvent {
        val alphabet = "abcdefgh {}\":,-0123456789é"
        fun text(max: Int) = String(CharArray(random.nextInt(1, max)) { alphabet.random(random) })
        return ServerSentEvent(
            event = text(12).filterNot { it == ' ' || it == ':' }.ifEmpty { "e" },
            data = List(random.nextInt(1, 3)) { text(20) }.joinToString("\n"),
            id = if (random.nextBoolean()) text(5).filter { it.isLetterOrDigit() }.ifEmpty { "1" } else null,
        )
    }

    private fun split(stream: String, random: Random): Array<String> {
        val cuts = List(random.nextInt(0, 8)) { random.nextInt(0, stream.length + 1) }.sorted()
        return (listOf(0) + cuts + stream.length).zipWithNext { a, b -> stream.substring(a, b) }.toTypedArray()
    }
}
