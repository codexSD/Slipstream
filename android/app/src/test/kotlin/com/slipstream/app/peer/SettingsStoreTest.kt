package com.slipstream.app.peer

import android.content.Context
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowPowerManager

/**
 * Tests for [SettingsStore]: persistence, validation, and state reflection.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsStoreTest {

    private lateinit var context: Context
    private lateinit var store: SettingsStore
    private lateinit var shadowPowerManager: ShadowPowerManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Clear any existing prefs
        context.getSharedPreferences(SettingsStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().apply()
        store = SettingsStore(context)
        shadowPowerManager = shadowOf(context.getSystemService(PowerManager::class.java))
    }

    @Test
    fun parallelStreamCount_clamps_below_min() {
        store.setParallelStreamCount(0)
        assertEquals(1, store.getParallelStreamCount())
    }

    @Test
    fun parallelStreamCount_clamps_above_max() {
        store.setParallelStreamCount(10)
        assertEquals(8, store.getParallelStreamCount())
    }

    @Test
    fun parallelStreamCount_accepts_valid_value() {
        store.setParallelStreamCount(4)
        assertEquals(4, store.getParallelStreamCount())
    }

    @Test
    fun parallelStreamCount_persists_across_instances() {
        store.setParallelStreamCount(5)
        val newStore = SettingsStore(context)
        assertEquals(5, newStore.getParallelStreamCount())
    }

    @Test
    fun parallelStreamCount_defaults_to_4() {
        val defaultValue = store.getParallelStreamCount()
        assertEquals(4, defaultValue)
    }

    @Test
    fun downloadFolder_validates_and_creates() {
        val folder = context.cacheDir
        store.setDownloadFolder(folder.absolutePath)
        assertEquals(folder.absolutePath, store.getDownloadFolder())
    }

    @Test
    fun downloadFolder_falls_back_to_default_on_invalid() {
        store.setDownloadFolder("/invalid/nonexistent/path/that/cannot/exist")
        // Should fall back to app cache dir
        val fallback = store.getDownloadFolder()
        assertTrue(fallback.isNotEmpty())
        assertTrue(fallback.contains("cache") || fallback.contains("files"))
    }

    @Test
    fun downloadFolder_persists_across_instances() {
        val folder = context.cacheDir
        store.setDownloadFolder(folder.absolutePath)
        val newStore = SettingsStore(context)
        assertEquals(folder.absolutePath, newStore.getDownloadFolder())
    }

    @Test
    fun theme_persists_light() {
        store.setTheme(SettingsStore.Theme.Light)
        val newStore = SettingsStore(context)
        assertEquals(SettingsStore.Theme.Light, newStore.getTheme())
    }

    @Test
    fun theme_persists_dark() {
        store.setTheme(SettingsStore.Theme.Dark)
        val newStore = SettingsStore(context)
        assertEquals(SettingsStore.Theme.Dark, newStore.getTheme())
    }

    @Test
    fun theme_persists_system() {
        store.setTheme(SettingsStore.Theme.System)
        val newStore = SettingsStore(context)
        assertEquals(SettingsStore.Theme.System, newStore.getTheme())
    }

    @Test
    fun theme_defaults_to_system() {
        assertEquals(SettingsStore.Theme.System, store.getTheme())
    }

    @Test
    fun batteryExemptionStatus_reflects_real_check() {
        assertFalse(store.isBatteryExemptionGranted())
        // Grant exemption via shadow
        shadowPowerManager.setIgnoringBatteryOptimizations(context.packageName, true)
        assertTrue(store.isBatteryExemptionGranted())
    }

    @Test
    fun batteryExemptionStatus_not_persisted() {
        // Battery exemption is a real-time check, not persisted
        shadowPowerManager.setIgnoringBatteryOptimizations(context.packageName, true)
        assertTrue(store.isBatteryExemptionGranted())
        val newStore = SettingsStore(context)
        assertTrue(newStore.isBatteryExemptionGranted())
    }
}
