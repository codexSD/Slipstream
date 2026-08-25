package com.slipstream.core

import java.net.InetAddress

/**
 * Fixed ports and protocol version for the Slipstream LAN protocol. Must match the
 * C# side exactly, or the two ends of a pairing will fail to speak to each other.
 */
object SlipstreamPorts {
    const val DISCOVERY = 53320
    const val CONTROL = 53321
    const val BULK = 53322
    const val MEDIA = 53323
    const val PROTOCOL_VERSION = 1

    // Constrained to 224.0.0.0/24 — some Android devices reject other groups.
    val MULTICAST_GROUP: InetAddress = InetAddress.getByName("224.0.0.167")
}
