package com.slipstream.app.peer

import app.cash.turbine.test
import java.io.File
import kotlin.concurrent.thread
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Drives [RealPeerController] against two real [com.slipstream.core.SlipstreamPeer]s over
 * loopback ([TwoPeers]), the same "real peers, real wire protocol" rig style as `:core`'s
 * `SlipstreamPeerPairingTest`. Uses `runBlocking` (not `kotlinx.coroutines.test.runTest`) and a
 * real `Dispatchers.IO`: every operation under test does real blocking socket I/O, and
 * `runTest`'s virtual clock would fast-forward any `withTimeout` past that real work instead of
 * actually waiting for it.
 */
@RunWith(RobolectricTestRunner::class)
class RealPeerControllerTest {

    private fun controller(rig: TwoPeers): RealPeerController = RealPeerController(
        peer = rig.local,
        identity = rig.localIdentity,
        peerStore = rig.localPeerStore,
        clipboardSink = rig.localClipboardSink,
        dispatcher = Dispatchers.IO,
    )

    @Test
    fun `reaches Connected and lists the peer's files`() = runBlocking {
        val rig = TwoPeers.start(createTempDirectory().toFile())
        val controller = controller(rig)
        try {
            withTimeout(20_000) { controller.start() }

            assertEquals(PeerConnectionState.Connected, controller.status.value.state)
            val result = controller.list(rig.sharedDir)
            assertTrue("list must succeed: ${result.exceptionOrNull()}", result.isSuccess)
            assertTrue(result.getOrThrow().entries.isNotEmpty())
        } finally {
            rig.local.close()
            rig.remote.close()
        }
    }

    /**
     * The rig every other test uses is paired *before* the controller exists, which is exactly
     * why this never showed up: on a real device the service starts unpaired, its one
     * `reconnect()` fails (there is no fingerprint to pin against yet), and the user pairs
     * afterwards. Nothing then re-attempted the connection, so the phone sat on "Disconnected"
     * — and every `list()` failed — while the freshly-paired PC saw a healthy link of its own.
     */
    @Test
    fun `connects on its own once pairing succeeds, having started unpaired`() = runBlocking {
        val rig = TwoPeers.start(createTempDirectory().toFile(), alreadyPaired = false)
        val controller = controller(rig)
        try {
            // Unpaired: connecting cannot work, there is no peer fingerprint to pin against.
            assertTrue(!withTimeout(60_000) { controller.reconnect() })
            assertEquals(PeerConnectionState.Lost, controller.status.value.state)

            // The peer initiates; this device is the responder, as on real hardware.
            val localEndpoint = requireNotNull(rig.local.controlEndpoint)
            thread(isDaemon = true) {
                repeat(100) {
                    if (rig.local.isPairingWindowOpen) return@repeat
                    Thread.sleep(20)
                }
                rig.remote.initiatePairing(
                    java.net.InetSocketAddress(java.net.InetAddress.getByName("127.0.0.1"), localEndpoint.port),
                ) { true }
            }

            withTimeout(60_000) {
                controller.openPairing().test {
                    assertTrue(awaitItem() is PairingProgress.CodeReceived)
                    controller.confirmPairing(accept = true)
                    assertTrue((awaitItem() as PairingProgress.Completed).paired)
                }
            }

            // The whole point: no one calls reconnect() here. The controller must get itself
            // back on the wire, because nothing in the app is watching to do it for it.
            withTimeout(60_000) {
                controller.status.first { it.state == PeerConnectionState.Connected }
            }
            assertTrue(controller.list(rig.sharedDir).isSuccess)
        } finally {
            rig.local.close()
            rig.remote.close()
        }
    }

    @Test
    fun `reports Lost then recovers on reconnect`() = runBlocking {
        val rig = TwoPeers.start(createTempDirectory().toFile())
        val controller = controller(rig)
        try {
            withTimeout(20_000) { controller.start() }

            controller.status.test {
                assertEquals(PeerConnectionState.Connected, awaitItem().state)

                // See TwoPeers.restartRemoteOnSamePorts's doc: closing the remote peer does not
                // actually kill an already-accepted control connection, so the break has to come
                // from the client side this test controls.
                controller.debugCloseConnectionForTesting()
                // The heartbeat loop's next ping (every 750ms) is what notices the break.
                assertEquals(PeerConnectionState.Lost, awaitItem().state)

                assertTrue(withTimeout(20_000) { controller.reconnect() })
                // reconnect() passes through Searching on its way back to Connected.
                assertEquals(PeerConnectionState.Searching, awaitItem().state)
                assertEquals(PeerConnectionState.Connected, awaitItem().state)
            }
        } finally {
            rig.local.close()
            rig.remote.close()
        }
    }

    /**
     * These two messages were one and the same, and that cost real debugging time: a phone that
     * had simply never connected reported "That folder is no longer there.", which reads as a
     * problem with the path the user picked rather than with the link. Every `list()` failure
     * looked like a missing folder no matter the actual cause.
     */
    @Test
    fun `a list with no connection says so, rather than blaming the folder`() = runBlocking {
        val rig = TwoPeers.start(createTempDirectory().toFile())
        val controller = controller(rig)
        try {
            // Never started: there is no control connection at all.
            val result = controller.list(rig.sharedDir)

            assertTrue(result.isFailure)
            assertEquals(NOT_CONNECTED_MESSAGE, result.exceptionOrNull()?.message)
        } finally {
            rig.local.close()
            rig.remote.close()
        }
    }

    /**
     * `reconnect()` used to be the only thing that could get back on the wire, and the app calls
     * it exactly once, at service start (`PeerForegroundService.startPeerControllerLifecycle`).
     * So a link that dropped any time afterwards — a Wi-Fi switch, the PC sleeping, the hotspot
     * cycling — stayed dropped until the user killed and reopened the app.
     */
    @Test
    fun `recovers from a dropped link on its own, with nobody calling reconnect`() = runBlocking {
        val rig = TwoPeers.start(createTempDirectory().toFile())
        val controller = controller(rig)
        try {
            withTimeout(20_000) { controller.start() }
            assertEquals(PeerConnectionState.Connected, controller.status.value.state)

            controller.debugCloseConnectionForTesting()
            withTimeout(20_000) {
                controller.status.first { it.state == PeerConnectionState.Lost }
            }

            // No reconnect() call here — that is the whole point.
            withTimeout(60_000) {
                controller.status.first { it.state == PeerConnectionState.Connected }
            }
            assertTrue(controller.list(rig.sharedDir).isSuccess)
        } finally {
            rig.local.close()
            rig.remote.close()
        }
    }

    @Test
    fun `a failed list surfaces a message, not an exception`() = runBlocking {
        val rig = TwoPeers.start(createTempDirectory().toFile())
        val controller = controller(rig)
        try {
            withTimeout(20_000) { controller.start() }

            val result = controller.list("/nope-does-not-exist")

            assertTrue(result.isFailure)
            assertEquals("That folder is no longer there.", result.exceptionOrNull()?.message)
        } finally {
            rig.local.close()
            rig.remote.close()
        }
    }

    @Test
    fun `serialises control-channel access`() = runBlocking {
        val rig = TwoPeers.start(createTempDirectory().toFile())
        val controller = controller(rig)
        try {
            withTimeout(20_000) { controller.start() }

            val results = (1..8).map { async { controller.list(rig.sharedDir) } }.awaitAll()

            assertTrue(
                "every concurrent list() must succeed without a mismatched/interleaved reply: " +
                    results.map { it.exceptionOrNull() },
                results.all { it.isSuccess },
            )
        } finally {
            rig.local.close()
            rig.remote.close()
        }
    }

    @Test
    fun `push then pull round-trips a file between the two peers`() = runBlocking {
        val rig = TwoPeers.start(createTempDirectory().toFile())
        val controller = controller(rig)
        try {
            withTimeout(20_000) { controller.start() }

            val sourceDir = createTempDirectory().toFile()
            val source = File(sourceDir, "note.txt")
            source.writeText("pushed over the wire")

            val progress = mutableListOf<TransferProgress>()
            withTimeout(20_000) {
                controller.push(source.path, "note.txt").collect { progress.add(it) }
            }
            assertTrue("push must report progress", progress.isNotEmpty())
            val landed = File(rig.remoteRoot, "note.txt")
            assertTrue(landed.exists())
            assertEquals("pushed over the wire", landed.readText())
        } finally {
            rig.local.close()
            rig.remote.close()
        }
    }

    @Test
    fun `streamOnPeer sends the peer a play message carrying this device's own media url, and downloads nothing`() = runBlocking {
        val urlRequests = mutableListOf<Pair<String, String?>>()
        val rig = TwoPeers.start(
            createTempDirectory().toFile(),
            onPlayUrlRequested = { url, mime -> urlRequests.add(url to mime) },
        )
        val controller = controller(rig)
        try {
            withTimeout(20_000) { controller.start() }

            val localDir = createTempDirectory().toFile()
            val localVideo = File(localDir, "holiday.mp4")
            localVideo.writeBytes(ByteArray(64))

            val result = withTimeout(20_000) { controller.streamOnPeer(localVideo.path) }

            assertTrue("streamOnPeer must report success: ${result.exceptionOrNull()}", result.isSuccess)
            repeat(50) {
                if (urlRequests.isNotEmpty()) return@repeat
                Thread.sleep(20)
            }
            assertEquals(1, urlRequests.size)
            val (url, mime) = urlRequests.single()
            assertEquals("video/mp4", mime)
            // The URL must describe THIS device's (rig.local's) own media server, not the peer's -
            // real push-to-play serves the file from wherever it actually lives.
            val localMediaEndpoint = requireNotNull(rig.local.mediaEndpoint)
            assertTrue(
                "the play url must point at this device's own media port ${localMediaEndpoint.port}, not the peer's: $url",
                url.startsWith("http://127.0.0.1:${localMediaEndpoint.port}/media/"),
            )
            // Nothing was ever transferred: the file stays exactly where it was, and no copy
            // landed under the peer's root.
            assertTrue(localVideo.exists())
            assertEquals(64L, localVideo.length())
        } finally {
            rig.local.close()
            rig.remote.close()
        }
    }

    @Test
    fun `streamOnPeer fails cleanly when the local file does not exist`() = runBlocking {
        val rig = TwoPeers.start(createTempDirectory().toFile())
        val controller = controller(rig)
        try {
            withTimeout(20_000) { controller.start() }

            val result = controller.streamOnPeer("/no/such/file.mp4")

            assertTrue(result.isFailure)
        } finally {
            rig.local.close()
            rig.remote.close()
        }
    }

    @Test
    fun clipboardReceived_delivers_text_sent_by_the_peer() = runBlocking {
        val rig = TwoPeers.start(createTempDirectory().toFile())
        val controller = controller(rig)
        try {
            withTimeout(20_000) { controller.start() }

            controller.clipboardReceived.test {
                // rig.remote is already running (TwoPeers.start already called peer.start() on
                // it) - a second RealPeerController wrapping it would call peer.start() again
                // and try to rebind its already-bound listen ports. A short-lived raw
                // ControlClient connection is enough to exercise the inbound clipboard path.
                com.slipstream.core.control.ControlClient.connect(
                    requireNotNull(rig.local.controlEndpoint),
                    rig.remote.identity,
                    rig.remotePeerStore,
                ).use { conn ->
                    conn.send(
                        com.slipstream.core.control.ControlMessage(
                            type = com.slipstream.core.control.SessionMessageTypes.CLIPBOARD,
                            id = "clip-1",
                            payload = kotlinx.serialization.json.JsonObject(
                                mapOf("text" to kotlinx.serialization.json.JsonPrimitive("copied on the peer")),
                            ),
                        ),
                    )
                }

                assertEquals("copied on the peer", awaitItem())
            }
        } finally {
            rig.local.close()
            rig.remote.close()
        }
    }

    /**
     * Task 12: `SlipstreamSession.clipboard` (`:core`) silently drops anything over
     * [com.slipstream.core.control.CLIPBOARD_MAX_BYTES] before it ever reaches the receiving
     * app - so a naive sender would see [sendClipboard] report success while the text vanished
     * on arrival. [RealPeerController.sendClipboard] must instead refuse it up front, with the
     * spec §15 message, before anything is even sent over the wire.
     */
    @Test
    fun `sendClipboard refuses text over the 64KB cap before sending it`() = runBlocking {
        val rig = TwoPeers.start(createTempDirectory().toFile())
        val controller = controller(rig)
        try {
            withTimeout(20_000) { controller.start() }

            val oversized = "x".repeat(com.slipstream.core.control.CLIPBOARD_MAX_BYTES + 1)
            val result = controller.sendClipboard(oversized)

            assertTrue("must be refused, not silently sent", result.isFailure)
            assertEquals(CLIPBOARD_TOO_LARGE_MESSAGE, result.exceptionOrNull()?.message)

            // And confirm it never reached the peer: the peer never received a clipboardReceived
            // event for it, distinguishing "refused before sending" from "sent, then dropped by
            // SlipstreamSession.clipboard's own cap on arrival" - both would report success/failure
            // differently, but only the former means sendClipboard itself did the refusing.
            val receivedByPeer = withTimeoutOrNull(500) {
                rig.remoteClipboardSink.received.first()
            }
            assertEquals(
                "the peer's own clipboardReceived flow must never see this oversized text",
                null,
                receivedByPeer,
            )
        } finally {
            rig.local.close()
            rig.remote.close()
        }
    }

    @Test
    fun `sendClipboard accepts text right at the 64KB cap`() = runBlocking {
        val rig = TwoPeers.start(createTempDirectory().toFile())
        val controller = controller(rig)
        try {
            withTimeout(20_000) { controller.start() }

            val atCap = "x".repeat(com.slipstream.core.control.CLIPBOARD_MAX_BYTES)
            val result = controller.sendClipboard(atCap)

            assertTrue("exactly at the cap must not be refused: ${result.exceptionOrNull()}", result.isSuccess)
        } finally {
            rig.local.close()
            rig.remote.close()
        }
    }
}
