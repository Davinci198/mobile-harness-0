package com.jarves.mh.runtime

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.jarves.mh.MainActivity
import com.jarves.mh.R
import java.util.Collections

private const val TAG = "SessionKeepAlive"

/** True when the keep-alive FGS should run: always-on mode, or something to protect. */
internal fun shouldKeepAlive(enabled: Boolean, hasHolds: Boolean): Boolean = enabled || hasHolds

/**
 * Refcount + persistent toggle for [SessionKeepAliveService].
 *
 * The service runs while either:
 *  - the "keep alive" setting is on (default): Termux-style, the process and
 *    its PRoot children survive swipe-away and memory pressure; or
 *  - at least one hold is registered (PTY terminal sessions): even with the
 *    setting off, open sessions stay alive until the last one is closed.
 *
 * Task execution and the Studio server keep their own protection in
 * [RuntimeExecutionService]; this tracker only covers the gap that service
 * does not: idle terminals.
 */
object KeepAliveTracker {
    private val holds: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
    @Volatile private var appContext: Context? = null
    @Volatile var enabled: Boolean = true
        private set

    fun init(context: Context, keepAliveEnabled: Boolean) {
        appContext = context.applicationContext
        enabled = keepAliveEnabled
        evaluate()
    }

    fun setEnabled(context: Context, value: Boolean) {
        appContext = context.applicationContext
        enabled = value
        evaluate()
    }

    fun acquire(marker: String) {
        holds.add(marker)
        evaluate()
    }

    fun release(marker: String) {
        holds.remove(marker)
        evaluate()
    }

    private fun evaluate() {
        val ctx = appContext ?: return
        if (shouldKeepAlive(enabled, holds.isNotEmpty())) {
            SessionKeepAliveService.start(ctx)
        } else {
            SessionKeepAliveService.stop(ctx)
        }
    }
}

/**
 * Lightweight foreground service that exists purely to keep the process at a
 * survivable oom_score_adj (Termux's TermuxService model). It does no work of
 * its own: no wakelock, no watchdog — the notification says the app is alive.
 * Restart after swipe-away is handled in [onTaskRemoved].
 */
class SessionKeepAliveService : Service() {
    override fun onCreate() {
        super.onCreate()
        RuntimeExecutionService.ensureNotificationChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promote()
        return START_STICKY
    }

    private fun promote() {
        val notification = NotificationCompat.Builder(this, RuntimeExecutionService.RUNNING_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.keepalive_text))
            .setContentIntent(openAppIntent())
            .addAction(0, getString(R.string.ask_anything), askIntent())
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    KEEP_ALIVE_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(KEEP_ALIVE_NOTIFICATION_ID, notification)
            }
        }.onFailure {
            // Foreground start can be refused (app in background restricted
            // state); never crash the service, just stop trying to protect.
            Log.w(TAG, "startForeground refused: $it")
            stopSelf()
        }
    }

    /**
     * The user swiped the app from Recents. The service was foreground until
     * this moment, so re-promoting keeps the process (and PRoot children with
     * it) out of the cached-app freezer instead of dying with the task.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        runCatching {
            ContextCompat.startForegroundService(
                applicationContext,
                Intent(applicationContext, SessionKeepAliveService::class.java),
            )
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        2,
        Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun askIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        5,
        Intent(this, MainActivity::class.java).apply {
            action = MainActivity.ACTION_ASK
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        private const val KEEP_ALIVE_NOTIFICATION_ID = 43

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, SessionKeepAliveService::class.java),
                )
            }.onFailure { Log.w(TAG, "keep-alive start refused: $it") }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, SessionKeepAliveService::class.java)) }
        }
    }
}
