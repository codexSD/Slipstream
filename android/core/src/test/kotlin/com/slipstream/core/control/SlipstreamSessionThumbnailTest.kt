package com.slipstream.core.control

import com.slipstream.core.identity.DeviceIdentity
import com.slipstream.core.media.MediaTokenVault
import com.slipstream.core.media.ThumbnailProvider
import com.slipstream.core.transfer.TokenVault
import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.io.path.createTempDirectory
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowBitmapFactory

/**
 * Closes a Plan 3 deviation: [ThumbnailProvider] existed but had no production caller. `list`
 * must issue a `/thumb/<token>` for each image entry, using the same [MediaTokenVault] the
 * `stream.request` handler already issues playback tokens from.
 */
@RunWith(RobolectricTestRunner::class)
class SlipstreamSessionThumbnailTest {

    @org.junit.Before
    fun setUp() {
        ShadowBitmapFactory.setAllowInvalidImageData(false)
    }

    private fun makeJpeg(dir: File, name: String = "photo.jpg"): File {
        val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
        val file = File(dir, name)
        FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out) }
        bitmap.recycle()
        return file
    }

    private fun newSession(
        root: File,
        mediaTokenVault: MediaTokenVault = MediaTokenVault(),
        thumbnailProvider: ThumbnailProvider? = ThumbnailProvider(createTempDirectory().toFile()),
    ) = SlipstreamSession(
        identity = DeviceIdentity.createNew("Pixel"),
        rootDirectory = root,
        bulkTokenVault = TokenVault(),
        bulkEndpoint = { InetSocketAddress(InetAddress.getByName("127.0.0.1"), 53322) },
        mediaTokenVault = mediaTokenVault,
        mediaPort = { 53323 },
        clipboardSink = ClipboardSink { },
        thumbnailProvider = thumbnailProvider,
    )

    @Test
    fun `list issues a thumbnail token for an image entry`() {
        val root = createTempDirectory().toFile()
        makeJpeg(root)
        val session = newSession(root = root)

        val reply = session.dispatch(
            ControlMessage(type = SessionMessageTypes.LIST, id = "1", payload = JsonObject(mapOf("path" to JsonPrimitive(".")))),
        )

        val entries = requireNotNull(reply?.payload)["entries"]!!.jsonArray
        val photoEntry = entries.first { it.jsonObject.getValue("name").jsonPrimitive.content == "photo.jpg" }
        val token = photoEntry.jsonObject["thumbnailToken"]?.jsonPrimitive?.contentOrNull
        assertTrue("expected a non-blank thumbnailToken for an image entry", !token.isNullOrBlank())
    }

    @Test
    fun `the issued thumbnail token resolves through the same mediaTokenVault`() {
        val root = createTempDirectory().toFile()
        makeJpeg(root)
        val vault = MediaTokenVault()
        val session = newSession(root = root, mediaTokenVault = vault)

        val reply = session.dispatch(
            ControlMessage(type = SessionMessageTypes.LIST, id = "1", payload = JsonObject(mapOf("path" to JsonPrimitive(".")))),
        )
        val entries = requireNotNull(reply?.payload)["entries"]!!.jsonArray
        val token = entries.first().jsonObject["thumbnailToken"]!!.jsonPrimitive.content

        val resolved = vault.validate(java.util.UUID.fromString(token))
        assertTrue(resolved != null && resolved.mime == "image/jpeg")
    }

    @Test
    fun `list omits thumbnailToken for a non-image entry`() {
        val root = createTempDirectory().toFile()
        File(root, "notes.txt").writeText("hello")
        val session = newSession(root = root)

        val reply = session.dispatch(
            ControlMessage(type = SessionMessageTypes.LIST, id = "1", payload = JsonObject(mapOf("path" to JsonPrimitive(".")))),
        )
        val entries = requireNotNull(reply?.payload)["entries"]!!.jsonArray
        val txtEntry = entries.first { it.jsonObject.getValue("name").jsonPrimitive.content == "notes.txt" }
        assertNull(txtEntry.jsonObject["thumbnailToken"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `list without a thumbnailProvider never crashes and omits tokens`() {
        val root = createTempDirectory().toFile()
        makeJpeg(root)
        val session = newSession(root = root, thumbnailProvider = null)

        val reply = session.dispatch(
            ControlMessage(type = SessionMessageTypes.LIST, id = "1", payload = JsonObject(mapOf("path" to JsonPrimitive(".")))),
        )
        val entries = requireNotNull(reply?.payload)["entries"]!!.jsonArray
        assertEquals(1, entries.size)
        assertNull(entries.first().jsonObject["thumbnailToken"]?.jsonPrimitive?.contentOrNull)
    }
}
