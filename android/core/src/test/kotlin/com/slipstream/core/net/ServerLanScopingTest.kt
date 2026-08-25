package com.slipstream.core.net

import com.slipstream.core.media.MediaServer
import com.slipstream.core.media.MediaTokenVault
import com.slipstream.core.transfer.BulkFrameHeader
import com.slipstream.core.transfer.BulkServer
import com.slipstream.core.transfer.TokenVault
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val LOOPBACK: InetAddress = InetAddress.getByName("127.0.0.1")

/**
 * Spec §11 layers 1 and 2 for the two *plaintext* servers. These carry no TLS and no peer
 * identity of their own - the bulk and media protocols are safe only because they are
 * unreachable from anywhere but the current LAN (design.md §7: "LAN-only, paired-only, and
 * non-routable by construction (§11)"), so binding and the LanGuard check are the whole of
 * their access control, not a defence in depth.
 */
class ServerLanScopingTest {

    private fun bulkServer(
        bindAddress: InetAddress = LOOPBACK,
        isLocalAddress: (InetAddress) -> Boolean = LanGuard::isLocal,
        file: File? = null,
        vault: TokenVault = TokenVault(),
    ) = BulkServer(
        vault,
        fileForTransfer = { file },
        port = 0,
        bindAddress = bindAddress,
        isLocalAddress = isLocalAddress,
    )

    @Test
    fun `BulkServer binds to the address it is given, never the wildcard`() {
        bulkServer().use { server ->
            assertEquals(LOOPBACK, server.listenEndpoint.address)
            assertFalse(
                "binding to the wildcard address exposes the plaintext bulk protocol on every interface",
                server.listenEndpoint.address.isAnyLocalAddress,
            )
        }
    }

    @Test
    fun `BulkServer defaults to loopback rather than the wildcard when no address is given`() {
        BulkServer(TokenVault(), fileForTransfer = { null }, port = 0).use { server ->
            assertFalse(server.listenEndpoint.address.isAnyLocalAddress)
            assertTrue(server.listenEndpoint.address.isLoopbackAddress)
        }
    }

    @Test
    fun `BulkServer drops a non-local peer with no reply at all`() {
        val root = createTempDirectory().toFile()
        val source = File(root, "src.bin").apply { writeBytes(ByteArray(64) { it.toByte() }) }
        val transferId = UUID.randomUUID()
        val vault = TokenVault()
        val token = vault.issueBulk(transferId, source.path, source.length(), 1)

        // isLocalAddress = { false } stands in for a peer off the LAN: a loopback client is
        // always local, so this is the only way to exercise the rejection branch.
        bulkServer(isLocalAddress = { false }, file = source, vault = vault).use { server ->
            Socket().use { client ->
                client.connect(InetSocketAddress(LOOPBACK, server.boundPort), 2000)
                client.getOutputStream().write(
                    BulkFrameHeader(
                        version = 1u,
                        streamIndex = 0u,
                        token = token.value,
                        transferId = transferId,
                        rangeStart = 0,
                        rangeLength = source.length(),
                        chunkSize = 16,
                    ).toBytes(),
                )
                client.getOutputStream().flush()
                client.soTimeout = 5000
                assertEquals(
                    "a rejected peer must get nothing at all, not a single byte of file data",
                    0,
                    drain(client),
                )
            }
        }
    }

    @Test
    fun `BulkServer serves a local peer with the same guard in place`() {
        // The positive control for the test above: the real LanGuard default lets loopback in,
        // so the rejection above is the guard working and not the server being broken.
        val root = createTempDirectory().toFile()
        val source = File(root, "src.bin").apply { writeBytes(ByteArray(16) { it.toByte() }) }
        val transferId = UUID.randomUUID()
        val vault = TokenVault()
        val token = vault.issueBulk(transferId, source.path, source.length(), 1)

        bulkServer(file = source, vault = vault).use { server ->
            Socket().use { client ->
                client.connect(InetSocketAddress(LOOPBACK, server.boundPort), 2000)
                client.getOutputStream().write(
                    BulkFrameHeader(
                        version = 1u,
                        streamIndex = 0u,
                        token = token.value,
                        transferId = transferId,
                        rangeStart = 0,
                        rangeLength = source.length(),
                        chunkSize = 16,
                    ).toBytes(),
                )
                client.getOutputStream().flush()
                client.soTimeout = 5000
                assertTrue("a local peer must be served", drain(client) > 0)
            }
        }
    }

    @Test
    fun `MediaServer binds to the address it is given, never the wildcard`() {
        MediaServer(MediaTokenVault(), port = 0, bindAddress = LOOPBACK).use { server ->
            assertEquals(LOOPBACK, server.listenEndpoint.address)
            assertFalse(server.listenEndpoint.address.isAnyLocalAddress)
        }
    }

    @Test
    fun `MediaServer defaults to loopback rather than the wildcard when no address is given`() {
        MediaServer(MediaTokenVault(), port = 0).use { server ->
            assertFalse(server.listenEndpoint.address.isAnyLocalAddress)
            assertTrue(server.listenEndpoint.address.isLoopbackAddress)
        }
    }

    @Test
    fun `MediaServer drops a non-local peer without even a 404`() {
        val root = createTempDirectory().toFile()
        val file = File(root, "clip.bin").apply { writeBytes(ByteArray(32)) }
        val vault = MediaTokenVault()
        val token = vault.issue(file, "video/mp4")

        MediaServer(vault, port = 0, bindAddress = LOOPBACK, isLocalAddress = { false }).use { server ->
            assertEquals(
                "a rejected peer must get end-of-stream, not an HTTP response of any kind",
                "",
                httpGet(server.boundPort, "/media/${token.value}"),
            )
        }
    }

    @Test
    fun `MediaServer answers a local peer with the same guard in place`() {
        val root = createTempDirectory().toFile()
        val file = File(root, "clip.bin").apply { writeBytes(ByteArray(32)) }
        val vault = MediaTokenVault()
        val token = vault.issue(file, "video/mp4")

        MediaServer(vault, port = 0, bindAddress = LOOPBACK).use { server ->
            val response = httpGet(server.boundPort, "/media/${token.value}")
            assertNotNull(response)
            assertTrue("expected a real HTTP response, got: $response", response.startsWith("HTTP/1.1 200"))
        }
    }

    private fun httpGet(port: Int, path: String): String = Socket().use { client ->
        client.connect(InetSocketAddress(LOOPBACK, port), 2000)
        client.soTimeout = 5000
        client.getOutputStream().write(
            "GET $path HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray(StandardCharsets.US_ASCII),
        )
        client.getOutputStream().flush()
        val out = StringBuilder()
        val buffer = ByteArray(1024)
        try {
            while (true) {
                val read = client.getInputStream().read(buffer)
                if (read == -1) break
                out.append(String(buffer, 0, read, StandardCharsets.ISO_8859_1))
                if (out.contains("\r\n\r\n")) break
            }
        } catch (e: java.net.SocketException) {
            // See drain(): a reset from a silently-dropped connection is "no response".
        }
        out.toString()
    }

    /**
     * Bytes received before the server hangs up. A silently-dropped connection surfaces as
     * end-of-stream on Linux/macOS but as a connection-reset [java.net.SocketException] on
     * Windows; both mean "nothing was sent", which is the property under test.
     */
    private fun drain(client: Socket): Int {
        var total = 0
        val buffer = ByteArray(1024)
        try {
            while (true) {
                val read = client.getInputStream().read(buffer)
                if (read == -1) break
                total += read
            }
        } catch (e: java.net.SocketException) {
            // Reset rather than a clean FIN; whatever arrived before it still counts.
        }
        return total
    }
}
