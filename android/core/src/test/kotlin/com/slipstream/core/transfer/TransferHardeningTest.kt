package com.slipstream.core.transfer

import com.slipstream.core.net.NonLocalAddressException
import java.io.DataOutputStream
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.UUID
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The transfer-layer half of the final whole-branch review: outbound LAN scoping on the bulk
 * client, allocation bounds on the bulk server, sidecar identity on [PartFile], and bounded
 * token bookkeeping in [TokenVault].
 */
class TransferHardeningTest {

    private val loopback: InetAddress = InetAddress.getByName("127.0.0.1")

    // --- Critical 1: outbound LanGuard on the bulk client ---

    @Test
    fun `the bulk client refuses a non-local endpoint before opening a socket`() {
        val dir = createTempDirectory().toFile()
        val part = PartFile.openOrCreate(File(dir, "x.bin"), UUID.randomUUID(), size = 64, chunkSize = 16)
        try {
            // 93.184.216.34 is a public address. Reaching it would breach spec §11 layer 2 -
            // and would have been reachable, since BulkClient previously connected to whatever
            // endpoint the peer's pull.ok named, with no check of its own.
            assertThrows(NonLocalAddressException::class.java) {
                BulkClient().download(
                    endpoint = InetSocketAddress(InetAddress.getByName("93.184.216.34"), 53322),
                    transferId = UUID.randomUUID(),
                    token = UUID.randomUUID(),
                    part = part,
                    streams = 1,
                    onProgress = null,
                )
            }
        } finally {
            part.close()
        }
    }

    // --- Minor 9: the bulk server bounds a wire-supplied range and chunk size ---

    private fun headerBytes(
        token: UUID,
        transferId: UUID,
        rangeStart: Long,
        rangeLength: Long,
        chunkSize: Int,
    ) = BulkFrameHeader(
        version = 1u,
        streamIndex = 0u,
        token = token,
        transferId = transferId,
        rangeStart = rangeStart,
        rangeLength = rangeLength,
        chunkSize = chunkSize,
    ).toBytes()

    private fun refusedWithNoReply(
        source: File,
        vault: TokenVault,
        transferId: UUID,
        header: ByteArray,
    ) {
        BulkServer(vault, fileForTransfer = { source }, port = 0, bindAddress = loopback).use { server ->
            java.net.Socket().use { socket ->
                socket.connect(InetSocketAddress(loopback, server.boundPort), 5000)
                val out = DataOutputStream(socket.getOutputStream())
                out.write(header)
                out.flush()
                socket.soTimeout = 3000
                assertEquals("must be refused by closing the socket, with no reply", -1, socket.getInputStream().read())
            }
        }
    }

    @Test
    fun `a range beyond what the token authorizes is refused`() {
        val dir = createTempDirectory().toFile()
        val source = File(dir, "src.bin").apply { writeBytes(ByteArray(64)) }
        val vault = TokenVault()
        val transferId = UUID.randomUUID()
        val token = vault.issueBulk(transferId, source.path, size = 64, expectedStreams = 1)

        refusedWithNoReply(source, vault, transferId, headerBytes(token.value, transferId, 0, 65, 16))
        refusedWithNoReply(source, vault, transferId, headerBytes(token.value, transferId, 60, 16, 16))
        refusedWithNoReply(source, vault, transferId, headerBytes(token.value, transferId, -1, 16, 16))
    }

    @Test
    fun `an absurd chunk size is refused rather than allocated`() {
        val dir = createTempDirectory().toFile()
        val source = File(dir, "src.bin").apply { writeBytes(ByteArray(64)) }
        val vault = TokenVault()
        val transferId = UUID.randomUUID()
        val token = vault.issueBulk(transferId, source.path, size = 64, expectedStreams = 1)

        // Before this bound, `ByteArray(minOf(remaining, chunkSize))` was capped only by
        // rangeLength - so a peer claiming a multi-gigabyte range/chunk got a multi-gigabyte
        // allocation attempt out of a 64-byte transfer.
        refusedWithNoReply(source, vault, transferId, headerBytes(token.value, transferId, 0, 64, Int.MAX_VALUE))
        refusedWithNoReply(source, vault, transferId, headerBytes(token.value, transferId, 0, 64, 0))
        refusedWithNoReply(source, vault, transferId, headerBytes(token.value, transferId, 0, 64, -16))
    }

    @Test
    fun `a range within the token is still served normally`() {
        val dir = createTempDirectory().toFile()
        val payload = ByteArray(64) { it.toByte() }
        val source = File(dir, "src.bin").apply { writeBytes(payload) }
        val vault = TokenVault()
        val transferId = UUID.randomUUID()
        val token = vault.issueBulk(transferId, source.path, size = 64, expectedStreams = 1)

        BulkServer(vault, fileForTransfer = { source }, port = 0, bindAddress = loopback).use { server ->
            java.net.Socket().use { socket ->
                socket.connect(InetSocketAddress(loopback, server.boundPort), 5000)
                val out = DataOutputStream(socket.getOutputStream())
                out.write(headerBytes(token.value, transferId, 0, 64, 64))
                out.flush()
                socket.soTimeout = 5000
                val input = java.io.DataInputStream(socket.getInputStream())
                assertEquals(64, input.readInt())
            }
        }
    }

    // --- Minor 12: the sidecar is stamped with the transfer it describes ---

    @Test
    fun `a sidecar from a different transfer of the same size is discarded, not inherited`() {
        val dir = createTempDirectory().toFile()
        val destination = File(dir, "download.bin")

        val firstTransfer = UUID.randomUUID()
        PartFile.openOrCreate(destination, firstTransfer, size = 64, chunkSize = 16, debounceMs = 0).use { part ->
            part.writeChunk(0, ByteArray(16), Crc32C.compute(ByteArray(16)))
            part.writeChunk(1, ByteArray(16), Crc32C.compute(ByteArray(16)))
        }
        assertTrue(PartFile.sidecarFor(destination).exists())

        // A completely different file that happens to be the same size, pulled to the same
        // path. Inheriting the earlier bitmap would skip chunks 0-1 and leave the result a
        // silent splice of two unrelated files.
        val secondTransfer = UUID.randomUUID()
        PartFile.openOrCreate(destination, secondTransfer, size = 64, chunkSize = 16).use { part ->
            assertFalse("stale completion bits must not carry over", part.bitmap[0])
            assertFalse(part.bitmap[1])
            assertEquals(listOf(0..3), part.missingRanges())
        }
    }

    @Test
    fun `a sidecar for the same transfer and geometry is still restored`() {
        val dir = createTempDirectory().toFile()
        val destination = File(dir, "resume.bin")
        val transferId = UUID.randomUUID()

        PartFile.openOrCreate(destination, transferId, size = 64, chunkSize = 16, debounceMs = 0).use { part ->
            part.writeChunk(2, ByteArray(16), Crc32C.compute(ByteArray(16)))
        }

        PartFile.openOrCreate(destination, transferId, size = 64, chunkSize = 16).use { part ->
            assertTrue("resume must still work for the transfer the sidecar belongs to", part.bitmap[2])
            assertFalse(part.bitmap[0])
        }
    }

    @Test
    fun `a sidecar whose geometry no longer matches is discarded`() {
        val dir = createTempDirectory().toFile()
        val destination = File(dir, "geometry.bin")
        val transferId = UUID.randomUUID()

        PartFile.openOrCreate(destination, transferId, size = 64, chunkSize = 16, debounceMs = 0).use { part ->
            part.writeChunk(0, ByteArray(16), Crc32C.compute(ByteArray(16)))
        }
        // Same transfer id, but the sender re-negotiated a different chunk size: bit i no
        // longer means the same bytes.
        PartFile.openOrCreate(destination, transferId, size = 64, chunkSize = 32).use { part ->
            assertFalse(part.bitmap[0])
        }
    }

    @Test
    fun `raw legacy sidecar bytes with no header are discarded`() {
        val dir = createTempDirectory().toFile()
        val destination = File(dir, "legacy.bin")
        PartFile.sidecarFor(destination).writeBytes(byteArrayOf(0xFF.toByte()))

        PartFile.openOrCreate(destination, UUID.randomUUID(), size = 64, chunkSize = 16).use { part ->
            assertFalse("an unidentifiable sidecar proves nothing and must be rebuilt", part.complete())
        }
    }

    // --- Minor 7: close() releases the fd and flushes the final debounced sidecar write ---

    @Test
    fun `close flushes the completion bits a debounced write had not persisted yet`() {
        val dir = createTempDirectory().toFile()
        val destination = File(dir, "debounced.bin")
        val transferId = UUID.randomUUID()

        val part = PartFile.openOrCreate(destination, transferId, size = 64, chunkSize = 16, debounceMs = 60_000)
        repeat(4) { i -> part.writeChunk(i, ByteArray(16), Crc32C.compute(ByteArray(16))) }
        part.close()

        PartFile.openOrCreate(destination, transferId, size = 64, chunkSize = 16).use { reopened ->
            assertTrue("close() must flush, else a resume re-downloads the last chunks", reopened.complete())
        }
    }

    // --- Minor 8: token bookkeeping is bounded ---

    @Test
    fun `purgeExpired drops expired tokens and names their transfers`() {
        var now = 1_000L
        val vault = TokenVault(nowMs = { now })
        val liveTransfer = UUID.randomUUID()
        val staleTransfer = UUID.randomUUID()
        val stale = vault.issueBulk(staleTransfer, "/a", size = 1, expectedStreams = 1)

        now += TokenVault.TTL_MS + 1
        val live = vault.issueBulk(liveTransfer, "/b", size = 1, expectedStreams = 1)

        assertEquals(listOf(staleTransfer), vault.purgeExpired())
        assertNull("an expired token must be gone even though nobody presented it", vault.validate(stale.value, staleTransfer))
        assertNotNull(vault.validate(live.value, liveTransfer))
    }

    @Test
    fun `revoke removes a completed transfer's token`() {
        val vault = TokenVault()
        val transferId = UUID.randomUUID()
        val token = vault.issueBulk(transferId, "/a", size = 1, expectedStreams = 1)
        assertNotNull(vault.validate(token.value, transferId))

        vault.revoke(transferId)

        assertNull(vault.validate(token.value, transferId))
    }
}
