package com.slipstream.app.permissions

import android.app.AlertDialog
import android.content.Context
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowPowerManager

/**
 * Task 12: [PermissionGate] must (a) show a plain-language rationale before any of the three
 * system prompts, (b) never ask for the same one twice in one install, and (c) leave the app
 * functional either way - none of these three checks throws or blocks anything when denied.
 */
@RunWith(RobolectricTestRunner::class)
class PermissionGateTest {

    private lateinit var activity: androidx.activity.ComponentActivity
    private lateinit var gate: PermissionGate

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(androidx.activity.ComponentActivity::class.java).setup().get()
        gate = PermissionGate(activity)
        prefs().edit().clear().apply()
    }

    private fun prefs() = activity.getSharedPreferences(PermissionGate.PREFS_NAME, Context.MODE_PRIVATE)

    /** [AlertDialog]'s button `OnClickListener`s run via a posted `Runnable`, not synchronously
     * on [android.view.View.performClick] - without idling the main looper afterwards, the
     * click is recorded but [PermissionGate.Ask.onContinue] never actually runs, per Robolectric's
     * own "Main looper has queued unexecuted runnables" hint. */
    private fun clickAndIdle(dialog: AlertDialog, which: Int) {
        dialog.getButton(which).performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    // --- POST_NOTIFICATIONS -------------------------------------------------------------------

    @Test
    @Config(sdk = [33])
    fun `notifications rationale is shown before the runtime prompt on API 33+`() {
        gate.maybeRequestNotifications()

        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertTrue("a rationale dialog must appear", dialog != null)
        // The OS prompt must not have fired yet - only tapping Continue triggers it.
        assertNull(shadowOf(activity).lastRequestedPermission)
    }

    @Test
    @Config(sdk = [33])
    fun `accepting the notifications rationale triggers the real permission request`() {
        gate.maybeRequestNotifications()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        clickAndIdle(dialog, AlertDialog.BUTTON_POSITIVE)

        val requested = shadowOf(activity).lastRequestedPermission
        assertEquals(listOf(PermissionGate.POST_NOTIFICATIONS), requested?.requestedPermissions?.toList())
    }

    @Test
    @Config(sdk = [33])
    fun `declining the notifications rationale never requests the permission`() {
        gate.maybeRequestNotifications()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        clickAndIdle(dialog, AlertDialog.BUTTON_NEGATIVE)

        assertNull(shadowOf(activity).lastRequestedPermission)
    }

    @Test
    @Config(sdk = [33])
    fun `notifications are asked at most once per install, even after being declined`() {
        gate.maybeRequestNotifications()
        val firstDialog = ShadowAlertDialog.getLatestAlertDialog()
        clickAndIdle(firstDialog, AlertDialog.BUTTON_NEGATIVE)

        gate.maybeRequestNotifications()

        assertSame(
            "must not show a second dialog in the same install",
            firstDialog,
            ShadowAlertDialog.getLatestAlertDialog(),
        )
    }

    @Test
    @Config(sdk = [33])
    fun `nothing is asked when the permission is already granted`() {
        shadowOf(activity).grantPermissions(PermissionGate.POST_NOTIFICATIONS)

        gate.maybeRequestNotifications()

        assertNull(ShadowAlertDialog.getLatestAlertDialog())
    }

    @Test
    @Config(sdk = [30])
    fun `nothing is asked below API 33, where the permission does not exist`() {
        gate.maybeRequestNotifications()
        assertNull(ShadowAlertDialog.getLatestAlertDialog())
        assertNull(shadowOf(activity).lastRequestedPermission)
    }

    // --- Battery optimisation exemption -------------------------------------------------------

    @Test
    @Config(sdk = [30])
    fun `battery rationale is shown, and accepting it opens the ignore-battery-optimizations settings screen`() {
        gate.maybeRequestBatteryExemption()
        val dialog = requireNotNull(ShadowAlertDialog.getLatestAlertDialog()) { "rationale must be shown" }
        clickAndIdle(dialog, AlertDialog.BUTTON_POSITIVE)

        val started = shadowOf(activity).nextStartedActivity
        assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, started?.action)
    }

    @Test
    @Config(sdk = [30])
    fun `battery exemption is not asked when already granted`() {
        val shadowPowerManager: ShadowPowerManager =
            shadowOf(activity.getSystemService(PowerManager::class.java))
        shadowPowerManager.setIgnoringBatteryOptimizations(activity.packageName, true)

        gate.maybeRequestBatteryExemption()

        assertNull(ShadowAlertDialog.getLatestAlertDialog())
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test
    @Config(sdk = [30])
    fun `battery exemption is asked at most once per install`() {
        gate.maybeRequestBatteryExemption()
        val firstDialog = ShadowAlertDialog.getLatestAlertDialog()
        clickAndIdle(firstDialog, AlertDialog.BUTTON_NEGATIVE)

        gate.maybeRequestBatteryExemption()

        assertSame(firstDialog, ShadowAlertDialog.getLatestAlertDialog())
    }

    // --- MANAGE_EXTERNAL_STORAGE -------------------------------------------------------------

    @Test
    @Config(sdk = [30])
    fun `storage rationale names the exact spec section 15 denial message`() {
        gate.maybeRequestStorage()
        val dialog = requireNotNull(ShadowAlertDialog.getLatestAlertDialog()) { "rationale must be shown" }

        assertEquals(
            "Slipstream needs file access to browse this device.",
            shadowOf(dialog).message.toString(),
        )
    }

    @Test
    @Config(sdk = [30])
    fun `accepting the storage rationale opens the all-files-access settings screen`() {
        gate.maybeRequestStorage()
        clickAndIdle(ShadowAlertDialog.getLatestAlertDialog(), AlertDialog.BUTTON_POSITIVE)

        val started = shadowOf(activity).nextStartedActivity
        assertEquals(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, started?.action)
        assertEquals("package:${activity.packageName}", started?.data.toString())
    }

    @Test
    @Config(sdk = [29])
    fun `nothing is asked below API 30, where MANAGE_EXTERNAL_STORAGE does not exist`() {
        gate.maybeRequestStorage()
        assertNull(ShadowAlertDialog.getLatestAlertDialog())
    }

    @Test
    @Config(sdk = [30])
    fun `storage is asked at most once per install`() {
        gate.maybeRequestStorage()
        val firstDialog = ShadowAlertDialog.getLatestAlertDialog()
        clickAndIdle(firstDialog, AlertDialog.BUTTON_NEGATIVE)

        gate.maybeRequestStorage()

        assertSame(firstDialog, ShadowAlertDialog.getLatestAlertDialog())
    }

    // --- requestAll sequencing -----------------------------------------------------------------

    @Test
    @Config(sdk = [33])
    fun `requestAll shows one dialog at a time rather than stacking all three`() {
        gate.requestAll()

        // Exactly one dialog should be visible - if all three had been shown simultaneously,
        // the notification permission (queued first) would already have been superseded.
        val dialog = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
        assertEquals("Stay notified", shadowOf(dialog).title.toString())

        clickAndIdle(dialog, AlertDialog.BUTTON_POSITIVE)

        // Dismissing the first must reveal the next (storage, since it's still API 30+ eligible
        // and unasked) rather than leaving nothing on screen.
        val next = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
        assertEquals("Browse this device", shadowOf(next).title.toString())
    }
}
