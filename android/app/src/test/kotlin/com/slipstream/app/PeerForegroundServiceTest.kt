package com.slipstream.app

import android.net.NetworkCapabilities
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A Wi-Fi-only [android.net.NetworkRequest] means a device on a wired LAN - a tablet in a
 * USB/Ethernet dock, or anything sharing an Ethernet segment with the PC hosting the hotspot -
 * never receives a single network callback, so the peer never starts discovery at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PeerForegroundServiceTest {

    @Test
    fun `the network request covers wired as well as wireless local networks`() {
        val request = PeerForegroundService().networkRequest()

        assertTrue("Wi-Fi is the common case", request.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
        assertTrue("Ethernet was missing entirely", request.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
    }

    @Test
    fun `the network request still excludes cellular and VPN`() {
        val request = PeerForegroundService().networkRequest()

        assertFalse(
            "binding Slipstream to a cellular network is exactly what spec §11 layer 3 forbids",
            request.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
        )
        assertTrue(request.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN))
    }
}
