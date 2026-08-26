package com.slipstream.app.peer

import com.slipstream.core.SlipstreamPeer
import com.slipstream.core.discovery.DiscoveredPeer
import com.slipstream.core.discovery.DiscoveryCoordinator
import com.slipstream.core.discovery.DiscoveryStrategy
import com.slipstream.core.identity.DeviceIdentity
import com.slipstream.core.identity.PairedPeerStore
import com.slipstream.core.net.LocalNetwork
import com.slipstream.core.net.NetworkInfo
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds

private val LOOPBACK: InetAddress = InetAddress.getByName("127.0.0.1")

private class LoopbackNetworkInfo : NetworkInfo {
    override fun current() = LocalNetwork(LOOPBACK, null, 32, "if:lo|127.0.0.0/32")
}

/** A discovery strategy that always "finds" one fixed, already-known endpoint — standing in for
 * a real multicast/subnet-sweep strategy in a loopback test, where both peers' addresses are
 * already known ahead of time. */
private class FixedEndpointStrategy(private val endpoint: InetSocketAddress) : DiscoveryStrategy {
    override val name = "fixed-loopback"
    override suspend fun find(network: LocalNetwork): DiscoveredPeer? = DiscoveredPeer(endpoint)
}

/** Three ports that were free a moment ago, fixed rather than 0 ("any free port") so
 * [TwoPeers.breakControlConnection] can restart the remote's control listener on the very same
 * port [RealPeerController] already discovered — see that method's doc. */
private fun threeFreePorts(): Triple<Int, Int, Int> {
    val sockets = List(3) { ServerSocket(0, 1, LOOPBACK) }
    val ports = sockets.map { it.localPort }
    sockets.forEach { it.close() }
    return Triple(ports[0], ports[1], ports[2])
}

/**
 * Two real [SlipstreamPeer]s, already paired, wired up over loopback — the `:app`-level
 * equivalent of `:core`'s `SlipstreamPeerPairingTest.pair()`, reused rather than reinvented (per
 * the task addendum) so [RealPeerController] tests exercise the real wire protocol end to end.
 */
class TwoPeers private constructor(
    val local: SlipstreamPeer,
    @Volatile var remote: SlipstreamPeer,
    val localIdentity: DeviceIdentity,
    val localPeerStore: PairedPeerStore,
    val remotePeerStore: PairedPeerStore,
    val localClipboardSink: ForwardingClipboardSink,
    val remoteClipboardSink: ForwardingClipboardSink,
    val remoteRoot: File,
    /** A subdirectory under [remoteRoot] with one file in it, ready for a `list()` test. */
    val sharedDir: String,
    private val remoteIdentity: DeviceIdentity,
    private val networkInfo: LoopbackNetworkInfo,
    private val remotePorts: Triple<Int, Int, Int>,
) {

    /**
     * Neither of the addendum's two suggested implementations actually works, verified by
     * running both: [com.slipstream.core.SlipstreamPeer.close] only stops [remote]'s *listening*
     * sockets (`ControlServer.close()` closes the `ServerSocket`, not any connection it already
     * `accept()`-ed - that socket is owned by `SlipstreamPeer`'s private per-connection serving
     * thread, with no way to reach it from outside `:core`), so an already-open control
     * connection - and therefore a heartbeat pinging over it - keeps working right through a
     * `remote.close()` + restart. This method is kept for a scenario it DOES model correctly -
     * "the peer's whole app restarted, same address" - by tearing [remote] down and bringing up
     * a fresh [SlipstreamPeer] on the exact same fixed ports and (already-paired) peer store, so
     * [RealPeerController]'s re-discovery has something real to land on afterwards. It is
     * deliberately NOT what `RealPeerControllerTest`'s reconnect test uses to produce
     * [PeerConnectionState.Lost] in the first place - that test closes the *client-side* socket
     * `RealPeerController` itself opened (see `RealPeerController.debugCloseConnectionForTesting`),
     * since that is the only side either test or `:core` can actually reach.
     */
    fun restartRemoteOnSamePorts() {
        remote.close()
        remote = SlipstreamPeer(
            identity = remoteIdentity,
            peerStore = remotePeerStore,
            networkInfo = networkInfo,
            rootDirectory = remoteRoot,
            clipboardSink = remoteClipboardSink,
            discoveryCoordinatorFactory = { DiscoveryCoordinator(networkInfo, cache = null, strategies = emptyList()) },
            controlPort = remotePorts.first,
            bulkPort = remotePorts.second,
            mediaPort = remotePorts.third,
        )
        remote.start()
    }

    companion object {
        fun start(tempDir: File): TwoPeers {
            val localDir = File(tempDir, "local").apply { mkdirs() }
            val remoteDir = File(tempDir, "remote").apply { mkdirs() }
            val networkInfo = LoopbackNetworkInfo()
            val localIdentity = DeviceIdentity.createNew("Local")
            val remoteIdentity = DeviceIdentity.createNew("Remote")
            val localStore = PairedPeerStore(localDir)
            val remoteStore = PairedPeerStore(remoteDir)
            val localClipboard = ForwardingClipboardSink()
            val remoteClipboard = ForwardingClipboardSink()
            val remotePorts = threeFreePorts()

            val remotePeer = SlipstreamPeer(
                identity = remoteIdentity,
                peerStore = remoteStore,
                networkInfo = networkInfo,
                rootDirectory = remoteDir,
                clipboardSink = remoteClipboard,
                discoveryCoordinatorFactory = { DiscoveryCoordinator(networkInfo, cache = null, strategies = emptyList()) },
                controlPort = remotePorts.first,
                bulkPort = remotePorts.second,
                mediaPort = remotePorts.third,
            )
            remotePeer.start()

            val remoteEndpoint = requireNotNull(remotePeer.controlEndpoint)
            val localPeer = SlipstreamPeer(
                identity = localIdentity,
                peerStore = localStore,
                networkInfo = networkInfo,
                rootDirectory = localDir,
                clipboardSink = localClipboard,
                discoveryCoordinatorFactory = {
                    DiscoveryCoordinator(
                        networkInfo,
                        cache = null,
                        strategies = listOf(FixedEndpointStrategy(InetSocketAddress(LOOPBACK, remoteEndpoint.port))),
                    )
                },
                controlPort = 0,
                bulkPort = 0,
                mediaPort = 0,
            )
            localPeer.start()

            pair(localPeer, remotePeer)

            val sharedDirName = "shared"
            File(remoteDir, sharedDirName).apply { mkdirs() }
            File(remoteDir, "$sharedDirName/hello.txt").writeText("hello from the peer")

            return TwoPeers(
                local = localPeer,
                remote = remotePeer,
                localIdentity = localIdentity,
                localPeerStore = localStore,
                remotePeerStore = remoteStore,
                localClipboardSink = localClipboard,
                remoteClipboardSink = remoteClipboard,
                remoteRoot = remoteDir,
                sharedDir = sharedDirName,
                remoteIdentity = remoteIdentity,
                networkInfo = networkInfo,
                remotePorts = remotePorts,
            )
        }

        /** Same pairing dance as `SlipstreamPeerPairingTest.pair`. */
        private fun pair(a: SlipstreamPeer, b: SlipstreamPeer) {
            val bDone = CountDownLatch(1)
            thread(isDaemon = true) {
                b.awaitPairing(timeout = 20.seconds) { true }
                bDone.countDown()
            }
            val endpoint = requireNotNull(b.controlEndpoint)
            repeat(50) {
                if (b.isPairingWindowOpen) return@repeat
                Thread.sleep(20)
            }
            val result = a.initiatePairing(InetSocketAddress(LOOPBACK, endpoint.port)) { true }
            check(bDone.await(20, TimeUnit.SECONDS)) { "pairing must complete for the rig to be usable" }
            checkNotNull(result) { "pairing must succeed for the rig to be usable" }
        }
    }
}
