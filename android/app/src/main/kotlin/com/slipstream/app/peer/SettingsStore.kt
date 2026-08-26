package com.slipstream.app.peer

import android.content.Context
import android.os.PowerManager
import androidx.core.content.getSystemService
import java.io.File

/**
 * Persists and retrieves user settings: parallel stream count, download folder,
 * theme preference, and reports battery-exemption status.
 *
 * Uses SharedPreferences for persistence; battery-exemption status is not persisted,
 * only checked in real-time via [PowerManager.isIgnoringBatteryOptimizations].
 */
class SettingsStore(private val context: Context) {

    enum class Theme {
        System, Light, Dark
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val powerManager = context.getSystemService<PowerManager>()

    fun getParallelStreamCount(): Int {
        val value = prefs.getInt(KEY_PARALLEL_STREAMS, DEFAULT_PARALLEL_STREAMS)
        return value.coerceIn(MIN_PARALLEL_STREAMS, MAX_PARALLEL_STREAMS)
    }

    fun setParallelStreamCount(value: Int) {
        val clamped = value.coerceIn(MIN_PARALLEL_STREAMS, MAX_PARALLEL_STREAMS)
        prefs.edit().putInt(KEY_PARALLEL_STREAMS, clamped).apply()
    }

    fun getDownloadFolder(): String {
        val saved = prefs.getString(KEY_DOWNLOAD_FOLDER, null)
        if (saved != null) {
            val folder = File(saved)
            if (folder.exists() && folder.isDirectory) {
                return saved
            }
        }
        // Fallback to app cache dir if invalid or not set
        return context.cacheDir.absolutePath
    }

    fun setDownloadFolder(path: String) {
        val folder = File(path)
        if (folder.exists() && folder.isDirectory) {
            prefs.edit().putString(KEY_DOWNLOAD_FOLDER, path).apply()
        } else {
            // Silently fall back to default on invalid path
            prefs.edit().remove(KEY_DOWNLOAD_FOLDER).apply()
        }
    }

    fun getTheme(): Theme {
        val saved = prefs.getString(KEY_THEME, Theme.System.name)
        return try {
            Theme.valueOf(saved ?: Theme.System.name)
        } catch (e: IllegalArgumentException) {
            Theme.System
        }
    }

    fun setTheme(theme: Theme) {
        prefs.edit().putString(KEY_THEME, theme.name).apply()
    }

    fun isBatteryExemptionGranted(): Boolean {
        if (powerManager == null) return false
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    companion object {
        const val PREFS_NAME = "slipstream_settings"
        private const val KEY_PARALLEL_STREAMS = "parallel_stream_count"
        private const val KEY_DOWNLOAD_FOLDER = "download_folder"
        private const val KEY_THEME = "theme"

        private const val MIN_PARALLEL_STREAMS = 1
        private const val MAX_PARALLEL_STREAMS = 8
        private const val DEFAULT_PARALLEL_STREAMS = 4
    }
}
