package com.slipstream.core.control

import com.slipstream.core.files.FileBrowser
import com.slipstream.core.identity.DeviceIdentity
import com.slipstream.core.media.MediaTokenVault
import com.slipstream.core.transfer.TokenVault
import java.io.File
import java.net.InetSocketAddress
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

/** Delivers clipboard text to the platform clipboard. Kept as a seam so [SlipstreamSession]
 * doesn't need an Android `Context`/`ClipboardManager` reference to be unit tested. */
fun interface ClipboardSink {
    fun setText(text: String)
}

/** Wire message types for the primary (post-pairing) control session, per design.md §6. */
object SessionMessageTypes {
    const val HELLO = "hello"
    const val HELLO_OK = "hello.ok"
    const val PING = "ping"
    const val PONG = "pong"
    const val LIST = "list"
    const val LIST_OK = "list.ok"
    const val STAT = "stat"
    const val STAT_OK = "stat.ok"
    const val PULL_REQUEST = "pull.request"
    const val PULL_OK = "pull.ok"
    const val STREAM_REQUEST = "stream.request"
    const val STREAM_OK = "stream.ok"
    const val CLIPBOARD = "clipboard"
    const val ERROR = "error"
}

/** Maximum clipboard payload accepted over the control channel (design.md §6). */
const val CLIPBOARD_MAX_BYTES = 64 * 1024

/**
 * Handles inbound [ControlMessage]s on an established, already-paired control session
 * (protocol.md/design.md §6). One instance answers messages for one connection. [dispatch] is
 * pure request -> response: it never itself touches the connection, so it's unit testable
 * without a socket.
 *
 * Per [JsonLineCodec]'s existing skip-malformed-line contract, an unrecognized
 * [ControlMessage.type] is not an error at the dispatch layer either: [dispatch] returns null
 * and the caller must simply send nothing back - it must never disconnect the peer merely
 * because it didn't understand one message type. This matters for forward compatibility: a
 * newer peer sending a message type this build doesn't know yet must not tear down the
 * session.
 */
class SlipstreamSession(
    private val identity: DeviceIdentity,
    private val rootDirectory: File,
    private val bulkTokenVault: TokenVault,
    private val bulkEndpoint: () -> InetSocketAddress,
    private val mediaTokenVault: MediaTokenVault,
    private val mediaPort: () -> Int,
    private val clipboardSink: ClipboardSink,
    private val onBulkIssued: (transferId: UUID, sourceFile: File) -> Unit = { _, _ -> },
) {
    fun dispatch(message: ControlMessage): ControlMessage? = when (message.type) {
        SessionMessageTypes.HELLO -> helloOk(message)
        SessionMessageTypes.PING -> ControlMessage(type = SessionMessageTypes.PONG, id = message.id)
        SessionMessageTypes.LIST -> list(message)
        SessionMessageTypes.STAT -> stat(message)
        SessionMessageTypes.PULL_REQUEST -> pullRequest(message)
        SessionMessageTypes.STREAM_REQUEST -> streamRequest(message)
        SessionMessageTypes.CLIPBOARD -> clipboard(message)
        // Unknown type: ignored, never disconnects (see class doc).
        else -> null
    }

    // --- hello ---

    private fun helloOk(message: ControlMessage): ControlMessage {
        val payload = JsonObject(
            mapOf(
                "deviceId" to JsonPrimitive(identity.deviceId),
                "name" to JsonPrimitive(identity.displayName),
                "fingerprint" to JsonPrimitive(identity.fingerprint),
            ),
        )
        return ControlMessage(type = SessionMessageTypes.HELLO_OK, id = message.id, payload = payload)
    }

    // --- file browsing ---

    /** Resolves a client-supplied relative path against [rootDirectory], refusing to ever
     * escape it (e.g. via `..` segments). */
    private fun resolvePath(relative: String?): File? {
        val base = rootDirectory.canonicalFile
        val target = File(base, relative ?: ".").canonicalFile
        if (target != base && !target.path.startsWith(base.path + File.separator)) return null
        return target
    }

    private fun list(message: ControlMessage): ControlMessage {
        val path = message.payload?.get("path")?.jsonPrimitive?.contentOrNull
        val dir = resolvePath(path)
        if (dir == null || !dir.isDirectory) {
            return errorReply(message)
        }
        val listing = FileBrowser.list(dir)
        val entries = listing.entries.map { e ->
            JsonObject(
                mapOf(
                    "name" to JsonPrimitive(e.name),
                    "size" to JsonPrimitive(e.size),
                    "mtimeMs" to JsonPrimitive(e.mtimeMs),
                    "isDirectory" to JsonPrimitive(e.isDirectory),
                    "mime" to (e.mime?.let { JsonPrimitive(it) } ?: JsonNull),
                ),
            )
        }
        val payload = JsonObject(
            mapOf(
                "entries" to JsonArray(entries),
                "truncated" to JsonPrimitive(listing.truncated),
            ),
        )
        return ControlMessage(type = SessionMessageTypes.LIST_OK, id = message.id, payload = payload)
    }

    private fun stat(message: ControlMessage): ControlMessage {
        val path = message.payload?.get("path")?.jsonPrimitive?.contentOrNull
        val file = resolvePath(path)
        if (file == null || !file.exists()) {
            return errorReply(message)
        }
        val payload = JsonObject(
            mapOf(
                "name" to JsonPrimitive(file.name),
                "size" to JsonPrimitive(if (file.isDirectory) 0L else file.length()),
                "mtimeMs" to JsonPrimitive(file.lastModified()),
                "isDirectory" to JsonPrimitive(file.isDirectory),
                "mime" to (if (file.isDirectory) JsonNull else JsonPrimitive(FileBrowser.mimeFor(file.name))),
            ),
        )
        return ControlMessage(type = SessionMessageTypes.STAT_OK, id = message.id, payload = payload)
    }

    // --- bulk transfer ---

    private fun pullRequest(message: ControlMessage): ControlMessage {
        val path = message.payload?.get("path")?.jsonPrimitive?.contentOrNull
        val streams = message.payload?.get("streams")?.jsonPrimitive?.int ?: 4
        val file = resolvePath(path)
        if (file == null || !file.isFile) {
            return errorReply(message)
        }

        val transferId = UUID.randomUUID()
        val token = bulkTokenVault.issueBulk(transferId, file.path, file.length(), streams)
        onBulkIssued(transferId, file)
        val endpoint = bulkEndpoint()
        val payload = JsonObject(
            mapOf(
                "transferId" to JsonPrimitive(transferId.toString()),
                "token" to JsonPrimitive(token.value.toString()),
                "size" to JsonPrimitive(file.length()),
                "streams" to JsonPrimitive(streams),
                "host" to JsonPrimitive(endpoint.address.hostAddress),
                "port" to JsonPrimitive(endpoint.port),
            ),
        )
        return ControlMessage(type = SessionMessageTypes.PULL_OK, id = message.id, payload = payload)
    }

    // --- media streaming ---

    private fun streamRequest(message: ControlMessage): ControlMessage {
        val path = message.payload?.get("path")?.jsonPrimitive?.contentOrNull
        val file = resolvePath(path)
        if (file == null || !file.isFile) {
            return errorReply(message)
        }

        val mime = FileBrowser.mimeFor(file.name)
        val token = mediaTokenVault.issue(file, mime)
        val payload = JsonObject(
            mapOf(
                "token" to JsonPrimitive(token.value.toString()),
                "mime" to JsonPrimitive(mime),
                "port" to JsonPrimitive(mediaPort()),
            ),
        )
        return ControlMessage(type = SessionMessageTypes.STREAM_OK, id = message.id, payload = payload)
    }

    // --- clipboard ---

    /** `clipboard` is an event (design.md §6): it never gets a reply, success or failure -
     * either the text lands on the system clipboard, or (over the 64 KB cap) it's dropped. */
    private fun clipboard(message: ControlMessage): ControlMessage? {
        val text = message.payload?.get("text")?.jsonPrimitive?.contentOrNull ?: return null
        val byteSize = text.toByteArray(Charsets.UTF_8).size
        if (byteSize > CLIPBOARD_MAX_BYTES) {
            return null
        }
        clipboardSink.setText(text)
        return null
    }

    private fun errorReply(message: ControlMessage): ControlMessage =
        ControlMessage(type = SessionMessageTypes.ERROR, id = message.id)
}
