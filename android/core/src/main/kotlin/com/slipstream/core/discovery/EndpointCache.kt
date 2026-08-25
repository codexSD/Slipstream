package com.slipstream.core.discovery

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * EndpointCache is a persistent map of network identity (LocalNetwork.key) to
 * last-known peer endpoint (host:port). Used by discovery strategy S1 to avoid
 * a multicast sweep when we already know a peer's address.
 *
 * Degrades to empty cache only on JSON parse failure (not I/O errors).
 */
class EndpointCache(private val dir: File) {
    companion object {
        const val CACHE_FILE = "endpoint-cache.json"
    }

    private val cacheFile = File(dir, CACHE_FILE)
    private var _cache: MutableMap<String, String> = mutableMapOf()
    private var _initialized = false

    private fun ensureInitialized() {
        if (!_initialized) {
            _cache = loadCache().toMutableMap()
            _initialized = true
        }
    }

    /**
     * Get the last-known endpoint for a network key, or null if not cached.
     */
    fun get(key: String): String? {
        ensureInitialized()
        return _cache[key]
    }

    /**
     * Store a network key → endpoint mapping. Persists to disk.
     */
    fun put(key: String, endpoint: String) {
        ensureInitialized()
        _cache[key] = endpoint
        saveCache()
    }

    private fun saveCache() {
        dir.mkdirs()
        val wrapper = CacheWrapper(_cache)
        cacheFile.writeText(Json.encodeToString(wrapper))
    }

    private fun loadCache(): Map<String, String> {
        if (!cacheFile.exists()) {
            return emptyMap()
        }

        // readText() can throw I/O errors - let them propagate
        val json = cacheFile.readText()

        // Only JSON parse errors degrade to empty cache
        return try {
            val wrapper = Json.decodeFromString<CacheWrapper>(json)
            wrapper.endpoints
        } catch (e: SerializationException) {
            // On JSON parse failure, degrade to empty cache
            emptyMap()
        } catch (e: IllegalArgumentException) {
            // kotlinx.serialization also throws this for malformed/invalid JSON
            emptyMap()
        }
    }
}

/**
 * Wrapper for JSON serialization of the cache map.
 */
@Serializable
internal data class CacheWrapper(
    val endpoints: Map<String, String>,
)
