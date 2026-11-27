package com.jarves.mh.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.jarves.mh.MainActivity
import com.jarves.mh.R
import com.jarves.mh.data.AppPreferences

/**
 * Restart bookkeeping for the in-app updater.
 *
 * Installing an update to `com.jarves.mh` kills this process: the install is
 * carried out by the package manager, and the app that asked for it is gone
 * before the broadcast result can be delivered. That is why the intent is
 * recorded in [AppPreferences] *before* the install is committed and the
 * relaunch happens on the next process start, not in the broadcast receiver.
 *
 * Without this the user landed on the launcher after every update: the app
 * showed "installed" only if it survived, and nobody opened it again.
 */
object UpdateRestart {

    private const val CHANNEL_ID = "app-updates"
    private const val NOTIFICATION_ID = 43

    /** Marks that the next start of the app is the tail of an update install. */
    fun markPendingRestart(context: Context, fromVersionName: String, toVersionName: String) {
        AppPreferences(context).pendingUpdateRestart = "$fromVersionName\u0000$toVersionName"
    }

    fun cancelPendingRestart(context: Context) {
        AppPreferences(context).pendingUpdateRestart = ""
    }

    /** Version that started the update, without clearing the marker. */
    fun pendingRestartFrom(context: Context): String? =
        AppPreferences(context).pendingUpdateRestart.split('\u0000').firstOrNull()?.takeIf { it.isNotBlank() }

    /**
     * Called once per process start by the ViewModel, which owns the user-facing
     * state. Returns the versions when this process start completes an update.
     */
    fun consumePendingRestart(context: Context): Pair<String, String>? {
        val raw = AppPreferences(context).pendingUpdateRestart
        if (raw.isBlank()) return null
        cancelPendingRestart(context)
        val parts = raw.split('\u0000')
        return (parts.getOrNull(0) ?: "") to (parts.getOrNull(1) ?: "")
    }

    /**
     * Last resort when the relaunch cannot happen (notification permission
     * denied, OEM blocking background activity starts): a notification the user
     * can tap. It carries the same intent a relaunch would.
     */
    fun notifyUpdateInstalled(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.notification_channel_updates), NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = context.getString(R.string.update_installed_notification_text) },
            )
        }
        val open = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(context.getString(R.string.update_installed_notification_title))
            .setContentText(context.getString(R.string.update_installed_notification_text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        // Throws nothing when the permission is missing; the update still applied.
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }
    }

    /**
     * True when the update really replaced the running build: comparing versions
     * stops a stale marker (e.g. the install was cancelled) from relaunching the
     * app forever.
     */
    fun updateApplied(fromVersionName: String, installedVersionName: String): Boolean =
        fromVersionName.isNotBlank() && fromVersionName != installedVersionName
}
