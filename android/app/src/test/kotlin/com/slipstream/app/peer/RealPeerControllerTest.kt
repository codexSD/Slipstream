package com.slipstream.app.peer

import app.cash.turbine.test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
}
