package com.slipstream.core.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
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

    /**
     * Whether [local] is known to sit on [network]'s own link - the question
     * [com.slipstream.core.SlipstreamPeer.onNetworkChanged] has to answer before it binds
     * servers to the address a network change just implied.
     *
     * Deliberately three-valued:
     *  - `true`  - attested match; the address genuinely belongs to the reported network.
     *  - `false` - attested mismatch; the address belongs to some *other* link, which is
     *              exactly the stale-address case a naive "is there any address?" gate binds.
     *  - `null`  - cannot tell. This is not an error state: the interface a device hosts its
     *              own hotspot on is never surfaced by `ConnectivityManager`, so the AP address
     *              the hotspot fix exists to select can never be attested against a `Network`.
     *
     * The default is `null` so a [NetworkInfo] that has no framework to ask (every test fake,
     * and any future non-Android implementation) simply declines to attest.
     */
    fun attestBelongsTo(network: Network, local: LocalNetwork): Boolean? = null
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

        // The active network's gateways do double duty: they tell [selectLocalInterface] which
        // of two tied interfaces this device is a *client* on (see its doc), and they supply
        // the selected network's own gateway when one of them turns out to be in its subnet.
        val gatewayAddresses = connectivityManager?.activeGateways().orEmpty()

        selectLocalInterface(interfaces(), gatewayAddresses)?.let { selected ->
            return buildLocalNetwork(
                interfaceName = selected.name,
                localAddress = selected.address,
                prefixLength = selected.prefixLength,
                gatewayAddresses = gatewayAddresses,
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

    /**
     * Answers from [LinkProperties], the only place the framework describes a specific
     * [Network]'s own link. `null` ("cannot tell") is returned generously - whenever the
     * framework has nothing to say about this network, or says nothing about IPv4 local
     * addresses at all - because a device hosting a hotspot gets no `LinkProperties` for its
     * own AP interface, and a hard "no" there would refuse to bind the very address the
     * live-interface selection above exists to find.
     */
    override fun attestBelongsTo(network: Network, local: LocalNetwork): Boolean? {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return null
        val properties = runCatching { connectivityManager.getLinkProperties(network) }
            .getOrNull() ?: return null

        val linkAddresses = properties.linkAddresses.map { it.address }
        if (linkAddresses.any { it == local.localAddress }) return true

        // A definite "no" only when this network does describe a usable IPv4 local link of its
        // own and [local] is not on it - i.e. the address genuinely belongs somewhere else.
        val describesLocalIpv4 =
            linkAddresses.any { it is Inet4Address && LanGuard.isLocal(it) }
        return if (describesLocalIpv4) false else null
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
    /** The kernel's interface index. Only ever used as the last-resort tie-break key in
     * [selectLocalInterface]; defaults to 0 for candidates that cannot report one. */
    val index: Int = 0,
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
                index = runCatching { nif.index }.getOrDefault(0),
            )
        }
    } catch (e: java.net.SocketException) {
        emptyList()
    }

/**
 * Pure candidate selection: the first local IPv4 address on an interface that is up and
 * not loopback, preferring WiFi/AP/ethernet-style interfaces over cellular uplinks.
 *
 * Interfaces that tie on that name priority — STA+AP concurrency, where the device is a Wi-Fi
 * client on one `wlan` interface and hosting a hotspot on another — are ordered by two further
 * keys, in this order:
 *
 *  1. **Serving beats client.** An interface whose subnet contains one of [gatewayAddresses]
 *     is one where some *other* box is the gateway, i.e. this device is a client on it. An
 *     interface with no gateway in its subnet is one this device is itself serving — the AP
 *     side, and the only side a PC that joined the hotspot can reach. Spec §5's primary
 *     scenario is "the phone is the PC's default gateway", so the AP side wins.
 *  2. **Lowest interface index.** A documented, stable, reproducible key for when nothing
 *     above separates them — as opposed to enumeration order, which is merely whatever the
 *     kernel happened to hand back.
 *
 * The tie-break never outranks the priority band: it only orders interfaces already equal on it.
 *
 * NOTE: this path is unit-tested with synthetic interfaces but **not hardware-verified** in an
 * actual STA+AP configuration — no such device was available. The single-interface cases it
 * shares code with *are* hardware-verified (commit `0f7c662`).
 */
internal fun selectLocalInterface(
    candidates: List<InterfaceCandidate>,
    gatewayAddresses: List<InetAddress> = emptyList(),
): SelectedInterface? =
    candidates
        .filter { it.isUp && !it.isLoopback }
        .mapNotNull { candidate ->
            candidate.addresses
                .firstOrNull { (address, _) ->
                    address is Inet4Address && !address.isLoopbackAddress && LanGuard.isLocal(address)
                }
                ?.let { (address, prefix) -> candidate to SelectedInterface(candidate.name, address, prefix) }
        }
        .minWithOrNull(
            compareBy(
                { (candidate, _) -> interfacePriority(candidate.name) },
                { (_, selected) -> if (isServedByAGateway(selected, gatewayAddresses)) 1 else 0 },
                { (candidate, _) -> candidate.index },
            ),
        )
        ?.second

/**
 * Whether one of [gatewayAddresses] sits inside [selected]'s own subnet — the signature of an
 * interface on which this device is a *client* of someone else's router, rather than the one
 * serving the subnet.
 */
private fun isServedByAGateway(
    selected: SelectedInterface,
    gatewayAddresses: List<InetAddress>,
): Boolean {
    val networkAddress = runCatching {
        SubnetMath.networkAddress(selected.address, selected.prefixLength)
    }.getOrNull() ?: return false

    return gatewayAddresses.any { gateway ->
        gateway is Inet4Address &&
            runCatching { SubnetMath.networkAddress(gateway, selected.prefixLength) }
                .getOrNull() == networkAddress
    }
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
    val key = "cm:$networkHandle|${networkAddress.hostAddress}/$prefixLength"

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

    // Namespaced ("if:") so it can never collide with the ConnectivityManager path's key
    // ("cm:") for the same subnet - the same physical network flipping between the two code
    // paths would otherwise invalidate its own cache entry.
    //
    // Tradeoff, deliberately accepted: within this path the key is only as distinct as
    // interface name + subnet, so two different physical networks that share both (two
    // routers each handing out 192.168.1.x on wlan0) collide. Nothing more stable is
    // available here - the interface's MAC is unreadable to apps since API 24, and the local
    // address itself changes on every DHCP lease, which would invalidate the entry far more
    // often than a collision costs. A collision is harmless beyond a wasted fast path: S1's
    // cached endpoint is still verified by a pinned-TLS probe before it is used, so a stale
    // hit fails that probe and discovery falls through to the other strategies.
    val key = "if:$interfaceName|${networkAddress.hostAddress}/$prefixLength"
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
