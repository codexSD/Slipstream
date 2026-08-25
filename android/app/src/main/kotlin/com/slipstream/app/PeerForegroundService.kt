package com.slipstream.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Foreground service that keeps [SlipstreamApplication.peer] running while the app is
 * backgrounded (design.md §14). Low-priority, persistent notification - this is
 * infrastructure, not something the user needs to act on.
 *
 * Also owns the [ConnectivityManager.NetworkCallback] registration: this is the one place
 * that observes network changes and calls [com.slipstream.core.SlipstreamPeer.onNetworkChanged],
 * which is in turn the one place every socket's [com.slipstream.core.net.NetworkBinder] gets
 * updated (spec §11 layer 3).
 */
class PeerForegroundService : Service() {

    private lateinit var connectivityManager: ConnectivityManager
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        val app = application as SlipstreamApplication
        app.peer.start()

        connectivityManager = getSystemService(ConnectivityManager::class.java)
        val callback = buildNetworkCallback(connectivityManager) { app.peer.onNetworkChanged(it) }
        connectivityManager.registerNetworkCallback(networkRequest(), callback)
    }

    /**
     * Ethernet as well as Wi-Fi: a tablet in a USB/Ethernet dock, or a device on a wired LAN
     * alongside the PC that hosts the hotspot (design.md's PC-hosts-hotspot scenario), is a
     * perfectly ordinary Slipstream setup, and with a Wi-Fi-only filter it would never receive
     * a single network callback - so the peer would never start discovery at all.
     *
     * The filter is not dropped entirely: without it, a cellular network becoming available
     * would be reported here and bound to, which is the one thing spec §11 layer 3 exists to
     * prevent. VPNs are excluded for the same reason.
     */
    internal fun networkRequest(): NetworkRequest =
        NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()

    /**
     * The transport filter in [networkRequest] only constrains which networks are *reported* to
     * this callback - it says nothing about [ConnectivityManager.getActiveNetwork], which on a
     * phone with mobile data is very often the cellular network the moment Wi-Fi drops. Handing
     * that straight to `onNetworkChanged` would bind every subsequent socket to cellular, which
     * is precisely what spec §11 layer 3 forbids. So the active network is re-checked against
     * the same rules the request applies, and anything that does not qualify becomes `null` -
     * an explicit "no local network", which [com.slipstream.core.SlipstreamPeer] already handles.
     */
    internal fun qualifiesAsLocalNetwork(cm: ConnectivityManager, network: Network?): Boolean {
        val capabilities = cm.getNetworkCapabilities(network ?: return false) ?: return false
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    /** Internal so a test can drive the real callback (notably [onLost]) without a live service. */
    internal fun buildNetworkCallback(
        cm: ConnectivityManager,
        onNetworkChanged: (Network?) -> Unit,
    ): ConnectivityManager.NetworkCallback {
        // SlipstreamPeer.onNetworkChanged serializes these (they arrive concurrently on the
        // framework's own threads) and ignores a repeat of the network it is already on, which
        // is what keeps the routine onCapabilitiesChanged storm from restarting the servers.
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onNetworkChanged(network)

            override fun onLost(network: Network) {
                val active = cm.activeNetwork
                onNetworkChanged(if (qualifiesAsLocalNetwork(cm, active)) active else null)
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
                onNetworkChanged(network)
        }
        networkCallback = callback
        return callback
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Sticky: the OS should recreate this service (and re-run onCreate, restarting the
        // peer) if it was killed for resources, per design.md §14's background-operation goal.
        return START_STICKY
    }

    override fun onDestroy() {
        networkCallback?.let { connectivityManager.unregisterNetworkCallback(it) }
        (application as SlipstreamApplication).peer.close()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Slipstream background service",
                NotificationManager.IMPORTANCE_MIN,
            )
            channel.description = "Keeps Slipstream reachable from your paired computer."
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Slipstream is running")
            .setContentText("Ready to receive files from your paired computer.")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()

    companion object {
        private const val CHANNEL_ID = "slipstream-peer"
        private const val NOTIFICATION_ID = 1
    }
}
