package com.slipstream.core.transfer

import java.io.File
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.rules.TemporaryFolder

private const val SMALL_CHUNK = 64 * 1024 // small chunk size keeps these tests fast

class PartFileTest {

    @get:org.junit.Rule
    val tmp = TemporaryFolder()

    private lateinit var destination: File
    private val transferId = UUID.randomUUID()

    @Before
    fun setUp() {
        destination = File(tmp.root, "download.bin")
    }

    @Test
    fun `preallocates the destination file to the full size`() {
        PartFile.openOrCreate(destination, transferId, 5L * SMALL_CHUNK, SMALL_CHUNK).use {
            assertEquals(5L * SMALL_CHUNK, destination.length())
        }
    }

    @Test
    fun `writes chunks at arbitrary offsets`() {
        val chunk0 = Random(1).nextBytes(SMALL_CHUNK)
        val chunk3 = Random(2).nextBytes(SMALL_CHUNK)
        PartFile.openOrCreate(destination, transferId, 5L * SMALL_CHUNK, SMALL_CHUNK).use { part ->
            part.writeChunk(3, chunk3, Crc32C.compute(chunk3))
            part.writeChunk(0, chunk0, Crc32C.compute(chunk0))
        }
        val bytes = destination.readBytes()
        assertArrayEquals(chunk0, bytes.copyOfRange(0, SMALL_CHUNK))
        assertArrayEquals(chunk3, bytes.copyOfRange(3 * SMALL_CHUNK, 4 * SMALL_CHUNK))
    }

    @Test
    fun `CRC mismatch throws and leaves the bit clear`() {
        val data = Random(3).nextBytes(SMALL_CHUNK)
        PartFile.openOrCreate(destination, transferId, 5L * SMALL_CHUNK, SMALL_CHUNK).use { part ->
            assertTrue(
                runCatching { part.writeChunk(0, data, Crc32C.compute(data) xor 1L) }.isFailure,
            )
            assertFalse(part.bitmap[0])
        }
    }

    @Test
    fun `reopening restores the bitmap from the sidecar`() {
        val chunk1 = Random(4).nextBytes(SMALL_CHUNK)
        PartFile.openOrCreate(destination, transferId, 5L * SMALL_CHUNK, SMALL_CHUNK).use { part ->
            part.writeChunk(1, chunk1, Crc32C.compute(chunk1))
        }
        PartFile.openOrCreate(destination, transferId, 5L * SMALL_CHUNK, SMALL_CHUNK).use { part ->
            assertTrue(part.bitmap[1])
            assertFalse(part.bitmap[0])
        }
    }

    @Test
    fun `short final chunk is written and marked complete`() {
        val size = 4L * SMALL_CHUNK + 100
        val lastChunk = Random(5).nextBytes(100)
        PartFile.openOrCreate(destination, transferId, size, SMALL_CHUNK).use { part ->
            part.writeChunk(4, lastChunk, Crc32C.compute(lastChunk))
            assertTrue(part.bitmap[4])
        }
        val bytes = destination.readBytes()
        assertArrayEquals(lastChunk, bytes.copyOfRange(4 * SMALL_CHUNK, 4 * SMALL_CHUNK + 100))
    }

    @Test
    fun `parallel-stream writes reassemble to byte-identical output`() {
        val chunkCount = 40
        val chunks = List(chunkCount) { Random(100 + it).nextBytes(SMALL_CHUNK) }
        val expected = chunks.reduce { a, b -> a + b }

        PartFile.openOrCreate(destination, transferId, chunkCount.toLong() * SMALL_CHUNK, SMALL_CHUNK).use { part ->
            val pool = Executors.newFixedThreadPool(8)
            val latch = CountDownLatch(chunkCount)
            for (i in 0 until chunkCount) {
                pool.submit {
                    part.writeChunk(i, chunks[i], Crc32C.compute(chunks[i]))
                    latch.countDown()
                }
            }
            latch.await()
            pool.shutdown()
            assertTrue(part.complete())
        }

        assertArrayEquals(expected, destination.readBytes())
    }

    @Test
    fun `does not rewrite the sidecar on every chunk`() {
        // Per-chunk persistence under a global lock serialises every parallel stream.
        val writes = AtomicInteger()
        val chunk = Random(6).nextBytes(SMALL_CHUNK)
        val crc = Crc32C.compute(chunk)
        PartFile.openOrCreate(
            destination,
            transferId,
            50L * SMALL_CHUNK,
            SMALL_CHUNK,
            onPersist = { writes.incrementAndGet() },
        ).use { part -> repeat(50) { part.writeChunk(it, chunk, crc) } }

        assertTrue("sidecar rewritten ${writes.get()} times for 50 chunks", writes.get() < 20)
    }
}

class TokenVaultTest {

    @Test
    fun `a token is usable more times than expectedStreams within its window`() {
        val vault = TokenVault()
        val transferId = UUID.randomUUID()
        val token = vault.issueBulk(transferId, "C:/file.bin", 1024, expectedStreams = 4)

        repeat(10) {
            assertNotNull(vault.validate(token.value, transferId))
        }
    }

    @Test
    fun `a token does not validate for a different transfer id`() {
        val vault = TokenVault()
        val token = vault.issueBulk(UUID.randomUUID(), "C:/file.bin", 1024, expectedStreams = 1)
        assertNull(vault.validate(token.value, UUID.randomUUID()))
    }

    @Test
    fun `an expired token no longer validates`() {
        var now = 0L
        val vault = TokenVault(nowMs = { now })
        val transferId = UUID.randomUUID()
        val token = vault.issueBulk(transferId, "C:/file.bin", 1024, expectedStreams = 1)

        now = TokenVault.TTL_MS + 1
        assertNull(vault.validate(token.value, transferId))
    }

    @Test
    fun `an unknown token does not validate`() {
        val vault = TokenVault()
        assertNull(vault.validate(UUID.randomUUID(), UUID.randomUUID()))
    }
}

class BulkTransferIoTest {

    @get:org.junit.Rule
    val tmp = TemporaryFolder()

    private lateinit var vault: TokenVault
    private lateinit var source: File
    private lateinit var destination: File
    private lateinit var sourceData: ByteArray
    private val transferId = UUID.randomUUID()
    private lateinit var server: BulkServer
    private lateinit var serverEndpoint: InetSocketAddress

    private fun startServer() {
        server = BulkServer(vault, fileForTransfer = { id -> if (id == transferId) source else null }, port = 0)
        serverEndpoint = InetSocketAddress("127.0.0.1", server.boundPort)
    }

    @Before
    fun setUp() {
        vault = TokenVault()
        source = File(tmp.root, "source.bin")
        destination = File(tmp.root, "download.bin")
    }

    @After
    fun tearDown() {
        if (::server.isInitialized) server.close()
    }

    private fun seedFragmented(chunkCount: Int, completed: IntArray, chunkSize: Int): ChunkBitmap {
        sourceData = Random(42).nextBytes(chunkCount * chunkSize)
        source.writeBytes(sourceData)

        // Simulate a previously-dropped transfer: the completed chunks were already written
        // to the destination file, and the sidecar already reflects them as done.
        java.io.RandomAccessFile(destination, "rw").use { raf ->
            raf.setLength(chunkCount.toLong() * chunkSize)
            completed.forEach { i ->
                raf.seek(i.toLong() * chunkSize)
                raf.write(sourceData, i * chunkSize, chunkSize)
            }
        }

        val bitmap = ChunkBitmap(chunkCount)
        completed.forEach { bitmap[it] = true }
        val sidecar = PartFile.sidecarFor(destination)
        sidecar.writeBytes(bitmap.rawBytes())
        return bitmap
    }

    @Test
    fun `an invalid header is answered by closing the socket, not an error frame`() {
        startServer()
        java.net.Socket().use { socket ->
            socket.connect(serverEndpoint, 5000)
            socket.getOutputStream().write(ByteArray(64)) // all zero: bad magic
            socket.getOutputStream().flush()
            socket.soTimeout = 2000
            val read = socket.getInputStream().read()
            assertEquals(-1, read) // peer closed with no reply
        }
    }

    @Test
    fun `a token issued for a different path is refused with no reply`() {
        // TokenVault scopes a token to one transfer id AND one source path. Issue a token
        // claiming a different path than the one fileForTransfer actually resolves for this
        // transferId, and confirm the server refuses it the same way it refuses a bad header.
        sourceData = Random(8).nextBytes(3 * SMALL_CHUNK)
        source.writeBytes(sourceData)
        startServer()
        val token = vault.issueBulk(transferId, source.path + ".wrong", sourceData.size.toLong(), expectedStreams = 1)

        val header = BulkFrameHeader(
            version = 1u,
            streamIndex = 0u,
            token = token.value,
            transferId = transferId,
            rangeStart = 0L,
            rangeLength = sourceData.size.toLong(),
            chunkSize = SMALL_CHUNK,
        )
        java.net.Socket().use { socket ->
            socket.connect(serverEndpoint, 5000)
            socket.getOutputStream().write(header.toBytes())
            socket.getOutputStream().flush()
            socket.soTimeout = 2000
            val read = socket.getInputStream().read()
            assertEquals(-1, read) // peer closed with no reply
        }
    }

    @Test
    fun `downloads a small file end to end`() {
        val chunkSize = SMALL_CHUNK
        sourceData = Random(7).nextBytes(3 * chunkSize)
        source.writeBytes(sourceData)
        startServer()

        val token = vault.issueBulk(transferId, source.path, sourceData.size.toLong(), expectedStreams = 1)
        PartFile.openOrCreate(destination, transferId, sourceData.size.toLong(), chunkSize).use { part ->
            BulkClient().download(serverEndpoint, transferId, token.value, part, streams = 1, onProgress = null)
            assertTrue(part.complete())
        }
        assertArrayEquals(sourceData, destination.readBytes())
    }

    @Test
    fun `resumes a bitmap with more gaps than streams`() = runTest {
        // The shape a dropped 4-stream transfer actually leaves behind. The C# side
        // failed here: its token allowed only `streams` uses.
        val chunkSize = SMALL_CHUNK
        val chunkCount = 18
        seedFragmented(chunkCount, completed = intArrayOf(0, 3, 6, 9, 12, 15), chunkSize = chunkSize)
        val size = sourceData.size.toLong()
        startServer()
        val token = vault.issueBulk(transferId, source.path, size, expectedStreams = 4)

        PartFile.openOrCreate(destination, transferId, size, chunkSize).use { part ->
            assertTrue(part.bitmap.missingRanges().count() > 4)
            BulkClient().download(serverEndpoint, transferId, token.value, part, streams = 4, onProgress = null)
            assertTrue(part.complete())
        }
        assertArrayEquals(sourceData, destination.readBytes())
    }
}
