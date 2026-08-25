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

    val peer: PairedPeer?
        get() {
            if (!_initialized) {
                _peer = loadPeer()
                _initialized = true
            }
            return _peer
        }

    fun store(peer: PairedPeer) {
        // I/O errors propagate; we don't catch them
        dir.mkdirs()
        peerFile.writeText(peer.toJson())
        _peer = peer
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
