package com.slipstream.core.pairing

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * pairing.md §1: pairing only ever happens inside a 120-second window a user opens
 * explicitly on each device. Outside an open window, [isOpen] is false and the accept path
 * must be byte-for-byte identical to normal operation. The window closes itself on expiry
 * ([isOpen] observing that the deadline has passed), on successful pairing, or on user
 * cancel — [close] covers the latter two.
 *
 * Not opened by default: a freshly constructed window is closed, matching "never opened" in
 * the brief's own test.
 */
class PairingWindow(
    private val duration: Duration = 120.seconds,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    @Volatile
    private var deadline: TimeMark? = null

    /** Opens (or re-opens) the window for a fresh [duration] starting now. */
    @Synchronized
    fun open() {
        deadline = timeSource.markNow() + duration
    }

    /** Closes the window immediately: on successful pairing, or on user cancel. */
    @Synchronized
    fun close() {
        deadline = null
    }

    /** True only while a window is open and its deadline has not yet passed. Observing an
     * expired deadline here also closes the window (self-expiry), so a stale [deadline]
     * never lingers past the moment it's checked. */
    val isOpen: Boolean
        @Synchronized get() {
            val d = deadline ?: return false
            if (d.hasPassedNow()) {
                deadline = null
                return false
            }
            return true
        }
}
