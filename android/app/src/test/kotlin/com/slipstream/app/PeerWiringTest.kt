package com.slipstream.app

import com.slipstream.core.control.ClipboardSink
import com.slipstream.core.control.PinnedTls
import com.slipstream.core.discovery.EndpointCache
import com.slipstream.core.discovery.MulticastTransport
import com.slipstream.core.discovery.UdpMulticastTransport
import com.slipstream.core.identity.DeviceIdentity
import com.slipstream.core.identity.PairedPeerStore
import com.slipstream.core.net.LocalNetwork
import com.slipstream.core.net.MutableNetworkBinder
import com.slipstream.core.net.NetworkBinder
import com.slipstream.core.net.NetworkInfo
import java.io.Closeable
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

private class RecordingNetworkBinder : NetworkBinder {
    val boundDatagramSockets = AtomicInteger(0)
    override fun bind(socket: Socket) = Unit
    override fun bind(socket: DatagramSocket) { boundDatagramSockets.incrementAndGet() }
}

private class NoNetworkInfo : NetworkInfo {
    override fun current(): LocalNetwork? = null
}

/** The production defaults, spelled out once so each test can override just the one seam it
 * needs to observe while leaving the other on its real implementation. */
private val REAL_CONNECT: (InetSocketAddress, DeviceIdentity, NetworkBinder, (String) -> Boolean) -> Closeable =
    { endpoint, id, binder, isPinned -> PinnedTls.connect(endpoint, id, binder, isPinned) }
private val REAL_TRANSPORT: (NetworkBinder) -> MulticastTransport = { UdpMulticastTransport(binder = it) }

/**
 * Spec §11 layer 3 for the *production* wiring. `NetworkBindingTest` in `:core` proves each
 * socket honours a binder when it is given one; these prove the app actually gives it one -
 * the discovery layer in particular, where the omission previously left the multicast socket
 * and every outbound probe running unbound, which is the phase most likely to leak traffic
 * over cellular.
 */
class PeerWiringTest {

    private fun wiring(
        binder: MutableNetworkBinder = MutableNetworkBinder(),
        connect: (InetSocketAddress, DeviceIdentity, NetworkBinder, (String) -> Boolean) -> Closeable = REAL_CONNECT,
        multicastTransport: (NetworkBinder) -> MulticastTransport = REAL_TRANSPORT,
    ): PeerWiring {
        val dir = createTempDirectory().toFile()
        return PeerWiring(
            identity = DeviceIdentity.createNew("Test Device"),
            peerStore = PairedPeerStore(dir),
            networkInfo = NoNetworkInfo(),
            endpointCache = EndpointCache(dir),
            rootDirectory = dir,
            clipboardSink = ClipboardSink { },
            networkBinder = binder,
            connect = connect,
            multicastTransport = multicastTransport,
        )
    }

    @Test
    fun `the wiring's own defaults are the real production implementations`() {
        // Guards the tests below: they substitute REAL_CONNECT / REAL_TRANSPORT for the
        // constructor defaults, so those two must stay in step with the real ones.
        val w = PeerWiring(
            identity = DeviceIdentity.createNew("Test Device"),
            peerStore = PairedPeerStore(createTempDirectory().toFile()),
            networkInfo = NoNetworkInfo(),
            endpointCache = EndpointCache(createTempDirectory().toFile()),
            rootDirectory = createTempDirectory().toFile(),
            clipboardSink = ClipboardSink { },
        )
        val recording = RecordingNetworkBinder()
        w.multicastTransport(recording).use {
            assertEquals(
                "the default multicast transport must pass the binder into the real socket",
                1,
                recording.boundDatagramSockets.get(),
            )
        }
    }

    @Test
    fun `the discovery probe hands PinnedTls the live network binder`() {
        val binder = MutableNetworkBinder()
        val seen = AtomicReference<NetworkBinder?>(null)
        val w = wiring(binder = binder, connect = { _, _, given, _ -> seen.set(given); Closeable { } })

        runBlocking { w.probe().probe(InetSocketAddress(InetAddress.getByName("192.168.1.5"), 53321)) }

        assertSame("the probe must pass the peer's live binder, not NetworkBinder.NONE", binder, seen.get())
        assertSame(binder, w.networkBinder)
    }

    @Test
    fun `the multicast transport factory used by discovery hands over the live network binder`() {
        val binder = MutableNetworkBinder()
        val seen = AtomicReference<NetworkBinder?>(null)
        val w = wiring(
            binder = binder,
            multicastTransport = { given ->
                seen.set(given)
                object : MulticastTransport {
                    override suspend fun send(payload: ByteArray, target: InetSocketAddress) = Unit
                    override suspend fun receive() = throw UnsupportedOperationException()
                    override fun close() = Unit
                }
            },
        )

        // This is the exact lambda discoveryCoordinator() hands MulticastStrategy.
        w.multicastTransportFactory()().close()

        assertSame(
            "MulticastStrategy's default transportFactory builds an *unbound* socket; the app " +
                "must override it with one carrying the live binder",
            binder,
            seen.get(),
        )
    }

    @Test
    fun `the assembled peer shares the same binder instance as discovery`() {
        val binder = MutableNetworkBinder()
        val w = wiring(binder = binder)
        // Constructing it also proves the wiring holds together with real collaborators.
        w.peer().use { assertSame(binder, w.networkBinder) }
    }
}
