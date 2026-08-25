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
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = app.peer.onNetworkChanged(network)
            override fun onLost(network: Network) = app.peer.onNetworkChanged(connectivityManager.activeNetwork)
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
                app.peer.onNetworkChanged(network)
        }
        networkCallback = callback
        connectivityManager.registerNetworkCallback(request, callback)
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
