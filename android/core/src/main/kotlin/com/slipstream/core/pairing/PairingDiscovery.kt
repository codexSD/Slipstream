package com.slipstream.core.pairing

import com.slipstream.core.SlipstreamPorts
import com.slipstream.core.discovery.MulticastLockHandle
import com.slipstream.core.discovery.MulticastTransport
import com.slipstream.core.discovery.NoopMulticastLock
import com.slipstream.core.discovery.PeerAnnouncement
import com.slipstream.core.discovery.UdpMulticastTransport
import com.slipstream.core.identity.DeviceIdentity
import com.slipstream.core.net.LanGuard
import java.net.InetSocketAddress
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.withTimeoutOrNull

/** A candidate peer found by [PairingDiscovery]: address-only plus what the (untrusted,
 * unverified) announcement claims about itself. TLS + [PairingCoordinator] are what
 * actually verify anything. */
data class PairingCandidate(
    val deviceId: String,
    val name: String,
    val fingerprint: String,
    val endpoint: InetSocketAddress,
)

/**
 * pairing.md §6: while a [PairingWindow] is open, listens for peer announcements from
 * *any* device - not just already-paired ones, since finding an unpaired peer is the whole
 * point of pairing discovery. Outside an open window, [find] returns null immediately
 * without listening at all: no socket is opened, no lock is acquired.
 *
 * Still applies spec §11 LAN-only filtering ([LanGuard]) and never surfaces the local
 * device's own announcement.
 */
class PairingDiscovery(
    private val identity: DeviceIdentity,
    private val window: PairingWindow,
    private val transportFactory: () -> MulticastTransport = { UdpMulticastTransport() },
    private val multicastLock: MulticastLockHandle = NoopMulticastLock,
) {
    suspend fun find(timeout: Duration = 5.seconds): PairingCandidate? {
        if (!window.isOpen) return null

        multicastLock.acquire()
        val transport = transportFactory()
        try {
            val query = PeerAnnouncement(
                v = SlipstreamPorts.PROTOCOL_VERSION,
                deviceId = identity.deviceId,
                name = identity.displayName,
                fingerprint = identity.fingerprint,
                control = SlipstreamPorts.CONTROL,
                kind = "query",
            )
            transport.send(
                query.toJson().toByteArray(Charsets.UTF_8),
                InetSocketAddress(SlipstreamPorts.MULTICAST_GROUP, SlipstreamPorts.DISCOVERY),
            )

            return withTimeoutOrNull(timeout) {
                var found: PairingCandidate? = null
                while (found == null && window.isOpen) {
                    val message = transport.receive()
                    if (!LanGuard.isLocal(message.sender.address)) continue

                    val announcement = PeerAnnouncement.tryParse(String(message.payload, Charsets.UTF_8)) ?: continue
                    if (announcement.kind != "announce") continue
                    if (announcement.deviceId == identity.deviceId) continue

                    found = PairingCandidate(
                        deviceId = announcement.deviceId,
                        name = announcement.name,
                        fingerprint = announcement.fingerprint,
                        endpoint = InetSocketAddress(message.sender.address, announcement.control),
                    )
                }
                found
            }
        } finally {
            transport.close()
            multicastLock.release()
        }
    }
}
