package com.slipstream.core

import com.slipstream.core.control.ClipboardSink
import com.slipstream.core.control.ControlClient
import com.slipstream.core.control.ControlConnection
import com.slipstream.core.control.ControlMessage
import com.slipstream.core.control.ControlServer
import com.slipstream.core.control.SessionMessageTypes
import com.slipstream.core.control.SlipstreamSession
import com.slipstream.core.discovery.DiscoveryCoordinator
import com.slipstream.core.discovery.DiscoveryResponder
import com.slipstream.core.discovery.DiscoveryResult
import com.slipstream.core.identity.DeviceIdentity
import com.slipstream.core.identity.PairedPeer
import com.slipstream.core.identity.PairedPeerStore
import com.slipstream.core.media.MediaServer
import com.slipstream.core.media.MediaTokenVault
import com.slipstream.core.media.ThumbnailProvider
import com.slipstream.core.net.IpLiteral
import com.slipstream.core.net.LanGuard
import com.slipstream.core.net.MutableNetworkBinder
import com.slipstream.core.net.NetworkInfo
import com.slipstream.core.pairing.PairingCoordinator
import com.slipstream.core.pairing.PairingWindow
import com.slipstream.core.transfer.BulkServer
import com.slipstream.core.transfer.BulkSession
import com.slipstream.core.transfer.PartFile
import com.slipstream.core.transfer.TokenVault
import com.slipstream.core.transfer.TransferEngine
import java.io.File
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** Default chunk size for pulled files; no shared constant exists elsewhere in the module. */
private const val DEFAULT_CHUNK_SIZE = 256 * 1024

/** Generous bound on how long [SlipstreamPeer.pushFile] waits for its own [BulkServer] to
 * finish serving the file, standing in for the completion ack this protocol doesn't have. */
private val PUSH_TIMEOUT = 10.minutes

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
    /**
     * The always-on half of discovery (spec §5: "the phone only ever listens and responds").
     * Kept live for as long as this peer is running, so a PC's multicast query is answered by
     * a phone that is merely *running*, not only by one that happens to be inside its own
     * [discover] call. Null (the default) leaves the peer discovery-passive, which is what
     * every unit test that isn't specifically about the responder wants.
     */
    private val discoveryResponder: DiscoveryResponder? = null,
    /**
     * The 120-second window pairing is only ever permitted inside (pairing.md §1). Held here
     * rather than left to [ControlServer]'s own default so [openPairingWindow] has something
     * to open: without a reference, nothing outside `:core` could ever start a pairing.
     */
    private val pairingWindow: PairingWindow = PairingWindow(),
    /** Listen ports. Overridable (with 0 = "any free port") so tests can start real servers
     * without colliding with a device's fixed Slipstream ports or with each other. */
    private val controlPort: Int = SlipstreamPorts.CONTROL,
    private val bulkPort: Int = SlipstreamPorts.BULK,
    private val mediaPort: Int = SlipstreamPorts.MEDIA,
    /**
     * How long [onNetworkChanged] will wait for the network it was just told about to actually
     * produce a bindable interface, and how often it re-reads while waiting. See
     * [awaitNetworkReady]. The window is short on purpose: it runs on a framework
     * `NetworkCallback` thread, and failing to reach readiness is not fatal - it just leaves the
     * event un-applied so the next one for the same network retries.
     */
    private val readinessWindow: Duration = 1.seconds,
    private val readinessPollInterval: Duration = 50.milliseconds,
    /** Lifecycle hooks fired during [onNetworkChanged], mainly so callers (including tests)
     * can observe the tear-down / re-discovery / resume sequence without reaching into
     * private state. Production use is expected too, e.g. surfacing status to the UI. */
    private val onTeardown: () -> Unit = {},
    private val onRediscover: (DiscoveryResult?) -> Unit = {},
    private val onResumeAttempt: (UUID) -> Unit = {},
    /** Fired when a paired peer sends `play` with a `path` field (design.md §8, push-to-play,
     * legacy/path-based shape - see [com.slipstream.core.control.SlipstreamSession]'s doc on the
     * two `play` callbacks): this device is asked to start playing a file resolved against its
     * own root. [SlipstreamPeer] only forwards - the app-level owner decides what "play" means
     * (e.g. launching `ACTION_VIEW`). */
    private val onPlayRequested: (File) -> Unit = {},
    /** Fired when a paired peer sends `play` with a `url` field: the real push-to-play shape
     * (design.md §8) - the peer already owns the file, already issued itself a stream token, and
     * is handing this device a ready-to-open URL to *its own* media server. [mime] mirrors
     * whatever the sender's `stream.request` resolved, if it sent one. */
    private val onPlayUrlRequested: (url: String, mime: String?) -> Unit = { _, _ -> },
) : AutoCloseable {

    val bulkTokenVault = TokenVault()
    val mediaTokenVault = MediaTokenVault()

    /** Cached thumbnails live next to, but outside, [rootDirectory] - a sibling directory
     * rather than a child - so a generated thumbnail never itself shows up as a browsable file
     * in a `list` of the user's own folders. */
    private val thumbnailProvider = ThumbnailProvider(File(rootDirectory.parentFile ?: rootDirectory, "thumb-cache"))

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
    fun start(network: android.net.Network? = null) {
        mediaTokenVault.clear()
        networkChangeLock.withLock {
            // Bind outbound sockets to this network too, not only the dedup state below.
            // Seeding one without the other meant that whenever the caller's activeNetwork
            // already WAS the Wi-Fi network, the genuine onAvailable(wifi) that followed was
            // dismissed as a duplicate and the binder was left null forever. Servers still
            // bound correctly (they go through networkInfo, not the binder), so inbound
            // traffic worked and the device looked healthy — while every outbound socket fell
            // back to the default network, which on a phone with mobile data is cellular, and
            // could not reach a single LAN address. Discovery simply found nothing.
            SlipstreamLog.i("network", "start() binding sockets to ${network ?: "(none — default routing)"}")
            networkBinder.network = network
            startServers()
            // Seed the dedup state with what was just brought up. Without this the very first
            // onNetworkChanged callback after start() - which, for the network start() already
            // bound to, carries no new information at all - would tear these servers straight
            // back down and rebuild them, on every single cold start.
            hasAppliedNetwork = true
            appliedNetwork = network
        }
    }

    /** The control channel's actual listen address, once [start] has brought it up. Needed by
     * anything that hands this device's endpoint to a peer out of band - pairing in particular. */
    val controlEndpoint: InetSocketAddress?
        get() = controlServer?.listenEndpoint

    /** This device's own currently-bound media server address, or null if it isn't running (not
     * yet [start]-ed, or the active network just went away). Needed for real push-to-play
     * (design.md §8): to hand a peer a URL for a file *this* device owns, this device must be
     * able to describe its own address - unlike [com.slipstream.core.control.SlipstreamSession.streamRequest],
     * which only ever describes the *responder's* address back to a remote asker over the wire,
     * this is a same-process read with no round trip. */
    val mediaEndpoint: InetSocketAddress?
        get() {
            val server = mediaServer ?: return null
            val local = networkInfo.current() ?: return null
            return InetSocketAddress(local.localAddress, server.boundPort)
        }

    private fun startServers() {
        // Spec §11 layer 1: every listening socket binds to this network's own address, never
        // the wildcard. ControlServer derives it itself (and throws if there is none); the bulk
        // and media servers take it explicitly, from the same source.
        val bindAddress = requireNotNull(networkInfo.current()) {
            "No local network available to bind Slipstream's servers to"
        }.localAddress

        SlipstreamLog.i(
            "servers",
            "binding to ${bindAddress.hostAddress} " +
                "(control=$controlPort bulk=$bulkPort media=$mediaPort)",
        )

        val server = ControlServer(identity, peerStore, networkInfo, port = controlPort, pairingWindow)
        server.onPeerConnected = { conn -> thread(isDaemon = true) { serveConnection(conn) } }
        server.onPairingConnected = { conn -> servePairingConnection(conn) }
        // Only now, with both handlers assigned, may connections start arriving: the accept
        // loop used to start inside ControlServer's constructor, so anything landing in this
        // window was routed to a null callback and dropped (and leaked).
        server.start()
        controlServer = server

        bulkServer = BulkServer(
            bulkTokenVault,
            fileForTransfer = { id -> sourceFileForTransfer[id] },
            port = bulkPort,
            bindAddress = bindAddress,
            onBytesServed = { transferId, bytes -> onPushBytesServed(transferId, bytes) },
        )
        mediaServer = MediaServer(mediaTokenVault, port = mediaPort, bindAddress = bindAddress)

        // Last, once every listening socket is up: the responder is bound to the same network,
        // so it is restarted in step with the servers rather than left listening on a socket
        // scoped to the network that just went away.
        startResponder()
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
        thumbnailProvider = thumbnailProvider,
        onBulkIssued = { transferId, file -> recordServedTransfer(transferId, file) },
        onPushOffered = { transferId, token, endpoint, size, destination ->
            handlePushOffered(transferId, token, endpoint, size, destination)
        },
        onPlayRequested = onPlayRequested,
        onPlayUrlRequested = onPlayUrlRequested,
    )

    // --- push (device-initiated send) ---

    /** Cumulative bytes this device's own [BulkServer] has served for a push it initiated,
     * keyed by transfer id - how [pushFile] knows "fully served" without a network-level
     * completion ack (none exists; see [pushFile]'s doc). Only populated for transfers this
     * device is the *sender* of via [pushFile]; unrelated `pull.request` traffic served by the
     * same [BulkServer] never has an entry here. */
    private val pushBytesServed = ConcurrentHashMap<UUID, java.util.concurrent.atomic.AtomicLong>()

    /** Per-push caller progress sinks, routed by transfer id. Keeps [BulkServer] itself a
     * single dumb global callback rather than something stateful about individual pushes. */
    private val pushProgressSinks = ConcurrentHashMap<UUID, (Long) -> Unit>()

    private fun onPushBytesServed(transferId: UUID, bytes: Long) {
        pushBytesServed[transferId]?.addAndGet(bytes)
        pushProgressSinks[transferId]?.invoke(bytes)
    }

    /**
     * Receiving side of a push: an inbound `push.offer` was just accepted (destination
     * resolved, parent directories created, `push.ok` about to be sent) - this runs the same
     * [TransferEngine]/[BulkClient] download machinery [pullFile] uses, but against the
     * *sender's* [endpoint]/[token]/[transferId] rather than one this device negotiated itself,
     * on a background thread since this is an unsolicited background receive rather than a
     * caller-blocking call.
     *
     * A network change mid-receive is not resumed (this transfer is never added to
     * [activePulls]) - the brief for this addition only requires a clean receive, not resume
     * guarantees for the receiving device; a dropped connection here simply leaves a partial
     * file behind, exactly as an un-resumed [pullFile] failure would.
     */
    private fun handlePushOffered(
        transferId: UUID,
        token: UUID,
        endpoint: InetSocketAddress,
        size: Long,
        destination: File,
    ) {
        thread(isDaemon = true) {
            val part = try {
                val validated = bulkEndpointFrom(endpoint.hostString, endpoint.port)
                val part = PartFile.openOrCreate(destination, transferId, size, DEFAULT_CHUNK_SIZE)
                try {
                    transferEngine.pull(part, streams = 1) { BulkSession(validated, transferId, token) }
                } catch (e: Exception) {
                    // Best-effort receive; resume-on-push is out of scope (see doc above).
                }
                part
            } catch (e: Exception) {
                // The offer's own endpoint failed validation (non-literal/non-local host, bad
                // port) - nothing was ever opened for writing, so there is no PartFile to close.
                null
            }
            if (part != null) finishTransfer(transferId, part)
        }
    }

    /**
     * Sending side of a push: issues this device's own bulk token for [localFile], offers it to
     * the peer at [peerControlEndpoint] over a short-lived control connection (mirroring
     * [negotiatePull]'s connect-send-receive-close shape), and - once the peer accepts - blocks
     * until [localFile] has been fully served out by this device's own [BulkServer], driving
     * [onProgress] from the bytes that server actually writes.
     *
     * There is no network-level "transfer complete" acknowledgement in this protocol (a pull
     * doesn't have one either), so completion is inferred the same way a caller of `pullFile`
     * would infer it for the file it's pulling: cumulative bytes served for this transfer id
     * reaching [File.length]. A generous bounded wait stands in for that ack rather than
     * blocking forever on a peer that vanished mid-transfer.
     */
    fun pushFile(
        peerControlEndpoint: InetSocketAddress,
        localFile: File,
        remoteName: String,
        onProgress: ((Long) -> Unit)? = null,
    ): Boolean {
        val transferId = UUID.randomUUID()
        val token = bulkTokenVault.issueBulk(transferId, localFile.path, localFile.length(), expectedStreams = 1)
        recordServedTransfer(transferId, localFile)
        pushBytesServed[transferId] = java.util.concurrent.atomic.AtomicLong(0)
        if (onProgress != null) pushProgressSinks[transferId] = onProgress
        try {
            val accepted = offerPush(peerControlEndpoint, transferId, token.value, localFile, remoteName)
            if (!accepted) return false

            val target = localFile.length()
            val deadlineMs = System.currentTimeMillis() + PUSH_TIMEOUT.inWholeMilliseconds
            val servedCounter = pushBytesServed.getValue(transferId)
            while (System.currentTimeMillis() < deadlineMs) {
                if (servedCounter.get() >= target) return true
                Thread.sleep(100)
            }
            return servedCounter.get() >= target
        } finally {
            pushProgressSinks.remove(transferId)
            pushBytesServed.remove(transferId)
            completeServedTransfer(transferId)
        }
    }

    /** Sends `push.offer` and waits for `push.ok`. Any failure to connect, a closed connection
     * before a reply arrives, or an `error` reply are all treated the same: the peer never
     * accepted, so the caller must release the token it issued for nothing. */
    private fun offerPush(
        peerControlEndpoint: InetSocketAddress,
        transferId: UUID,
        token: UUID,
        localFile: File,
        remoteName: String,
    ): Boolean = try {
        ControlClient.connect(peerControlEndpoint, identity, peerStore, networkBinder).use { conn ->
            val myBulkEndpoint = run {
                val port = bulkServer?.boundPort ?: bulkPort
                InetSocketAddress(requireNotNull(networkInfo.current()) { "no active network" }.localAddress, port)
            }
            conn.send(
                ControlMessage(
                    type = SessionMessageTypes.PUSH_OFFER,
                    id = UUID.randomUUID().toString(),
                    payload = JsonObject(
                        mapOf(
                            "path" to JsonPrimitive(remoteName),
                            "transferId" to JsonPrimitive(transferId.toString()),
                            "token" to JsonPrimitive(token.toString()),
                            "size" to JsonPrimitive(localFile.length()),
                            "host" to JsonPrimitive(myBulkEndpoint.address.hostAddress),
                            "port" to JsonPrimitive(myBulkEndpoint.port),
                        ),
                    ),
                ),
            )
            val reply = conn.receive() ?: return@use false
            reply.type == SessionMessageTypes.PUSH_OK
        }
    } catch (e: Exception) {
        false
    }

    /** Records a transfer this device is serving out, and takes the opportunity to expire any
     * that are long over. Internal so a test can exercise the bookkeeping without a wire. */
    internal fun recordServedTransfer(transferId: UUID, file: File) {
        sourceFileForTransfer[transferId] = file
        purgeExpiredTransfers()
    }

    /** Drops a served transfer's authorization and its source-file entry together. */
    internal fun completeServedTransfer(transferId: UUID) {
        bulkTokenVault.revoke(transferId)
        sourceFileForTransfer.remove(transferId)
    }

    /**
     * Drops the state of every transfer whose bulk token has expired. The sending side never
     * learns that a pull finished (the bulk protocol has no completion message), so without
     * this [sourceFileForTransfer] grows by one entry per `pull.request` ever served and never
     * shrinks. The token's own 5-minute TTL is the authoritative "this transfer is over"
     * signal, so both are expired together.
     */
    internal fun purgeExpiredTransfers() {
        bulkTokenVault.purgeExpired().forEach { sourceFileForTransfer.remove(it) }
    }

    /** Number of served-transfer entries currently retained. Internal, for a test that this
     * bookkeeping is actually bounded rather than merely intended to be. */
    internal val servedTransferCount: Int get() = sourceFileForTransfer.size

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

    // --- pairing (pairing.md §1-§3) ---

    /** One in-progress [openPairingWindow] call, waiting for a stranger to connect. */
    private class PendingPairing(onConfirm: (String) -> Boolean) {
        val outcome = ArrayBlockingQueue<Boolean>(1)

        /**
         * True once the user has actually *answered* — not merely been shown a code.
         *
         * Displaying the code is not an answer. The peer can hang up while the user is still
         * looking at it (the initiator has its own timeout, and a probe or a stranger can drop
         * at any moment). Treating "was asked" as the gate meant that disconnect surfaced as
         * "Pairing declined." with Confirm greyed out, attributing to the user a decision they
         * never made and killing a window that was still perfectly valid.
         *
         * Only a real answer, a success, or the window's own timeout may end the window.
         */
        @Volatile
        var userAnswered: Boolean = false
            private set

        val confirmCode: (String) -> Boolean = { code ->
            val accepted = onConfirm(code)
            userAnswered = true
            accepted
        }
    }

    @Volatile
    private var pendingPairing: PendingPairing? = null

    /** True while [openPairingWindow] is waiting. Exists so a test (or a UI) can observe that
     * the window really is open without reaching into private state. */
    val isPairingWindowOpen: Boolean get() = pairingWindow.isOpen

    /**
     * Opens the pairing window and waits (up to [timeout]) for a peer to connect and complete
     * the exchange, returning the peer that got paired or null if nobody did.
     *
     * [confirmCode] is called once with the derived 6-digit code (pairing.md §5) - the caller
     * shows it to the user and returns true only if the user says it matches what the other
     * device is showing. The code is always derived from the fingerprint the TLS handshake
     * actually proved ([ControlConnection.verifiedFingerprint]), never from anything claimed
     * in a `pair.offer` payload.
     *
     * This is the responder half. The initiating half is [pairWith].
     */
    suspend fun openPairingWindow(
        timeout: Duration = 120.seconds,
        confirmCode: (code: String) -> Boolean,
    ): PairedPeer? = withContext(Dispatchers.IO) { awaitPairing(timeout, confirmCode) }

    /** Blocking form of [openPairingWindow], for callers that already own a thread. */
    fun awaitPairing(timeout: Duration = 120.seconds, confirmCode: (code: String) -> Boolean): PairedPeer? {
        val pending = PendingPairing(confirmCode)
        synchronized(this) {
            check(pendingPairing == null) { "a pairing window is already open" }
            pendingPairing = pending
        }
        pairingWindow.open()
        try {
            val paired = pending.outcome.poll(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            return if (paired == true) peerStore.peer else null
        } finally {
            // Closed on success, decline, and timeout alike: an unpaired stranger must never
            // find a window still standing open after the user's attempt ended.
            pairingWindow.close()
            synchronized(this) { pendingPairing = null }
        }
    }

    /**
     * Initiates pairing against a peer whose window is already open, at [endpoint]. Connects
     * over TLS *without* a pin - there is nothing to pin yet, which is the entire point of
     * pairing - and derives the code from the certificate that handshake presented.
     */
    suspend fun pairWith(
        endpoint: InetSocketAddress,
        confirmCode: (code: String) -> Boolean,
    ): PairedPeer? = withContext(Dispatchers.IO) { initiatePairing(endpoint, confirmCode) }

    /** Blocking form of [pairWith]. */
    fun initiatePairing(endpoint: InetSocketAddress, confirmCode: (code: String) -> Boolean): PairedPeer? {
        LanGuard.ensureLocal(
            requireNotNull(endpoint.address) { "pairing endpoint $endpoint has no resolved address" },
        )
        val socket = com.slipstream.core.control.PinnedTls.connect(endpoint, identity, networkBinder) { true }
        val certificate = socket.session.peerCertificates.firstOrNull() as? java.security.cert.X509Certificate
        val fingerprint = certificate?.let(com.slipstream.core.identity.Fingerprint::of)
        ControlConnection(socket, fingerprint, certificate).use { conn ->
            if (certificate == null || fingerprint == null) return null
            val paired = PairingCoordinator(
                identity = identity,
                peerStore = peerStore,
                connection = conn,
                remoteVerifiedFingerprint = fingerprint,
                remoteCertificate = certificate,
                isInitiator = true,
                decide = confirmCode,
            ).run()
            return if (paired) peerStore.peer else null
        }
    }

    private fun servePairingConnection(conn: ControlConnection) {
        val pending = pendingPairing
        val fingerprint = conn.verifiedFingerprint
        val certificate = conn.peerCertificate
        if (pending == null || fingerprint == null || certificate == null) {
            // Nobody is waiting (or the handshake produced no identity to derive a code from):
            // close rather than leave the socket dangling.
            conn.close()
            return
        }
        thread(isDaemon = true) {
            val paired = try {
                PairingCoordinator(
                    identity = identity,
                    peerStore = peerStore,
                    connection = conn,
                    remoteVerifiedFingerprint = fingerprint,
                    remoteCertificate = certificate,
                    isInitiator = false,
                    decide = pending.confirmCode,
                ).run()
            } catch (e: Exception) {
                false
            } finally {
                conn.close()
            }
            // A connection that ended before the user was ever shown a code is not an
            // answer, and must not collapse the window. Pairing discovery probes connect
            // over unpinned TLS and hang up immediately - that is how a peer is found at
            // all on a network that drops multicast - and a stranger who drops mid-exchange
            // must not be able to cancel the user's attempt either. The window still closes
            // on a real outcome, on user cancel, and on its own 120-second timeout.
            if (paired || pending.userAnswered) pending.outcome.offer(paired)
        }
    }

    // --- discovery responder ---

    /** Balances [startResponder] against [stopResponder]: [closeServers] is called on paths
     * where the responder was never started (e.g. a [startServers] that threw early), and an
     * unmatched stop would tear down a listener nobody started. */
    private var responderStarted = false

    private fun startResponder() {
        val responder = discoveryResponder ?: return
        if (responderStarted) return
        try {
            runBlocking { responder.startResponder() }
            responderStarted = true
        } catch (e: Exception) {
            // Multicast can be unavailable (restricted, no lock, no interface). Discovery's
            // other strategies still work, so this must never stop the servers coming up.
        }
    }

    private fun stopResponder() {
        val responder = discoveryResponder ?: return
        if (!responderStarted) return
        responderStarted = false
        try {
            runBlocking { responder.stopResponder() }
        } catch (e: Exception) {
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

            // Which network every socket is about to be pinned to (spec §11 layer 3). Binding
            // to the wrong one — cellular, on a phone that has both — makes every LAN address
            // unreachable while the device looks perfectly online, and there was no way to see
            // which one had been chosen.
            SlipstreamLog.i("network", "binding sockets to ${network ?: "(none)"}")
            networkBinder.network = network

            // Only a *successful* apply is recorded (below). If startServers() throws, or the
            // network's interface is not ready yet (see [awaitNetworkReady] - routine on
            // boot, where onAvailable can beat the interface coming up), this event must leave
            // the dedup state untouched, so the next event for the SAME network - typically the
            // onCapabilitiesChanged that arrives once the interface *is* ready - is still
            // processed instead of being silently dropped as a duplicate.
            var applied = false

            // A failure here (e.g. networkInfo.current() racing to null, or the previous
            // listen socket not yet fully released) must never propagate: this runs on a
            // framework NetworkCallback thread, where an escaping exception takes the whole
            // foreground service down on a routine Wi-Fi transition.
            try {
                teardown()
                if (network == null) {
                    // "No network" is a complete, successfully applied state: there is nothing
                    // to start, and the torn-down peer is exactly right.
                    applied = true
                } else if (awaitNetworkReady(network)) {
                    startServers()
                    applied = true
                }
            } catch (e: Exception) {
                // Leave the peer in the cleanly-torn-down state; the next network event (or an
                // explicit start()) restarts it.
                closeServers()
            }

            // Re-discovery and resume are the *consequences* of a successful apply, so they
            // only run when one happened. Running them after a failed apply (or after a
            // network that isn't ready yet) means discovering and resuming over servers that
            // are not up and a binder pointing at a network with no usable interface - pure
            // wasted work, and a resume that is guaranteed to fail and burn its retry.
            if (!applied) return

            hasAppliedNetwork = true
            appliedNetwork = network

            thread(isDaemon = true) {
                val result = try {
                    runBlocking { discover() }
                } catch (e: Exception) {
                    null
                }
                onRediscover(result)
            }
            resumeActivePulls()
        }
    }

    /**
     * The readiness gate for a network change: has the network just reported actually produced
     * an interface we can bind to yet?
     *
     * Asking only "does [NetworkInfo.current] return something?" is not enough, and getting that
     * wrong silently reproduces the bug the live-interface selection exists to fix. `current()`
     * enumerates every live interface, so it happily returns the address of the interface we are
     * *leaving* while the newly reported network's own interface is still coming up. Binding
     * that address counts as a successful apply, which records the dedup state - so the very
     * next event for the same network, the one that would have found the right address, is
     * dropped as a duplicate and every server stays listening where no peer can reach it.
     *
     * So readiness is decided against the *specific* [network]:
     *  - [NetworkInfo.attestBelongsTo] says `true`: the address genuinely belongs to this
     *    network's link. Ready immediately.
     *  - It says `false`: the address is attested to some other link. Not ready - keep looking.
     *  - It cannot tell (`null`): fall back to enumeration stability - the same address must be
     *    reported twice, [readinessPollInterval] apart. This is the load-bearing case for a
     *    device hosting a hotspot, whose AP interface `ConnectivityManager` never surfaces and
     *    therefore can never attest; an interface mid-transition is what stability rejects.
     *
     * If neither condition is met inside [readinessWindow] this returns false, which leaves the
     * dedup state untouched, so the next event for the same network retries rather than the peer
     * binding optimistically. It never widens the dedup window: a repeat of an *applied* network
     * is still ignored, and a genuinely different network still rebinds.
     */
    private fun awaitNetworkReady(network: android.net.Network): Boolean {
        val deadline = System.nanoTime() + readinessWindow.inWholeNanoseconds
        var previousKey: String? = null

        while (true) {
            val local = networkInfo.current()
            if (local != null) {
                when (networkInfo.attestBelongsTo(network, local)) {
                    true -> return true
                    null -> if (previousKey == local.key) return true
                    false -> Unit
                }
            }
            previousKey = local?.key

            if (System.nanoTime() >= deadline) return false
            try {
                Thread.sleep(readinessPollInterval.inWholeMilliseconds)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
    }

    /** Closes every listening server and forgets it. Safe to call twice, and safe to call on a
     * partially-started set (which is exactly what a failed [startServers] leaves behind). */
    private fun closeServers() {
        stopResponder()
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
                    // A resume that finished the file owns the PartFile's shutdown - the
                    // original pullFile() call that created it is long gone (its connection
                    // is what dropped), so nothing else will ever close it.
                    if (pull.part.complete()) {
                        activePulls.remove(transferId)
                        finishTransfer(transferId, pull.part)
                    }
                }
            }
        }
    }

    /**
     * Runs [body] holding the [resumeInFlight] claim for [transferId] - the same claim
     * [resumeActivePulls] checks - so exactly one [TransferEngine.pull] loop can be writing a
     * given [PartFile] at a time, whether it was started by [pullFile] (the primary writer) or
     * by a resume. Internal so a test can hold a claim without running a real transfer.
     */
    internal fun <T> withPullClaim(transferId: UUID, body: () -> T): T {
        val claimed = resumeInFlight.putIfAbsent(transferId, Unit) == null
        try {
            return body()
        } finally {
            if (claimed) resumeInFlight.remove(transferId)
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

            return Negotiated(BulkSession(bulkEndpointFrom(host, port), transferId, token), size)
        }
    }

    /**
     * Pulls [remotePath] from the peer at [peerControlEndpoint] into [destination], driving
     * [TransferEngine]'s retry loop with a `session` lambda that re-negotiates a fresh bulk
     * token on every attempt (Task 10's seam, wired for real here). Tracks the transfer in
     * [activePulls] for the duration so a network change mid-pull triggers a resume instead of
     * abandoning it.
     */
    fun pullFile(
        peerControlEndpoint: InetSocketAddress,
        remotePath: String,
        destination: File,
        streams: Int = 4,
        onProgress: ((Long) -> Unit)? = null,
    ): PartFile {
        val first = negotiatePull(peerControlEndpoint, remotePath, streams)
        val transferId = first.session.transferId
        val part = PartFile.openOrCreate(destination, transferId, first.size, DEFAULT_CHUNK_SIZE)
        // Claim the same in-flight slot resumeActivePulls() checks, *before* publishing into
        // activePulls: this is the primary writer for this PartFile, so a network change landing
        // mid-pull must dedup against it rather than spawning a second concurrent pull loop over
        // the same file. The claim is taken first so a resume can never observe the activePulls
        // entry without also observing the claim.
        withPullClaim(transferId) {
            activePulls[transferId] = ActivePull(part, streams, remotePath, peerControlEndpoint)
            try {
                transferEngine.pull(part, streams, onProgress) {
                    negotiatePull(peerControlEndpoint, remotePath, streams).session
                }
            } finally {
                activePulls.remove(transferId)
                // close() releases the file descriptor AND performs the final debounced sidecar
                // flush. Dropping the PartFile without it leaks the fd and loses the last few
                // chunks' completion bits, so a resumed transfer re-downloads them for nothing.
                finishTransfer(transferId, part)
            }
        }
        return part
    }

    /**
     * Ends this device's involvement in [transferId]: closes [part] (fd + final sidecar
     * flush) and drops the per-transfer state kept for a transfer this device *served*.
     *
     * [TokenVault.revoke] and the [sourceFileForTransfer] entry are cleared together, since
     * they are two halves of one authorization: leaving either behind means the map grows by
     * one entry per `pull.request` ever answered, for the life of the process.
     */
    private fun finishTransfer(transferId: UUID, part: PartFile?) {
        try { part?.close() } catch (_: Exception) {}
        completeServedTransfer(transferId)
    }

    override fun close() {
        // Same lock as onNetworkChanged, so close() cannot land halfway through a restart.
        networkChangeLock.withLock { teardown() }
    }

    companion object {
        /**
         * Turns the `host`/`port` of an untrusted `pull.ok` payload into a bulk endpoint, or
         * throws. Two rules apply before peer-supplied text may become a socket address:
         *
         *  - spec §11 layer 4 ("no outbound calls of any kind"): it must be an IP *literal*.
         *    `InetSocketAddress(String, Int)` and `InetAddress.getByName` both fall back to
         *    DNS, so a hostile `pull.ok` carrying `host: "attacker.example.com"` would make
         *    this device emit a DNS query off the LAN before anything else got a look at it.
         *  - spec §11 layer 2: the resulting address must be local, exactly as
         *    [com.slipstream.core.control.PinnedTls.connect] enforces for the control channel.
         *    Without it a paired-but-compromised peer could point the bulk connection at any
         *    routable address on the internet.
         *
         * Enforced here, at the parse site, rather than only at the socket: a host that fails
         * either rule must never reach [com.slipstream.core.transfer.BulkClient] at all.
         */
        internal fun bulkEndpointFrom(host: String, port: Int): InetSocketAddress {
            val address = IpLiteral.parse(host)
                ?: throw IllegalArgumentException("pull.ok host '$host' is not an IP literal")
            LanGuard.ensureLocal(address)
            require(port in 1..65535) { "pull.ok port $port out of range" }
            return InetSocketAddress(address, port)
        }
    }
}
