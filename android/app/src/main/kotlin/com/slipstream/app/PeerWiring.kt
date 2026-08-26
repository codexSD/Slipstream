package com.slipstream.app

import com.slipstream.core.SlipstreamPeer
import com.slipstream.core.control.ClipboardSink
import com.slipstream.core.control.PinnedTls
import com.slipstream.core.discovery.CachedEndpointStrategy
import com.slipstream.core.discovery.DiscoveredPeer
import com.slipstream.core.discovery.DiscoveryCoordinator
import com.slipstream.core.discovery.EndpointCache
import com.slipstream.core.discovery.GatewayProbeStrategy
import com.slipstream.core.discovery.MulticastLockHandle
import com.slipstream.core.discovery.MulticastStrategy
import com.slipstream.core.discovery.MulticastTransport
import com.slipstream.core.discovery.NoopMulticastLock
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
    internal val identity: DeviceIdentity,
    internal val peerStore: PairedPeerStore,
    private val networkInfo: NetworkInfo,
    private val endpointCache: EndpointCache,
    private val rootDirectory: File,
    private val clipboardSink: ClipboardSink,
    /** Fired for an inbound `play` carrying a `path` field - see
     * [com.slipstream.core.control.SlipstreamSession]'s doc on the two `play` callbacks. Defaults
     * to a no-op so every existing caller/test that never heard of push-to-play keeps compiling
     * unchanged, same pattern as [clipboardSink]'s own default elsewhere in this file. */
    private val onPlayRequested: (File) -> Unit = {},
    /** Fired for an inbound `play` carrying a `url` field - the shape real push-to-play
     * (design.md §8) actually uses. See [onPlayRequested]'s doc for why both exist. */
    private val onPlayUrlRequested: (url: String, mime: String?) -> Unit = { _, _ -> },
    /** The one binder instance shared by the peer and by everything discovery constructs. */
    val networkBinder: MutableNetworkBinder = MutableNetworkBinder(),
    /** Seam over [PinnedTls.connect], so a test can observe which binder the probe passes it
     * without needing a real TLS peer on the other end. Production default is the real thing. */
    internal val connect: (InetSocketAddress, DeviceIdentity, NetworkBinder, (String) -> Boolean) -> Closeable =
        { endpoint, id, binder, isPinned -> PinnedTls.connect(endpoint, id, binder, isPinned) },
    /** Seam over [UdpMulticastTransport]'s construction, for the same reason. */
    internal val multicastTransport: (NetworkBinder) -> MulticastTransport =
        { binder -> UdpMulticastTransport(binder = binder) },
    /**
     * The real `WifiManager.MulticastLock`. Defaulting this to [NoopMulticastLock] is what
     * previously made S3 silently non-functional on shipped hardware: without a held lock most
     * Wi-Fi drivers never deliver multicast to the app layer at all, so the manifest's
     * `CHANGE_WIFI_MULTICAST_STATE` permission bought nothing. [SlipstreamApplication] supplies
     * the real one; the default exists only for tests with no `WifiManager`.
     */
    internal val multicastLock: MulticastLockHandle = NoopMulticastLock,
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

    /**
     * The single [MulticastStrategy] instance, shared between every [DiscoveryCoordinator]
     * this wiring builds AND the peer's always-on responder. One instance is essential, not
     * incidental: the strategy refcounts one socket bound to the fixed discovery port across
     * both concerns, and two instances would be two sockets contending for the same port and
     * stealing each other's datagrams.
     */
    val multicastStrategy: MulticastStrategy by lazy {
        MulticastStrategy(
            identity,
            peerStore,
            probe(),
            transportFactory = multicastTransportFactory(),
            multicastLock = multicastLock,
        )
    }

    fun discoveryCoordinator(): DiscoveryCoordinator {
        val probe = probe()
        return DiscoveryCoordinator(
            networkInfo = networkInfo,
            cache = endpointCache,
            strategies = listOf(
                CachedEndpointStrategy(endpointCache, probe),
                GatewayProbeStrategy(probe),
                multicastStrategy,
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
        // Spec §5: the phone must answer a PC's query while it is merely running, not only
        // while it is itself discovering.
        discoveryResponder = multicastStrategy,
        onPlayRequested = onPlayRequested,
        onPlayUrlRequested = onPlayUrlRequested,
    )
}
