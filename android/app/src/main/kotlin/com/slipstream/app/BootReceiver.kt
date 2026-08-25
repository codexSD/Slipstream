package com.slipstream.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Restarts [PeerForegroundService] after device boot (design.md §14: background operation
 * without requiring the user to relaunch the app first).
 *
 * The service is declared `foregroundServiceType="connectedDevice"` precisely so this start is
 * permitted on API 34+, where only certain types may be started from a BOOT_COMPLETED broadcast.
 * The start is still guarded: an OEM policy, a background-start restriction, or a future
 * platform tightening must degrade to "the peer starts when the user next opens the app", never
 * to a crash inside a system broadcast.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        try {
            ContextCompat.startForegroundService(context, Intent(context, PeerForegroundService::class.java))
        } catch (e: Exception) {
            Log.w(TAG, "Could not start the Slipstream peer service at boot", e)
        }
    }

    private companion object {
        const val TAG = "SlipstreamBoot"
    }
}
