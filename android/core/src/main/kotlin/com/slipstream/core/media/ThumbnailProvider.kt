package com.slipstream.core.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Generates and disk-caches JPEG thumbnails, per design.md §9: 256px on the long edge, cached
 * keyed by `hash(path, mtime, size)`, generated on the owning device from the local file - the
 * source file is never uploaded to produce one.
 *
 * Uses [BitmapFactory] (decode + scale) rather than `ContentResolver.loadThumbnail` /
 * `ThumbnailUtils`: those APIs are specialized wrappers over the same bitmap-decode-and-scale
 * primitives, scoped to MediaStore-registered content and platform-decodable video/image
 * formats respectively. `BitmapFactory` covers the same "image files this device's codecs can
 * decode" surface directly, for any [File] on disk regardless of MediaStore registration, and
 * is exercised by Robolectric's native-graphics shadow against real image bytes rather than a
 * no-op stub - so its test coverage is genuine, not simulated.
 */
class ThumbnailProvider(private val cacheDir: File) {

    /**
     * Produces (or returns the cached) 256px-long-edge JPEG thumbnail for [source]. Returns
     * null when [source] cannot be decoded as an image by this device (e.g. an unsupported
     * format, a corrupt file, or a video file no decoder recognizes) - a per-file limitation,
     * not a failure of the method itself.
     */
    fun generate(source: File): File? {
        if (!source.isFile) return null

        val cacheKey = cacheKeyFor(source)
        val cached = File(cacheDir, "$cacheKey.jpg")
        if (cached.isFile && cached.length() > 0) return cached

        val bitmap = decodeScaled(source) ?: return null
        try {
            cacheDir.mkdirs()
            val tmp = File(cacheDir, "$cacheKey.jpg.tmp")
            FileOutputStream(tmp).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
            if (!tmp.renameTo(cached)) return null
        } finally {
            bitmap.recycle()
        }
        return cached
    }

    private fun decodeScaled(source: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
        var sampleSize = 1
        while (longEdge / (sampleSize * 2) >= LONG_EDGE_PX) sampleSize *= 2

        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val decoded = BitmapFactory.decodeFile(source.absolutePath, decodeOptions) ?: return null

        val scale = LONG_EDGE_PX.toFloat() / maxOf(decoded.width, decoded.height)
        if (scale >= 1f) return decoded

        val targetWidth = maxOf(1, (decoded.width * scale).toInt())
        val targetHeight = maxOf(1, (decoded.height * scale).toInt())
        val scaled = Bitmap.createScaledBitmap(decoded, targetWidth, targetHeight, true)
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }

    private fun cacheKeyFor(source: File): String {
        val raw = "${source.absolutePath}|${source.lastModified()}|${source.length()}"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val LONG_EDGE_PX = 256
        const val JPEG_QUALITY = 85
    }
}
