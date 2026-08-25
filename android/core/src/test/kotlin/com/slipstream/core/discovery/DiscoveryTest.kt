package com.slipstream.core.discovery

import com.slipstream.core.SlipstreamPorts
import com.slipstream.core.Vectors
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.io.File
import java.nio.file.Files
import kotlin.io.path.createTempDirectory

class PeerAnnouncementTest {

    @Test
    fun `announcement vectors round-trip byte-for-byte`() {
        val vectors = Json.parseToJsonElement(Vectors.read("announcements.json"))
            .jsonObject["cases"]!!.jsonArray

        for (case in vectors) {
            val o = case.jsonObject
            val originalJson = o["json"]!!.jsonPrimitive.content

            // Parse the JSON
            val parsed = PeerAnnouncement.tryParse(originalJson)
            assertNotNull("Failed to parse ${o["name"]}: $originalJson", parsed)

            // Re-serialize and verify byte-for-byte match
            val serialized = parsed!!.toJson()
            assertEquals(
                "Serialization mismatch for ${o["name"]}",
                originalJson,
                serialized,
            )
        }
    }

    @Test
    fun `field order is preserved in serialization`() {
        val announcement = PeerAnnouncement(
            v = 1,
            deviceId = "abc123",
            name = "Test PC",
            fingerprint = "deadbeef",
            control = 53321,
            kind = "announce",
        )

        val json = announcement.toJson()

        // Verify field order by checking positions in the JSON string
        val vPos = json.indexOf("\"v\"")
        val deviceIdPos = json.indexOf("\"deviceId\"")
        val namePos = json.indexOf("\"name\"")
        val fingerprintPos = json.indexOf("\"fingerprint\"")
        val controlPos = json.indexOf("\"control\"")
        val kindPos = json.indexOf("\"kind\"")

        assertTrue("v should come first", vPos < deviceIdPos)
        assertTrue("deviceId should come second", deviceIdPos < namePos)
        assertTrue("name should come third", namePos < fingerprintPos)
        assertTrue("fingerprint should come fourth", fingerprintPos < controlPos)
        assertTrue("control should come fifth", controlPos < kindPos)
    }

    @Test
    fun `tryParse returns null for empty string`() {
        assertNull(PeerAnnouncement.tryParse(""))
    }

    @Test
    fun `tryParse returns null for non-JSON garbage`() {
        assertNull(PeerAnnouncement.tryParse("not json at all"))
        assertNull(PeerAnnouncement.tryParse("{ invalid json"))
    }

    @Test
    fun `tryParse returns null for empty object`() {
        assertNull(PeerAnnouncement.tryParse("{}"))
    }

    @Test
    fun `tryParse returns null for missing fields`() {
        // Missing v
        assertNull(PeerAnnouncement.tryParse("""{"deviceId":"abc","name":"Test","fingerprint":"abc","control":53321,"kind":"announce"}"""))

        // Missing deviceId
        assertNull(PeerAnnouncement.tryParse("""{"v":1,"name":"Test","fingerprint":"abc","control":53321,"kind":"announce"}"""))

        // Missing fingerprint
        assertNull(PeerAnnouncement.tryParse("""{"v":1,"deviceId":"abc","name":"Test","control":53321,"kind":"announce"}"""))

        // Missing control
        assertNull(PeerAnnouncement.tryParse("""{"v":1,"deviceId":"abc","name":"Test","fingerprint":"abc","kind":"announce"}"""))

        // Missing kind
        assertNull(PeerAnnouncement.tryParse("""{"v":1,"deviceId":"abc","name":"Test","fingerprint":"abc","control":53321}"""))
    }

    @Test
    fun `tryParse returns null for blank deviceId`() {
        assertNull(PeerAnnouncement.tryParse("""{"v":1,"deviceId":"","name":"Test","fingerprint":"abc","control":53321,"kind":"announce"}"""))
    }

    @Test
    fun `tryParse returns null for blank fingerprint`() {
        assertNull(PeerAnnouncement.tryParse("""{"v":1,"deviceId":"abc","name":"Test","fingerprint":"","control":53321,"kind":"announce"}"""))
    }

    @Test
    fun `tryParse returns null for wrong protocol version`() {
        assertNull(PeerAnnouncement.tryParse("""{"v":2,"deviceId":"abc","name":"Test","fingerprint":"abc","control":53321,"kind":"announce"}"""))
        assertNull(PeerAnnouncement.tryParse("""{"v":0,"deviceId":"abc","name":"Test","fingerprint":"abc","control":53321,"kind":"announce"}"""))
    }

    @Test
    fun `tryParse returns null for control port out of range`() {
        // Too low
        assertNull(PeerAnnouncement.tryParse("""{"v":1,"deviceId":"abc","name":"Test","fingerprint":"abc","control":0,"kind":"announce"}"""))

        // Too high
        assertNull(PeerAnnouncement.tryParse("""{"v":1,"deviceId":"abc","name":"Test","fingerprint":"abc","control":65536,"kind":"announce"}"""))

        // Negative
        assertNull(PeerAnnouncement.tryParse("""{"v":1,"deviceId":"abc","name":"Test","fingerprint":"abc","control":-1,"kind":"announce"}"""))
    }

    @Test
    fun `payload stays under 1024 bytes for realistic instance`() {
        val announcement = PeerAnnouncement(
            v = SlipstreamPorts.PROTOCOL_VERSION,
            deviceId = "3ff30679fabe9b70581b49cce48bf9dc", // 32 chars, realistic
            name = "My Phone Device Display Name Here", // Realistic name
            fingerprint = "63ecadfc1fe320f16dd69281b0ad8d42b81023089e8564054f02b721fe9fac33", // 64 chars
            control = 53321,
            kind = "announce",
        )

        val json = announcement.toJson()
        val bytes = json.toByteArray(Charsets.UTF_8)

        assertTrue("Payload size ${bytes.size} should be under 1024 bytes", bytes.size < 1024)
    }

    @Test
    fun `both announce and query kinds are valid`() {
        val announce = PeerAnnouncement.tryParse("""{"v":1,"deviceId":"abc","name":"Test","fingerprint":"abc","control":53321,"kind":"announce"}""")
        assertNotNull("announce kind should be valid", announce)

        val query = PeerAnnouncement.tryParse("""{"v":1,"deviceId":"abc","name":"Test","fingerprint":"abc","control":53321,"kind":"query"}""")
        assertNotNull("query kind should be valid", query)
    }
}

class EndpointCacheTest {

    @Test
    fun `get returns null for unknown network key`() {
        val dir = createTempDirectory().toFile()
        try {
            val cache = EndpointCache(dir)
            assertNull(cache.get("unknown-key"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `put and get round-trip`() {
        val dir = createTempDirectory().toFile()
        try {
            val cache = EndpointCache(dir)
            val key = "192.168.1.0/24"
            val endpoint = "192.168.1.100:53321"

            cache.put(key, endpoint)
            assertEquals(endpoint, cache.get(key))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `cache persists across instances`() {
        val dir = createTempDirectory().toFile()
        try {
            val key = "192.168.1.0/24"
            val endpoint = "192.168.1.100:53321"

            // Store in first instance
            val cache1 = EndpointCache(dir)
            cache1.put(key, endpoint)

            // Load in second instance
            val cache2 = EndpointCache(dir)
            assertEquals(endpoint, cache2.get(key))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `put overwrites previous value`() {
        val dir = createTempDirectory().toFile()
        try {
            val cache = EndpointCache(dir)
            val key = "192.168.1.0/24"

            cache.put(key, "192.168.1.100:53321")
            assertEquals("192.168.1.100:53321", cache.get(key))

            cache.put(key, "192.168.1.200:53321")
            assertEquals("192.168.1.200:53321", cache.get(key))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `multiple keys can be stored independently`() {
        val dir = createTempDirectory().toFile()
        try {
            val cache = EndpointCache(dir)
            val key1 = "192.168.1.0/24"
            val key2 = "10.0.0.0/24"
            val endpoint1 = "192.168.1.100:53321"
            val endpoint2 = "10.0.0.100:53321"

            cache.put(key1, endpoint1)
            cache.put(key2, endpoint2)

            assertEquals(endpoint1, cache.get(key1))
            assertEquals(endpoint2, cache.get(key2))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `cache degrades gracefully to empty on JSON parse failure`() {
        val dir = createTempDirectory().toFile()
        try {
            // Write corrupt JSON
            val file = File(dir, EndpointCache.CACHE_FILE)
            file.writeText("{ invalid json")

            // Should not throw, just start empty
            val cache = EndpointCache(dir)
            assertNull(cache.get("some-key"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
