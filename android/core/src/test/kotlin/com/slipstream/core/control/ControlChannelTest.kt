package com.slipstream.core.control

import com.slipstream.core.identity.DeviceIdentity
import com.slipstream.core.identity.PairedPeer
import com.slipstream.core.identity.PairedPeerStore
import com.slipstream.core.net.LocalNetwork
import com.slipstream.core.net.NetworkInfo
import com.slipstream.core.net.NonLocalAddressException
import com.slipstream.core.pairing.PairingWindow
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.createTempDirectory
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private val LOOPBACK: InetAddress = InetAddress.getByName("127.0.0.1")

private class FixedNetworkInfo(private val address: InetAddress) : NetworkInfo {
    override fun current(): LocalNetwork = LocalNetwork(address, null, 32, "test-network")
}

private fun emptyPeerStore(): PairedPeerStore = PairedPeerStore(createTempDirectory().toFile())

private fun pairedPeerStore(trustedIdentity: DeviceIdentity): PairedPeerStore {
    val store = PairedPeerStore(createTempDirectory().toFile())
    store.store(
        PairedPeer(
            deviceId = trustedIdentity.deviceId,
            fingerprint = trustedIdentity.fingerprint,
            certificate = trustedIdentity.certificate,
        ),
    )
    return store
}

/** Starts a bare TLS server accepting the given identity's cert, for client-side pin tests. */
private fun startTlsServer(identity: DeviceIdentity): ControlServer {
    val server = ControlServer(identity, emptyPeerStore(), FixedNetworkInfo(LOOPBACK), port = 0).start()
    return server
}

class ControlChannelTest {

    // --- TLS pinning ---

    @Test
    fun `client refuses a server whose fingerprint is not pinned`() {
        val serverIdentity = DeviceIdentity.createNew("Server")
        val server = startTlsServer(serverIdentity)
        try {
            try {
                PinnedTls.connect(server.listenEndpoint, DeviceIdentity.createNew("Client")) { false }
                fail("expected connect to throw")
            } catch (e: Exception) {
                // expected: fingerprint predicate always rejects
            }
        } finally {
            server.close()
        }
    }

    @Test
    fun `client accepts a server whose fingerprint matches the pin`() {
        val serverIdentity = DeviceIdentity.createNew("Server")
        val server = startTlsServer(serverIdentity)
        try {
            val socket = PinnedTls.connect(server.listenEndpoint, DeviceIdentity.createNew("Client")) { fp ->
                fp == serverIdentity.fingerprint
            }
            assertTrue(socket.isConnected)
            socket.close()
        } finally {
            server.close()
        }
    }

    @Test
    fun `server drops a connection from an untrusted fingerprint before any message reaches app code`() {
        // Matches the C# guarantee: unpaired devices get nothing, and the connection is
        // dropped before a single message is read.
        val serverIdentity = DeviceIdentity.createNew("Server")
        val handled = AtomicBoolean(false)
        val server = ControlServer(serverIdentity, emptyPeerStore(), FixedNetworkInfo(LOOPBACK), port = 0).start()
        server.onPeerConnected = { handled.set(true) }

        try {
            // Stranger connects: TLS layer accepts any cert (no exception here), but the
            // server's post-handshake fingerprint check has no matching paired peer.
            val strangerIdentity = DeviceIdentity.createNew("Stranger")
            val socket = PinnedTls.connect(server.listenEndpoint, strangerIdentity) { true }

            // Prove the socket is torn down from the server's side: writing/reading eventually
            // observes EOF/reset, and no message the client sends is ever surfaced by the server.
            val conn = ControlConnection(socket)
            try {
                conn.send(ControlMessage(type = "hello", id = "1"))
            } catch (e: Exception) {
                // an immediate reset while writing is also acceptable evidence of the drop
            }

            Thread.sleep(200)
            assertFalse("server must never invoke onPeerConnected for an untrusted fingerprint", handled.get())
            conn.close()
        } finally {
            server.close()
        }
    }

    @Test
    fun `server accepts a connection from the paired peer's fingerprint`() {
        val serverIdentity = DeviceIdentity.createNew("Server")
        val clientIdentity = DeviceIdentity.createNew("Client")
        val store = pairedPeerStore(clientIdentity)

        val received = AtomicReference<ControlMessage?>(null)
        val latch = CountDownLatch(1)
        val server = ControlServer(serverIdentity, store, FixedNetworkInfo(LOOPBACK), port = 0).start()
        server.onPeerConnected = { conn ->
            received.set(conn.receive())
            latch.countDown()
        }

        try {
            val socket = PinnedTls.connect(server.listenEndpoint, clientIdentity) { true }
            val conn = ControlConnection(socket)
            conn.send(ControlMessage(type = "hello", id = "1"))

            assertTrue(latch.await(5, TimeUnit.SECONDS))
            assertEquals("hello", received.get()?.type)
            conn.close()
        } finally {
            server.close()
        }
    }

    @Test
    fun `onPeerConnected receives a ControlConnection whose verifiedFingerprint matches the connecting client's real certificate`() {
        val serverIdentity = DeviceIdentity.createNew("Server")
        val clientIdentity = DeviceIdentity.createNew("Client")
        val store = pairedPeerStore(clientIdentity)

        val observedFingerprint = AtomicReference<String?>(null)
        val latch = CountDownLatch(1)
        val server = ControlServer(serverIdentity, store, FixedNetworkInfo(LOOPBACK), port = 0).start()
        server.onPeerConnected = { conn ->
            observedFingerprint.set(conn.verifiedFingerprint)
            latch.countDown()
        }

        try {
            val socket = PinnedTls.connect(server.listenEndpoint, clientIdentity) { true }
            val conn = ControlConnection(socket)

            assertTrue(latch.await(5, TimeUnit.SECONDS))
            // The value ControlServer hands to onPeerConnected must be derived from the TLS
            // handshake itself, not from the store or anything the client claims - it must equal
            // the connecting client's actual certificate fingerprint.
            assertEquals(clientIdentity.fingerprint, observedFingerprint.get())
            conn.close()
        } finally {
            server.close()
        }
    }

    @Test
    fun `onPairingConnected receives a ControlConnection whose verifiedFingerprint matches the connecting stranger's real certificate`() {
        val serverIdentity = DeviceIdentity.createNew("Server")
        val strangerIdentity = DeviceIdentity.createNew("Stranger")

        val observedFingerprint = AtomicReference<String?>(null)
        val latch = CountDownLatch(1)
        val server = ControlServer(
            serverIdentity,
            emptyPeerStore(),
            FixedNetworkInfo(LOOPBACK),
            port = 0,
            pairingWindow = PairingWindow().apply { open() },
        ).start()
        server.onPairingConnected = { conn ->
            observedFingerprint.set(conn.verifiedFingerprint)
            latch.countDown()
        }

        try {
            val socket = PinnedTls.connect(server.listenEndpoint, strangerIdentity) { true }
            val conn = ControlConnection(socket)

            assertTrue(latch.await(5, TimeUnit.SECONDS))
            assertEquals(strangerIdentity.fingerprint, observedFingerprint.get())
            conn.close()
        } finally {
            server.close()
        }
    }

    // --- Read-cap teardown (protocol.md §5) ---

    @Test
    fun `receive tears down the socket when a line exceeds the read cap, regardless of caller`() {
        // Plain (non-TLS) loopback socket pair: the teardown guarantee lives in
        // ControlConnection.receive() itself, not in any TLS or ControlServer scaffolding.
        val serverSocket = ServerSocket(0, 0, LOOPBACK)
        try {
            val clientSocket = Socket(LOOPBACK, serverSocket.localPort)
            val acceptedSocket = serverSocket.accept()

            try {
                val overLong = "x".repeat(JsonLineCodec.MAX_LINE_BYTES + 10) + "\n"
                // Write on a separate thread: the payload exceeds typical OS socket buffers, so a
                // synchronous write here would block until something reads — but nothing reads
                // until conn.receive() below, which would deadlock the test.
                val writer = Thread {
                    clientSocket.getOutputStream().write(overLong.toByteArray(Charsets.UTF_8))
                    clientSocket.getOutputStream().flush()
                }
                writer.isDaemon = true
                writer.start()

                // Call receive() directly - no ControlServer/try-catch in the call stack to
                // accidentally supply the teardown.
                val conn = ControlConnection(acceptedSocket)
                try {
                    conn.receive()
                    fail("expected LineTooLargeException")
                } catch (e: LineTooLargeException) {
                    // expected
                }

                assertTrue("socket must be closed by receive() itself, not by the caller", conn.isClosed)
                assertTrue("underlying socket must be closed", acceptedSocket.isClosed)
                writer.join(5000)
            } finally {
                clientSocket.close()
                if (!acceptedSocket.isClosed) acceptedSocket.close()
            }
        } finally {
            serverSocket.close()
        }
    }

    // --- LanGuard gating ---

    @Test(expected = NonLocalAddressException::class)
    fun `client connect refuses a non-local target address`() {
        val nonLocal = InetSocketAddress(InetAddress.getByName("8.8.8.8"), SlipstreamPortForTest)
        PinnedTls.connect(nonLocal, DeviceIdentity.createNew("Client")) { true }
    }

    @Test
    fun `server binds to the specific local interface, not the wildcard address`() {
        val identity = DeviceIdentity.createNew("Server")
        val server = ControlServer(identity, emptyPeerStore(), FixedNetworkInfo(LOOPBACK), port = 0).start()
        try {
            assertEquals(LOOPBACK, server.listenEndpoint.address)
            assertFalse(server.listenEndpoint.address.isAnyLocalAddress)
        } finally {
            server.close()
        }
    }

    @Test
    fun `end to end round trip carries id on request and echoes it on the response`() {
        val serverIdentity = DeviceIdentity.createNew("Server")
        val clientIdentity = DeviceIdentity.createNew("Client")
        val store = pairedPeerStore(clientIdentity)

        val latch = CountDownLatch(1)
        val server = ControlServer(serverIdentity, store, FixedNetworkInfo(LOOPBACK), port = 0).start()
        server.onPeerConnected = { conn ->
            val msg = conn.receive()
            if (msg != null) {
                conn.send(ControlMessage(type = "${msg.type}.ok", id = msg.id))
            }
            latch.countDown()
        }

        try {
            val socket = PinnedTls.connect(server.listenEndpoint, clientIdentity) { true }
            val conn = ControlConnection(socket)
            conn.send(
                ControlMessage(
                    type = "hello",
                    id = "42",
                    payload = JsonObject(mapOf("deviceId" to JsonPrimitive(clientIdentity.deviceId))),
                ),
            )

            val response = conn.receive()
            assertEquals("hello.ok", response?.type)
            assertEquals("42", response?.id)
            assertTrue(latch.await(5, TimeUnit.SECONDS))
            conn.close()
        } finally {
            server.close()
        }
    }
}

// A plausible local port number; only used to build an address that LanGuard will reject
// before any socket operation is attempted.
private const val SlipstreamPortForTest = 53321
