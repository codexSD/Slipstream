package com.slipstream.app.ui.browse

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fetches a browse-row thumbnail's bytes from the peer and decodes them, per the brief's LAN-only
 * constraint: "a plain `HttpURLConnection`/OkHttp-free loader bound to the peer's LAN address —
 * no image library that could fetch from anywhere else". [url] is always the exact
 * `http://<peer-host>:<MEDIA-port>/thumb/<token>` URL [com.slipstream.app.peer.PeerController]
 * built against the connection's own already-trusted endpoint — this loader never resolves a
 * hostname or follows a redirect to somewhere else, it only GETs the one URL it was given.
 *
 * A small in-memory LRU cache keyed by the URL (which already embeds the token) avoids
 * re-fetching the same thumbnail on every recomposition/scroll.
 */
object ThumbnailLoader {

    private const val MAX_CACHE_ENTRIES = 200
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 5_000

    private val cache = Collections.synchronizedMap(
        object : LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean =
                size > MAX_CACHE_ENTRIES
        },
    )

    /** Returns the decoded [Bitmap] for [url], from cache or a fresh fetch. Null when the fetch
     * or decode failed (peer unreachable, token expired, corrupt bytes) — the caller falls back
     * to a placeholder tile rather than crashing. Runs on [Dispatchers.IO]; never blocks the
     * caller's own thread. */
    suspend fun load(url: String): Bitmap? {
        cache[url]?.let { return it }
        return withContext(Dispatchers.IO) {
            val bitmap = fetch(url) ?: return@withContext null
            cache[url] = bitmap
            bitmap
        }
    }

    private fun fetch(url: String): Bitmap? = try {
        (URL(url).openConnection() as HttpURLConnection).run {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
            instanceFollowRedirects = false
            try {
                if (responseCode != HttpURLConnection.HTTP_OK) return null
                val bytes = inputStream.use { it.readBytes() }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } finally {
                disconnect()
            }
        }
    } catch (e: Exception) {
        null
    }

    /** Test/debug seam: drops every cached bitmap. */
    internal fun clearForTesting() = cache.clear()
}
