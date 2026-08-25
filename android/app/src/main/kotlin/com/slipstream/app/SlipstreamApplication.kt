package com.slipstream.app

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import com.slipstream.core.SlipstreamPeer
import com.slipstream.core.control.ClipboardSink
import com.slipstream.core.discovery.EndpointCache
import com.slipstream.core.identity.DeviceIdentity
import com.slipstream.core.identity.PairedPeerStore
import com.slipstream.core.net.AndroidNetworkInfo
import java.io.File

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

    val peer: SlipstreamPeer by lazy { buildPeer() }

    private fun buildPeer(): SlipstreamPeer {
        val storageDir = File(filesDir, "slipstream")
        val identity = DeviceIdentity.loadOrCreate(storageDir, displayName = Build.MODEL ?: "Android Device")

        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        return PeerWiring(
            identity = identity,
            peerStore = PairedPeerStore(storageDir),
            networkInfo = AndroidNetworkInfo(this),
            endpointCache = EndpointCache(storageDir),
            rootDirectory = getExternalFilesDir(null) ?: filesDir,
            clipboardSink = ClipboardSink { text ->
                clipboard.setPrimaryClip(ClipData.newPlainText("Slipstream", text))
            },
        ).peer()
    }
}
