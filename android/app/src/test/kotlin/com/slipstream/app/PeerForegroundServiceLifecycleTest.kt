package com.slipstream.app

import com.slipstream.app.peer.PeerConnectionState
import com.slipstream.app.peer.RealPeerController
import com.slipstream.app.peer.TwoPeers
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Review finding (fix round 1): nothing in production ever called [com.slipstream.app.peer.PeerController.start]
 * or [com.slipstream.app.peer.PeerController.reconnect] - [PeerForegroundService] drove the
 * underlying [com.slipstream.core.SlipstreamPeer] directly and left `RealPeerController`'s own
 * state machine parked at [PeerConnectionState.Idle] forever, so the nav shell's connection pill
 * (bound to `peerController.status`) never moved. [startPeerControllerLifecycle] is
 * [PeerForegroundService.onCreate]'s exact fix, extracted to a top-level function so it can be
 * exercised here against a real [RealPeerController] (the same "real peers, real wire protocol"
 * rig [RealPeerControllerTest] uses) without needing a live Android `Service` or real network -
 * see [RealPeerControllerTest]'s own class doc for why `runBlocking` and a real `Dispatchers.IO`
 * are used instead of `runTest`'s virtual clock.
 */
@RunWith(RobolectricTestRunner::class)
class PeerForegroundServiceLifecycleTest {

    @Test
    fun `starting the lifecycle advances the controller past Idle to Connected`() = runBlocking {
        val rig = TwoPeers.start(createTempDirectory().toFile())
        val controller = RealPeerController(
            peer = rig.local,
            identity = rig.localIdentity,
            peerStore = rig.localPeerStore,
            clipboardSink = rig.localClipboardSink,
            dispatcher = Dispatchers.IO,
        )
        assertEquals(
            "precondition: the controller must start out parked at Idle",
            PeerConnectionState.Idle,
            controller.status.value.state,
        )

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            startPeerControllerLifecycle(controller, scope)

            withTimeout(20_000) {
                while (controller.status.value.state != PeerConnectionState.Connected) {
                    delay(20)
                }
            }

            assertEquals(PeerConnectionState.Connected, controller.status.value.state)
        } finally {
            scope.cancel()
        }
    }
}
