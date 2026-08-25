package com.slipstream.core.control

import com.slipstream.core.identity.DeviceIdentity
import com.slipstream.core.identity.PairedPeer
import com.slipstream.core.identity.PairedPeerStore
import com.slipstream.core.net.LocalNetwork
import com.slipstream.core.net.NetworkInfo
import com.slipstream.core.pairing.PairingWindow
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private val LOOPBACK: InetAddress = InetAddress.getByName("127.0.0.1")

private class LoopbackNetworkInfo : NetworkInfo {
    override fun current() = LocalNetwork(LOOPBACK, null, 32, "test-network")
}

/**
 * Two seams that only show up when [ControlServer] is looked at as a lifecycle rather than a
 * request handler: what happens to a connection with no handler to give it to, and what
 * happens to one that arrives before the owner has finished wiring the handlers up.
 */
class ControlServerLifecycleTest {

    private fun emptyStore() = PairedPeerStore(createTempDirectory().toFile())

    @Test
    fun `a connection with no peer handler assigned is closed, not leaked`() {
        val serverIdentity = DeviceIdentity.createNew("Server")
        val clientIdentity = DeviceIdentity.createNew("Client")
        val store = emptyStore()
        store.store(PairedPeer(clientIdentity.deviceId, clientIdentity.fingerprint, clientIdentity.certificate))

        // onPeerConnected deliberately left null: the previous code returned without closing,
        // stranding the socket (and its fd) for the process lifetime.
        val server = ControlServer(serverIdentity, store, LoopbackNetworkInfo(), port = 0).start()
        try {
            val socket = PinnedTls.connect(server.listenEndpoint, clientIdentity) { true }
            socket.soTimeout = 5000
            assertEquals(
                "the server must close a connection it has no handler for",
                -1,
                socket.inputStream.read(),
            )
            socket.close()
        } finally {
            server.close()
        }
    }

    @Test
    fun `an in-window connection with no pairing handler assigned is closed, not leaked`() {
        val serverIdentity = DeviceIdentity.createNew("Server")
        val window = PairingWindow().apply { open() }
        // This is exactly production's shape before the fix: a window can be open while
        // onPairingConnected is null, and every stranger's socket leaked.
        val server = ControlServer(serverIdentity, emptyStore(), LoopbackNetworkInfo(), port = 0, window).start()
        try {
            val socket = PinnedTls.connect(server.listenEndpoint, DeviceIdentity.createNew("Stranger")) { true }
            socket.soTimeout = 5000
            assertEquals(-1, socket.inputStream.read())
            socket.close()
        } finally {
            server.close()
        }
    }

    @Test
    fun `no connection is accepted before start is called`() {
        val serverIdentity = DeviceIdentity.createNew("Server")
        val clientIdentity = DeviceIdentity.createNew("Client")
        val store = emptyStore()
        store.store(PairedPeer(clientIdentity.deviceId, clientIdentity.fingerprint, clientIdentity.certificate))

        val server = ControlServer(serverIdentity, store, LoopbackNetworkInfo(), port = 0)
        val handled = CountDownLatch(1)
        try {
            // The accept loop used to start in a property initializer, i.e. before the owner
            // could assign a handler. A connection landing in that window was dropped silently
            // AND leaked; now it simply waits in the backlog until start() runs. The client
            // runs on its own thread because its TLS handshake cannot complete until the
            // server accepts.
            val client = kotlin.concurrent.thread(isDaemon = true) {
                try {
                    PinnedTls.connect(server.listenEndpoint, clientIdentity) { true }.close()
                } catch (e: Exception) {
                    // The server closing first is fine; the assertion below is what matters.
                }
            }

            assertFalse("nothing may be handled before start()", handled.await(500, TimeUnit.MILLISECONDS))

            server.onPeerConnected = { conn -> handled.countDown(); conn.close() }
            server.start()

            assertTrue(
                "the connection queued in the backlog must be handled once start() runs",
                handled.await(10, TimeUnit.SECONDS),
            )
            client.join(5000)
        } finally {
            server.close()
        }
    }

    @Test
    fun `start is idempotent`() {
        val server = ControlServer(DeviceIdentity.createNew("Server"), emptyStore(), LoopbackNetworkInfo(), port = 0)
        try {
            assertEquals(server, server.start())
            assertEquals(server, server.start())
        } finally {
            server.close()
        }
    }
}
