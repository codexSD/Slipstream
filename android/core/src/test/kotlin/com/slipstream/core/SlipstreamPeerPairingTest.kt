package com.slipstream.core

import com.slipstream.core.control.ClipboardSink
import com.slipstream.core.control.ControlClient
import com.slipstream.core.control.ControlMessage
import com.slipstream.core.control.SessionMessageTypes
import com.slipstream.core.discovery.DiscoveryCoordinator
import com.slipstream.core.discovery.DiscoveryResponder
import com.slipstream.core.identity.DeviceIdentity
import com.slipstream.core.identity.PairedPeerStore
import com.slipstream.core.identity.PairingCode
import com.slipstream.core.net.LocalNetwork
import com.slipstream.core.net.NetworkInfo
import java.io.File
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

    /** Same construction as [peer], but also returns the root directory so a test can inspect
     * what actually landed on disk (e.g. after a push). */
    private fun peerWithRoot(name: String): Pair<SlipstreamPeer, File> {
        val (p, dir, _) = peerWithRootAndStore(name)
        return p to dir
    }

    /** Same construction as [peerWithRoot], but also returns the [PairedPeerStore] backing it -
     * needed by tests that want to connect to this peer as a raw [ControlClient], since pinning
     * a control connection to a peer requires the caller's own paired-peer store. Also accepts
     * [onPlayRequested] so the `play` end-to-end test can observe it fire. */
    private fun peerWithRootAndStore(
        name: String,
        onPlayRequested: (File) -> Unit = {},
        onPlayUrlRequested: (String, String?) -> Unit = { _, _ -> },
    ): Triple<SlipstreamPeer, File, PairedPeerStore> {
        val dir = createTempDirectory().toFile()
        val networkInfo = LoopbackNetworkInfo()
        val store = PairedPeerStore(dir)
        val p = SlipstreamPeer(
            identity = DeviceIdentity.createNew(name),
            peerStore = store,
            networkInfo = networkInfo,
            rootDirectory = dir,
            clipboardSink = ClipboardSink { },
            discoveryCoordinatorFactory = { DiscoveryCoordinator(networkInfo, cache = null, strategies = emptyList()) },
            controlPort = 0,
            bulkPort = 0,
            mediaPort = 0,
            onPlayRequested = onPlayRequested,
            onPlayUrlRequested = onPlayUrlRequested,
        )
        return Triple(p, dir, store)
    }

    /** Pairs [a] and [b] over loopback through the same public pairing API the pairing tests
     * above exercise, so push/pull tests can start from an already-paired pair without
     * duplicating that dance inline. */
    private fun pair(a: SlipstreamPeer, b: SlipstreamPeer) {
        a.start()
        b.start()
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
        assertTrue(bDone.await(20, TimeUnit.SECONDS))
        assertNotNull("pairing must succeed for the push/pull rig to be usable", result)
    }

    @Test
    fun `pushFile delivers the file to the peer's root directory`() {
        val (sender, senderRoot) = peerWithRoot("Sender")
        val (receiver, receiverRoot) = peerWithRoot("Receiver")
        try {
            pair(sender, receiver)

            val delivered = File(senderRoot, "photo.jpg")
            val bytes = ByteArray(5 * 1024 * 1024) { (it % 251).toByte() }
            delivered.writeBytes(bytes)
            val progress = mutableListOf<Long>()

            val ok = sender.pushFile(
                requireNotNull(receiver.controlEndpoint),
                delivered,
                "incoming/photo.jpg",
            ) { progress.add(it) }

            assertTrue("pushFile must report success", ok)
            val landed = File(receiverRoot, "incoming/photo.jpg")
            assertTrue("the pushed file must exist under the receiver's root", landed.exists())
            assertEquals(delivered.readBytes().toList(), landed.readBytes().toList())
            assertTrue("progress must add up to at least the full file size", progress.sum() >= delivered.length())
        } finally {
            sender.close()
            receiver.close()
        }
    }

    @Test
    fun `pullFile reports cumulative progress for a real loopback pull`() {
        val (sender, senderRoot) = peerWithRoot("Sender")
        val (receiver, receiverRoot) = peerWithRoot("Receiver")
        try {
            pair(sender, receiver)

            val source = File(senderRoot, "movie.bin")
            val bytes = ByteArray(3 * 1024 * 1024) { (it % 253).toByte() }
            source.writeBytes(bytes)

            val progress = mutableListOf<Long>()
            val destination = File(receiverRoot, "pulled/movie.bin")
            val part = receiver.pullFile(
                requireNotNull(sender.controlEndpoint),
                "movie.bin",
                destination,
                streams = 2,
            ) { progress.add(it) }
            part.close()

            assertTrue("pulled file must exist", destination.exists())
            assertEquals(source.readBytes().toList(), destination.readBytes().toList())
            assertTrue(
                "onProgress must deliver a non-empty cumulative byte count for the pull",
                progress.isNotEmpty() && progress.sum() >= source.length(),
            )
        } finally {
            sender.close()
            receiver.close()
        }
    }

    @Test
    fun `a push offer that escapes the root is refused`() {
        val (sender, senderRoot) = peerWithRoot("Sender")
        val (receiver, receiverRoot) = peerWithRoot("Receiver")
        try {
            pair(sender, receiver)

            val delivered = File(senderRoot, "secret.txt")
            delivered.writeBytes(ByteArray(16))

            val ok = sender.pushFile(
                requireNotNull(receiver.controlEndpoint),
                delivered,
                "../../etc/passwd",
            )

            assertFalse("an escaping push offer must be refused", ok)
            val escaped = File(receiverRoot.parentFile, "etc/passwd")
            assertFalse("nothing must be written outside the receiver's root", escaped.exists())
        } finally {
            sender.close()
            receiver.close()
        }
    }

    @Test
    fun `pushFile releases its token when the peer never accepts`() {
        val (sender, senderRoot) = peerWithRoot("Sender")
        val (receiver, _) = peerWithRoot("Receiver")
        try {
            pair(sender, receiver)
            // Kill the receiver's control server so the sender's push.offer connection is
            // refused/closed before a push.ok can ever arrive - the "peer never accepts" case.
            receiver.close()

            val delivered = File(senderRoot, "photo.jpg")
            delivered.writeBytes(ByteArray(1024))
            val before = sender.servedTransferCount

            val ok = sender.pushFile(InetSocketAddress(LOOPBACK, 1), delivered, "photo.jpg")

            assertFalse("pushFile must fail when no push.ok ever arrives", ok)
            assertEquals(
                "the token issued for the abandoned push must be released",
                before,
                sender.servedTransferCount,
            )
        } finally {
            sender.close()
        }
    }

    @Test
    fun `sending play over a real connection invokes the receiver's callback`() {
        val (sender, _, senderStore) = peerWithRootAndStore("Sender")
        val requested = mutableListOf<File>()
        val (receiver, receiverRoot, _) = peerWithRootAndStore("Receiver", onPlayRequested = { requested.add(it) })
        try {
            pair(sender, receiver)

            val file = File(receiverRoot, "song.mp3")
            file.writeBytes(ByteArray(10))

            ControlClient.connect(requireNotNull(receiver.controlEndpoint), sender.identity, senderStore).use { conn ->
                conn.send(
                    ControlMessage(
                        type = "play",
                        payload = JsonObject(mapOf("path" to JsonPrimitive("song.mp3"))),
                    ),
                )
            }

            repeat(50) {
                if (requested.isNotEmpty()) return@repeat
                Thread.sleep(20)
            }
            assertEquals(1, requested.size)
            assertEquals(file.canonicalFile, requested.single().canonicalFile)
        } finally {
            sender.close()
            receiver.close()
        }
    }

    @Test
    fun `sending play with a url over a real connection invokes onPlayUrlRequested, not onPlayRequested`() {
        val (sender, _, senderStore) = peerWithRootAndStore("Sender")
        val fileRequests = mutableListOf<File>()
        val urlRequests = mutableListOf<Pair<String, String?>>()
        val (receiver, _, _) = peerWithRootAndStore(
            "Receiver",
            onPlayRequested = { fileRequests.add(it) },
            onPlayUrlRequested = { url, mime -> urlRequests.add(url to mime) },
        )
        try {
            pair(sender, receiver)

            ControlClient.connect(requireNotNull(receiver.controlEndpoint), sender.identity, senderStore).use { conn ->
                conn.send(
                    ControlMessage(
                        type = "play",
                        payload = JsonObject(
                            mapOf(
                                "url" to JsonPrimitive("http://192.168.1.5:53323/media/token-1"),
                                "mime" to JsonPrimitive("video/mp4"),
                            ),
                        ),
                    ),
                )
            }

            repeat(50) {
                if (urlRequests.isNotEmpty()) return@repeat
                Thread.sleep(20)
            }
            assertEquals(listOf("http://192.168.1.5:53323/media/token-1" to "video/mp4"), urlRequests)
            assertTrue("a url-carrying play must never invoke the path-based callback", fileRequests.isEmpty())
        } finally {
            sender.close()
            receiver.close()
        }
    }

    @Test
    fun `mediaEndpoint reflects the peer's own bound media server once started, and is null before start`() {
        val (peer, _) = peerWithRoot("Solo")
        assertNull("mediaEndpoint must be null before start()", peer.mediaEndpoint)
        try {
            peer.start()
            val endpoint = peer.mediaEndpoint
            assertNotNull("mediaEndpoint must be populated once the media server is up", endpoint)
            assertEquals(LOOPBACK, endpoint!!.address)
            assertTrue("mediaEndpoint's port must be the real bound port, not the requested 0", endpoint.port > 0)
        } finally {
            peer.close()
        }
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
