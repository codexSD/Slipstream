package com.slipstream.app

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Task 11 (design.md §8, push-to-play, inbound direction): whatever [SlipstreamApplication.onCreate]
 * collects off [SlipstreamApplication.playSink] must turn into a real `ACTION_VIEW` `Intent`,
 * with the flags a collector outside any `Activity` context requires. Emits directly into
 * [SlipstreamApplication.playSink] (Robolectric never runs a real peer/socket here) rather than
 * driving a whole [com.slipstream.app.peer.RealPeerController] - that real wire path is proven
 * separately by `RealPeerControllerTest`/`SlipstreamPeerPairingTest`.
 */
@RunWith(RobolectricTestRunner::class)
class SlipstreamApplicationPlayTest {

    /** Robolectric already calls [SlipstreamApplication.onCreate] once while bootstrapping the
     * application context, exactly as the real Android framework would - calling it again here
     * would register a second collector on [SlipstreamApplication.playSink] and double-fire
     * `startActivity` for a single event. */
    private fun app(): SlipstreamApplication =
        ApplicationProvider.getApplicationContext()

    /** [SlipstreamApplication.onCreate]'s collector runs on a real [Dispatchers.Default] thread,
     * not the test's own thread - so the resulting `startActivity` call lands asynchronously. */
    private fun awaitStartedActivity(app: SlipstreamApplication): Intent? {
        repeat(100) {
            val started = shadowOf(app).nextStartedActivity
            if (started != null) return started
            Thread.sleep(20)
        }
        return null
    }

    @Test
    fun `a remote url play request opens ACTION_VIEW against that url with its mime type`() {
        val app = app()

        app.playSink.onRemoteUrl("http://192.168.1.5:53324/media/tok-1", "video/mp4")

        val started = requireNotNull(awaitStartedActivity(app)) { "no activity was ever started" }
        assertEquals("must launch an ACTION_VIEW intent", Intent.ACTION_VIEW, started.action)
        assertEquals("http://192.168.1.5:53324/media/tok-1", started.data.toString())
        assertEquals("video/mp4", started.type)
        assertTrue(
            "must carry FLAG_ACTIVITY_NEW_TASK since this fires outside an Activity context",
            (started.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0,
        )
    }

    @Test
    fun `a local file play request opens ACTION_VIEW against a content uri, not a bare file uri`() {
        val app = app()
        // Must live under one of file_paths.xml's declared roots (files-path/external-files-path)
        // - SlipstreamApplication.localFileContentUri throws for anything outside them.
        val file = File(app.filesDir, "song.mp3").apply { writeBytes(ByteArray(4)) }

        app.playSink.onLocalFile(file, "audio/mpeg")

        val started = requireNotNull(awaitStartedActivity(app)) { "no activity was ever started" }
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals("audio/mpeg", started.type)
        assertEquals("content", started.data?.scheme)
        assertEquals("com.slipstream.app.fileprovider", started.data?.authority)
        assertTrue(
            "must grant read access to the resolved player, since it never had one otherwise",
            (started.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0,
        )
    }

    @Test
    fun `a local file nested under a subdirectory of filesDir resolves to a content uri under the right root`() {
        val app = app()
        val nested = File(app.filesDir, "slipstream/incoming/clip.mp4").apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(4))
        }

        app.playSink.onLocalFile(nested, "video/mp4")

        val started = requireNotNull(awaitStartedActivity(app)) { "no activity was ever started" }
        assertEquals("content", started.data?.scheme)
        assertEquals(
            "the encoded path must carry the internal_root name and the nested relative path",
            "/internal_root/slipstream/incoming/clip.mp4",
            started.data?.encodedPath,
        )
    }
}
