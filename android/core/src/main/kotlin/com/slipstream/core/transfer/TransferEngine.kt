package com.slipstream.core.transfer

import java.net.InetSocketAddress
import java.util.UUID

/** Everything [TransferEngine] needs to attempt one bulk-download pass. */
data class BulkSession(
    val endpoint: InetSocketAddress,
    val transferId: UUID,
    val token: UUID,
)

/**
 * Orchestrates a pull transfer by driving [BulkClient] against a [PartFile], retrying on
 * failure.
 *
 * The retry does not simply reopen a TCP connection to the same [BulkSession] - it calls
 * [pull]'s `session` lambda again on every attempt. That lambda is expected to go back through
 * `ControlClient.connect` (or whatever the caller's control-channel plumbing is) to negotiate a
 * fresh bulk token before handing back a new [BulkSession]. Reusing the connection that just
 * died is what made the C# retry useless for its primary case (spec §7) - a peer that dropped
 * the bulk socket has, by definition, nothing left to answer on it.
 */
class TransferEngine(
    private val bulkClient: BulkClient = BulkClient(),
    private val maxAttempts: Int = 3,
) {
    /**
     * Pulls the missing chunks of [part] via [BulkClient.download], retrying up to
     * [maxAttempts] times. Returns normally once [part] reports complete; otherwise rethrows
     * the last failure once attempts are exhausted.
     */
    fun pull(
        part: PartFile,
        streams: Int,
        onProgress: ((Long) -> Unit)? = null,
        session: () -> BulkSession,
    ) {
        var lastError: Throwable? = null
        var attempt = 0
        while (attempt < maxAttempts && !part.complete()) {
            attempt++
            val s = session()
            try {
                bulkClient.download(s.endpoint, s.transferId, s.token, part, streams, onProgress)
                lastError = null
            } catch (e: Throwable) {
                lastError = e
            }
        }
        if (!part.complete()) {
            throw lastError ?: IllegalStateException("transfer incomplete after $maxAttempts attempts")
        }
    }
}
