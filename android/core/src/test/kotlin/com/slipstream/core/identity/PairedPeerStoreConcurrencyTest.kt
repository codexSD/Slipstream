package com.slipstream.core.identity

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PairedPeerStore] is read from several threads at once in production - every accepted
 * control connection checks the paired fingerprint, the multicast responder checks it for
 * every datagram, and each discovery probe checks it - while [PairedPeerStore.store] writes
 * it from a pairing thread. Its lazy-init state used to be two ordinary (non-volatile,
 * unsynchronized) fields, so a reader could see `_initialized == true` alongside a `_peer`
 * that had not been published yet, and conclude "not paired" for a peer that is.
 */
class PairedPeerStoreConcurrencyTest {

    @Test
    fun `a concurrently stored peer is never observed as a torn half-initialized state`() {
        repeat(REPEATS) {
            val dir = createTempDirectory().toFile()
            try {
                val store = PairedPeerStore(dir)
                val identity = DeviceIdentity.createNew("Peer")
                val peer = PairedPeer(identity.deviceId, identity.fingerprint, identity.certificate)

                val failures = CopyOnWriteArrayList<String>()
                val barrier = CyclicBarrier(READERS + 1)
                val seenPaired = CopyOnWriteArrayList<Boolean>()

                val readers = (0 until READERS).map {
                    thread(isDaemon = true) {
                        barrier.await(10, TimeUnit.SECONDS)
                        repeat(READS_PER_THREAD) {
                            val observed = store.peer
                            if (observed != null) {
                                // Any non-null read must be fully formed - a torn publish would
                                // show up here as a peer with an empty fingerprint or id.
                                if (observed.fingerprint != peer.fingerprint || observed.deviceId != peer.deviceId) {
                                    failures.add("observed $observed")
                                }
                                seenPaired.add(true)
                            }
                        }
                    }
                }

                barrier.await(10, TimeUnit.SECONDS)
                store.store(peer)
                readers.forEach { it.join(10_000) }

                assertTrue("torn reads: $failures", failures.isEmpty())
                // And once the write has landed, the store reports it.
                assertTrue(store.peer?.fingerprint == peer.fingerprint)
            } finally {
                dir.deleteRecursively()
            }
        }
    }

    private companion object {
        const val REPEATS = 20
        const val READERS = 4
        const val READS_PER_THREAD = 200
    }
}
