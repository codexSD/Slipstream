package com.slipstream.app.peer

import com.slipstream.core.files.FileEntry
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** A capped directory listing returned by [PeerController.list], mirroring
 * `com.slipstream.core.files.DirectoryListing`'s shape (that type itself isn't reused directly
 * since it's built server-side from a local [File] tree — this one comes off the wire). */
data class ListResult(val entries: List<FileEntry>, val truncated: Boolean)

/** Cumulative progress for one [PeerController.pull] or [PeerController.push]. [totalBytes] is
 * 0 when the size genuinely could not be determined ahead of the transfer. */
data class TransferProgress(val bytesTransferred: Long, val totalBytes: Long)

/** Emitted while [PeerController.openPairing] is running. */
sealed interface PairingProgress {
    /** The 6-digit code (pairing.md §5) both devices must show the user, derived from the
     * TLS-verified fingerprint. The caller must show this and then call [PeerController.confirmPairing]. */
    data class CodeReceived(val code: String) : PairingProgress

    /** The pairing attempt ended, one way or another (accepted, declined, or timed out). */
    data class Completed(val paired: Boolean) : PairingProgress
}

/**
 * The single owner of `:core` inside `:app` (Task 2). Mirrors `Slipstream.App.Services.PeerHost`
 * (`PeerHost.cs`): one persistent control connection to the paired peer, one `Mutex` serialising
 * every round trip over it (a single duplex JSON-lines stream cannot have two concurrent
 * request/response pairs without interleaving replies and mismatching ids), and a heartbeat
 * loop that treats a network switch (spec §5) as routine — [PeerConnectionState.Lost], never an
 * exception thrown out of nowhere.
 *
 * No composable or view model may open a socket or touch `:core` directly — this interface is
 * the entire boundary. Every method here does its `:core`/network work off the caller's thread
 * (`Dispatchers.IO`); [status] is how state gets back to callers without blocking.
 */
interface PeerController {
    val status: StateFlow<PeerStatus>

    /** Whether a peer device is currently paired with this device. Updates as the pairing state
     * changes via [openPairing] and [confirmPairing]. */
    val isPaired: StateFlow<Boolean>

    /** Starts the underlying peer (if not already started), then discovers, connects, and
     * begins the heartbeat loop. Suspends until the first connection attempt has settled
     * (connected or not) — it does not suspend forever waiting for a peer that never appears. */
    suspend fun start()

    /** Tears down the current connection (if any) and re-establishes it: re-runs discovery with
     * backoff (1s, 2s, 4s, capped at 15s) and reopens the control connection. Returns whether it
     * ended up [PeerConnectionState.Connected]. */
    suspend fun reconnect(): Boolean

    /** Lists [path] on the peer. A failure (peer refused, connection lost, path gone) surfaces
     * as a [Result.failure] with a direct, no-apology message (spec §15) — never a thrown
     * exception the caller has to unwrap. */
    suspend fun list(path: String): Result<ListResult>

    /** Builds the full `/thumb/<token>` URL for a [FileEntry.thumbnailToken] returned by
     * [list], against the peer's already-known control endpoint — never a fresh lookup, and
     * never any address other than the one this connection already trusts. Null when there is
     * no current connection to build an endpoint from. */
    fun thumbnailUrl(token: String): String?

    /** Pulls [remotePath] from the peer into [destination], emitting cumulative
     * [TransferProgress] as bytes arrive. Runs the blocking `:core` transfer on `Dispatchers.IO`. */
    fun pull(remotePath: String, destination: File): Flow<TransferProgress>

    /** Pushes the local file at [localPath] to the peer, landing at [remoteName] under its root. */
    fun push(localPath: String, remoteName: String): Flow<TransferProgress>

    /**
     * Push-to-play (design.md §8): asks the peer to start playing [localPath], a file *this*
     * device owns (an absolute path on this device's own filesystem — never resolved against any
     * root, exactly like [push]'s `localPath`). This device issues itself a stream token, builds
     * a URL to its own media server, and sends the peer a `play` message carrying that URL — the
     * peer never touches this device's filesystem, and nothing is downloaded either direction.
     */
    suspend fun streamOnPeer(localPath: String): Result<Unit>

    /** Asks the peer to serve [remotePath] for playback on *this* device, returning the URL to
     * hand to a local media player. */
    suspend fun streamUrlFor(remotePath: String): Result<String>

    suspend fun sendClipboard(text: String): Result<Unit>

    /** Text the peer has sent to this device's clipboard. */
    val clipboardReceived: SharedFlow<String>

    /** `play` messages received from the peer (design.md §8, push-to-play in the inbound
     * direction) — the app-level owner (see `SlipstreamApplication`) collects this and launches
     * `ACTION_VIEW`. */
    val playRequests: SharedFlow<PlayRequest>

    /** Opens a pairing window against a not-yet-paired peer at a discovered endpoint, emitting
     * the derived code once the handshake happens and completing once the attempt is decided.
     * The caller must observe [PairingProgress.CodeReceived] and then call [confirmPairing]. */
    fun openPairing(): Flow<PairingProgress>

    /** Answers the pairing attempt [openPairing] is currently waiting on. */
    suspend fun confirmPairing(accept: Boolean)

    /** Clears the paired peer state, returning to unpaired. Does not affect the connection. */
    suspend fun unpair()
}
