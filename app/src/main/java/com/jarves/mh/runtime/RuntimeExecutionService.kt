package com.jarves.mh.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.jarves.mh.MainActivity
import com.jarves.mh.R
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Session registry shared between the bridges and the foreground service.
 * Bridges register a session before spawning the guest process and unregister
 * it on terminal events; the service keeps the FGS + WakeLock alive while the
 * count is above zero. Session ids make concurrent starts/stops safe: a stale
 * COMPLETE/FAILED from an old session can never stop a newer one.
 */
internal object RuntimeTaskController {
    private val activeSessions = ConcurrentHashMap<String, String>()
    @Volatile var stopAction: (() -> Unit)? = null
    @Volatile var newestSessionId: String? = null

    /**
     * True when the service recovered from a process death that left tasks in
     * flight (see [RuntimeRecoveryState]). While set, the bridges' pre-spawn
     * orphan sweep spares the surviving guest agents instead of killing them.
     */
    @Volatile var recoveryActive: Boolean = false

    fun begin(sessionId: String, description: String) {
        activeSessions[sessionId] = description
        newestSessionId = sessionId
    }

    fun end(sessionId: String): Boolean {
        activeSessions.remove(sessionId)
        if (newestSessionId == sessionId) {
            newestSessionId = activeSessions.keys.firstOrNull()
        }
        return activeSessions.isEmpty()
    }

    fun activeCount(): Int = activeSessions.size

    fun describe(): String? = newestSessionId?.let { activeSessions[it] }

    fun requestStop() {
        stopAction?.invoke()
    }

    /** Drops all bookkeeping. Unit tests call this between test cases. */
    internal fun reset() {
        activeSessions.clear()
        newestSessionId = null
        stopAction = null
        recoveryActive = false
    }
}

class RuntimeExecutionService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * Whether the wake lock SHOULD be held. Auto-protection turns it on for
     * every task and for the Studio keepalive; the notification toggle
     * (Termux-style "Acquire/Release wake lock" button) flips it manually.
     * When the user releases it, the watchdog refreshes must not re-acquire.
     */
    @Volatile private var wakeLockDesired: Boolean = true
    private var projectName: String = "your project"
    private var notificationTitle: String = "Mobile Harness is working"
    private var canStop: Boolean = true

    @Volatile private var studioKeepalive: Boolean = false
    private var studioWatchdog: Thread? = null
    private var recoverySessionIds: List<String> = emptyList()

    private fun taskRunning(): Boolean = RuntimeTaskController.activeCount() > 0

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannels(this)
        // A non-empty on-disk ledger means the previous process died with tasks
        // in flight (START_STICKY restart, cached-app freeze escalation, memory
        // pressure). Consume it and arm the recovery flag so the next orphan
        // sweep spares whatever guest agents survived the kill.
        val recovered = RuntimeRecoveryState.consumeIn(filesDir)
        recoverySessionIds = recovered.map { it.first }
        RuntimeTaskController.recoveryActive = recovered.isNotEmpty()
        if (recovered.isNotEmpty()) {
            Log.w(TAG, "Recovered after process death with ${recovered.size} task(s) in flight")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            // STICKY restart after the process was killed. Promote immediately
            // (the FGS time window starts now) and decide in the background
            // what actually survived: recovered tasks, the Studio server, or
            // nothing worth protecting.
            promoteToForeground(detail = workingDetail(), includeStop = false)
            thread(name = "runtime-recovery") { decideAfterRestart() }
            return START_STICKY
        }
        intent.getStringExtra(EXTRA_PROJECT_NAME)?.takeIf(String::isNotBlank)?.let { projectName = it }
        intent.getStringExtra(EXTRA_TITLE)?.takeIf(String::isNotBlank)?.let { notificationTitle = it }
        if (intent.hasExtra(EXTRA_CAN_STOP)) canStop = intent.getBooleanExtra(EXTRA_CAN_STOP, true)
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)
        when (intent.action ?: ACTION_START) {
            ACTION_STOP -> handleStop()
            ACTION_TOGGLE_WAKELOCK -> {
                // Termux-style notification toggle: the button label flips
                // because the notification is rebuilt with the new state.
                wakeLockDesired = wakeLock?.isHeld != true
                if (wakeLockDesired) acquireWakeLock() else releaseWakeLock()
                Log.d(TAG, "Wake lock ${if (wakeLockDesired) "acquired" else "released"} from notification toggle")
                if (taskRunning() || studioKeepalive) refreshNotification()
            }
            ACTION_PROGRESS -> {
                if (!taskRunning()) return START_STICKY
                refreshNotification(
                    detail = intent.getStringExtra(EXTRA_DETAIL)?.takeIf { it.isNotBlank() } ?: workingDetail(),
                    includeStop = canStop,
                )
            }
            ACTION_KEEPALIVE -> {
                studioKeepalive = true
                startStudioWatchdog()
                if (!taskRunning()) {
                    promoteToForeground(
                        detail = "Ekko Studio server is running in the background.",
                        title = "Mobile Harness Studio",
                        includeStop = false,
                    )
                }
            }
            ACTION_STOP_STUDIO -> {
                studioKeepalive = false
                thread(name = "studio-stop") {
                    runCatching {
                        StudioServerManager(RuntimeInstaller(applicationContext)).stop()
                    }
                    if (!taskRunning()) teardown() else refreshNotification()
                }
            }
            ACTION_COMPLETE -> finishTask(
                sessionId = sessionId,
                title = "Task completed",
                detail = intent.getStringExtra(EXTRA_DETAIL) ?: "Mobile Harness finished working in $projectName.",
                failed = false,
            )
            ACTION_FAILED -> finishTask(
                sessionId = sessionId,
                title = "Task needs attention",
                detail = intent.getStringExtra(EXTRA_DETAIL) ?: "Mobile Harness could not finish the task.",
                failed = true,
            )
            ACTION_CANCELLED -> finishTask(
                sessionId = sessionId,
                title = "Task cancelled",
                detail = intent.getStringExtra(EXTRA_DETAIL) ?: "Mobile Harness task was cancelled.",
                failed = false,
            )
            else -> {
                val sid = sessionId ?: defaultSessionId()
                val description = "$notificationTitle · $projectName"
                RuntimeTaskController.begin(sid, description)
                RuntimeRecoveryState.beginIn(filesDir, sid, description)
                // A user-started task takes over the device: the recovery
                // window ends here, so this session's own cleanup sweeps work
                // normally again. Protection resets to ON for the fresh task.
                RuntimeTaskController.recoveryActive = false
                recoverySessionIds = emptyList()
                wakeLockDesired = true
                promoteToForeground()
            }
        }
        // Terminal actions end the task on purpose; everything else (start,
        // progress, stop, keepalive) wants the service recreated if Android
        // kills the process, so the guest children are not orphan-frozen.
        return when (intent.action) {
            ACTION_COMPLETE, ACTION_FAILED, ACTION_CANCELLED -> START_NOT_STICKY
            else -> START_STICKY
        }
    }

    /**
     * START_STICKY restart handler. Runs off the main thread; the FGS is
     * already promoted by the time this executes.
     */
    private fun decideAfterRestart() {
        if (recoverySessionIds.isNotEmpty()) {
            val orphans = runCatching { RuntimeInstaller(applicationContext).countGuestOrphans() }.getOrDefault(0)
            if (orphans > 0) {
                Log.w(TAG, "Recovery: $orphans guest process(es) survived; keeping them alive")
                notificationTitle = "Mobile Harness survived"
                getSystemService(NotificationManager::class.java).notify(
                    RUNNING_NOTIFICATION_ID,
                    runningNotification(
                        "The system stopped the app while a task was running; the agent may still be alive. " +
                            "Start a new task to take over, or press Stop to end it.",
                        includeStop = true,
                    ),
                )
                return
            }
            // Nothing survived the kill: drop the recovery state and fall
            // through to the Studio check.
            RuntimeTaskController.recoveryActive = false
            recoverySessionIds = emptyList()
        }
        val studio = StudioServerManager(RuntimeInstaller(applicationContext))
        if (studio.healthCheck()) {
            Log.d(TAG, "Recovery: Studio server survived; keeping it protected")
            studioKeepalive = true
            startStudioWatchdog()
            notificationTitle = "Mobile Harness Studio"
            getSystemService(NotificationManager::class.java).notify(
                RUNNING_NOTIFICATION_ID,
                runningNotification("Ekko Studio server is running in the background.", includeStop = false),
            )
            return
        }
        if (!taskRunning()) teardown()
    }

    private fun handleStop() {
        RuntimeTaskController.requestStop()
        if (taskRunning()) {
            refreshNotification(detail = "Stopping safely…", includeStop = false)
            return
        }
        // No live task: this is a "stop everything" press. Kill surviving
        // guest processes (recovered agents included — the user asked for it),
        // clear the recovery ledger and quit unless the Studio keepalive is on.
        thread(name = "runtime-stop-cleanup") {
            // Clear the recovery guard first: the user explicitly asked for a
            // full stop, so the orphan sweep must be allowed to kill survivors.
            RuntimeTaskController.recoveryActive = false
            recoverySessionIds = emptyList()
            runCatching { RuntimeInstaller(applicationContext).killGuestOrphans() }
            if (studioKeepalive) {
                refreshNotification(
                    detail = "Ekko Studio server is running in the background.",
                    includeStop = false,
                )
            } else {
                teardown()
            }
        }
    }

    private fun workingDetail(): String =
        RuntimeTaskController.describe()?.let { "Working: $it" }
            ?: "Working in $projectName"

    private fun promoteToForeground(
        detail: String = workingDetail(),
        title: String = notificationTitle,
        includeStop: Boolean = canStop,
    ) {
        val notification = runningNotification(detail, title, includeStop)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                RUNNING_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(RUNNING_NOTIFICATION_ID, notification)
        }
        acquireWakeLock()
    }

    private fun refreshNotification(
        detail: String = workingDetail(),
        includeStop: Boolean = canStop,
    ) {
        getSystemService(NotificationManager::class.java).notify(
            RUNNING_NOTIFICATION_ID,
            runningNotification(detail, includeStop = includeStop),
        )
    }

    private fun runningNotification(
        detail: String,
        title: String = notificationTitle,
        includeStop: Boolean,
    ): Notification {
        val builder = NotificationCompat.Builder(this, RUNNING_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(detail)
            .setContentIntent(openAppIntent())
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        // Termux-style wake lock toggle first, then the task action.
        if (wakeLock?.isHeld == true) {
            builder.addAction(0, "Release wake lock", pendingAction(ACTION_TOGGLE_WAKELOCK, 4))
        } else {
            builder.addAction(0, "Acquire wake lock", pendingAction(ACTION_TOGGLE_WAKELOCK, 4))
        }
        if (includeStop) {
            builder.addAction(0, "Stop task", pendingAction(ACTION_STOP, 2))
        } else if (studioKeepalive) {
            builder.addAction(0, "Stop Studio", pendingAction(ACTION_STOP_STUDIO, 3))
        }
        return builder.build()
    }

    private fun pendingAction(action: String, requestCode: Int): PendingIntent = PendingIntent.getService(
        this,
        requestCode,
        Intent(this, RuntimeExecutionService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun finishTask(title: String, detail: String, failed: Boolean, sessionId: String?) {
        val noneLeft = sessionId?.let {
            RuntimeRecoveryState.endIn(filesDir, it)
            RuntimeTaskController.end(it)
        } ?: true
        if (!noneLeft) {
            // Another session is still active: refresh the notification instead
            // of tearing the FGS down (a stale result must not kill a live task).
            refreshNotification()
            return
        }
        releaseWakeLock()
        if (studioKeepalive) {
            // The Studio server still needs the foreground shield — switch the
            // running notification to keepalive mode instead of tearing down.
            notificationTitle = "Mobile Harness Studio"
            projectName = "Ekko Studio"
            refreshNotification(detail = "Ekko Studio server is running in the background.", includeStop = false)
            startStudioWatchdog()
            return
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        val notification = NotificationCompat.Builder(this, RESULT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .setCategory(if (failed) NotificationCompat.CATEGORY_ERROR else NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        getSystemService(NotificationManager::class.java).notify(RESULT_NOTIFICATION_ID, notification)
        stopSelf()
    }

    /**
     * Keeps a PARTIAL WakeLock refreshed while the Studio keepalive is armed,
     * and tears the FGS down when the server stops answering (crashed or was
     * killed) and no task needs the service anymore.
     */
    private fun startStudioWatchdog() {
        if (studioWatchdog?.isAlive == true) return
        studioWatchdog = thread(name = "studio-keepalive") {
            while (studioKeepalive) {
                val manager = StudioServerManager(RuntimeInstaller(applicationContext))
                val alive = runCatching { manager.healthCheck() }.getOrDefault(false)
                val booting = runCatching { manager.isRunning() }.getOrDefault(false)
                if (!alive && !booting) {
                    // Neither answering HTTP nor alive as a process: the server
                    // is gone (crashed or killed) — stop protecting it. A slow
                    // first boot keeps the keepalive (process alive, port not
                    // bound yet).
                    Log.d(TAG, "Studio keepalive: server is gone; releasing")
                    studioKeepalive = false
                    if (!taskRunning()) teardown()
                    break
                }
                acquireWakeLock()
                Thread.sleep(STUDIO_PING_INTERVAL_MS)
            }
        }
    }

    private fun teardown() {
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        1,
        Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun acquireWakeLock() {
        if (!wakeLockDesired || wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "com.jarves.mh:active-coding-task")
            .apply { acquire(MAX_WAKE_LOCK_MS) }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    /**
     * The user swiped the app from Recents or stopped the task. Repromote as a
     * foreground service so Android keeps the process (and its PRoot children
     * with it) alive instead of freezing the cached app. If no session is
     * actually registered and no keepalive is armed, there is nothing to
     * protect — let the system do its job.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (taskRunning()) {
            runCatching {
                val restart = Intent(applicationContext, RuntimeExecutionService::class.java)
                    .setAction(ACTION_PROGRESS)
                    .putExtra(EXTRA_PROJECT_NAME, projectName)
                    .putExtra(EXTRA_DETAIL, "Still working in the background — tap to return")
                // Foreground start is legal here: the service had been foreground
                // until the task was removed, which Android treats as an exemption.
                ContextCompat.startForegroundService(this, restart)
            }
        } else if (studioKeepalive) {
            runCatching {
                startService(
                    Intent(applicationContext, RuntimeExecutionService::class.java).setAction(ACTION_KEEPALIVE),
                )
            }
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        studioKeepalive = false
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "RuntimeExecService"
        const val ACTION_START = "com.jarves.mh.START_RUNTIME"
        const val ACTION_STOP = "com.jarves.mh.STOP_RUNTIME"
        const val ACTION_PROGRESS = "com.jarves.mh.PROGRESS_RUNTIME"
        const val ACTION_COMPLETE = "com.jarves.mh.COMPLETE_RUNTIME"
        const val ACTION_FAILED = "com.jarves.mh.FAIL_RUNTIME"
        const val ACTION_CANCELLED = "com.jarves.mh.CANCEL_RUNTIME"
        const val ACTION_KEEPALIVE = "com.jarves.mh.KEEPALIVE_RUNTIME"
        const val ACTION_STOP_STUDIO = "com.jarves.mh.STOP_STUDIO_RUNTIME"
        const val ACTION_TOGGLE_WAKELOCK = "com.jarves.mh.TOGGLE_WAKELOCK_RUNTIME"
        const val EXTRA_PROJECT_NAME = "project_name"
        const val EXTRA_DETAIL = "detail"
        const val EXTRA_TITLE = "title"
        const val EXTRA_CAN_STOP = "can_stop"
        const val EXTRA_SESSION_ID = "session_id"

        internal const val RUNNING_CHANNEL_ID = "runtime"
        private const val RESULT_CHANNEL_ID = "task-results"
        private const val RUNNING_NOTIFICATION_ID = 41
        private const val RESULT_NOTIFICATION_ID = 42
        private const val MAX_WAKE_LOCK_MS = 90 * 60 * 1_000L
        private const val STUDIO_PING_INTERVAL_MS = 30_000L

        fun defaultSessionId(): String =
            "sess-" + java.util.UUID.randomUUID().toString()

        fun ensureNotificationChannels(context: android.content.Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(RUNNING_CHANNEL_ID, "Running coding tasks", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shows progress while Mobile Harness is working in the background"
                },
            )
            manager.createNotificationChannel(
                NotificationChannel(RESULT_CHANNEL_ID, "Task results", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Notifies you when a coding task finishes or needs attention"
                },
            )
        }
    }
}
