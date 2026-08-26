package com.slipstream.app

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import com.slipstream.core.SlipstreamPeer
import com.slipstream.core.discovery.AndroidMulticastLock
import com.slipstream.core.discovery.EndpointCache
import com.slipstream.core.discovery.MulticastLockHandle
import com.slipstream.core.discovery.NoopMulticastLock
import com.slipstream.core.identity.DeviceIdentity
import com.slipstream.core.identity.PairedPeerStore
import com.slipstream.core.net.AndroidNetworkInfo
import com.slipstream.app.peer.ForwardingClipboardSink
import com.slipstream.app.peer.PeerController
import com.slipstream.app.peer.RealPeerController
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Owns the single, process-lifetime [SlipstreamPeer] instance. An [Application] subclass is
 * used (rather than holding it on an `Activity` or `Service`) specifically so it survives
 * configuration changes and is reachable from both [MainActivity] and [PeerForegroundService]
 * without either one owning its lifecycle.
 *
 * The assembly itself lives in [PeerWiring] so it can be tested directly; this class only
 * supplies the Android-specific collaborators.
 */
class SlipstreamApplication : Application() {

    /** The forwarding half of the clipboard bridge, shared between [SlipstreamPeer] (which
     * writes incoming peer clipboard text into it) and [peerController] (which exposes that
     * same stream as [PeerController.clipboardReceived]). [onCreate] also collects it to mirror
     * incoming text into the system clipboard, preserving the previous direct-write behaviour. */
    private val clipboardSink = ForwardingClipboardSink()

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Built once and shared by [peer] and [peerController] so they agree on identity, peer
     * store, and network binder - see [PeerWiring]'s class doc. */
    private val wiring: PeerWiring by lazy { buildWiring() }

    val peer: SlipstreamPeer by lazy { wiring.peer() }

    /** The sole `:core` access point for the UI (Task 2.5), backed by the same [peer] this
     * class already owns. Mirrors the [peer] pattern: an application-scoped lazy singleton,
     * reachable from [MainActivity] via `(application as SlipstreamApplication).peerController`. */
    val peerController: PeerController by lazy {
        RealPeerController(
            peer = peer,
            identity = wiring.identity,
            peerStore = wiring.peerStore,
            clipboardSink = clipboardSink,
        )
    }

    override fun onCreate() {
        super.onCreate()
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        appScope.launch {
            clipboardSink.received.collect { text ->
                clipboard.setPrimaryClip(ClipData.newPlainText("Slipstream", text))
            }
        }
    }

    /** Internal (rather than folded into [peer]) so a test can assert what the *production*
     * path actually assembles - in particular that discovery gets a real multicast lock. */
    internal fun buildWiring(): PeerWiring {
        val storageDir = File(filesDir, "slipstream")
        val identity = DeviceIdentity.loadOrCreate(storageDir, displayName = Build.MODEL ?: "Android Device")

        return PeerWiring(
            identity = identity,
            peerStore = PairedPeerStore(storageDir),
            networkInfo = AndroidNetworkInfo(this),
            endpointCache = EndpointCache(storageDir),
            rootDirectory = getExternalFilesDir(null) ?: filesDir,
            clipboardSink = clipboardSink,
            multicastLock = androidMulticastLock(),
        )
    }

    /**
     * The real `WifiManager.MulticastLock`. The manifest already requests
     * `CHANGE_WIFI_MULTICAST_STATE`; without actually acquiring a lock most Wi-Fi drivers
     * filter multicast before it ever reaches the app, so S3 discovery never worked on
     * hardware.
     */
    private fun androidMulticastLock(): MulticastLockHandle {
        val wifiManager = applicationContext.getSystemService(WifiManager::class.java)
            ?: return NoopMulticastLock
        return AndroidMulticastLock(wifiManager)
    }
}
