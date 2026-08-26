package com.slipstream.app

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
import com.slipstream.app.ui.SlipstreamNavHost

/**
 * Starts [PeerForegroundService] on launch and, once per install, asks the user to exempt
 * Slipstream from battery optimisation (design.md §14). The app works either way - being
 * denied (or the user never seeing the dialog, e.g. OEM-specific power management) only means
 * slower reconnection while backgrounded, never a hard failure - so this is a one-shot request,
 * not something re-prompted on every launch.
 *
 * Extends [ComponentActivity] (rather than plain `Activity`) so it can host Compose content via
 * [setContent] - the navigation shell built in [SlipstreamNavHost]. [ComponentActivity] is
 * itself an `Activity`, so [requestPermissions] and [startActivity] below are unaffected.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ContextCompat.startForegroundService(this, Intent(this, PeerForegroundService::class.java))
        maybeRequestNotificationPermission()
        maybeRequestBatteryOptimizationExemption()

        setContent {
            val app = application as SlipstreamApplication
            SlipstreamNavHost(
                peerController = app.peerController,
                settingsStore = app.settingsStore,
                sharedUris = sharedUrisFromIntent(intent),
            )
        }
    }

    /**
     * Task 10: when Slipstream is launched from the share sheet (`ACTION_SEND` /
     * `ACTION_SEND_MULTIPLE`, per the manifest's new intent filters), extracts the shared
     * item(s) so [SlipstreamNavHost] can route straight to the send screen with them pre-queued.
     * Returns an empty list for an ordinary launch (`ACTION_MAIN`) - the overwhelmingly common
     * case - so the nav host's default behaviour is unaffected.
     */
    internal fun sharedUrisFromIntent(intent: Intent?): List<Uri> = when (intent?.action) {
        Intent.ACTION_SEND ->
            intent.getParcelableExtraCompat(Intent.EXTRA_STREAM, Uri::class.java)?.let { listOf(it) } ?: emptyList()

        Intent.ACTION_SEND_MULTIPLE ->
            intent.getParcelableArrayListExtraCompat(Intent.EXTRA_STREAM, Uri::class.java) ?: emptyList()

        else -> emptyList()
    }

    /**
     * On API 33+ `POST_NOTIFICATIONS` is a runtime permission, and without it the foreground
     * service's ongoing notification is silently suppressed - the service still runs, but the
     * user has no visible indication of it, which is both a poor experience and (for a
     * persistent background service) something the platform expects to be surfaced.
     * Declaring it in the manifest alone is not enough; it has to be asked for here.
     */
    internal fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        requestPermissions(arrayOf(POST_NOTIFICATIONS), REQUEST_POST_NOTIFICATIONS)
    }

    private fun maybeRequestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_ASKED, false)) return

        val powerManager = getSystemService(PowerManager::class.java)
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) return

        prefs.edit().putBoolean(KEY_ASKED, true).apply()
        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName"),
                ),
            )
        } catch (e: Exception) {
            // Some OEM builds don't support this intent; the app still works, just with
            // slower reconnection while backgrounded (see class doc).
        }
    }

    internal companion object {
        const val PREFS_NAME = "slipstream"
        const val KEY_ASKED = "asked_battery_optimization_exemption"
        const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
        const val REQUEST_POST_NOTIFICATIONS = 1
    }
}

/** [Intent.getParcelableExtra] with a [Class] argument is API 33+; the single-argument overload
 * it replaces is deprecated but still the only option below that. Both branches return the same
 * thing - this just picks whichever one the running OS actually has. */
private fun <T : android.os.Parcelable> Intent.getParcelableExtraCompat(name: String, clazz: Class<T>): T? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(name, clazz)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(name)
    }

private fun <T : android.os.Parcelable> Intent.getParcelableArrayListExtraCompat(
    name: String,
    clazz: Class<T>,
): java.util.ArrayList<T>? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableArrayListExtra(name, clazz)
    } else {
        @Suppress("DEPRECATION")
        getParcelableArrayListExtra(name)
    }
