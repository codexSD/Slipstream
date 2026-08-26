package com.slipstream.core

/**
 * One place every part of Slipstream reports what it is actually doing.
 *
 * There was no logging anywhere in either app, so every failure looked identical from the
 * outside — "That folder is no longer there." was, at various times, a missing path, a peer
 * that had never connected, and a parse error, with nothing to tell them apart. Diagnosing on
 * real hardware meant guessing. This exists so it does not have to.
 *
 * Deliberately always-on: the whole point is that the interesting failures happen on someone's
 * phone, on their hotspot, once. A log that has to be enabled first is never enabled in time.
 * Spec §11 is unaffected — this writes to logcat and nowhere else, and never off the device.
 */
object SlipstreamLog {
    const val TAG = "Slipstream"

    /** Logged as `[area] message`, so `adb logcat -s Slipstream` reads as a single narrative. */
    fun i(area: String, message: String) = emit(area, message, null)

    fun w(area: String, message: String, error: Throwable? = null) = emit(area, "WARN $message", error)

    private fun emit(area: String, message: String, error: Throwable?) {
        val line = "[$area] $message" + (error?.let { " :: ${it.javaClass.simpleName}: ${it.message}" } ?: "")
        try {
            android.util.Log.i(TAG, line)
        } catch (_: Throwable) {
            // Plain JVM unit tests have no android.util.Log; the trace still belongs somewhere.
            println("$TAG $line")
        }
    }
}
