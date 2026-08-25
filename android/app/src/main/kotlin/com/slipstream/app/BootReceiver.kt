package com.slipstream.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Restarts [PeerForegroundService] after device boot (design.md §14: background operation
 * without requiring the user to relaunch the app first). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        ContextCompat.startForegroundService(context, Intent(context, PeerForegroundService::class.java))
    }
}
