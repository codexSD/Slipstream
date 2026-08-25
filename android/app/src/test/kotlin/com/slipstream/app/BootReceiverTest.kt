package com.slipstream.app

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Starting a foreground service from a BOOT_COMPLETED broadcast is only permitted on API 34+
 * for certain `foregroundServiceType`s - `dataSync`, which this service originally declared,
 * is not one of them, and the start would be rejected with
 * `ForegroundServiceStartNotAllowedException` on a real device at boot. These tests pin both
 * halves of the fix: the declared type, and the receiver surviving a rejected start anyway.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BootReceiverTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `boot starts the peer foreground service`() {
        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        val started = shadowOf(context as android.app.Application).nextStartedService
        assertNotNull("BOOT_COMPLETED must start the peer service", started)
        assertEquals(PeerForegroundService::class.java.name, started.component?.className)
    }

    @Test
    fun `an unrelated broadcast starts nothing`() {
        BootReceiver().onReceive(context, Intent(Intent.ACTION_SCREEN_ON))
        assertNull(shadowOf(context as android.app.Application).nextStartedService)
    }

    @Test
    fun `a rejected foreground start does not crash the receiver`() {
        // Exactly what API 34+ does when a start is not allowed from this broadcast: it throws
        // out of startForegroundService. A BroadcastReceiver that lets it escape crashes the app.
        val hostile = object : ContextWrapper(context) {
            override fun startForegroundService(service: Intent?): android.content.ComponentName? =
                throw IllegalStateException("ForegroundServiceStartNotAllowedException")

            override fun startService(service: Intent?): android.content.ComponentName? =
                throw IllegalStateException("ForegroundServiceStartNotAllowedException")
        }

        BootReceiver().onReceive(hostile, Intent(Intent.ACTION_BOOT_COMPLETED))
    }

    @Test
    fun `the service declares a foreground type that may be started from BOOT_COMPLETED`() {
        val info = context.packageManager.getServiceInfo(
            android.content.ComponentName(context, PeerForegroundService::class.java),
            PackageManager.GET_META_DATA,
        )

        assertEquals(
            "dataSync may not be started from a BOOT_COMPLETED receiver on API 34+",
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            info.foregroundServiceType,
        )
    }

    @Test
    fun `POST_NOTIFICATIONS is declared in the manifest`() {
        val declared = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            ?.toList()
            .orEmpty()

        assertTrue(
            "the foreground notification is suppressed on API 33+ without this",
            declared.contains(MainActivity.POST_NOTIFICATIONS),
        )
        assertTrue(declared.contains("android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE"))
    }
}
