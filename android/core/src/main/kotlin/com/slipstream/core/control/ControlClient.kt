package com.slipstream.core.control

import com.slipstream.core.identity.DeviceIdentity
import com.slipstream.core.identity.PairedPeerStore
import com.slipstream.core.net.NetworkBinder
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
        binder: NetworkBinder = NetworkBinder.NONE,
    ): ControlConnection {
        val socket = PinnedTls.connect(endpoint, identity, binder) { fingerprint ->
            peerStore.peer?.fingerprint == fingerprint
        }
        val certificate = socket.session.peerCertificates.firstOrNull() as? java.security.cert.X509Certificate
        return ControlConnection(
            socket,
            certificate?.let(com.slipstream.core.identity.Fingerprint::of),
            certificate,
        )
    }
}
