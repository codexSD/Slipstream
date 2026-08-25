package com.slipstream.core.transfer

import java.io.File
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private const val SMALL_CHUNK = 64 * 1024

class TransferEngineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var vault: TokenVault
    private lateinit var source: File
    private lateinit var destination: File
    private lateinit var sourceData: ByteArray
    private val transferId = UUID.randomUUID()
    private var server: BulkServer? = null

    @Before
    fun setUp() {
        vault = TokenVault()
        source = File(tmp.root, "source.bin")
        destination = File(tmp.root, "download.bin")
    }

    @After
    fun tearDown() {
        server?.close()
    }

    private fun startServer(): InetSocketAddress {
        val s = BulkServer(vault, fileForTransfer = { id -> if (id == transferId) source else null }, port = 0)
        server = s
        return InetSocketAddress("127.0.0.1", s.boundPort)
    }

    @Test
    fun `pull is byte-identical end to end and progress reaches the total`() {
        sourceData = Random(11).nextBytes(5 * SMALL_CHUNK)
        source.writeBytes(sourceData)
        val endpoint = startServer()
        val token = vault.issueBulk(transferId, source.path, sourceData.size.toLong(), expectedStreams = 2)

        val progressTotal = AtomicLong(0)
        PartFile.openOrCreate(destination, transferId, sourceData.size.toLong(), SMALL_CHUNK).use { part ->
            TransferEngine().pull(
                part = part,
                streams = 2,
                onProgress = { progressTotal.addAndGet(it) },
                session = { BulkSession(endpoint, transferId, token.value) },
            )
            assertTrue(part.complete())
        }
        assertArrayEquals(sourceData, destination.readBytes())
        assertEquals(sourceData.size.toLong(), progressTotal.get())
    }

    @Test
    fun `retry reconnects via a fresh session rather than reusing a dead endpoint`() {
        // First session points at a server that is immediately closed (simulating a dropped
        // connection); the second session - obtained by calling the lambda again, as a real
        // caller would after reconnecting through ControlClient - points at a live server.
        sourceData = Random(12).nextBytes(3 * SMALL_CHUNK)
        source.writeBytes(sourceData)

        val deadEndpoint = InetSocketAddress("127.0.0.1", 1) // nothing listens here
        val token = vault.issueBulk(transferId, source.path, sourceData.size.toLong(), expectedStreams = 1)

        var sessionCalls = 0
        var liveEndpoint: InetSocketAddress? = null

        PartFile.openOrCreate(destination, transferId, sourceData.size.toLong(), SMALL_CHUNK).use { part ->
            TransferEngine(maxAttempts = 3).pull(
                part = part,
                streams = 1,
                session = {
                    sessionCalls++
                    if (sessionCalls == 1) {
                        BulkSession(deadEndpoint, transferId, token.value)
                    } else {
                        if (liveEndpoint == null) liveEndpoint = startServer()
                        BulkSession(liveEndpoint!!, transferId, token.value)
                    }
                },
            )
            assertTrue(part.complete())
        }
        assertTrue("session lambda must be called again on retry", sessionCalls >= 2)
        assertArrayEquals(sourceData, destination.readBytes())
    }

    @Test(expected = Exception::class)
    fun `gives up after maxAttempts and surfaces the last failure`() {
        val deadEndpoint = InetSocketAddress("127.0.0.1", 1)
        val token = vault.issueBulk(transferId, "does-not-matter", 5L * SMALL_CHUNK, expectedStreams = 1)

        PartFile.openOrCreate(destination, transferId, 5L * SMALL_CHUNK, SMALL_CHUNK).use { part ->
            TransferEngine(maxAttempts = 2).pull(
                part = part,
                streams = 1,
                session = { BulkSession(deadEndpoint, transferId, token.value) },
            )
        }
    }
}
