package com.slipstream.app

import android.app.AlertDialog
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

/**
 * Task 12: [PermissionGate] shows a rationale dialog before the real `POST_NOTIFICATIONS`
 * runtime prompt fires - unlike Task 1's original direct-request behaviour, tapping "Continue"
 * on that dialog is now required before [android.app.Activity.requestPermissions] is ever called.
 * The dialog interaction itself is exercised more thoroughly in `PermissionGateTest`; these
 * confirm [MainActivity.onCreate] actually wires [MainActivity.permissionGate] in.
 */
@RunWith(RobolectricTestRunner::class)
class MainActivityTest {

    @Test
    @Config(sdk = [34])
    fun `notification permission is requested at runtime on API 33 and up, after the rationale is accepted`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()

        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertTrue("a rationale dialog must be shown before the OS prompt", dialog != null)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        // AlertDialog button clicks run via a posted Runnable, not synchronously - see
        // PermissionGateTest's `clickAndIdle` helper doc for the same note.
        shadowOf(Looper.getMainLooper()).idle()

        val requested = shadowOf(controller.get()).lastRequestedPermission
        assertEquals(
            listOf(com.slipstream.app.permissions.PermissionGate.POST_NOTIFICATIONS),
            requested?.requestedPermissions?.toList(),
        )
        controller.destroy()
    }

    @Test
    @Config(sdk = [30])
    fun `nothing is requested below API 33, where the permission does not exist`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        assertNull(shadowOf(controller.get()).lastRequestedPermission)
        controller.destroy()
    }
}
