package com.slipstream.app

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.FileProvider
import com.slipstream.core.SlipstreamPeer
import com.slipstream.core.discovery.AndroidMulticastLock
import com.slipstream.core.discovery.EndpointCache
import com.slipstream.core.discovery.MulticastLockHandle
import com.slipstream.core.discovery.NoopMulticastLock
import com.slipstream.core.files.FileBrowser
import com.slipstream.core.identity.DeviceIdentity
import com.slipstream.core.identity.PairedPeerStore
import com.slipstream.core.net.AndroidNetworkInfo
import com.slipstream.app.peer.ForwardingClipboardSink
import com.slipstream.app.peer.ForwardingPlaySink
import com.slipstream.app.peer.PeerController
import com.slipstream.app.peer.PlayRequest
import com.slipstream.app.peer.RealPeerController
import com.slipstream.app.peer.SettingsStore
import com.slipstream.app.peer.TransferQueue
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
     * incoming text into the system clipboard, preserving the previous direct-write behaviour,
     * and (Task 12) to post a notification for it. Internal (rather than private), same as
     * [playSink], so a test can emit into it directly without a full end-to-end peer. */
    internal val clipboardSink = ForwardingClipboardSink()

    /** The forwarding half of the push-to-play bridge (Task 11), same role as [clipboardSink]
     * but for inbound `play` messages — see [ForwardingPlaySink]'s doc. [onCreate] collects it
     * and launches `ACTION_VIEW`. Internal (rather than private) so a test can emit a
     * [PlayRequest] directly and observe [launchPlayback]'s resulting `Intent`, the same
     * precedent [buildWiring] already sets for testing this class's Android-specific wiring
     * without a full end-to-end peer. */
    internal val playSink = ForwardingPlaySink()

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
            playSink = playSink,
        )
    }

    /** User settings: parallel stream count, download folder, theme, battery exemption status. */
    val settingsStore: SettingsStore by lazy {
        SettingsStore(this)
    }

    /** Manages queued file transfers: serial execution, failure resilience, progress throttling.
     * Exposed as an application-scoped lazy singleton so the Transfers screen can observe live
     * queue state via [TransferQueue.activeTransfersState]. Reachable from any composable via
     * `(LocalContext.current.applicationContext as SlipstreamApplication).transferQueue`. */
    val transferQueue: TransferQueue by lazy { TransferQueue() }

    override fun onCreate() {
        super.onCreate()
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        appScope.launch {
            clipboardSink.received.collect { text ->
                clipboard.setPrimaryClip(ClipData.newPlainText("Slipstream", text))
                postClipboardNotification(this@SlipstreamApplication, text)
            }
        }
        appScope.launch {
            playSink.received.collect { request -> launchPlayback(request) }
        }
    }

    /**
     * Task 11 (design.md §8, push-to-play, inbound direction): opens the system default player
     * via `ACTION_VIEW` for a `play` message the peer just sent. Runs outside any `Activity`
     * context (this is an [Application]-scoped collector, same as the clipboard one above), so
     * [Intent.FLAG_ACTIVITY_NEW_TASK] is required.
     *
     * [PlayRequest.LocalFile] needs a `content://` URI rather than a bare `file://` one — Android
     * has refused `file://` URIs handed to another app (`FileUriExposedException`) since API 24 -
     * so it goes through the [FileProvider] declared in the manifest, granting the resolved
     * player app read access for exactly this one URI.
     */
    private fun launchPlayback(request: PlayRequest) {
        val (uri, mime) = when (request) {
            is PlayRequest.LocalFile -> {
                val authority = "$packageName.fileprovider"
                FileProvider.getUriForFile(this, authority, request.file) to request.mime
            }
            is PlayRequest.RemoteUrl -> Uri.parse(request.url) to request.mime
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (e: android.content.ActivityNotFoundException) {
            // No app on this device can open the file/URL - nothing more this collector can do;
            // silently dropping mirrors clipboard's own "over the cap? drop it" discipline for a
            // fire-and-forget inbound event with no reply channel back to the peer.
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
            onPlayRequested = { file -> playSink.onLocalFile(file, FileBrowser.mimeFor(file.name)) },
            onPlayUrlRequested = { url, mime -> playSink.onRemoteUrl(url, mime ?: "video/*") },
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
