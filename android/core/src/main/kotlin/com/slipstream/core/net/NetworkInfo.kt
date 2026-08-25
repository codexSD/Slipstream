package com.slipstream.core.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import java.net.Inet4Address
import java.net.InetAddress

/**
 * The active local network. [key] is the cache key used by discovery strategy S1 —
 * stable for a given network, distinct across networks.
 */
data class LocalNetwork(
    val localAddress: InetAddress,
    val gateway: InetAddress?,
    val prefixLength: Int,
    val key: String,
)

interface NetworkInfo {
    fun current(): LocalNetwork?
}

/**
 * Reads the active [LinkProperties] from [ConnectivityManager]: the first local IPv4
 * link address, the default route's gateway, and the prefix length.
 */
class AndroidNetworkInfo internal constructor(
    private val context: Context,
    private val interfaces: () -> List<InterfaceCandidate>,
) : NetworkInfo {

    constructor(context: Context) : this(context, ::liveInterfaceCandidates)

    override fun current(): LocalNetwork? {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

        selectLocalInterface(interfaces())?.let { selected ->
            return buildLocalNetwork(
                interfaceName = selected.name,
                localAddress = selected.address,
                prefixLength = selected.prefixLength,
                gatewayAddresses = connectivityManager?.activeGateways().orEmpty(),
            )
        }

        // No usable live interface: fall back to whatever ConnectivityManager calls active.
        val network = connectivityManager?.activeNetwork ?: return null
        val properties = connectivityManager.getLinkProperties(network) ?: return null

        return buildLocalNetwork(
            networkHandle = network.networkHandle,
            linkAddresses = properties.linkAddresses.map { it.address to it.prefixLength },
            gatewayAddresses = properties.routes.mapNotNull { it.gateway },
        )
    }

    /** Best-effort gateway lookup; never load-bearing for the bind address. */
    private fun ConnectivityManager.activeGateways(): List<InetAddress> =
        runCatching {
            val network = activeNetwork ?: return emptyList()
            getLinkProperties(network)?.routes?.mapNotNull { it.gateway }.orEmpty()
        }.getOrDefault(emptyList())
}

/**
 * One live network interface reduced to plain data: everything the selection logic needs,
 * and nothing that requires a real [java.net.NetworkInterface] to fake in a unit test.
 */
internal data class InterfaceCandidate(
    val name: String,
    val isUp: Boolean,
    val isLoopback: Boolean,
    val addresses: List<Pair<InetAddress, Int>>,
)

/** The interface and address [selectLocalInterface] settled on. */
internal data class SelectedInterface(
    val name: String,
    val address: InetAddress,
    val prefixLength: Int,
)

/**
 * Snapshot of the device's live interfaces. Reads what is actually UP and addressable,
 * which — unlike [ConnectivityManager.getActiveNetwork] — includes the softAP interface
 * when this device is the one hosting the hotspot.
 */
internal fun liveInterfaceCandidates(): List<InterfaceCandidate> =
    try {
        java.net.NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().map { nif ->
            InterfaceCandidate(
                name = nif.name.orEmpty(),
                isUp = runCatching { nif.isUp }.getOrDefault(false),
                isLoopback = runCatching { nif.isLoopback }.getOrDefault(false),
                addresses = nif.interfaceAddresses.mapNotNull { ia ->
                    ia.address?.let { it to ia.networkPrefixLength.toInt() }
                },
            )
        }
    } catch (e: java.net.SocketException) {
        emptyList()
    }

/**
 * Pure candidate selection: the first local IPv4 address on an interface that is up and
 * not loopback, preferring WiFi/AP/ethernet-style interfaces over cellular uplinks.
 */
internal fun selectLocalInterface(candidates: List<InterfaceCandidate>): SelectedInterface? =
    candidates
        .filter { it.isUp && !it.isLoopback }
        .sortedBy { interfacePriority(it.name) }
        .firstNotNullOfOrNull { candidate ->
            candidate.addresses
                .firstOrNull { (address, _) ->
                    address is Inet4Address && !address.isLoopbackAddress && LanGuard.isLocal(address)
                }
                ?.let { (address, prefix) -> SelectedInterface(candidate.name, address, prefix) }
        }

/** Lower sorts first. WiFi/AP/tether interfaces beat anything; cellular uplinks lose. */
private fun interfacePriority(name: String): Int {
    val n = name.lowercase()
    return when {
        n.startsWith("wlan") || n.startsWith("swlan") || n.startsWith("ap") -> 0
        n.startsWith("eth") || n.startsWith("rndis") || n.startsWith("usb") -> 1
        n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("pdp") -> 3
        else -> 2
    }
}

/**
 * Pure assembly of a [LocalNetwork] from the raw pieces [AndroidNetworkInfo] pulls out of
 * [LinkProperties]. Separated from the [ConnectivityManager] plumbing so it can be tested
 * directly, without needing to construct the framework's hidden-constructor `LinkAddress`
 * and `RouteInfo` types.
 */
internal fun buildLocalNetwork(
    networkHandle: Long,
    linkAddresses: List<Pair<InetAddress, Int>>,
    gatewayAddresses: List<InetAddress>,
): LocalNetwork? {
    val (localAddress, prefixLength) = linkAddresses.firstOrNull { (address, _) ->
        address is Inet4Address && LanGuard.isLocal(address)
    } ?: return null

    val gateway = gatewayAddresses.firstOrNull { it is Inet4Address && LanGuard.isLocal(it) }

    val networkAddress = SubnetMath.networkAddress(localAddress, prefixLength)
    val key = "$networkHandle|${networkAddress.hostAddress}/$prefixLength"

    return LocalNetwork(localAddress, gateway, prefixLength, key)
}

/**
 * Pure assembly of a [LocalNetwork] from a selected live interface. The gateway is
 * best-effort: only accepted when it actually sits inside the selected subnet, since
 * [ConnectivityManager]'s active network may describe a different link entirely.
 */
internal fun buildLocalNetwork(
    interfaceName: String,
    localAddress: InetAddress,
    prefixLength: Int,
    gatewayAddresses: List<InetAddress>,
): LocalNetwork? {
    if (localAddress !is Inet4Address) return null

    val networkAddress = SubnetMath.networkAddress(localAddress, prefixLength)
    val gateway = gatewayAddresses.firstOrNull {
        it is Inet4Address &&
            LanGuard.isLocal(it) &&
            SubnetMath.networkAddress(it, prefixLength) == networkAddress
    }

    val key = "$interfaceName|${networkAddress.hostAddress}/$prefixLength"
    return LocalNetwork(localAddress, gateway, prefixLength, key)
}

/**
 * Host addresses in a subnet, excluding network and broadcast. Yields nothing for
 * anything wider than a /24 — spec §5 S4 bounds the sweep, and 65 000 concurrent
 * sockets is a denial of service against your own machine.
 */
object SubnetMath {
    fun enumerateHosts(address: InetAddress, prefixLength: Int): Sequence<InetAddress> {
        if (address !is Inet4Address) return emptySequence()
        if (prefixLength < 24 || prefixLength > 30) return emptySequence()

        val value = toUInt(address.address)
        val mask = maskFor(prefixLength)
        val network = value and mask
        val broadcast = network or mask.inv()

        return generateSequence(network + 1u) { it + 1u }
            .takeWhile { it < broadcast }
            .map { InetAddress.getByAddress(toBytes(it)) }
    }

    internal fun networkAddress(address: InetAddress, prefixLength: Int): InetAddress {
        require(address is Inet4Address) { "Only IPv4 addresses are supported" }
        val mask = maskFor(prefixLength)
        val network = toUInt(address.address) and mask
        return InetAddress.getByAddress(toBytes(network))
    }

    private fun maskFor(prefixLength: Int): UInt =
        if (prefixLength == 0) 0u else (UInt.MAX_VALUE shl (32 - prefixLength))

    private fun toUInt(bytes: ByteArray): UInt =
        (bytes[0].toUInt() and 0xFFu shl 24) or
            (bytes[1].toUInt() and 0xFFu shl 16) or
            (bytes[2].toUInt() and 0xFFu shl 8) or
            (bytes[3].toUInt() and 0xFFu)

    private fun toBytes(value: UInt): ByteArray = byteArrayOf(
        (value shr 24 and 0xFFu).toByte(),
        (value shr 16 and 0xFFu).toByte(),
        (value shr 8 and 0xFFu).toByte(),
        (value and 0xFFu).toByte(),
    )
}
