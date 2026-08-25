package com.slipstream.core.identity

import com.slipstream.core.Vectors
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.File
import java.nio.file.Files
import kotlin.io.path.createTempDirectory

class IdentityTest {

    @Test
    fun `pairing codes match the shared vectors`() {
        val cases = Json.parseToJsonElement(Vectors.read("pairing-codes.json"))
            .jsonObject["cases"]!!.jsonArray

        for (case in cases) {
            val o = case.jsonObject
            assertEquals(
                o["code"]!!.jsonPrimitive.content,
                PairingCode.derive(o["a"]!!.jsonPrimitive.content, o["b"]!!.jsonPrimitive.content),
            )
        }
    }

    @Test
    fun `derivation is order independent`() {
        val a = "0".repeat(64)
        val b = "f".repeat(64)
        assertEquals(PairingCode.derive(a, b), PairingCode.derive(b, a))
    }

    @Test
    fun `certificate is self-signed with a usable private key`() {
        val identity = DeviceIdentity.createNew("Test Phone")
        assertEquals(64, identity.fingerprint.length)
        assertTrue(identity.fingerprint.matches(Regex("^[0-9a-f]{64}$")))
        assertEquals(identity.certificate.subjectX500Principal, identity.certificate.issuerX500Principal)
    }

    @Test
    fun `loadOrCreate is stable across instances`() {
        val dir = createTempDirectory().toFile()
        val name = "Test"
        val fp1 = DeviceIdentity.loadOrCreate(dir, name).fingerprint
        val fp2 = DeviceIdentity.loadOrCreate(dir, name).fingerprint
        assertEquals(fp1, fp2)
        dir.deleteRecursively()
    }

    @Test
    fun `PairedPeerStore round-trip saves and loads JSON`() {
        val dir = Files.createTempDirectory("peer-store").toFile()
        try {
            val store = PairedPeerStore(dir)

            // Initially unpaired
            assertEquals(null, store.peer)

            // Create and store a peer
            val identity = DeviceIdentity.createNew("Test Device")
            val peer = PairedPeer(identity.deviceId, identity.fingerprint, identity.certificate)
            store.store(peer)

            // Load again
            val loaded = store.peer
            assertEquals(peer.deviceId, loaded?.deviceId)
            assertEquals(peer.fingerprint, loaded?.fingerprint)

            // Create new instance, should still have the peer
            val store2 = PairedPeerStore(dir)
            assertEquals(peer.deviceId, store2.peer?.deviceId)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `PairedPeerStore degrades to unpaired on JSON parse failure`() {
        val dir = Files.createTempDirectory("peer-corrupt").toFile()
        try {
            // Write corrupt JSON
            val file = File(dir, PairedPeerStore.PEER_FILE)
            file.writeText("{ invalid json")

            // Should degrade gracefully to unpaired
            val store = PairedPeerStore(dir)
            assertEquals(null, store.peer)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `PairedPeerStore clear on call removes peer`() {
        val dir = Files.createTempDirectory("peer-clear").toFile()
        try {
            val store = PairedPeerStore(dir)
            val identity = DeviceIdentity.createNew("Test Device")
            val peer = PairedPeer(identity.deviceId, identity.fingerprint, identity.certificate)

            store.store(peer)
            assertEquals(peer.deviceId, store.peer?.deviceId)

            // Delete the peer file
            File(dir, PairedPeerStore.PEER_FILE).delete()

            // Create new instance, should be unpaired now
            val store2 = PairedPeerStore(dir)
            assertEquals(null, store2.peer)
        } finally {
            dir.deleteRecursively()
        }
    }
}
