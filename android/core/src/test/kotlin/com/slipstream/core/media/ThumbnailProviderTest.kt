package com.slipstream.core.media

import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Before
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowBitmapFactory
import org.junit.runner.RunWith

@RunWith(RobolectricTestRunner::class)
class ThumbnailProviderTest {

    @Before
    fun setUp() {
        // By default Robolectric's BitmapFactory shadow falls back to a fixed placeholder
        // bitmap for undecodable bytes instead of failing like the real decoder does. Disable
        // that so `returns null for a corrupt file` genuinely exercises decode failure.
        ShadowBitmapFactory.setAllowInvalidImageData(false)
    }

    /** Builds a real, valid JPEG on disk via Android's own bitmap encoder - the same codec
     * path [ThumbnailProvider] decodes with - so this genuinely exercises image decoding
     * rather than a hand-rolled or stubbed file format. */
    private fun makeJpeg(dir: File, name: String = "source.jpg", width: Int = 800, height: Int = 600): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (x in 0 until width) {
            for (y in 0 until height) {
                val r = x * 255 / width
                val g = y * 255 / height
                bitmap.setPixel(x, y, (0xFF shl 24) or (r shl 16) or (g shl 8))
            }
        }
        val file = File(dir, name)
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        bitmap.recycle()
        return file
    }

    @Test
    fun `generates a thumbnail for an image`() {
        val dir = createTempDirectory().toFile()
        val cacheDir = createTempDirectory().toFile()
        val provider = ThumbnailProvider(cacheDir)

        val thumbnail = provider.generate(makeJpeg(dir))

        assertNotNull(thumbnail)
        assertTrue(thumbnail!!.length() > 0)
    }

    @Test
    fun `thumbnail long edge is scaled to 256px`() {
        val dir = createTempDirectory().toFile()
        val cacheDir = createTempDirectory().toFile()
        val provider = ThumbnailProvider(cacheDir)

        val thumbnail = provider.generate(makeJpeg(dir, width = 1600, height = 900))!!
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(thumbnail.absolutePath, bounds)

        assertEquals(256, maxOf(bounds.outWidth, bounds.outHeight))
    }

    @Test
    fun `returns null for a corrupt file`() {
        val dir = createTempDirectory().toFile()
        val cacheDir = createTempDirectory().toFile()
        val provider = ThumbnailProvider(cacheDir)

        val corrupt = File(dir, "not-an-image.jpg")
        corrupt.writeBytes(
            "this is definitely not an image file, just some plain text bytes".repeat(5).toByteArray(),
        )

        assertNull(provider.generate(corrupt))
    }

    @Test
    fun `returns null for a nonexistent file`() {
        val cacheDir = createTempDirectory().toFile()
        val provider = ThumbnailProvider(cacheDir)

        assertNull(provider.generate(File("/does/not/exist.jpg")))
    }

    @Test
    fun `second call for the same file reuses the disk cache`() {
        val dir = createTempDirectory().toFile()
        val cacheDir = createTempDirectory().toFile()
        val provider = ThumbnailProvider(cacheDir)
        val source = makeJpeg(dir)

        val first = provider.generate(source)!!
        val cachedFilesAfterFirst = cacheDir.listFiles()?.size ?: 0
        val second = provider.generate(source)!!
        val cachedFilesAfterSecond = cacheDir.listFiles()?.size ?: 0

        assertEquals(first.absolutePath, second.absolutePath)
        assertEquals(cachedFilesAfterFirst, cachedFilesAfterSecond)
    }

    @Test
    fun `cache key changes when the source file is modified`() {
        val dir = createTempDirectory().toFile()
        val cacheDir = createTempDirectory().toFile()
        val provider = ThumbnailProvider(cacheDir)
        val source = makeJpeg(dir)

        val first = provider.generate(source)!!
        source.setLastModified(source.lastModified() + 60_000)
        val second = provider.generate(source)!!

        assertNotEquals(first.absolutePath, second.absolutePath)
    }
}
