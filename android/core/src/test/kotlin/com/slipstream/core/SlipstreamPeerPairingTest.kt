package com.slipstream.core

import com.slipstream.core.control.ClipboardSink
import com.slipstream.core.discovery.DiscoveryCoordinator
import com.slipstream.core.discovery.DiscoveryResponder
import com.slipstream.core.identity.DeviceIdentity
import com.slipstream.core.identity.PairedPeerStore
import com.slipstream.core.identity.PairingCode
import com.slipstream.core.net.LocalNetwork
import com.slipstream.core.net.NetworkInfo
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.io.path.createTempDirectory
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val LOOPBACK: InetAddress = InetAddress.getByName("127.0.0.1")

private class LoopbackNetworkInfo : NetworkInfo {
    override fun current() = LocalNetwork(LOOPBACK, null, 32, "if:lo|127.0.0.0/32")
}

/** Records the responder lifecycle without opening a real multicast socket. */
private class RecordingResponder : DiscoveryResponder {
    val started = AtomicInteger(0)
    val stopped = AtomicInteger(0)
    override suspend fun startResponder() { started.incrementAndGet() }
    override suspend fun stopResponder() { stopped.incrementAndGet() }
}

/**
 * The pairing subsystem (Task 7) previously had no production caller at all: `SlipstreamPeer`
 * built a `ControlServer` with a default `PairingWindow` it kept no reference to, and never
 * assigned `onPairingConnected`. Two peers could not pair through `:core`'s own public API -
 * which is what Task 13's hardware testing had to work around by hand-editing the paired-peer
 * JSON on both devices.
 *
 * These drive two real peers over loopback through the API that now exists.
 */
class SlipstreamPeerPairingTest {

    private fun peer(
        name: String,
        responder: DiscoveryResponder? = null,
    ): SlipstreamPeer {
        val dir = createTempDirectory().toFile()
        val networkInfo = LoopbackNetworkInfo()
        return SlipstreamPeer(
            identity = DeviceIdentity.createNew(name),
            peerStore = PairedPeerStore(dir),
            networkInfo = networkInfo,
            rootDirectory = dir,
            clipboardSink = ClipboardSink { },
            discoveryCoordinatorFactory = { DiscoveryCoordinator(networkInfo, cache = null, strategies = emptyList()) },
            discoveryResponder = responder,
            controlPort = 0,
            bulkPort = 0,
            mediaPort = 0,
        )
    }

    @Test
    fun `two peers complete a real pairing flow end to end through the public API`() {
        val phone = peer("Phone")
        val pc = peer("PC")
        try {
            phone.start()
            pc.start()

            val phoneCode = AtomicReference<String?>(null)
            val pcCode = AtomicReference<String?>(null)
            val phoneResult = AtomicReference<com.slipstream.core.identity.PairedPeer?>(null)
            val phoneDone = CountDownLatch(1)

            // Phone opens its window and waits, exactly as a UI would on "Pair this device".
            thread(isDaemon = true) {
                phoneResult.set(
                    phone.awaitPairing(timeout = 20.seconds) { code ->
                        phoneCode.set(code)
                        true
                    },
                )
                phoneDone.countDown()
            }

            // Give the window a moment to open before the PC dials in.
            val endpoint = requireNotNull(phone.controlEndpoint)
            var opened = false
            repeat(50) {
                if (phone.isPairingWindowOpen) { opened = true; return@repeat }
                Thread.sleep(20)
            }
            assertTrue("the pairing window must actually open", opened || phone.isPairingWindowOpen)

            val pcResult = pc.initiatePairing(InetSocketAddress(LOOPBACK, endpoint.port)) { code ->
                pcCode.set(code)
                true
            }

            assertTrue(phoneDone.await(20, TimeUnit.SECONDS))

            assertNotNull("the initiator must end up paired", pcResult)
            assertNotNull("the responder must end up paired", phoneResult.get())
            assertEquals(phone.identity.fingerprint, pcResult!!.fingerprint)
            assertEquals(pc.identity.fingerprint, phoneResult.get()!!.fingerprint)

            // Both sides showed the same code, and it is the wire-compatible derivation over
            // the TLS-verified fingerprints - never anything read out of a pair.offer payload.
            assertEquals(
                PairingCode.derive(phone.identity.fingerprint, pc.identity.fingerprint),
                phoneCode.get(),
            )
            assertEquals(phoneCode.get(), pcCode.get())

            assertFalse("the window must close once pairing completes", phone.isPairingWindowOpen)
        } finally {
            phone.close()
            pc.close()
        }
    }

    @Test
    fun `a declined code pairs neither side and closes the window`() {
        val phone = peer("Phone")
        val pc = peer("PC")
        try {
            phone.start()
            pc.start()

            val phoneResult = AtomicReference<com.slipstream.core.identity.PairedPeer?>(null)
            val phoneDone = CountDownLatch(1)
            thread(isDaemon = true) {
                phoneResult.set(phone.awaitPairing(timeout = 20.seconds) { false })
                phoneDone.countDown()
            }
            repeat(50) {
                if (phone.isPairingWindowOpen) return@repeat
                Thread.sleep(20)
            }

            val endpoint = requireNotNull(phone.controlEndpoint)
            val pcResult = pc.initiatePairing(InetSocketAddress(LOOPBACK, endpoint.port)) { true }

            assertTrue(phoneDone.await(20, TimeUnit.SECONDS))
            assertNull(pcResult)
            assertNull(phoneResult.get())
            assertFalse(phone.isPairingWindowOpen)
        } finally {
            phone.close()
            pc.close()
        }
    }

    @Test
    fun `a stranger connecting outside an open window is still refused`() {
        val phone = peer("Phone")
        val stranger = peer("Stranger")
        try {
            phone.start()
            stranger.start()
            val endpoint = requireNotNull(phone.controlEndpoint)

            assertFalse(phone.isPairingWindowOpen)
            // No window: the connection must not reach the pairing coordinator at all.
            val result = runCatching {
                stranger.initiatePairing(InetSocketAddress(LOOPBACK, endpoint.port)) { true }
            }.getOrNull()
            assertNull(result)
        } finally {
            phone.close()
            stranger.close()
        }
    }

    @Test
    fun `the pairing window closes itself when nobody connects`() {
        val phone = peer("Phone")
        try {
            phone.start()
            val result = runBlocking { phone.openPairingWindow(timeout = 200.milliseconds) { true } }
            assertNull(result)
            assertFalse("an expired attempt must not leave a window standing open", phone.isPairingWindowOpen)
        } finally {
            phone.close()
        }
    }

    // --- Important 4 at the peer level: the responder is live for the peer's whole lifetime ---

    @Test
    fun `start brings the discovery responder up and close takes it down`() {
        val responder = RecordingResponder()
        val phone = peer("Phone", responder)
        try {
            assertEquals(0, responder.started.get())
            phone.start()
            assertEquals(
                "the responder must be live from app start, not only during a find()",
                1,
                responder.started.get(),
            )
            assertEquals(0, responder.stopped.get())
        } finally {
            phone.close()
        }
        assertEquals(1, responder.stopped.get())
    }
}
