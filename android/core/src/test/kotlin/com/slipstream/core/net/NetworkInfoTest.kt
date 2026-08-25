package com.slipstream.core.net

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class NetworkInfoTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val connectivityManager: ConnectivityManager
        get() = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    @Test
    fun `current returns null when there is no active network`() {
        val shadowCm = shadowOf(connectivityManager)
        shadowCm.setDefaultNetworkActive(false)

        val info = AndroidNetworkInfo(context)

        assertNull(info.current())
    }

    // buildLocalNetwork carries the parsing logic that current() delegates to, kept as a
    // pure function so it can be exercised directly instead of via the framework's
    // hidden-constructor LinkAddress/RouteInfo types (which Robolectric doesn't shadow and
    // the public SDK jar can't construct).

    @Test
    fun `buildLocalNetwork picks the first local IPv4 link address`() {
        val local = buildLocalNetwork(
            networkHandle = 42L,
            linkAddresses = listOf(
                InetAddress.getByName("8.8.8.8") to 24, // not local, skipped
                InetAddress.getByName("192.168.1.42") to 24,
                InetAddress.getByName("192.168.1.99") to 24, // second local address, ignored
            ),
            gatewayAddresses = emptyList(),
        )

        assertTrue(local != null)
        assertEquals(InetAddress.getByName("192.168.1.42"), local!!.localAddress)
        assertEquals(24, local.prefixLength)
    }

    @Test
    fun `buildLocalNetwork returns null when no local IPv4 address is present`() {
        val local = buildLocalNetwork(
            networkHandle = 42L,
            linkAddresses = listOf(InetAddress.getByName("8.8.8.8") to 24),
            gatewayAddresses = emptyList(),
        )

        assertNull(local)
    }

    @Test
    fun `buildLocalNetwork picks the first local IPv4 gateway and ignores non-local ones`() {
        val local = buildLocalNetwork(
            networkHandle = 42L,
            linkAddresses = listOf(InetAddress.getByName("192.168.1.42") to 24),
            gatewayAddresses = listOf(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("192.168.1.1")),
        )

        assertEquals(InetAddress.getByName("192.168.1.1"), local!!.gateway)
    }

    @Test
    fun `buildLocalNetwork returns a null gateway when none is local`() {
        val local = buildLocalNetwork(
            networkHandle = 42L,
            linkAddresses = listOf(InetAddress.getByName("192.168.1.42") to 24),
            gatewayAddresses = listOf(InetAddress.getByName("8.8.8.8")),
        )

        assertTrue(local != null)
        assertNull(local!!.gateway)
    }

    @Test
    fun `buildLocalNetwork derives the key from the network handle and the subnet`() {
        val local = buildLocalNetwork(
            networkHandle = 42L,
            linkAddresses = listOf(InetAddress.getByName("192.168.1.42") to 24),
            gatewayAddresses = emptyList(),
        )

        assertEquals("42|192.168.1.0/24", local!!.key)
    }

    @Test
    fun `buildLocalNetwork keys differ across networks with the same handle but different subnets`() {
        val a = buildLocalNetwork(42L, listOf(InetAddress.getByName("192.168.1.42") to 24), emptyList())
        val b = buildLocalNetwork(42L, listOf(InetAddress.getByName("10.0.0.5") to 24), emptyList())

        assertTrue(a!!.key != b!!.key)
    }
}
