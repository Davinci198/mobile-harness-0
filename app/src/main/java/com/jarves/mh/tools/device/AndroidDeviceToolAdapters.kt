package com.jarves.mh.tools.device

import android.Manifest
import android.app.ActivityNotFoundException
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.jarves.mh.R
import java.net.URI

class AndroidClipboardAdapter(private val context: Context) : ClipboardAdapter {
    private val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        ?: throw DeviceOperationException(DeviceErrorCode.CLIPBOARD_UNAVAILABLE, "Clipboard service is unavailable")

    override fun copy(text: String) {
        try {
            manager.setPrimaryClip(android.content.ClipData.newPlainText("Mobile Harness", text))
        } catch (error: SecurityException) {
            throw DeviceOperationException(DeviceErrorCode.PERMISSION_DENIED, "Clipboard write was denied")
        }
    }

    override fun read(): String {
        val clip = try {
            manager.primaryClip
        } catch (error: SecurityException) {
            throw DeviceOperationException(DeviceErrorCode.PERMISSION_DENIED, "Clipboard read was denied")
        } ?: throw DeviceOperationException(DeviceErrorCode.CLIPBOARD_UNAVAILABLE, "Clipboard is unavailable")
        if (clip.itemCount == 0) throw DeviceOperationException(DeviceErrorCode.EMPTY_CLIPBOARD, "Clipboard is empty")
        val item = clip.getItemAt(0)
        if (item.uri != null && clip.description?.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) != true) {
            throw DeviceOperationException(DeviceErrorCode.CLIPBOARD_UNAVAILABLE, "Clipboard does not contain text")
        }
        return item.coerceToText(context).toString()
    }
}

class AndroidToastAdapter(private val context: Context) : ToastAdapter {
    private val handler = Handler(Looper.getMainLooper())

    override fun show(text: String): Boolean = handler.post {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }
}

class AndroidNotificationAdapter(private val context: Context) : NotificationAdapter {
    override fun notify(title: String, text: String): DeviceNotificationDispatch {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            throw DeviceOperationException(DeviceErrorCode.PERMISSION_REQUIRED, "Notification permission is required")
        }
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) {
            throw DeviceOperationException(DeviceErrorCode.NOTIFICATIONS_DISABLED, "Notifications are disabled")
        }
        if (Build.VERSION.SDK_INT >= 26) {
            val systemManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = systemManager.getNotificationChannel(DeviceToolLimits.NOTIFICATION_CHANNEL_ID)
                ?: NotificationChannel(
                    DeviceToolLimits.NOTIFICATION_CHANNEL_ID,
                    "Device tools",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).also { systemManager.createNotificationChannel(it) }
            if (channel.importance == NotificationManager.IMPORTANCE_NONE) {
                throw DeviceOperationException(DeviceErrorCode.NOTIFICATIONS_DISABLED, "Device tools notification channel is disabled")
            }
        }
        val notification = NotificationCompat.Builder(context, DeviceToolLimits.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .build()
        try {
            manager.notify(DeviceToolLimits.NOTIFICATION_ID, notification)
        } catch (error: SecurityException) {
            throw DeviceOperationException(DeviceErrorCode.PERMISSION_REQUIRED, "Notification permission is required")
        }
        return DeviceNotificationDispatch(DeviceToolLimits.NOTIFICATION_CHANNEL_ID, DeviceToolLimits.NOTIFICATION_ID)
    }
}

class AndroidHttpsLinkAdapter(private val context: Context) : HttpsLinkAdapter {
    override fun open(url: String) {
        val uri = try {
            URI(url)
        } catch (_: Throwable) {
            throw DeviceOperationException(DeviceErrorCode.INVALID_INPUT, "HTTPS URL is invalid")
        }
        if (!uri.isAbsolute || !uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank() || uri.rawUserInfo != null) {
            throw DeviceOperationException(DeviceErrorCode.INVALID_INPUT, "Only absolute HTTPS links without user info are allowed")
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (error: ActivityNotFoundException) {
            throw DeviceOperationException(DeviceErrorCode.NO_HANDLER, "No app can open the HTTPS link")
        } catch (error: SecurityException) {
            throw DeviceOperationException(DeviceErrorCode.ACTIVITY_LAUNCH_BLOCKED, "Opening the HTTPS link was blocked")
        }
    }
}
