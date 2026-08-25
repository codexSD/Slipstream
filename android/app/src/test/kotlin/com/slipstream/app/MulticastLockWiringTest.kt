package com.slipstream.app

import com.slipstream.core.discovery.AndroidMulticastLock
import com.slipstream.core.discovery.NoopMulticastLock
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * S3 (multicast discovery) was silently non-functional on shipped hardware: [PeerWiring]
 * omitted `multicastLock` when constructing `MulticastStrategy`, so it defaulted to
 * [NoopMulticastLock]. The manifest requested `CHANGE_WIFI_MULTICAST_STATE` and nothing ever
 * acquired a lock, and most Wi-Fi drivers filter multicast to the app layer without one.
 *
 * `AndroidMulticastLock` existed and was tested in isolation - which is exactly why this
 * needs a test at the *wiring* level, in the same spirit as [PeerWiringTest]'s proof that the
 * live network binder reaches production.
 */
@RunWith(RobolectricTestRunner::class)
class MulticastLockWiringTest {

    @Test
    fun `the production wiring hands discovery a real multicast lock`() {
        val app = RuntimeEnvironment.getApplication() as SlipstreamApplication
        val wiring = app.buildWiring()

        assertTrue(
            "production must supply a real WifiManager-backed lock, not the no-op default",
            wiring.multicastLock is AndroidMulticastLock,
        )
    }

    @Test
    fun `that same lock is the one the multicast strategy receives`() {
        val app = RuntimeEnvironment.getApplication() as SlipstreamApplication
        val wiring = app.buildWiring()

        // MulticastStrategy's own default is NoopMulticastLock, so holding the real lock in
        // the wiring is only half the fix - it has to arrive at the strategy that acquires it.
        assertSame(wiring.multicastLock, wiring.multicastStrategy.multicastLock)
    }
}
