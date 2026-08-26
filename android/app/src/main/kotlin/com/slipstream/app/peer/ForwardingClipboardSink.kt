package com.slipstream.app.peer

import com.slipstream.core.control.ClipboardSink
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * The `:core` -> `:app` half of the clipboard bridge. `SlipstreamPeer`'s constructor requires a
 * [ClipboardSink] instance up front (see [com.slipstream.app.PeerWiring.peer]), before
 * [RealPeerController] exists to receive from it — so this is constructed first, handed to
 * [com.slipstream.core.SlipstreamPeer], and then handed to [RealPeerController] too, which
 * exposes [received] as [PeerController.clipboardReceived].
 */
class ForwardingClipboardSink : ClipboardSink {
    private val _received = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val received: SharedFlow<String> = _received.asSharedFlow()

    override fun setText(text: String) {
        _received.tryEmit(text)
    }
}
