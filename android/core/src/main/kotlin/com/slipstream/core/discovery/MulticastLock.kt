package com.slipstream.core.discovery

import android.net.wifi.WifiManager

/**
 * Abstraction over `WifiManager.MulticastLock`. [MulticastStrategy] acquires this only for
 * the duration of a discovery burst (while its socket is open) and releases it immediately
 * after — spec §5/§14: never hold it idle.
 */
interface MulticastLockHandle {
    fun acquire()
    fun release()
}

/** No-op handle for environments where a multicast lock isn't relevant (e.g. tests). */
object NoopMulticastLock : MulticastLockHandle {
    override fun acquire() = Unit
    override fun release() = Unit
}

class AndroidMulticastLock(wifiManager: WifiManager) : MulticastLockHandle {
    private val lock = wifiManager.createMulticastLock("slipstream-discovery").apply {
        setReferenceCounted(true)
    }

    override fun acquire() = lock.acquire()
    override fun release() {
        if (lock.isHeld) lock.release()
    }
}
