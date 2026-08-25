package com.slipstream.app

import android.app.Application
import com.slipstream.core.SlipstreamPeer
import com.slipstream.core.control.ClipboardSink
import com.slipstream.core.discovery.CachedEndpointStrategy
import com.slipstream.core.discovery.DiscoveryCoordinator
import com.slipstream.core.discovery.EndpointCache
import com.slipstream.core.discovery.GatewayProbeStrategy
import com.slipstream.core.discovery.MulticastStrategy
import com.slipstream.core.discovery.PeerProbe
import com.slipstream.core.discovery.SubnetSweepStrategy
import com.slipstream.core.identity.DeviceIdentity
import com.slipstream.core.identity.PairedPeerStore
import com.slipstream.core.net.AndroidNetworkInfo
import java.io.File
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build

/**
 * Owns the single, process-lifetime [SlipstreamPeer] instance. An [Application] subclass is
 * used (rather than holding it on an `Activity` or `Service`) specifically so it survives
 * configuration changes and is reachable from both [MainActivity] and [PeerForegroundService]
 * without either one owning its lifecycle.
 */
class SlipstreamApplication : Application() {

    val peer: SlipstreamPeer by lazy { buildPeer() }

    private fun buildPeer(): SlipstreamPeer {
        val storageDir = File(filesDir, "slipstream")
        val identity = DeviceIdentity.loadOrCreate(storageDir, displayName = Build.MODEL ?: "Android Device")
        val peerStore = PairedPeerStore(storageDir)
        val networkInfo = AndroidNetworkInfo(this)
        val endpointCache = EndpointCache(storageDir)
        val downloadsDir = getExternalFilesDir(null) ?: filesDir

        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clipboardSink = ClipboardSink { text ->
            clipboard.setPrimaryClip(ClipData.newPlainText("Slipstream", text))
        }

        return SlipstreamPeer(
            identity = identity,
            peerStore = peerStore,
            networkInfo = networkInfo,
            rootDirectory = downloadsDir,
            clipboardSink = clipboardSink,
            discoveryCoordinatorFactory = {
                val probe = PeerProbe { endpoint ->
                    // A real probe = a real pinned-TLS handshake attempt; anything short of
                    // that (e.g. a bare TCP connect) can't tell a Slipstream peer from any
                    // other listener on that port.
                    try {
                        com.slipstream.core.control.PinnedTls.connect(endpoint, identity) { fingerprint ->
                            peerStore.peer?.fingerprint == fingerprint
                        }.use {
                            com.slipstream.core.discovery.DiscoveredPeer(endpoint)
                        }
                    } catch (e: Exception) {
                        null
                    }
                }
                DiscoveryCoordinator(
                    networkInfo = networkInfo,
                    cache = endpointCache,
                    strategies = listOf(
                        CachedEndpointStrategy(endpointCache, probe),
                        GatewayProbeStrategy(probe),
                        MulticastStrategy(identity, peerStore, probe),
                        SubnetSweepStrategy(probe),
                    ),
                )
            },
        )
    }
}
