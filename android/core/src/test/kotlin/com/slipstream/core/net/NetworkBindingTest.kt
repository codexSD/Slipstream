package com.slipstream.core.net

import com.slipstream.core.control.ControlServer
import com.slipstream.core.control.PinnedTls
import com.slipstream.core.discovery.UdpMulticastTransport
import com.slipstream.core.identity.DeviceIdentity
import com.slipstream.core.identity.PairedPeerStore
import com.slipstream.core.transfer.BulkClient
import com.slipstream.core.transfer.BulkServer
import com.slipstream.core.transfer.PartFile
import com.slipstream.core.transfer.TokenVault
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private val LOOPBACK: InetAddress = InetAddress.getByName("127.0.0.1")

/** Records every socket handed to it, so tests can assert binding actually happened - and
 * happened before the socket was used - without needing a real [android.net.Network]. */
private class RecordingNetworkBinder : NetworkBinder {
    val boundSockets = AtomicInteger(0)
    val boundDatagramSockets = AtomicInteger(0)

    override fun bind(socket: Socket) {
        // A real Network.bindSocket() requires the socket to be unconnected; assert that
        // invariant here too, so this test would fail if a caller ever binds too late.
        assertTrue("socket must be bound before it connects", !socket.isConnected)
        boundSockets.incrementAndGet()
    }

    override fun bind(socket: DatagramSocket) {
        boundDatagramSockets.incrementAndGet()
    }
}

private fun emptyPeerStore(): PairedPeerStore = PairedPeerStore(createTempDirectory().toFile())

class NetworkBindingTest {

    @Test
    fun `PinnedTls connect binds the client socket to the given network before connecting`() {
        val serverIdentity = DeviceIdentity.createNew("Server")
        val server = ControlServer(serverIdentity, emptyPeerStore(), object : com.slipstream.core.net.NetworkInfo {
            override fun current() = LocalNetwork(LOOPBACK, null, 32, "k")
        }, port = 0)
        val binder = RecordingNetworkBinder()
        try {
            val socket = PinnedTls.connect(server.listenEndpoint, DeviceIdentity.createNew("Client"), binder) { true }
            assertEquals(1, binder.boundSockets.get())
            socket.close()
        } finally {
            server.close()
        }
    }

    @Test
    fun `BulkClient binds every per-range socket it opens to the given network`() {
        val tokenVault = TokenVault()
        val srcRoot = createTempDirectory().toFile()
        val srcFile = java.io.File(srcRoot, "src.bin").apply { writeBytes(ByteArray(64) { it.toByte() }) }
        val transferId = UUID.randomUUID()
        val token = tokenVault.issueBulk(transferId, srcFile.path, srcFile.length(), 1)

        val server = BulkServer(tokenVault, fileForTransfer = { if (it == transferId) srcFile else null }, port = 0)
        val binder = RecordingNetworkBinder()
        try {
            val destRoot = createTempDirectory().toFile()
            val part = PartFile.openOrCreate(
                java.io.File(destRoot, "dst.bin"), transferId, srcFile.length(), chunkSize = 16,
            )
            val client = BulkClient(binder = binder)
            client.download(
                java.net.InetSocketAddress(LOOPBACK, server.boundPort),
                transferId,
                token.value,
                part,
                streams = 1,
                onProgress = null,
            )
            assertTrue("expected at least one socket bound", binder.boundSockets.get() >= 1)
            part.close()
        } finally {
            server.close()
        }
    }

    @Test
    fun `UdpMulticastTransport binds its socket to the given network`() {
        val binder = RecordingNetworkBinder()
        val transport = UdpMulticastTransport(binder = binder)
        try {
            assertEquals(1, binder.boundDatagramSockets.get())
        } finally {
            transport.close()
        }
    }

    @Test
    fun `NetworkBinder NONE is a safe no-op default`() {
        // Exercises the default path used everywhere a caller hasn't set up a binder yet.
        NetworkBinder.NONE.bind(Socket())
        NetworkBinder.NONE.bind(DatagramSocket())
    }

    @Test
    fun `MutableNetworkBinder swallows binding failures rather than throwing`() {
        val binder = MutableNetworkBinder()
        // network stays null: bind() must be a safe no-op, not a NullPointerException.
        binder.bind(Socket())
        binder.bind(DatagramSocket())
    }

    @Test
    fun `MulticastTransport does not require binding when using default NONE binder`() {
        // Sanity check that the new binder parameter is optional and doesn't change default behavior.
        val transport = UdpMulticastTransport()
        transport.close()
    }
}
