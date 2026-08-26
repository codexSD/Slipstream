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
import com.slipstream.core.SlipstreamLog
import com.slipstream.core.files.FileBrowser
import com.slipstream.core.identity.DeviceIdentity
import com.slipstream.core.identity.PairedPeerStore
import com.slipstream.core.net.AndroidNetworkInfo
import com.slipstream.app.peer.ForwardingClipboardSink
import com.slipstream.app.peer.ForwardingPlaySink
import com.slipstream.app.peer.HistoryStore
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
            settingsStore = settingsStore,
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

    /** Persisted record of completed/failed transfers (Task 8), shared by the History screen and
     * by whatever completes a transfer (Send screen pushes, Browse screen downloads) so a real
     * transfer's outcome actually shows up in History (C3) rather than the store sitting unused.
     * Application-scoped lazy singleton, same pattern as [transferQueue]/[settingsStore]. */
    val historyStore: HistoryStore by lazy { HistoryStore(File(filesDir, "history.json")) }

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
        appScope.launch { historyStore.load() }
    }

    /**
     * Task 11 (design.md §8, push-to-play, inbound direction): opens the system default player
     * via `ACTION_VIEW` for a `play` message the peer just sent. Runs outside any `Activity`
     * context (this is an [Application]-scoped collector, same as the clipboard one above), so
     * [Intent.FLAG_ACTIVITY_NEW_TASK] is required.
     *
     * [PlayRequest.LocalFile] needs a `content://` URI rather than a bare `file://` one — Android
     * has refused `file://` URIs handed to another app (`FileUriExposedException`) since API 24 -
     * so it goes through [localFileContentUri], backed by the [FileProvider] declared in the
     * manifest, granting the resolved player app read access for exactly this one URI.
     */
    private fun launchPlayback(request: PlayRequest) {
        val (uri, mime) = when (request) {
            is PlayRequest.LocalFile -> localFileContentUri(request.file) to request.mime
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

    /**
     * Builds a `content://` URI for [file] through the [FileProvider] declared in the manifest,
     * for exactly the two roots `res/xml/file_paths.xml` declares (`external_root` ==
     * [getExternalFilesDir]`(null)`, `internal_root` == [getFilesDir]).
     *
     * Does NOT call `FileProvider.getUriForFile` directly for this, because that method's own
     * root-matching (`SimplePathStrategy.belongsToRoot`) hardcodes a `/`-separated prefix check
     * against `File.getCanonicalPath()`, which is `\`-separated on Windows - so on a
     * Windows-hosted JVM (this project's Robolectric unit tests included) it can never match a
     * file nested under any declared root, real device correctness aside. Building the URI
     * ourselves - using [File.separator] (the *actual* platform separator) for the prefix check,
     * then re-encoding with `/` to match the URI path format `FileProvider.getFileForUri` expects
     * - sidesteps that bug entirely. `getFileForUri` itself (the read side, exercised whenever
     * another app or the system opens the resulting URI) only ever does a plain map lookup by
     * root *name*, never a path comparison, so a real device's provider resolves this exactly as
     * if `getUriForFile` itself had built it - this only replaces the *encoding* step, not the
     * provider or its security contract. Falls back to the real `FileProvider.getUriForFile` for
     * any file outside both known roots (should not happen given [PlayRequest.LocalFile] is only
     * ever built from a path [SlipstreamSession.play] resolved under its own `rootDirectory`) -
     * that path is correct as-is on any real device/Linux host, where the separator this bug
     * hinges on already is `/`.
     */
    private fun localFileContentUri(file: File): Uri {
        val authority = "$packageName.fileprovider"
        val roots = listOfNotNull(
            getExternalFilesDir(null)?.let { "external_root" to it },
            "internal_root" to filesDir,
        )
        val fileCanonical = file.canonicalFile
        for ((rootName, root) in roots) {
            val rootCanonical = root.canonicalFile
            val rootPath = rootCanonical.path
            val filePath = fileCanonical.path
            val relative = when {
                filePath == rootPath -> ""
                filePath.startsWith(rootPath + File.separator) -> filePath.substring(rootPath.length + 1)
                else -> null
            } ?: continue
            val encodedRelative = relative.split(File.separatorChar).joinToString("/") { Uri.encode(it) }
            val encodedPath = if (encodedRelative.isEmpty()) Uri.encode(rootName) else "${Uri.encode(rootName)}/$encodedRelative"
            return Uri.Builder().scheme("content").authority(authority).encodedPath(encodedPath).build()
        }
        return FileProvider.getUriForFile(this, authority, file)
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
            rootDirectory = sharedRoot(),
            clipboardSink = clipboardSink,
            onPlayRequested = { file -> playSink.onLocalFile(file, FileBrowser.mimeFor(file.name)) },
            onPlayUrlRequested = { url, mime -> playSink.onRemoteUrl(url, mime ?: "video/*") },
            multicastLock = androidMulticastLock(),
        )
    }

    /**
     * What this device actually offers the peer.
     *
     * This was `getExternalFilesDir(null)` — the app's own private sandbox under
     * `Android/data/`, which is empty and which the user cannot put anything into without a
     * file manager that can reach it. The peer would browse the phone, get zero entries, and
     * have no way to tell "no permission" apart from "nothing here". The whole reason this app
     * requests MANAGE_EXTERNAL_STORAGE is to share real storage, so share real storage.
     *
     * Falls back to the sandbox when the permission has not been granted: an empty listing is
     * a poor experience but a working one, where pointing at /sdcard unpermitted would just
     * throw on every request.
     */
    private fun sharedRoot(): File {
        val sandbox = getExternalFilesDir(null) ?: filesDir
        val granted = hasAllFilesAccess()

        // Every one of these can fail on a device (or an emulated environment) with no external
        // storage mounted at all. Sharing the sandbox is a working fallback; crashing on startup
        // because storage was unavailable is not.
        val root = if (granted) {
            runCatching { android.os.Environment.getExternalStorageDirectory() }.getOrNull() ?: sandbox
        } else {
            sandbox
        }

        val detail = runCatching {
            "exists=${root.exists()}, readable=${root.canRead()}, entries=${root.list()?.size ?: -1}"
        }.getOrElse { "could not be inspected: ${it.javaClass.simpleName}" }

        SlipstreamLog.i(
            "storage",
            "all-files access ${if (granted) "granted" else "NOT granted"}, sharing $root ($detail)",
        )
        return root
    }

    /** MANAGE_EXTERNAL_STORAGE is not a runtime permission — it is a per-app special access
     * the user grants in Settings, so it is checked, never requested with requestPermissions. */
    private fun hasAllFilesAccess(): Boolean = runCatching {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || android.os.Environment.isExternalStorageManager()
    }.getOrDefault(false)

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
