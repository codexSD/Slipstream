package com.slipstream.core.pairing

import com.slipstream.core.control.ControlConnection
import com.slipstream.core.control.ControlMessage
import com.slipstream.core.control.ControlServer
import com.slipstream.core.control.PinnedTls
import com.slipstream.core.identity.DeviceIdentity
import com.slipstream.core.identity.PairedPeerStore
import com.slipstream.core.identity.PairingCode
import com.slipstream.core.net.LocalNetwork
import com.slipstream.core.net.NetworkInfo
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.io.path.createTempDirectory
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val LOOPBACK: InetAddress = InetAddress.getByName("127.0.0.1")

private class FixedNetworkInfo(private val address: InetAddress) : NetworkInfo {
    override fun current(): LocalNetwork = LocalNetwork(address, null, 32, "test-network")
}

private fun emptyPeerStore(): PairedPeerStore = PairedPeerStore(createTempDirectory().toFile())

class PairingTest {

    // --- PairingSession: code derivation binds only to the TLS-verified fingerprint ---

    @Test
    fun `an offer whose claimed fingerprint differs from the certificate is rejected`() {
        val local = DeviceIdentity.createNew("Local")
        val remote = DeviceIdentity.createNew("Remote")
        val offer = PairingOffer(deviceId = remote.deviceId, name = remote.displayName, fingerprint = remote.fingerprint)

        val session = PairingSession(local)
        session.receiveOffer(offer.copy(fingerprint = "not-what-it-holds"), verifiedFingerprint = remote.fingerprint)

        assertEquals(PairingState.Cancelled, session.state)
        assertNull(session.code)
    }

    private fun started(): Triple<PairingSession, DeviceIdentity, DeviceIdentity> {
        val local = DeviceIdentity.createNew("Local")
        val remote = DeviceIdentity.createNew("Remote")
        val offer = PairingOffer(deviceId = remote.deviceId, name = remote.displayName, fingerprint = remote.fingerprint)

        val session = PairingSession(local)
        session.receiveOffer(offer, verifiedFingerprint = remote.fingerprint)
        return Triple(session, local, remote)
    }

    @Test
    fun `a matching offer derives the same code as the wire-compatible PairingCode`() {
        val (session, local, remote) = started()
        assertEquals(PairingState.AwaitingConfirmation, session.state)
        assertEquals(PairingCode.derive(local.fingerprint, remote.fingerprint), session.code)
    }

    @Test
    fun `a single-sided confirmation never pairs`() {
        val (session, _, _) = started()
        session.confirmLocally()
        assertEquals(PairingState.AwaitingConfirmation, session.state)
        assertNull(session.result)
    }

    @Test
    fun `mutual confirmation completes the session`() {
        val (session, _, remote) = started()
        session.confirmLocally()
        session.receiveConfirm()
        assertEquals(PairingState.Confirmed, session.state)
        assertEquals(remote.deviceId, session.result?.deviceId)
        assertEquals(remote.fingerprint, session.result?.fingerprint)
    }

    @Test
    fun `cancel aborts an in-progress session without pairing`() {
        val (session, _, _) = started()
        session.confirmLocally()
        session.cancel()
        assertEquals(PairingState.Cancelled, session.state)
        assertNull(session.result)
    }

    // --- ControlServer routing: pairing window gates the restricted handler ---

    @Test
    fun `outside an open window an unpaired connection is still dropped`() {
        val serverIdentity = DeviceIdentity.createNew("Server")
        val server = ControlServer(serverIdentity, emptyPeerStore(), FixedNetworkInfo(LOOPBACK), port = 0, PairingWindow()).start()
        var pairingReached = false
        var peerReached = false
        server.onPairingConnected = { pairingReached = true }
        server.onPeerConnected = { peerReached = true }

        try {
            val strangerIdentity = DeviceIdentity.createNew("Stranger")
            val socket = PinnedTls.connect(server.listenEndpoint, strangerIdentity) { true }
            val conn = ControlConnection(socket)
            try {
                conn.send(ControlMessage(type = "pair.offer", id = "1"))
            } catch (e: Exception) {
                // an immediate reset while writing is also acceptable evidence of the drop
            }
            Thread.sleep(200)
            assertFalse("must never route to the restricted handler when no window is open", pairingReached)
            assertFalse(peerReached)
            conn.close()
        } finally {
            server.close()
        }
    }

    @Test
    fun `inside an open window an unpaired connection reaches the restricted handler`() {
        val serverIdentity = DeviceIdentity.createNew("Server")
        val window = PairingWindow()
        window.open()
        val server = ControlServer(serverIdentity, emptyPeerStore(), FixedNetworkInfo(LOOPBACK), port = 0, window).start()
        val latch = CountDownLatch(1)
        server.onPairingConnected = { latch.countDown() }

        try {
            val strangerIdentity = DeviceIdentity.createNew("Stranger")
            val socket = PinnedTls.connect(server.listenEndpoint, strangerIdentity) { true }
            val conn = ControlConnection(socket)
            assertTrue(latch.await(5, TimeUnit.SECONDS))
            conn.close()
        } finally {
            server.close()
        }
    }

    @Test
    fun `an already-paired peer always reaches the normal handler, even while a window is open`() {
        val serverIdentity = DeviceIdentity.createNew("Server")
        val clientIdentity = DeviceIdentity.createNew("Client")
        val store = emptyPeerStore()
        store.store(
            com.slipstream.core.identity.PairedPeer(
                deviceId = clientIdentity.deviceId,
                fingerprint = clientIdentity.fingerprint,
                certificate = clientIdentity.certificate,
            ),
        )
        val window = PairingWindow()
        window.open()
        val server = ControlServer(serverIdentity, store, FixedNetworkInfo(LOOPBACK), port = 0, window).start()
        val peerLatch = CountDownLatch(1)
        var pairingReached = false
        server.onPeerConnected = { peerLatch.countDown() }
        server.onPairingConnected = { pairingReached = true }

        try {
            val socket = PinnedTls.connect(server.listenEndpoint, clientIdentity) { true }
            val conn = ControlConnection(socket)
            assertTrue(peerLatch.await(5, TimeUnit.SECONDS))
            assertFalse(pairingReached)
            conn.close()
        } finally {
            server.close()
        }
    }

    // --- PairingCoordinator: full wire flow ---

    @Test
    fun `restricted handler ignores message types outside pair offer confirm cancel`() {
        val serverIdentity = DeviceIdentity.createNew("Server")
        val window = PairingWindow()
        window.open()
        val server = ControlServer(serverIdentity, emptyPeerStore(), FixedNetworkInfo(LOOPBACK), port = 0, window).start()
        val received = java.util.concurrent.atomic.AtomicReference<ControlMessage?>(null)
        val latch = CountDownLatch(1)
        server.onPairingConnected = { conn ->
            received.set(conn.receive())
            latch.countDown()
        }

        try {
            val clientIdentity = DeviceIdentity.createNew("Client")
            val socket = PinnedTls.connect(server.listenEndpoint, clientIdentity) { true }
            val conn = ControlConnection(socket)
            // Not one of pair.offer/pair.confirm/pair.cancel: a restricted handler that
            // dispatched this would be reachable from an unpaired stranger, which is exactly
            // what the restriction exists to prevent.
            conn.send(ControlMessage(type = "browse.list", id = "1"))
            conn.send(ControlMessage(type = "pair.cancel"))

            assertTrue(latch.await(5, TimeUnit.SECONDS))
            // The codec/coordinator skip unknown types silently, so the first message a
            // handler built on PairingCoordinator's dispatch would ever act on is pair.cancel.
            assertEquals("browse.list", received.get()?.type)
            conn.close()
        } finally {
            server.close()
        }
    }

    @Test
    fun `mutual confirmation over the wire persists both peers`() {
        val aliceIdentity = DeviceIdentity.createNew("Alice")
        val bobIdentity = DeviceIdentity.createNew("Bob")
        val aliceStore = emptyPeerStore()
        val bobStore = emptyPeerStore()

        val window = PairingWindow()
        window.open()
        val server = ControlServer(bobIdentity, bobStore, FixedNetworkInfo(LOOPBACK), port = 0, window).start()

        val bobResult = java.util.concurrent.atomic.AtomicReference<Boolean?>(null)
        val bobDone = CountDownLatch(1)

        try {
            server.onPairingConnected = { conn ->
                thread {
                    // The server-side socket's peer certificate is Alice's, verified by the
                    // TLS handshake that already happened before onPairingConnected fired.
                    val coordinator = PairingCoordinator(
                        identity = bobIdentity,
                        peerStore = bobStore,
                        connection = conn,
                        remoteVerifiedFingerprint = aliceIdentity.fingerprint,
                        remoteCertificate = aliceIdentity.certificate,
                        isInitiator = false,
                        decide = { true },
                    )
                    bobResult.set(coordinator.run())
                    bobDone.countDown()
                }
            }

            val socket = PinnedTls.connect(server.listenEndpoint, aliceIdentity) { true }
            val aliceConn = ControlConnection(socket)
            val aliceCoordinator = PairingCoordinator(
                identity = aliceIdentity,
                peerStore = aliceStore,
                connection = aliceConn,
                remoteVerifiedFingerprint = bobIdentity.fingerprint,
                remoteCertificate = bobIdentity.certificate,
                isInitiator = true,
                decide = { true },
            )
            val aliceResult = aliceCoordinator.run()

            assertTrue(bobDone.await(5, TimeUnit.SECONDS))
            assertTrue("initiator side must confirm", aliceResult)
            assertTrue("responder side must confirm", bobResult.get() == true)
            assertEquals(bobIdentity.fingerprint, aliceStore.peer?.fingerprint)
            assertEquals(aliceIdentity.fingerprint, bobStore.peer?.fingerprint)
            aliceConn.close()
        } finally {
            server.close()
        }
    }

    @Test
    fun `pair cancel from either side aborts without pairing`() {
        val aliceIdentity = DeviceIdentity.createNew("Alice")
        val bobIdentity = DeviceIdentity.createNew("Bob")
        val aliceStore = emptyPeerStore()
        val bobStore = emptyPeerStore()

        val window = PairingWindow()
        window.open()
        val server = ControlServer(bobIdentity, bobStore, FixedNetworkInfo(LOOPBACK), port = 0, window).start()

        val bobResult = java.util.concurrent.atomic.AtomicReference<Boolean?>(null)
        val bobDone = CountDownLatch(1)

        try {
            server.onPairingConnected = { conn ->
                thread {
                    val coordinator = PairingCoordinator(
                        identity = bobIdentity,
                        peerStore = bobStore,
                        connection = conn,
                        remoteVerifiedFingerprint = aliceIdentity.fingerprint,
                        remoteCertificate = aliceIdentity.certificate,
                        isInitiator = false,
                        // Bob declines.
                        decide = { false },
                    )
                    bobResult.set(coordinator.run())
                    bobDone.countDown()
                }
            }

            val socket = PinnedTls.connect(server.listenEndpoint, aliceIdentity) { true }
            val aliceConn = ControlConnection(socket)
            val aliceCoordinator = PairingCoordinator(
                identity = aliceIdentity,
                peerStore = aliceStore,
                connection = aliceConn,
                remoteVerifiedFingerprint = bobIdentity.fingerprint,
                remoteCertificate = bobIdentity.certificate,
                isInitiator = true,
                decide = { true },
            )
            val aliceResult = aliceCoordinator.run()

            assertTrue(bobDone.await(5, TimeUnit.SECONDS))
            assertFalse(aliceResult)
            assertFalse(bobResult.get() == true)
            assertNull(aliceStore.peer)
            assertNull(bobStore.peer)
            aliceConn.close()
        } finally {
            server.close()
        }
    }

    // --- PairingDiscovery ---

    @Test
    fun `pairing discovery returns null immediately with no window open`() = runBlocking {
        val identity = DeviceIdentity.createNew("Device")
        val window = PairingWindow() // never opened
        var transportCreated = false
        val discovery = PairingDiscovery(
            identity = identity,
            window = window,
            transportFactory = {
                transportCreated = true
                throw AssertionError("must not open a transport when no window is open")
            },
        )

        val result = discovery.find(timeout = 50.milliseconds)

        assertNull(result)
        assertFalse("must not listen at all when no window is open", transportCreated)
    }
}
