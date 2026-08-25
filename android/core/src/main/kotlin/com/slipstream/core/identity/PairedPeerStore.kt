package com.slipstream.core.identity

import java.io.File
import kotlinx.serialization.SerializationException

/**
 * PairedPeerStore manages a single paired peer, persisted as JSON.
 * Degrades to unpaired only on JSON parse failure (not I/O errors).
 * This behavior matches the C# implementation's deliberate design choice.
 */
class PairedPeerStore(private val dir: File) {
    companion object {
        const val PEER_FILE = "paired-peer.json"
    }

    private val peerFile = File(dir, PEER_FILE)
    private var _peer: PairedPeer? = null
    private var _initialized = false

    /**
     * The lazy-init pair ([_peer], [_initialized]) is read concurrently from several threads -
     * every accepted control connection, the multicast responder, and each discovery probe -
     * while [store] writes it. Two independent non-volatile fields can be observed torn (e.g.
     * `_initialized == true` while `_peer` is still null, which reads as "not paired" and
     * silently drops a legitimate peer's connection), so both the read and the write are
     * serialized on the instance monitor rather than relying on field-level volatility.
     */
    val peer: PairedPeer?
        @Synchronized get() {
            if (!_initialized) {
                _peer = loadPeer()
                _initialized = true
            }
            return _peer
        }

    @Synchronized
    fun store(peer: PairedPeer) {
        // I/O errors propagate; we don't catch them
        dir.mkdirs()
        peerFile.writeText(peer.toJson())
        _peer = peer
        _initialized = true
    }

    private fun loadPeer(): PairedPeer? {
        if (!peerFile.exists()) {
            return null
        }

        // readText() can throw I/O errors - let them propagate
        val json = peerFile.readText()

        // Only JSON parse errors degrade to unpaired
        return try {
            PairedPeer.fromJson(json)
        } catch (e: SerializationException) {
            // On JSON parse failure, degrade to unpaired
            null
        } catch (e: IllegalArgumentException) {
            // kotlinx.serialization also throws this for malformed/invalid JSON content
            null
        }
    }
}
