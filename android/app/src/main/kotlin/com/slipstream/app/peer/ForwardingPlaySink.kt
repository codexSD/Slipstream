package com.slipstream.app.peer

import java.io.File
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * The `:core` -> `:app` half of the push-to-play bridge, mirroring [ForwardingClipboardSink]'s
 * pattern exactly. `SlipstreamPeer`'s constructor requires its two `onPlayRequested`/
 * `onPlayUrlRequested` callbacks up front (see [com.slipstream.app.PeerWiring.peer]), before
 * [RealPeerController] exists to receive from it — so this is constructed first, handed to both
 * callback slots, and then handed to [RealPeerController] too, which exposes [received] as
 * [PeerController.playRequests].
 */
class ForwardingPlaySink {
    private val _received = MutableSharedFlow<PlayRequest>(extraBufferCapacity = 16)
    val received: SharedFlow<PlayRequest> = _received.asSharedFlow()

    /** Wired to [com.slipstream.core.SlipstreamPeer]'s path-based `onPlayRequested`. */
    fun onLocalFile(file: File, mime: String) {
        _received.tryEmit(PlayRequest.LocalFile(file, mime))
    }

    /** Wired to [com.slipstream.core.SlipstreamPeer]'s url-based `onPlayUrlRequested`. */
    fun onRemoteUrl(url: String, mime: String) {
        _received.tryEmit(PlayRequest.RemoteUrl(url, mime))
    }
}
