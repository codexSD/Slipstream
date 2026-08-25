package com.slipstream.core

import com.slipstream.core.control.ClipboardSink
import com.slipstream.core.control.ControlClient
import com.slipstream.core.control.ControlConnection
import com.slipstream.core.control.ControlMessage
import com.slipstream.core.control.ControlServer
import com.slipstream.core.control.SessionMessageTypes
import com.slipstream.core.control.SlipstreamSession
import com.slipstream.core.discovery.DiscoveryCoordinator
import com.slipstream.core.discovery.DiscoveryResult
import com.slipstream.core.identity.DeviceIdentity
import com.slipstream.core.identity.PairedPeerStore
import com.slipstream.core.media.MediaServer
import com.slipstream.core.media.MediaTokenVault
import com.slipstream.core.net.MutableNetworkBinder
import com.slipstream.core.net.NetworkInfo
import com.slipstream.core.transfer.BulkServer
import com.slipstream.core.transfer.BulkSession
import com.slipstream.core.transfer.PartFile
import com.slipstream.core.transfer.TokenVault
import com.slipstream.core.transfer.TransferEngine
import java.io.File
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** Default chunk size for pulled files; no shared constant exists elsewhere in the module. */
private const val DEFAULT_CHUNK_SIZE = 256 * 1024

/**
 * Facade wiring identity, discovery, control, pairing, transfer, and media into one running
 * peer (Task 12). Owns the lifetime of every listening server this device runs and every
 * outbound connection it initiates, and is the single place [networkBinder] gets updated when
 * the active network changes - per spec §11 layer 3, every socket created after that point
 * (discovery UDP, control TCP client+server, bulk TCP client+server, media TCP server) is
 * bound to the (possibly new) active [android.net.Network], so traffic cannot silently route
 * over a different network path (e.g. cellular) even if one exists.
 *
 * Server-side (listening) sockets get their network-scoping a different way: all three
 * ([ControlServer], [BulkServer], [MediaServer]) bind to the specific local address
 * [NetworkInfo.current] reports for the active network (never the wildcard address) rather than
 * via [android.net.Network.bindSocket], which Android does not support for
 * [java.net.ServerSocket]. Each of them additionally applies [com.slipstream.core.net.LanGuard]
 * to every accepted connection's remote address (spec §11 layer 2) before reading a byte.
 */
class SlipstreamPeer(
    val identity: DeviceIdentity,
    private val peerStore: PairedPeerStore,
    private val networkInfo: NetworkInfo,
    private val rootDirectory: File,
    private val clipboardSink: ClipboardSink,
    private val discoveryCoordinatorFactory: () -> DiscoveryCoordinator,
    private val networkBinder: MutableNetworkBinder = MutableNetworkBinder(),
    /** Listen ports. Overridable (with 0 = "any free port") so tests can start real servers
     * without colliding with a device's fixed Slipstream ports or with each other. */
    private val controlPort: Int = SlipstreamPorts.CONTROL,
    private val bulkPort: Int = SlipstreamPorts.BULK,
    private val mediaPort: Int = SlipstreamPorts.MEDIA,
    /** Lifecycle hooks fired during [onNetworkChanged], mainly so callers (including tests)
     * can observe the tear-down / re-discovery / resume sequence without reaching into
     * private state. Production use is expected too, e.g. surfacing status to the UI. */
    private val onTeardown: () -> Unit = {},
    private val onRediscover: (DiscoveryResult?) -> Unit = {},
    private val onResumeAttempt: (UUID) -> Unit = {},
) : AutoCloseable {

    val bulkTokenVault = TokenVault()
    val mediaTokenVault = MediaTokenVault()

    private var controlServer: ControlServer? = null
    private var bulkServer: BulkServer? = null
    private var mediaServer: MediaServer? = null

    private val transferEngine get() = TransferEngine(networkBinder)

    /** Serializes [onNetworkChanged]; see its doc. */
    private val networkChangeLock = ReentrantLock()

    /** The network [onNetworkChanged] last acted on, and whether it has ever acted at all -
     * the latter is needed because `null` (no network) is itself a meaningful applied state
     * that must be distinguishable from "nothing applied yet". */
    private var appliedNetwork: android.net.Network? = null
    private var hasAppliedNetwork = false

    /** Transfer ids with a resume thread currently running; see [resumeActivePulls]. */
    private val resumeInFlight = ConcurrentHashMap<UUID, Unit>()

    /** How many of the three listening servers are currently up. Exists so a test can assert
     * that a storm of overlapping network changes leaves exactly one live set behind, rather
     * than a torn-down-and-never-restarted peer or a pile of orphans. */
    internal val runningServerCount: Int
        get() = listOfNotNull(controlServer, bulkServer, mediaServer).size

    /** Pulls currently in flight, keyed by transfer id, so a network change can resume them
     * from their on-disk bitmap rather than starting over. `internal` (rather than private) so
     * tests can seed an in-flight pull directly, without needing to race a real transfer
     * against a real network-change signal to exercise [onNetworkChanged]'s resume path. */
    internal val activePulls = ConcurrentHashMap<UUID, ActivePull>()

    internal data class ActivePull(
        val part: PartFile,
        val streams: Int,
        val remotePath: String,
        val peerControlEndpoint: InetSocketAddress,
    )

    /** Starts every listening server. Also clears [mediaTokenVault] - media tokens are
     * deliberately long-lived (12h) *or* app-restart, whichever is first (design.md §8), and
     * this is that "app restart" boundary. */
    fun start() {
        mediaTokenVault.clear()
        startServers()
    }

    private fun startServers() {
        // Spec §11 layer 1: every listening socket binds to this network's own address, never
        // the wildcard. ControlServer derives it itself (and throws if there is none); the bulk
        // and media servers take it explicitly, from the same source.
        val bindAddress = requireNotNull(networkInfo.current()) {
            "No local network available to bind Slipstream's servers to"
        }.localAddress

        val server = ControlServer(identity, peerStore, networkInfo, port = controlPort)
        server.onPeerConnected = { conn -> thread(isDaemon = true) { serveConnection(conn) } }
        controlServer = server

        bulkServer = BulkServer(
            bulkTokenVault,
            fileForTransfer = { id -> sourceFileForTransfer[id] },
            port = bulkPort,
            bindAddress = bindAddress,
        )
        mediaServer = MediaServer(mediaTokenVault, port = mediaPort, bindAddress = bindAddress)
    }

    // Source files this device is serving out via bulk (i.e. this device is the sender for
    // that transfer id) - distinct from activePulls, which tracks files this device is
    // *receiving*. Populated by SlipstreamSession.onBulkIssued as each pull.request is served.
    private val sourceFileForTransfer = ConcurrentHashMap<UUID, File>()

    private fun buildSession(): SlipstreamSession = SlipstreamSession(
        identity = identity,
        rootDirectory = rootDirectory,
        bulkTokenVault = bulkTokenVault,
        bulkEndpoint = {
            val port = bulkServer?.boundPort ?: bulkPort
            InetSocketAddress(requireNotNull(networkInfo.current()) { "no active network" }.localAddress, port)
        },
        mediaTokenVault = mediaTokenVault,
        mediaPort = { mediaServer?.boundPort ?: mediaPort },
        clipboardSink = clipboardSink,
        onBulkIssued = { transferId, file -> sourceFileForTransfer[transferId] = file },
    )

    private fun serveConnection(conn: ControlConnection) {
        val session = buildSession()
        try {
            while (!conn.isClosed) {
                val msg = conn.receive() ?: break
                val reply = session.dispatch(msg)
                if (reply != null) conn.send(reply)
            }
        } catch (e: Exception) {
            // Connection dropped mid-session; nothing more to do.
        } finally {
            conn.close()
        }
    }

    /** Runs discovery for the currently paired peer. */
    suspend fun discover(timeout: kotlin.time.Duration = 10.seconds): DiscoveryResult? =
        discoveryCoordinatorFactory().discover(timeout)

    /**
     * Called by the host app's `ConnectivityManager.NetworkCallback` whenever the active
     * network changes (including "went away entirely", in which case [network] is null): tears
     * down every listening server and updates [networkBinder] so every socket created *from
     * this point on* is scoped to the new network, then restarts the servers on the new local
     * address and resumes any in-flight pulls from where their bitmap left off.
     */
    fun onNetworkChanged(network: android.net.Network?) {
        // ConnectivityManager delivers onAvailable/onCapabilitiesChanged/onLost concurrently on
        // its own threads, and they routinely overlap. Without this lock two events can
        // interleave a teardown against a restart - orphaning one set of accept threads on
        // still-bound ports and making the *other* set's bind fail - so they are serialized:
        // a second event queues behind the first rather than racing it.
        networkChangeLock.withLock {
            // Layer-4-of-none, but just as important for behaviour: onCapabilitiesChanged fires
            // routinely (validation completing, signal or bandwidth changes) for a network that
            // has not actually changed. Restarting the servers for one of those would kill every
            // in-flight inbound transfer and live control session for no reason at all.
            if (hasAppliedNetwork && network == appliedNetwork) return
            hasAppliedNetwork = true
            appliedNetwork = network

            networkBinder.network = network

            // A failure here (e.g. networkInfo.current() racing to null, or the previous
            // listen socket not yet fully released) must never propagate: this runs on a
            // framework NetworkCallback thread, where an escaping exception takes the whole
            // foreground service down on a routine Wi-Fi transition.
            try {
                teardown()
                if (network != null && networkInfo.current() != null) {
                    startServers()
                }
            } catch (e: Exception) {
                // Leave the peer in the cleanly-torn-down state; the next network event (or an
                // explicit start()) restarts it.
                closeServers()
            }

            thread(isDaemon = true) {
                val result = try {
                    kotlinx.coroutines.runBlocking { discover() }
                } catch (e: Exception) {
                    null
                }
                onRediscover(result)
            }
            resumeActivePulls()
        }
    }

    /** Closes every listening server and forgets it. Safe to call twice, and safe to call on a
     * partially-started set (which is exactly what a failed [startServers] leaves behind). */
    private fun closeServers() {
        try { controlServer?.close() } catch (_: Exception) {}
        controlServer = null
        try { bulkServer?.close() } catch (_: Exception) {}
        bulkServer = null
        try { mediaServer?.close() } catch (_: Exception) {}
        mediaServer = null
    }

    private fun teardown() {
        closeServers()
        onTeardown()
    }

    /**
     * Restarts every incomplete pull that was in flight. Internal (not private) so a test can
     * drive two overlapping resume passes directly.
     *
     * Each transfer id is claimed in [resumeInFlight] *before* its thread is spawned, so two
     * network events in quick succession cannot end up with two [TransferEngine.pull] loops
     * writing the same [PartFile] concurrently - which would interleave chunk writes and
     * corrupt it.
     */
    internal fun resumeActivePulls() {
        activePulls.values.toList().forEach { pull ->
            val transferId = pull.part.transferId
            if (pull.part.complete()) return@forEach
            if (resumeInFlight.putIfAbsent(transferId, Unit) != null) return@forEach
            onResumeAttempt(transferId)
            thread(isDaemon = true) {
                try {
                    transferEngine.pull(pull.part, pull.streams) {
                        negotiatePull(pull.peerControlEndpoint, pull.remotePath, pull.streams).session
                    }
                } catch (e: Exception) {
                    // Best-effort resume; the caller can retry pullFile() explicitly later.
                } finally {
                    resumeInFlight.remove(transferId)
                }
            }
        }
    }

    private data class Negotiated(val session: BulkSession, val size: Long)

    private fun negotiatePull(peerControlEndpoint: InetSocketAddress, remotePath: String, streams: Int): Negotiated {
        ControlClient.connect(peerControlEndpoint, identity, peerStore, networkBinder).use { conn ->
            conn.send(
                ControlMessage(
                    type = SessionMessageTypes.PULL_REQUEST,
                    id = UUID.randomUUID().toString(),
                    payload = JsonObject(
                        mapOf("path" to JsonPrimitive(remotePath), "streams" to JsonPrimitive(streams)),
                    ),
                ),
            )
            val reply = conn.receive() ?: throw IllegalStateException("connection closed before pull.ok")
            require(reply.type == SessionMessageTypes.PULL_OK) { "unexpected reply type ${reply.type}" }
            val payload = requireNotNull(reply.payload) { "pull.ok missing payload" }
            val transferId = UUID.fromString(payload.getValue("transferId").jsonPrimitive.content)
            val token = UUID.fromString(payload.getValue("token").jsonPrimitive.content)
            val host = payload.getValue("host").jsonPrimitive.content
            val port = payload.getValue("port").jsonPrimitive.int
            val size = payload.getValue("size").jsonPrimitive.long
            return Negotiated(BulkSession(InetSocketAddress(host, port), transferId, token), size)
        }
    }

    /**
     * Pulls [remotePath] from the peer at [peerControlEndpoint] into [destination], driving
     * [TransferEngine]'s retry loop with a `session` lambda that re-negotiates a fresh bulk
     * token on every attempt (Task 10's seam, wired for real here). Tracks the transfer in
     * [activePulls] for the duration so a network change mid-pull triggers a resume instead of
     * abandoning it.
     */
    fun pullFile(peerControlEndpoint: InetSocketAddress, remotePath: String, destination: File, streams: Int = 4): PartFile {
        val first = negotiatePull(peerControlEndpoint, remotePath, streams)
        val part = PartFile.openOrCreate(destination, first.session.transferId, first.size, DEFAULT_CHUNK_SIZE)
        activePulls[first.session.transferId] = ActivePull(part, streams, remotePath, peerControlEndpoint)
        try {
            transferEngine.pull(part, streams) { negotiatePull(peerControlEndpoint, remotePath, streams).session }
        } finally {
            activePulls.remove(first.session.transferId)
        }
        return part
    }

    override fun close() {
        // Same lock as onNetworkChanged, so close() cannot land halfway through a restart.
        networkChangeLock.withLock { teardown() }
    }
}
