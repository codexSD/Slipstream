package com.slipstream.core.net

import android.net.Network
import java.net.DatagramSocket
import java.net.Socket

/**
 * Binds a socket to a specific network, per spec §11 layer 3: the guarantee that Slipstream's
 * LAN traffic cannot route over a different network path (e.g. cellular) even when the OS
 * would otherwise pick a route to it. [Network.bindSocket] is the modern, non-deprecated API
 * for this (as opposed to the process-wide, deprecated `setProcessDefaultNetwork`), so binding
 * happens per-socket, at the point each socket used for Slipstream traffic is created - never
 * as a process-wide default that could leak to unrelated sockets.
 *
 * [NONE] is the default everywhere a caller doesn't have (or doesn't yet have, e.g. in tests
 * or before the app has an active [Network] reference) a concrete network to bind to.
 */
interface NetworkBinder {
    fun bind(socket: Socket)
    fun bind(socket: DatagramSocket)

    companion object {
        val NONE: NetworkBinder = object : NetworkBinder {
            override fun bind(socket: Socket) = Unit
            override fun bind(socket: DatagramSocket) = Unit
        }
    }
}

/**
 * A [NetworkBinder] whose target [Network] can be swapped out at runtime - this is what lets
 * [com.slipstream.core.SlipstreamPeer] update every *future* socket's binding the moment
 * [ConnectivityManager.NetworkCallback] reports a network change, without having to rebuild
 * every collaborator that was handed a [NetworkBinder] reference at construction time.
 *
 * Binding failures (e.g. the network having gone away between selection and bind) are
 * swallowed rather than propagated: a socket that fails to bind still works, just without the
 * layer-3 guarantee for that one attempt, and the caller's own retry/reconnect logic is a far
 * better place to surface that than a bind() call deep inside socket construction.
 */
class MutableNetworkBinder : NetworkBinder {
    @Volatile
    var network: Network? = null

    override fun bind(socket: Socket) {
        try {
            network?.bindSocket(socket)
        } catch (e: Exception) {
            // Best-effort; see class doc.
        }
    }

    override fun bind(socket: DatagramSocket) {
        try {
            network?.bindSocket(socket)
        } catch (e: Exception) {
            // Best-effort; see class doc.
        }
    }
}
