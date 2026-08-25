package com.slipstream.core.control

import com.slipstream.core.identity.DeviceIdentity
import com.slipstream.core.identity.PairedPeerStore
import java.net.InetSocketAddress

/**
 * Client side of the control channel: connects to [endpoint] over pinned TLS, accepting the
 * server's certificate only if it matches the currently paired peer's fingerprint.
 */
object ControlClient {
    fun connect(
        endpoint: InetSocketAddress,
        identity: DeviceIdentity,
        peerStore: PairedPeerStore,
    ): ControlConnection {
        val socket = PinnedTls.connect(endpoint, identity) { fingerprint ->
            peerStore.peer?.fingerprint == fingerprint
        }
        return ControlConnection(socket)
    }
}
