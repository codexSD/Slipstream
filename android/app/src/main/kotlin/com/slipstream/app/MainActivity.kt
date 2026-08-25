package com.slipstream.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Starts [PeerForegroundService] on launch and, once per install, asks the user to exempt
 * Slipstream from battery optimisation (design.md §14). The app works either way - being
 * denied (or the user never seeing the dialog, e.g. OEM-specific power management) only means
 * slower reconnection while backgrounded, never a hard failure - so this is a one-shot request,
 * not something re-prompted on every launch.
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ContextCompat.startForegroundService(this, Intent(this, PeerForegroundService::class.java))
        maybeRequestBatteryOptimizationExemption()
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

    private companion object {
        const val PREFS_NAME = "slipstream"
        const val KEY_ASKED = "asked_battery_optimization_exemption"
    }
}
