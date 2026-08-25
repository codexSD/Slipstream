package com.slipstream.core.control

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class JsonLineCodecTest {

    @Test
    fun `round-trips a request with a payload, one message per line`() {
        val out = ByteArrayOutputStream()
        val request = ControlMessage(
            type = "hello",
            id = "1",
            payload = JsonObject(mapOf("name" to JsonPrimitive("Client Phone"))),
        )
        val second = ControlMessage(type = "ping", id = "2")

        JsonLineCodec.writeMessage(out, request)
        JsonLineCodec.writeMessage(out, second)

        val lines = out.toString(Charsets.UTF_8).split("\n").filter { it.isNotEmpty() }
        assertEquals(2, lines.size)

        val input = ByteArrayInputStream(out.toByteArray())
        val readBack1 = JsonLineCodec.readMessage(input)!!
        assertEquals("hello", readBack1.type)
        assertEquals("1", readBack1.id)
        assertEquals("Client Phone", readBack1.payload?.get("name")?.let { (it as JsonPrimitive).content })

        val readBack2 = JsonLineCodec.readMessage(input)!!
        assertEquals("ping", readBack2.type)
        assertEquals("2", readBack2.id)
    }

    @Test
    fun `events carry no id`() {
        val out = ByteArrayOutputStream()
        JsonLineCodec.writeMessage(out, ControlMessage(type = "pong"))

        val message = JsonLineCodec.readMessage(ByteArrayInputStream(out.toByteArray()))!!
        assertEquals("pong", message.type)
        assertNull(message.id)
    }

    @Test
    fun `a malformed line is skipped, not fatal`() {
        val body = "not json at all\n{\"type\":\"\"}\n{}\n\n{\"type\":\"ping\",\"id\":\"9\"}\n"
        val input = ByteArrayInputStream(body.toByteArray(Charsets.UTF_8))

        val message = JsonLineCodec.readMessage(input)
        assertEquals("ping", message?.type)
        assertEquals("9", message?.id)
    }

    @Test
    fun `a trailing carriage return is stripped`() {
        val body = "{\"type\":\"ping\",\"id\":\"1\"}\r\n"
        val input = ByteArrayInputStream(body.toByteArray(Charsets.UTF_8))
        val message = JsonLineCodec.readMessage(input)
        assertEquals("ping", message?.type)
    }

    @Test
    fun `end of stream is reported as null, not an exception`() {
        val input = ByteArrayInputStream(ByteArray(0))
        assertNull(JsonLineCodec.readMessage(input))
    }

    @Test
    fun `end of stream after some skipped garbage is still null`() {
        val input = ByteArrayInputStream("garbage that never parses\n".toByteArray())
        assertNull(JsonLineCodec.readMessage(input))
    }

    @Test
    fun `writing a line over the 1 MiB cap throws before sending anything`() {
        val out = ByteArrayOutputStream()
        val huge = ControlMessage(
            type = "big",
            payload = JsonObject(mapOf("blob" to JsonPrimitive("x".repeat(2 * JsonLineCodec.MAX_LINE_BYTES)))),
        )

        try {
            JsonLineCodec.writeMessage(out, huge)
            fail("expected LineTooLargeException")
        } catch (e: LineTooLargeException) {
            // expected
        }
        assertEquals(0, out.size())
    }

    @Test
    fun `reading a line over the 1 MiB cap throws and is fatal`() {
        val overLong = "x".repeat(JsonLineCodec.MAX_LINE_BYTES + 10) + "\n"
        val input = ByteArrayInputStream(overLong.toByteArray(Charsets.UTF_8))

        try {
            JsonLineCodec.readMessage(input)
            fail("expected LineTooLargeException")
        } catch (e: LineTooLargeException) {
            // expected — this is the one framing violation that tears the connection down
        }
    }

    @Test
    fun `a line at exactly the cap is accepted`() {
        val out = ByteArrayOutputStream()
        // Build a payload sized so the whole encoded line lands under the cap.
        val message = ControlMessage(type = "ok", id = "1")
        JsonLineCodec.writeMessage(out, message)
        assertTrue(out.size() <= JsonLineCodec.MAX_LINE_BYTES)
    }
}
