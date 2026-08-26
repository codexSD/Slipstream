package com.slipstream.app

import android.app.Notification
import android.app.NotificationManager
import android.content.ClipboardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNotificationManager

/**
 * Task 12 (design.md §10): received clipboard text must both land on the system clipboard
 * (already covered by [SlipstreamApplicationPlayTest]'s sibling test file existing for `play` -
 * this is the clipboard half, exercised end-to-end via [SlipstreamApplication.onCreate]'s real
 * collector below) and post a notification with a paste affordance - or, for a URL, an "Open"
 * affordance instead.
 */
@RunWith(RobolectricTestRunner::class)
class ClipboardNotificationsTest {

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    private fun notificationManager(): ShadowNotificationManager =
        shadowOf(context().getSystemService(NotificationManager::class.java))

    @Test
    fun `plain text is labelled as a paste affordance, not open`() {
        val notification = buildClipboardNotification(context(), "just some copied text")

        assertEquals(
            "Paste",
            notification.actions.single().title.toString(),
        )
    }

    @Test
    fun `a url is detected and offered an Open affordance instead of Paste`() {
        val notification = buildClipboardNotification(context(), "https://example.com/report.pdf")

        assertEquals(
            "Open",
            notification.actions.single().title.toString(),
        )
    }

    @Test
    fun `a url with surrounding whitespace is still detected`() {
        assertTrue(isLikelyUrl("  http://192.168.1.5:8080/file  "))
    }

    @Test
    fun `plain text is not mistaken for a url`() {
        assertTrue(!isLikelyUrl("call me at 555-0100, not a link"))
    }

    @Test
    fun `posting the notification actually delivers it through NotificationManager`() {
        postClipboardNotification(context(), "hello from the peer")

        val posted: Notification = notificationManager().getNotification(CLIPBOARD_NOTIFICATION_ID)
        assertEquals("Slipstream", posted.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
    }

    /**
     * End-to-end through the real collector [SlipstreamApplication.onCreate] wires up: emitting
     * into [SlipstreamApplication.clipboardSink] must both write to [ClipboardManager] (the
     * pre-existing behaviour) and post the notification added by this task - not one or the other.
     */
    @Test
    fun `incoming clipboard text reaches both the system clipboard and a notification`() {
        val app: SlipstreamApplication = ApplicationProvider.getApplicationContext()
        val clipboardManager = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        app.clipboardSink.setText("copied on the peer")

        // The collector runs on Dispatchers.Default, off the test thread - poll briefly.
        val gotClip = pollUntilTrue(timeoutMs = 2_000) {
            clipboardManager.hasPrimaryClip() &&
                clipboardManager.primaryClip?.getItemAt(0)?.text?.toString() == "copied on the peer"
        }
        assertTrue("clipboard text must still reach ClipboardManager", gotClip)

        val gotNotification = pollUntilTrue(timeoutMs = 2_000) {
            notificationManager().allNotifications.any { it.id == CLIPBOARD_NOTIFICATION_ID }
        }
        assertTrue("a notification must be posted for the same text", gotNotification)
    }

    private fun pollUntilTrue(timeoutMs: Long, check: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (check()) return true
            Thread.sleep(20)
        }
        return check()
    }
}
