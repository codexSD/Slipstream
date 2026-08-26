package com.slipstream.core.control

import com.slipstream.core.identity.DeviceIdentity
import com.slipstream.core.media.MediaTokenVault
import com.slipstream.core.transfer.TokenVault
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.io.path.createTempDirectory
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class RecordingClipboardSink : ClipboardSink {
    var lastText: String? = null
    var calls = 0
    override fun setText(text: String) {
        lastText = text
        calls++
    }
}

class SlipstreamSessionTest {

    private fun newSession(
        root: File = createTempDirectory().toFile(),
        clipboard: ClipboardSink = RecordingClipboardSink(),
        identity: DeviceIdentity = DeviceIdentity.createNew("Pixel"),
        onPlayRequested: (File) -> Unit = {},
        onPlayUrlRequested: (String, String?) -> Unit = { _, _ -> },
    ) = SlipstreamSession(
        identity = identity,
        rootDirectory = root,
        bulkTokenVault = TokenVault(),
        bulkEndpoint = { InetSocketAddress(InetAddress.getByName("127.0.0.1"), 53322) },
        mediaTokenVault = MediaTokenVault(),
        mediaPort = { 53323 },
        clipboardSink = clipboard,
        onPlayRequested = onPlayRequested,
        onPlayUrlRequested = onPlayUrlRequested,
    )

    // --- hello / hello.ok: the near-miss the brief calls out by name ---

    @Test
    fun `hello is answered with this device's own identity`() {
        val identity = DeviceIdentity.createNew("Ada's Pixel")
        val session = newSession(identity = identity)

        val reply = session.dispatch(ControlMessage(type = SessionMessageTypes.HELLO, id = "1"))

        requireNotNull(reply)
        assertEquals(SessionMessageTypes.HELLO_OK, reply.type)
        assertEquals("1", reply.id)
        val payload = requireNotNull(reply.payload)
        assertEquals(identity.deviceId, payload.getValue("deviceId").jsonPrimitive.content)
        assertEquals(identity.displayName, payload.getValue("name").jsonPrimitive.content)
        assertEquals(identity.fingerprint, payload.getValue("fingerprint").jsonPrimitive.content)
    }

    // --- ping/pong ---

    @Test
    fun `ping is answered with pong, echoing the id`() {
        val session = newSession()
        val reply = session.dispatch(ControlMessage(type = SessionMessageTypes.PING, id = "7"))
        assertEquals(SessionMessageTypes.PONG, reply?.type)
        assertEquals("7", reply?.id)
    }

    // --- unknown type: ignored, not an error, no disconnect implication ---

    @Test
    fun `an unknown message type returns null and is not treated as an error`() {
        val session = newSession()
        val reply = session.dispatch(ControlMessage(type = "some.future.type", id = "9"))
        assertNull(reply)
    }

    // --- list / stat ---

    @Test
    fun `list returns the directory's entries`() {
        val root = createTempDirectory().toFile()
        File(root, "a.txt").writeText("hi")
        File(root, "sub").mkdir()
        val session = newSession(root = root)

        val reply = session.dispatch(
            ControlMessage(type = SessionMessageTypes.LIST, id = "2", payload = JsonObject(mapOf("path" to JsonPrimitive(".")))),
        )

        assertEquals(SessionMessageTypes.LIST_OK, reply?.type)
        val entries = requireNotNull(reply?.payload)["entries"]
        assertTrue(entries.toString().contains("a.txt"))
        assertTrue(entries.toString().contains("sub"))
    }

    /**
     * "/" is the protocol's platform-neutral root, and it is what the Windows browser sends for
     * its very first request (`BrowseViewModel.JoinSegments` of no segments). Every existing
     * test asked for "." instead, so nothing covered the one path a real peer actually starts
     * from.
     */
    @Test
    fun `list of the protocol root returns the shared folder, not an error`() {
        val root = createTempDirectory().toFile()
        File(root, "a.txt").writeText("hi")
        val session = newSession(root = root)

        for (requested in listOf("/", "", ".")) {
            val reply = session.dispatch(
                ControlMessage(
                    type = SessionMessageTypes.LIST,
                    id = "2",
                    payload = JsonObject(mapOf("path" to JsonPrimitive(requested))),
                ),
            )

            assertEquals("list of '$requested' must succeed", SessionMessageTypes.LIST_OK, reply?.type)
            assertTrue(requireNotNull(reply?.payload)["entries"].toString().contains("a.txt"))
        }
    }

    @Test
    fun `stat returns metadata for a single file`() {
        val root = createTempDirectory().toFile()
        File(root, "a.txt").writeText("hello")
        val session = newSession(root = root)

        val reply = session.dispatch(
            ControlMessage(type = SessionMessageTypes.STAT, id = "3", payload = JsonObject(mapOf("path" to JsonPrimitive("a.txt")))),
        )

        assertEquals(SessionMessageTypes.STAT_OK, reply?.type)
        val payload = requireNotNull(reply?.payload)
        assertEquals("a.txt", payload.getValue("name").jsonPrimitive.content)
        assertEquals(5L, payload.getValue("size").jsonPrimitive.content.toLong())
    }

    @Test
    fun `stat on a missing path returns an error, not a crash`() {
        val session = newSession()
        val reply = session.dispatch(
            ControlMessage(type = SessionMessageTypes.STAT, id = "4", payload = JsonObject(mapOf("path" to JsonPrimitive("nope.txt")))),
        )
        assertEquals(SessionMessageTypes.ERROR, reply?.type)
    }

    @Test
    fun `list and stat cannot escape the root directory via a traversal path`() {
        val root = createTempDirectory().toFile()
        val session = newSession(root = root)
        val reply = session.dispatch(
            ControlMessage(type = SessionMessageTypes.STAT, id = "5", payload = JsonObject(mapOf("path" to JsonPrimitive("../../etc/passwd")))),
        )
        assertEquals(SessionMessageTypes.ERROR, reply?.type)
    }

    // --- pull.request / pull.ok ---

    @Test
    fun `pull request issues a bulk token and answers pull_ok with a downloadable endpoint`() {
        val root = createTempDirectory().toFile()
        val file = File(root, "movie.mp4")
        file.writeBytes(ByteArray(1000))
        val session = newSession(root = root)

        val reply = session.dispatch(
            ControlMessage(
                type = SessionMessageTypes.PULL_REQUEST,
                id = "6",
                payload = JsonObject(mapOf("path" to JsonPrimitive("movie.mp4"), "streams" to JsonPrimitive(4))),
            ),
        )

        assertEquals(SessionMessageTypes.PULL_OK, reply?.type)
        val payload = requireNotNull(reply?.payload)
        assertEquals(1000L, payload.getValue("size").jsonPrimitive.content.toLong())
        assertEquals(53322, payload.getValue("port").jsonPrimitive.content.toInt())
        assertTrue(payload.getValue("token").jsonPrimitive.content.isNotBlank())
        assertTrue(payload.getValue("transferId").jsonPrimitive.content.isNotBlank())
    }

    @Test
    fun `pull request for a directory is an error`() {
        val root = createTempDirectory().toFile()
        File(root, "sub").mkdir()
        val session = newSession(root = root)
        val reply = session.dispatch(
            ControlMessage(type = SessionMessageTypes.PULL_REQUEST, id = "6b", payload = JsonObject(mapOf("path" to JsonPrimitive("sub")))),
        )
        assertEquals(SessionMessageTypes.ERROR, reply?.type)
    }

    // --- stream.request ---

    @Test
    fun `stream request issues a media token`() {
        val root = createTempDirectory().toFile()
        File(root, "song.mp3").writeBytes(ByteArray(10))
        val session = newSession(root = root)

        val reply = session.dispatch(
            ControlMessage(type = SessionMessageTypes.STREAM_REQUEST, id = "8", payload = JsonObject(mapOf("path" to JsonPrimitive("song.mp3")))),
        )

        assertEquals(SessionMessageTypes.STREAM_OK, reply?.type)
        val payload = requireNotNull(reply?.payload)
        assertEquals("audio/mpeg", payload.getValue("mime").jsonPrimitive.content)
        assertEquals(53323, payload.getValue("port").jsonPrimitive.content.toInt())
        assertTrue(payload.getValue("token").jsonPrimitive.content.isNotBlank())
    }

    // --- clipboard ---

    @Test
    fun `clipboard within the cap reaches the system clipboard`() {
        val sink = RecordingClipboardSink()
        val session = newSession(clipboard = sink)

        val reply = session.dispatch(
            ControlMessage(type = SessionMessageTypes.CLIPBOARD, payload = JsonObject(mapOf("text" to JsonPrimitive("hello there")))),
        )

        assertNull(reply)
        assertEquals(1, sink.calls)
        assertEquals("hello there", sink.lastText)
    }

    @Test
    fun `clipboard over the 64KB cap is rejected, not truncated`() {
        val sink = RecordingClipboardSink()
        val session = newSession(clipboard = sink)
        val oversized = "x".repeat(CLIPBOARD_MAX_BYTES + 1)

        val reply = session.dispatch(
            ControlMessage(type = SessionMessageTypes.CLIPBOARD, payload = JsonObject(mapOf("text" to JsonPrimitive(oversized)))),
        )

        assertNull(reply)
        assertEquals("clipboard must never receive a truncated over-cap payload", 0, sink.calls)
    }

    @Test
    fun `clipboard exactly at the cap is accepted`() {
        val sink = RecordingClipboardSink()
        val session = newSession(clipboard = sink)
        val exact = "x".repeat(CLIPBOARD_MAX_BYTES)

        session.dispatch(
            ControlMessage(type = SessionMessageTypes.CLIPBOARD, payload = JsonObject(mapOf("text" to JsonPrimitive(exact)))),
        )

        assertEquals(1, sink.calls)
    }

    // --- play ---

    @Test
    fun `a play message invokes onPlayRequested with the resolved file`() {
        val root = createTempDirectory().toFile()
        val file = File(root, "song.mp3")
        file.writeBytes(ByteArray(10))
        val requested = mutableListOf<File>()
        val session = newSession(root = root, onPlayRequested = { requested.add(it) })

        val reply = session.dispatch(
            ControlMessage(type = SessionMessageTypes.PLAY, payload = JsonObject(mapOf("path" to JsonPrimitive("song.mp3")))),
        )

        assertNull(reply)
        assertEquals(1, requested.size)
        assertEquals(file.canonicalFile, requested.single().canonicalFile)
    }

    @Test
    fun `a play message with an escaping path is silently ignored`() {
        val root = createTempDirectory().toFile()
        val requested = mutableListOf<File>()
        val session = newSession(root = root, onPlayRequested = { requested.add(it) })

        val reply = session.dispatch(
            ControlMessage(
                type = SessionMessageTypes.PLAY,
                payload = JsonObject(mapOf("path" to JsonPrimitive("../../etc/passwd"))),
            ),
        )

        assertNull(reply)
        assertTrue("an escaping play path must never invoke onPlayRequested", requested.isEmpty())
    }

    // --- play with a `url` payload (Task 11's addendum fix: real push-to-play, where the file
    // being played is owned by the *sender*, not resolvable against this device's own root) ---

    @Test
    fun `a play message carrying a url invokes onPlayUrlRequested, not onPlayRequested`() {
        val fileRequests = mutableListOf<File>()
        val urlRequests = mutableListOf<Pair<String, String?>>()
        val session = newSession(
            onPlayRequested = { fileRequests.add(it) },
            onPlayUrlRequested = { url, mime -> urlRequests.add(url to mime) },
        )

        val reply = session.dispatch(
            ControlMessage(
                type = SessionMessageTypes.PLAY,
                payload = JsonObject(
                    mapOf(
                        "url" to JsonPrimitive("http://192.168.1.5:53323/media/abc-123"),
                        "mime" to JsonPrimitive("video/mp4"),
                    ),
                ),
            ),
        )

        assertNull(reply)
        assertEquals(listOf("http://192.168.1.5:53323/media/abc-123" to "video/mp4"), urlRequests)
        assertTrue("a url-carrying play must never resolve a local path", fileRequests.isEmpty())
    }

    @Test
    fun `a play message with a url but no mime still invokes onPlayUrlRequested with a null mime`() {
        val urlRequests = mutableListOf<Pair<String, String?>>()
        val session = newSession(onPlayUrlRequested = { url, mime -> urlRequests.add(url to mime) })

        val reply = session.dispatch(
            ControlMessage(
                type = SessionMessageTypes.PLAY,
                payload = JsonObject(mapOf("url" to JsonPrimitive("http://192.168.1.5:53323/media/xyz"))),
            ),
        )

        assertNull(reply)
        assertEquals(listOf("http://192.168.1.5:53323/media/xyz" to null), urlRequests)
    }

    @Test
    fun `a play message with neither url nor path is silently ignored`() {
        val fileRequests = mutableListOf<File>()
        val urlRequests = mutableListOf<Pair<String, String?>>()
        val session = newSession(
            onPlayRequested = { fileRequests.add(it) },
            onPlayUrlRequested = { url, mime -> urlRequests.add(url to mime) },
        )

        val reply = session.dispatch(ControlMessage(type = SessionMessageTypes.PLAY, payload = JsonObject(emptyMap())))

        assertNull(reply)
        assertTrue(fileRequests.isEmpty())
        assertTrue(urlRequests.isEmpty())
    }
}
