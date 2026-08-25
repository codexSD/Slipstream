package com.slipstream.core.transfer

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * A single bulk-transfer authorization: scoped to one transfer id and one source path,
 * usable any number of times until [expiresAtMs].
 *
 * Bulk tokens are deliberately *not* single-use. A resumed transfer opens fresh TCP
 * connections per range (potentially more than the original stream count, e.g. after a
 * dropped multi-stream transfer left more gaps than there are streams to fill them), and
 * every one of those connections must authenticate with the same token. A single-use token
 * would strand the second and later connections. This matches the ruling made on the
 * Windows/C# side (Plan 2b) for the same wire protocol.
 */
data class BulkToken(
    val value: UUID,
    val transferId: UUID,
    val sourcePath: String,
    val size: Long,
    val expectedStreams: Int,
    val expiresAtMs: Long,
)

/** Issues and validates [BulkToken]s. Thread-safe. */
class TokenVault(private val nowMs: () -> Long = System::currentTimeMillis) {

    private val tokens = ConcurrentHashMap<UUID, BulkToken>()

    /** Issues a new multi-use token, valid for [TTL_MS] from now. */
    fun issueBulk(transferId: UUID, sourcePath: String, size: Long, expectedStreams: Int): BulkToken {
        val token = BulkToken(
            value = UUID.randomUUID(),
            transferId = transferId,
            sourcePath = sourcePath,
            size = size,
            expectedStreams = expectedStreams,
            expiresAtMs = nowMs() + TTL_MS,
        )
        tokens[token.value] = token
        return token
    }

    /**
     * Validates [tokenValue] for [transferId]. Returns the token record (which carries the
     * authorized source path) when valid, or null when the token is unknown, expired, or
     * scoped to a different transfer. Does not consume the token - it remains usable for any
     * number of further connections until it expires.
     */
    fun validate(tokenValue: UUID, transferId: UUID): BulkToken? {
        val token = tokens[tokenValue] ?: return null
        if (nowMs() > token.expiresAtMs) {
            tokens.remove(tokenValue)
            return null
        }
        if (token.transferId != transferId) return null
        return token
    }

    /** Revokes every token issued for [transferId] (e.g. once the transfer completes). */
    fun revoke(transferId: UUID) {
        tokens.values.removeIf { it.transferId == transferId }
    }

    companion object {
        const val TTL_MS = 5 * 60 * 1000L
    }
}
