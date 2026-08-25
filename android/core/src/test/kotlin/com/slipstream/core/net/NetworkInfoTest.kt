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
    fun `current returns null when there is no active network and no usable interface`() {
        val shadowCm = shadowOf(connectivityManager)
        shadowCm.setDefaultNetworkActive(false)

        val info = AndroidNetworkInfo(context, interfaces = { emptyList() })

        assertNull(info.current())
    }

    // Spec §5's primary scenario: the phone HOSTS the hotspot. ConnectivityManager's
    // activeNetwork then describes the phone's own uplink (cellular), not the softAP
    // interface a PC client connects through, so the bind address must come from the
    // live interface list instead.

    @Test
    fun `current binds to the hotspot interface, not the ConnectivityManager uplink`() {
        val info = AndroidNetworkInfo(
            context,
            interfaces = {
                listOf(
                    // The phone's cellular uplink — what activeNetwork would report.
                    candidate("rmnet_data0", "10.255.81.4" to 30),
                    // The softAP interface a PC client actually connects through.
                    candidate("wlan1", "10.199.176.137" to 24),
                )
            },
        )

        val local = info.current()

        assertTrue(local != null)
        assertEquals(InetAddress.getByName("10.199.176.137"), local!!.localAddress)
        assertEquals(24, local.prefixLength)
    }

    @Test
    fun `current binds to wlan0 when the phone is a client of an external router`() {
        val info = AndroidNetworkInfo(
            context,
            interfaces = { listOf(candidate("wlan0", "192.168.1.42" to 24)) },
        )

        val local = info.current()

        assertEquals(InetAddress.getByName("192.168.1.42"), local!!.localAddress)
        assertEquals(24, local.prefixLength)
        assertEquals("if:wlan0|192.168.1.0/24", local.key)
    }

    @Test
    fun `selectLocalInterface skips down, loopback and non-local interfaces`() {
        val selected = selectLocalInterface(
            listOf(
                candidate("lo", "127.0.0.1" to 8, isLoopback = true),
                candidate("wlan0", "192.168.1.5" to 24, isUp = false),
                candidate("eth0", "8.8.8.8" to 24),
                candidate("wlan1", "192.168.5.7" to 24),
            ),
        )

        assertEquals(InetAddress.getByName("192.168.5.7"), selected!!.address)
        assertEquals("wlan1", selected.name)
    }

    @Test
    fun `selectLocalInterface prefers a wlan interface over a cellular one`() {
        val selected = selectLocalInterface(
            listOf(
                candidate("rmnet_data0", "10.255.81.4" to 30),
                candidate("wlan1", "10.199.176.137" to 24),
            ),
        )

        assertEquals("wlan1", selected!!.name)
    }

    @Test
    fun `a STA plus AP tie is broken toward the interface this device is serving`() {
        // The device is a Wi-Fi client on wlan0 (192.168.1.42, gateway 192.168.1.1 - some
        // router is serving *it*) while hosting a hotspot on wlan1 (10.199.176.137, no gateway
        // in that subnet, because this device *is* the gateway there). Both tie at the top of
        // the wlan/ap priority band, so without a tie-break the winner is enumeration order.
        // The AP side is the one a PC joining the hotspot can actually reach.
        val selected = selectLocalInterface(
            listOf(
                candidate("wlan0", "192.168.1.42" to 24, index = 3),
                candidate("wlan1", "10.199.176.137" to 24, index = 9),
            ),
            gatewayAddresses = listOf(InetAddress.getByName("192.168.1.1")),
        )

        assertEquals("wlan1", selected!!.name)
        assertEquals(InetAddress.getByName("10.199.176.137"), selected.address)
    }

    @Test
    fun `a tie with no gateway information falls back to the lowest interface index`() {
        // Nothing distinguishes the two sides, so the documented stable key decides. Listed in
        // descending index order on purpose: enumeration order would pick the other one.
        val selected = selectLocalInterface(
            listOf(
                candidate("wlan1", "10.199.176.137" to 24, index = 9),
                candidate("wlan0", "192.168.1.42" to 24, index = 3),
            ),
            gatewayAddresses = emptyList(),
        )

        assertEquals("wlan0", selected!!.name)
    }

    @Test
    fun `the tie-break never outranks the priority band`() {
        // A cellular interface with the lower index must still lose to a wlan one: the
        // tie-break only orders interfaces that already tie on priority.
        val selected = selectLocalInterface(
            listOf(
                candidate("rmnet_data0", "10.255.81.4" to 30, index = 1),
                candidate("wlan1", "10.199.176.137" to 24, index = 9),
            ),
            gatewayAddresses = listOf(InetAddress.getByName("10.255.81.1")),
        )

        assertEquals("wlan1", selected!!.name)
    }

    @Test
    fun `selectLocalInterface returns null when nothing is usable`() {
        assertNull(selectLocalInterface(listOf(candidate("lo", "127.0.0.1" to 8, isLoopback = true))))
    }

    @Test
    fun `buildLocalNetwork from an interface keeps a gateway only when it is in the same subnet`() {
        val sameSubnet = buildLocalNetwork(
            interfaceName = "wlan0",
            localAddress = InetAddress.getByName("192.168.1.42"),
            prefixLength = 24,
            gatewayAddresses = listOf(InetAddress.getByName("192.168.1.1")),
        )
        assertEquals(InetAddress.getByName("192.168.1.1"), sameSubnet!!.gateway)

        // Cellular default route while hosting a hotspot — must not leak in.
        val otherSubnet = buildLocalNetwork(
            interfaceName = "wlan1",
            localAddress = InetAddress.getByName("10.199.176.137"),
            prefixLength = 24,
            gatewayAddresses = listOf(InetAddress.getByName("10.255.81.1")),
        )
        assertNull(otherSubnet!!.gateway)
    }

    private fun candidate(
        name: String,
        vararg addresses: Pair<String, Int>,
        isUp: Boolean = true,
        isLoopback: Boolean = false,
        index: Int = 0,
    ) = InterfaceCandidate(
        name = name,
        isUp = isUp,
        isLoopback = isLoopback,
        index = index,
        addresses = addresses.map { (a, p) -> InetAddress.getByName(a) to p },
    )

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

        assertEquals("cm:42|192.168.1.0/24", local!!.key)
    }

    @Test
    fun `buildLocalNetwork keys differ across networks with the same handle but different subnets`() {
        val a = buildLocalNetwork(42L, listOf(InetAddress.getByName("192.168.1.42") to 24), emptyList())
        val b = buildLocalNetwork(42L, listOf(InetAddress.getByName("10.0.0.5") to 24), emptyList())

        assertTrue(a!!.key != b!!.key)
    }
}
