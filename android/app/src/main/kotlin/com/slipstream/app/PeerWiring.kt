package com.slipstream.app

import com.slipstream.core.SlipstreamPeer
import com.slipstream.core.control.ClipboardSink
import com.slipstream.core.control.PinnedTls
import com.slipstream.core.discovery.CachedEndpointStrategy
import com.slipstream.core.discovery.DiscoveredPeer
import com.slipstream.core.discovery.DiscoveryCoordinator
import com.slipstream.core.discovery.EndpointCache
import com.slipstream.core.discovery.GatewayProbeStrategy
import com.slipstream.core.discovery.MulticastStrategy
import com.slipstream.core.discovery.MulticastTransport
import com.slipstream.core.discovery.PeerProbe
import com.slipstream.core.discovery.SubnetSweepStrategy
import com.slipstream.core.discovery.UdpMulticastTransport
import com.slipstream.core.identity.DeviceIdentity
import com.slipstream.core.identity.PairedPeerStore
import com.slipstream.core.net.MutableNetworkBinder
import com.slipstream.core.net.NetworkBinder
import com.slipstream.core.net.NetworkInfo
import java.io.Closeable
import java.io.File
import java.net.InetSocketAddress

/**
 * Assembles the production [SlipstreamPeer] from real collaborators. Extracted out of
 * [SlipstreamApplication] so the wiring itself is testable without an [android.app.Application]
 * — in particular so there is a test proving the *live* [networkBinder] reaches every socket
 * construction site (spec §11 layer 3), not just that the parameter exists.
 *
 * The socket-construction sites and how each one receives [networkBinder]:
 *  - discovery multicast UDP — [multicastTransport], passed to [MulticastStrategy]'s
 *    `transportFactory` (the default `{ UdpMulticastTransport() }` would be unbound).
 *  - discovery outbound probes (all four strategies go through one [PeerProbe]) — [probe],
 *    which passes it into [PinnedTls.connect].
 *  - control client (hello/handshake, pull negotiation), bulk client — inside [SlipstreamPeer],
 *    which is handed the same [networkBinder] instance here.
 *  - control/bulk/media *listening* sockets are not `Network.bindSocket`-able; they are scoped
 *    by binding to the active network's own local address instead (see [SlipstreamPeer]).
 */
internal class PeerWiring(
    private val identity: DeviceIdentity,
    private val peerStore: PairedPeerStore,
    private val networkInfo: NetworkInfo,
    private val endpointCache: EndpointCache,
    private val rootDirectory: File,
    private val clipboardSink: ClipboardSink,
    /** The one binder instance shared by the peer and by everything discovery constructs. */
    val networkBinder: MutableNetworkBinder = MutableNetworkBinder(),
    /** Seam over [PinnedTls.connect], so a test can observe which binder the probe passes it
     * without needing a real TLS peer on the other end. Production default is the real thing. */
    internal val connect: (InetSocketAddress, DeviceIdentity, NetworkBinder, (String) -> Boolean) -> Closeable =
        { endpoint, id, binder, isPinned -> PinnedTls.connect(endpoint, id, binder, isPinned) },
    /** Seam over [UdpMulticastTransport]'s construction, for the same reason. */
    internal val multicastTransport: (NetworkBinder) -> MulticastTransport =
        { binder -> UdpMulticastTransport(binder = binder) },
) {

    /** Exactly what [discoveryCoordinator] hands [MulticastStrategy], named so a test can
     * invoke the production path and observe which binder the socket ends up with. */
    internal fun multicastTransportFactory(): () -> MulticastTransport =
        { multicastTransport(networkBinder) }

    /**
     * A real probe = a real pinned-TLS handshake attempt; anything short of that (e.g. a bare
     * TCP connect) can't tell a Slipstream peer from any other listener on that port.
     */
    fun probe(): PeerProbe = PeerProbe { endpoint ->
        try {
            connect(endpoint, identity, networkBinder) { fingerprint ->
                peerStore.peer?.fingerprint == fingerprint
            }.use {
                DiscoveredPeer(endpoint)
            }
        } catch (e: Exception) {
            null
        }
    }

    fun discoveryCoordinator(): DiscoveryCoordinator {
        val probe = probe()
        return DiscoveryCoordinator(
            networkInfo = networkInfo,
            cache = endpointCache,
            strategies = listOf(
                CachedEndpointStrategy(endpointCache, probe),
                GatewayProbeStrategy(probe),
                MulticastStrategy(
                    identity,
                    peerStore,
                    probe,
                    transportFactory = multicastTransportFactory(),
                ),
                SubnetSweepStrategy(probe),
            ),
        )
    }

    fun peer(): SlipstreamPeer = SlipstreamPeer(
        identity = identity,
        peerStore = peerStore,
        networkInfo = networkInfo,
        rootDirectory = rootDirectory,
        clipboardSink = clipboardSink,
        discoveryCoordinatorFactory = { discoveryCoordinator() },
        networkBinder = networkBinder,
    )
}
