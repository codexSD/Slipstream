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
class AndroidNetworkInfo(private val context: Context) : NetworkInfo {

    override fun current(): LocalNetwork? {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null

        val network = connectivityManager.activeNetwork ?: return null
        val properties = connectivityManager.getLinkProperties(network) ?: return null

        return buildLocalNetwork(
            networkHandle = network.networkHandle,
            linkAddresses = properties.linkAddresses.map { it.address to it.prefixLength },
            gatewayAddresses = properties.routes.mapNotNull { it.gateway },
        )
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
