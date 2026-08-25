package com.slipstream.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import com.slipstream.core.net.MutableNetworkBinder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities

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

    /**
     * The NetworkRequest's transport filter says nothing about `activeNetwork`: on a phone with
     * mobile data, the moment Wi-Fi drops the active network *is* cellular. Forwarding it from
     * `onLost` would bind every later socket to cellular - exactly what spec §11 layer 3 forbids.
     */
    @Test
    fun `losing wifi never binds the peer to the cellular active network`() {
        val cm = ApplicationProvider.getApplicationContext<Context>()
            .getSystemService(ConnectivityManager::class.java)
        val shadow = shadowOf(cm)

        val wifi = ShadowNetwork.newInstance(41)
        shadow.setNetworkCapabilities(wifi, capabilities(NetworkCapabilities.TRANSPORT_WIFI))

        // Mobile data carries on after Wi-Fi drops, so activeNetwork *is* the cellular network.
        val cellular = requireNotNull(cm.activeNetwork)
        shadow.setNetworkCapabilities(cellular, capabilities(NetworkCapabilities.TRANSPORT_CELLULAR))
        assertTrue(
            "precondition: the device's active network must really be the cellular one",
            cm.getNetworkCapabilities(cm.activeNetwork)!!
                .hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
        )

        // Stand-in for the peer: the binder is the thing onNetworkChanged actually writes, and
        // the thing every subsequent socket is scoped to.
        val binder = MutableNetworkBinder()
        val callback = PeerForegroundService().buildNetworkCallback(cm) { binder.network = it }

        callback.onAvailable(wifi)
        assertTrue(binder.network === wifi)

        callback.onLost(wifi)

        assertNotEquals("cellular must never become the bound network", cellular, binder.network)
        assertNull("with no qualifying local network left, the peer must be told 'no network'", binder.network)
    }

    @Test
    fun `losing one wifi network still binds to a remaining wifi active network`() {
        val cm = ApplicationProvider.getApplicationContext<Context>()
            .getSystemService(ConnectivityManager::class.java)
        val shadow = shadowOf(cm)

        val lost = ShadowNetwork.newInstance(51)
        val other = requireNotNull(cm.activeNetwork)
        shadow.setNetworkCapabilities(other, capabilities(NetworkCapabilities.TRANSPORT_WIFI))

        val binder = MutableNetworkBinder()
        PeerForegroundService().buildNetworkCallback(cm) { binder.network = it }.onLost(lost)

        assertEquals("a qualifying local network must still be adopted", other, binder.network)
    }

    private fun capabilities(transport: Int): NetworkCapabilities =
        shadowOf(ShadowNetworkCapabilities.newInstance()).let { shadowCaps ->
            shadowCaps.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            shadowCaps.addTransportType(transport)
        }
}
