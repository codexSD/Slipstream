package com.slipstream.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * On API 33+ `POST_NOTIFICATIONS` is a runtime permission. Declaring it in the manifest alone -
 * which is all the app did - leaves the foreground service's ongoing notification silently
 * suppressed, so the user has no indication the service is running at all.
 */
@RunWith(RobolectricTestRunner::class)
class MainActivityTest {

    @Test
    @Config(sdk = [34])
    fun `notification permission is requested at runtime on API 33 and up`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val requested = shadowOf(controller.get()).lastRequestedPermission

        assertEquals(
            listOf(MainActivity.POST_NOTIFICATIONS),
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
