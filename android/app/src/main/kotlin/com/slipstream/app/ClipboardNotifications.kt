package com.slipstream.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Task 12 (design.md §10): "The receiver places it on the system clipboard and shows a
 * notification with a paste affordance. URLs are detected and offered an open action instead."
 *
 * [SlipstreamApplication.onCreate] already mirrors incoming [com.slipstream.app.peer.PeerController.clipboardReceived]
 * text onto the system [android.content.ClipboardManager] directly - this file is the second half
 * of that same collector, surfacing the arrival as a notification rather than a silent clipboard
 * write the user has no way of noticing.
 *
 * A separate low-priority channel from [PeerForegroundService]'s: that one is ongoing background
 * infrastructure the user never acts on, this one is a one-shot event the user is meant to notice
 * and (optionally) tap.
 */
internal const val CLIPBOARD_NOTIFICATION_CHANNEL_ID = "slipstream-clipboard"
internal const val CLIPBOARD_NOTIFICATION_ID = 2

/** A best-effort URL sniff - good enough to decide "Open" vs "Paste" without pulling in a full
 * URI-grammar parser for what is, worst case, a wrong button label rather than a functional bug. */
internal fun isLikelyUrl(text: String): Boolean {
    val trimmed = text.trim()
    return trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)
}

internal fun createClipboardNotificationChannel(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val channel = NotificationChannel(
        CLIPBOARD_NOTIFICATION_CHANNEL_ID,
        "Clipboard from paired device",
        NotificationManager.IMPORTANCE_DEFAULT,
    )
    channel.description = "Shown when your paired computer sends text or a link to this device."
    context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
}

/**
 * Builds the notification for clipboard [text] just received. Internal (rather than private) so
 * a test can inspect its content/actions without needing a live [NotificationManager] to read
 * back a posted [Notification] from.
 */
internal fun buildClipboardNotification(context: Context, text: String): Notification {
    val isUrl = isLikelyUrl(text)
    val tapIntent = if (isUrl) {
        PendingIntent.getActivity(
            context,
            CLIPBOARD_NOTIFICATION_ID,
            Intent(Intent.ACTION_VIEW, Uri.parse(text.trim())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    } else {
        PendingIntent.getActivity(
            context,
            CLIPBOARD_NOTIFICATION_ID,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
    val actionLabel = if (isUrl) "Open" else "Paste"

    return NotificationCompat.Builder(context, CLIPBOARD_NOTIFICATION_CHANNEL_ID)
        .setContentTitle("Slipstream")
        .setContentText(if (isUrl) "Link received from your paired device." else "Text received from your paired device.")
        .setSmallIcon(android.R.drawable.ic_menu_send)
        .setContentIntent(tapIntent)
        .addAction(0, actionLabel, tapIntent)
        .setAutoCancel(true)
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .build()
}

/** Posts the notification built by [buildClipboardNotification] for [text] just received.
 * Safe to call even when `POST_NOTIFICATIONS` was never granted (API 33+): the platform simply
 * suppresses the notification, exactly as it already does for [PeerForegroundService]'s ongoing
 * one - there is nothing more useful this call can do about that. */
internal fun postClipboardNotification(context: Context, text: String) {
    createClipboardNotificationChannel(context)
    NotificationManagerCompat.from(context).notify(CLIPBOARD_NOTIFICATION_ID, buildClipboardNotification(context, text))
}
