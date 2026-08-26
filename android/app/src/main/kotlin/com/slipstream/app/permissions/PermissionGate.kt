package com.slipstream.app.permissions

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity

/**
 * Consolidates every "ask the user for a permission or exemption once, and explain why first"
 * flow the app needs (Task 12), replacing the ad-hoc pair of methods `MainActivity` previously
 * carried directly:
 *
 * - `POST_NOTIFICATIONS` (API 33+, runtime permission) - without it the foreground service's
 *   ongoing notification, and the clipboard-arrival notification (design.md §10), are both
 *   silently suppressed.
 * - `MANAGE_EXTERNAL_STORAGE` (API 30+, "all files access") - design.md §2 calls this out as
 *   required for full filesystem browsing; a normal runtime-permission dialog cannot grant it,
 *   only [Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION] can.
 * - Battery-optimisation exemption (design.md §14) - not a "permission" at all, but the same
 *   shape: a Settings intent the user can decline without breaking the app, just slowing
 *   reconnection while backgrounded.
 *
 * Every one of the three is requested **at most once per install** (tracked in [prefs], the same
 * `SharedPreferences` file and "already asked" pattern the original battery-only code used - the
 * "asked" flag is set the moment a rationale is about to be shown, not on the user's eventual
 * answer, so declining still counts as "asked") and is preceded by a plain-language rationale
 * dialog - never an OS permission prompt appearing with no context - per this task's brief.
 * [requestAll] shows at most one dialog on screen at a time, chaining to the next only once the
 * previous is dismissed, rather than stacking up to three dialogs on top of each other.
 *
 * Declining any of the three, or simply never seeing the dialog (some OEM builds block the
 * storage/battery Settings intents entirely), leaves the app fully functional; only the feature
 * that permission would have unlocked is affected.
 */
class PermissionGate(private val activity: ComponentActivity) {

    /** One rationale dialog's content plus what happens if the user taps "Continue". */
    internal data class Ask(val title: String, val message: String, val onContinue: () -> Unit)

    private val prefs get() = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Runs all three checks, showing rationale dialogs one at a time (never stacked) for
     * whichever of the three still need asking. Safe to call every launch - each check is a
     * no-op after its first ask (or once the permission/exemption is already held). */
    fun requestAll() {
        val pending = listOfNotNull(pendingNotificationAsk(), pendingStorageAsk(), pendingBatteryAsk())
        showSequentially(ArrayDeque(pending))
    }

    private fun showSequentially(queue: ArrayDeque<Ask>) {
        val next = queue.removeFirstOrNull() ?: return
        showRationale(next.title, next.message, onDismiss = { showSequentially(queue) }, onContinue = next.onContinue)
    }

    /** On API 33+, `POST_NOTIFICATIONS` is a runtime permission; declaring it in the manifest
     * alone leaves both the foreground service's notification and the clipboard-arrival
     * notification (design.md §10) silently suppressed. Internal (rather than folded only into
     * [requestAll]) so a test can trigger just this one rationale/prompt in isolation. */
    internal fun maybeRequestNotifications() {
        pendingNotificationAsk()?.let { showRationale(it.title, it.message, onContinue = it.onContinue) }
    }

    private fun pendingNotificationAsk(): Ask? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        if (alreadyAsked(KEY_ASKED_NOTIFICATIONS)) return null
        if (activity.checkSelfPermission(POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return null

        markAsked(KEY_ASKED_NOTIFICATIONS)
        return Ask(
            title = "Stay notified",
            message = "Slipstream shows a notification when your paired computer sends text, a link, " +
                "or files, and while it keeps you reachable in the background.",
        ) {
            activity.requestPermissions(arrayOf(POST_NOTIFICATIONS), REQUEST_POST_NOTIFICATIONS)
        }
    }

    /** `MANAGE_EXTERNAL_STORAGE` (design.md §2) is a "special" permission on API 30+: it can
     * only be granted through [Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION], never a
     * [ComponentActivity.requestPermissions] runtime dialog. Below API 30 it does not exist as a
     * concept at all - ordinary storage access already covers what the app needs there. */
    internal fun maybeRequestStorage() {
        pendingStorageAsk()?.let { showRationale(it.title, it.message, onContinue = it.onContinue) }
    }

    private fun pendingStorageAsk(): Ask? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        if (alreadyAsked(KEY_ASKED_STORAGE)) return null
        if (Environment.isExternalStorageManager()) return null

        markAsked(KEY_ASKED_STORAGE)
        return Ask(
            title = "Browse this device",
            // Spec §15's own worked example for this exact denial - used verbatim here too, so
            // the rationale and the eventual "you said no" message agree with each other.
            message = "Slipstream needs file access to browse this device.",
        ) {
            openAllFilesAccessSettings()
        }
    }

    private fun openAllFilesAccessSettings() {
        val perAppIntent = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${activity.packageName}"),
        )
        try {
            activity.startActivity(perAppIntent)
            return
        } catch (e: Exception) {
            // Falls through to the non-package-scoped variant below - some OEM builds only
            // support that one.
        }
        try {
            activity.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        } catch (e: Exception) {
            // Neither Settings screen exists on this build. The app still works, just limited to
            // scoped storage access (see class doc).
        }
    }

    /** Unchanged from the pre-Task-12 logic this class absorbed: one-shot per install, and a
     * no-op if the OS already exempts the app (some OEMs do this by default). */
    internal fun maybeRequestBatteryExemption() {
        pendingBatteryAsk()?.let { showRationale(it.title, it.message, onContinue = it.onContinue) }
    }

    private fun pendingBatteryAsk(): Ask? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        if (alreadyAsked(KEY_ASKED_BATTERY)) return null

        val powerManager = activity.getSystemService(PowerManager::class.java)
        if (powerManager.isIgnoringBatteryOptimizations(activity.packageName)) return null

        markAsked(KEY_ASKED_BATTERY)
        return Ask(
            title = "Stay reachable in the background",
            message = "Slipstream needs to keep running in the background to receive files and " +
                "clipboard text while your screen is off. Exempting it from battery optimisation " +
                "keeps that connection alive.",
        ) {
            try {
                activity.startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:${activity.packageName}"),
                    ),
                )
            } catch (e: Exception) {
                // Some OEM builds don't support this intent; the app still works, just with
                // slower reconnection while backgrounded (see class doc).
            }
        }
    }

    private fun alreadyAsked(key: String): Boolean = prefs.getBoolean(key, false)

    private fun markAsked(key: String) {
        prefs.edit().putBoolean(key, true).apply()
    }

    /** Shows the plain-language "why" before the actual system prompt/Settings screen. Internal
     * (rather than private) so a test can drive it without needing three separate real dialogs.
     * [onDismiss] fires whichever button is tapped (or the dialog is cancelled) - [requestAll]
     * uses it to chain to the next pending ask, if any. */
    internal fun showRationale(title: String, message: String, onDismiss: () -> Unit = {}, onContinue: () -> Unit) {
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Continue") { _, _ -> onContinue() }
            .setNegativeButton("Not now", null)
            .setOnDismissListener { onDismiss() }
            .setCancelable(true)
            .show()
    }

    companion object {
        internal const val PREFS_NAME = "slipstream"
        internal const val KEY_ASKED_NOTIFICATIONS = "asked_post_notifications"
        internal const val KEY_ASKED_STORAGE = "asked_manage_external_storage"
        internal const val KEY_ASKED_BATTERY = "asked_battery_optimization_exemption"
        internal const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
        const val REQUEST_POST_NOTIFICATIONS = 1
    }
}
