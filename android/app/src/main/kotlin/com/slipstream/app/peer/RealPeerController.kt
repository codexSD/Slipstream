package com.slipstream.app.peer

import com.slipstream.core.SlipstreamPeer
import com.slipstream.core.control.ControlClient
import com.slipstream.core.control.ControlConnection
import com.slipstream.core.control.ControlMessage
import com.slipstream.core.control.SessionMessageTypes
import com.slipstream.core.files.FileEntry
import com.slipstream.core.identity.DeviceIdentity
import com.slipstream.core.identity.PairedPeerStore
import com.slipstream.core.net.NetworkBinder
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** The direct, no-apology (spec §15) message surfaced for a failed [PeerController.list]. */
private const val LIST_FAILED_MESSAGE = "That folder is no longer there."

private const val HEARTBEAT_INTERVAL_MS = 750L
private val RECONNECT_BACKOFFS_MS = longArrayOf(1_000, 2_000, 4_000, 8_000, 15_000)

/**
 * The single owner of `:core` inside `:app`. Mirrors `Slipstream.App.Services.PeerHost`
 * (`windows/src/Slipstream.App/Services/PeerHost.cs`): one persistent [ControlConnection],
 * [mutex]-serialised (a duplex JSON-lines stream cannot have two concurrent request/response
 * pairs without interleaving replies and mismatching ids — see `PeerHost`'s remarks on `_gate`),
 * plus a heartbeat loop that treats a network switch (spec §5) as routine: [PeerConnectionState.Lost],
 * never a thrown exception.
 *
 * [peer] is handed in already constructed (mirroring `PeerHost`'s constructor) — this class never
 * builds its own [SlipstreamPeer]. It separately needs [identity], [peerStore], and [networkBinder]
 * because `SlipstreamPeer` keeps those private (only its own `pullFile`/`pushFile`/discovery use
 * them internally); opening this class's own second, persistent [ControlConnection] via
 * [ControlClient.connect] needs the same three collaborators the peer itself was built with.
 */
class RealPeerController(
    private val peer: SlipstreamPeer,
    private val identity: DeviceIdentity,
    private val peerStore: PairedPeerStore,
    private val clipboardSink: ForwardingClipboardSink,
    private val networkBinder: NetworkBinder = NetworkBinder.NONE,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : PeerController {

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    /** Guards the persistent control connection: one round trip (send + matching receive) at a
     * time, exactly as `PeerHost._gate` does. */
    private val mutex = Mutex()

    private val _status = MutableStateFlow(PeerStatus(PeerConnectionState.Idle))
    override val status: StateFlow<PeerStatus> = _status.asStateFlow()

    private val _isPaired = MutableStateFlow(peerStore.peer != null)
    override val isPaired: StateFlow<Boolean> = _isPaired.asStateFlow()

    override val clipboardReceived: SharedFlow<String> = clipboardSink.received

    @Volatile private var connection: ControlConnection? = null
    @Volatile private var peerEndpoint: java.net.InetSocketAddress? = null
    private var heartbeatJob: Job? = null

    /** The one [openPairing] attempt currently waiting on a user decision, if any. */
    @Volatile private var pendingPairingDecision: CompletableDeferred<Boolean>? = null

    override suspend fun start() {
        withContext(dispatcher) {
            peer.start()
            connectOnce()
        }
    }

    /**
     * Test-only seam: forcibly closes the outbound socket half of the persistent control
     * connection, without touching [peer] or [status] directly, so a test can simulate "the
     * network just dropped this connection" and observe the heartbeat loop's own detection and
     * [PeerConnectionState.Lost] transition — the same failure shape a real network switch
     * (spec §5) produces on the wire, just triggered locally instead of by killing the remote
     * process (which, per `ControlServer.close()`, only stops the *listening* socket and would
     * leave an already-accepted connection's serving thread running on the far end regardless).
     */
    internal fun debugCloseConnectionForTesting() {
        connection?.close()
    }

    override suspend fun reconnect(): Boolean = withContext(dispatcher) {
        dropConnection()
        for (backoffMs in RECONNECT_BACKOFFS_MS) {
            if (connectOnce()) return@withContext true
            delay(backoffMs)
        }
        connectOnce()
    }

    /** One discover-and-connect attempt. Updates [status] along the way; never throws. */
    private suspend fun connectOnce(): Boolean {
        _status.value = _status.value.copy(state = PeerConnectionState.Searching)
        val discovery = try {
            peer.discover(timeout = 10.seconds)
        } catch (e: Exception) {
            null
        }
        if (discovery == null) {
            _status.value = _status.value.copy(state = PeerConnectionState.Lost)
            return false
        }
        return try {
            val conn = ControlClient.connect(discovery.peer.endpoint, identity, peerStore, networkBinder)
            val peerName = helloExchange(conn)
            connection = conn
            peerEndpoint = discovery.peer.endpoint
            _status.value = PeerStatus(
                state = PeerConnectionState.Connected,
                peerName = peerName,
                strategy = discovery.strategyName,
            )
            startHeartbeat()
            true
        } catch (e: Exception) {
            _status.value = _status.value.copy(state = PeerConnectionState.Lost)
            false
        }
    }

    private fun helloExchange(conn: ControlConnection): String? = try {
        val id = UUID.randomUUID().toString()
        conn.send(ControlMessage(type = SessionMessageTypes.HELLO, id = id))
        val reply = conn.receive()
        reply?.payload?.get("name")?.jsonPrimitive?.contentOrNull
    } catch (e: Exception) {
        null
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                val alive = try {
                    sendRequest(SessionMessageTypes.PING, null)
                    true
                } catch (e: Exception) {
                    false
                }
                if (!alive) {
                    _status.value = _status.value.copy(state = PeerConnectionState.Lost)
                    break
                }
            }
        }
    }

    private fun dropConnection() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        connection?.close()
        connection = null
    }

    /** Sends [type]/[payload] under [mutex] and blocks (suspends) for the reply carrying the
     * same id, mirroring `PeerHost.SendRequestAsync`. Throws if there is no connection, the
     * connection closes before a matching reply arrives, or the round trip is interrupted. */
    private suspend fun sendRequest(type: String, payload: JsonObject?): ControlMessage = mutex.withLock {
        val conn = connection ?: throw IllegalStateException("Not connected")
        val id = UUID.randomUUID().toString()
        conn.send(ControlMessage(type = type, id = id, payload = payload))
        while (true) {
            val message = conn.receive() ?: throw IllegalStateException("The peer closed the control connection.")
            if (message.id == id) return@withLock message
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    override suspend fun list(path: String): Result<ListResult> = withContext(dispatcher) {
        try {
            val reply = sendRequest(SessionMessageTypes.LIST, JsonObject(mapOf("path" to JsonPrimitive(path))))
            if (reply.type != SessionMessageTypes.LIST_OK) {
                return@withContext Result.failure(IllegalStateException(LIST_FAILED_MESSAGE))
            }
            val payload = reply.payload ?: return@withContext Result.failure(IllegalStateException(LIST_FAILED_MESSAGE))
            val entries = payload["entries"]?.jsonArray?.map { element ->
                val obj = element.jsonObject
                FileEntry(
                    name = obj.getValue("name").jsonPrimitive.content,
                    size = obj.getValue("size").jsonPrimitive.long,
                    mtimeMs = obj.getValue("mtimeMs").jsonPrimitive.long,
                    isDirectory = obj.getValue("isDirectory").jsonPrimitive.boolean,
                    mime = obj["mime"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull,
                    thumbnailToken = obj["thumbnailToken"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull,
                )
            } ?: emptyList()
            val truncated = payload["truncated"]?.jsonPrimitive?.boolean ?: false
            Result.success(ListResult(entries, truncated))
        } catch (e: Exception) {
            Result.failure(IllegalStateException(LIST_FAILED_MESSAGE))
        }
    }

    override fun thumbnailUrl(token: String): String? {
        val endpoint = peerEndpoint ?: return null
        return "http://${endpoint.address.hostAddress}:${com.slipstream.core.SlipstreamPorts.MEDIA}/thumb/$token"
    }

    override fun pull(remotePath: String, destination: File): Flow<TransferProgress> = callbackFlow {
        val endpoint = peerEndpoint
        if (endpoint == null) {
            close(IllegalStateException("Not connected"))
            return@callbackFlow
        }
        val cumulative = AtomicLong(0)
        val job = launch(dispatcher) {
            try {
                val part = peer.pullFile(endpoint, remotePath, destination, streams = 4) { bytes ->
                    trySend(TransferProgress(cumulative.addAndGet(bytes), 0L))
                }
                trySend(TransferProgress(part.fileSize, part.fileSize))
                close()
            } catch (e: Exception) {
                close(e)
            }
        }
        awaitClose { job.cancel() }
    }

    override fun push(localPath: String, remoteName: String): Flow<TransferProgress> = callbackFlow {
        val endpoint = peerEndpoint
        val localFile = File(localPath)
        if (endpoint == null) {
            close(IllegalStateException("Not connected"))
            return@callbackFlow
        }
        val cumulative = AtomicLong(0)
        val total = localFile.length()
        val job = launch(dispatcher) {
            try {
                val ok = peer.pushFile(endpoint, localFile, remoteName) { bytes ->
                    trySend(TransferProgress(cumulative.addAndGet(bytes), total))
                }
                if (!ok) {
                    close(IllegalStateException("The peer never accepted the file."))
                } else {
                    trySend(TransferProgress(total, total))
                    close()
                }
            } catch (e: Exception) {
                close(e)
            }
        }
        awaitClose { job.cancel() }
    }

    /**
     * `:core`'s only streaming message is `stream.request`/`stream.ok` (see
     * `SlipstreamSession.streamRequest`), which resolves [remotePath] against the *responder's*
     * own root and hands back a URL to stream FROM that responder — there is no wire message
     * that tells a peer "render this on your own screen". So [streamOnPeer] and [streamUrlFor]
     * both drive the same exchange against the peer; the difference is purely what the caller
     * does with the result. [streamOnPeer] is therefore only as good as "the peer confirmed it
     * can serve that file" — it cannot actually cause remote playback, since `:core`'s protocol
     * has no such command today. Flagged in the task report as a load-bearing protocol gap the
     * plan did not anticipate.
     */
    override suspend fun streamOnPeer(remotePath: String): Result<Unit> =
        requestStream(remotePath).map { }

    override suspend fun streamUrlFor(remotePath: String): Result<String> =
        requestStream(remotePath).map { (host, port, token) -> "http://$host:$port/media/$token" }

    private suspend fun requestStream(remotePath: String): Result<Triple<String, Int, String>> = withContext(dispatcher) {
        try {
            val endpoint = peerEndpoint ?: return@withContext Result.failure(IllegalStateException("Not connected"))
            val reply = sendRequest(SessionMessageTypes.STREAM_REQUEST, JsonObject(mapOf("path" to JsonPrimitive(remotePath))))
            if (reply.type != SessionMessageTypes.STREAM_OK) {
                return@withContext Result.failure(IllegalStateException("The peer refused to stream that file."))
            }
            val payload = reply.payload ?: return@withContext Result.failure(IllegalStateException("The peer sent a malformed stream response."))
            val token = payload.getValue("token").jsonPrimitive.content
            val port = payload.getValue("port").jsonPrimitive.content.toInt()
            Result.success(Triple(endpoint.address.hostAddress, port, token))
        } catch (e: Exception) {
            Result.failure(IllegalStateException("The peer refused to stream that file."))
        }
    }

    override suspend fun sendClipboard(text: String): Result<Unit> = withContext(dispatcher) {
        try {
            mutex.withLock {
                val conn = connection ?: throw IllegalStateException("Not connected")
                // `clipboard` is a fire-and-forget event (SlipstreamSession.clipboard never
                // replies) — send only, never wait for a matching id.
                conn.send(
                    ControlMessage(
                        type = SessionMessageTypes.CLIPBOARD,
                        id = UUID.randomUUID().toString(),
                        payload = JsonObject(mapOf("text" to JsonPrimitive(text))),
                    ),
                )
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun openPairing(): Flow<PairingProgress> = callbackFlow {
        val decision = CompletableDeferred<Boolean>()
        pendingPairingDecision = decision
        val job = launch(dispatcher) {
            try {
                val paired = peer.openPairingWindow(timeout = 120.seconds) { code ->
                    trySend(PairingProgress.CodeReceived(code))
                    // openPairingWindow's confirmCode is a plain blocking (String) -> Boolean —
                    // not suspend — so the calling (IO) thread blocks here until confirmPairing()
                    // completes the deferred, exactly per the addendum's bridging instructions.
                    runBlocking { decision.await() }
                }
                trySend(PairingProgress.Completed(paired != null))
                _isPaired.value = peerStore.peer != null
                close()
            } catch (e: Exception) {
                close(e)
            } finally {
                pendingPairingDecision = null
            }
        }
        awaitClose { job.cancel() }
    }

    override suspend fun confirmPairing(accept: Boolean) {
        pendingPairingDecision?.complete(accept)
    }

    override suspend fun unpair() = withContext(dispatcher) {
        peerStore.clear()
        _isPaired.value = false
        dropConnection()
    }
}
