package com.slipstream.app.peer

import java.io.File

/**
 * One inbound `play` (design.md §8, push-to-play), already resolved down to exactly what
 * [SlipstreamApplication][com.slipstream.app.SlipstreamApplication]'s `ACTION_VIEW` launcher
 * needs — never a raw wire payload. Mirrors the two shapes
 * [com.slipstream.core.control.SlipstreamSession]'s `play()` handler recognises (see that file's
 * doc on `onPlayRequested`/`onPlayUrlRequested`):
 *  - [LocalFile]: the peer asked this device to play a file *this device* already owns.
 *  - [RemoteUrl]: real push-to-play — the peer (the file's owner) already issued itself a stream
 *    token and is handing this device a ready-to-open URL to *its own* media server.
 */
sealed interface PlayRequest {
    data class LocalFile(val file: File, val mime: String) : PlayRequest
    data class RemoteUrl(val url: String, val mime: String) : PlayRequest
}
