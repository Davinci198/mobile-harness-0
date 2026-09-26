package com.jarves.mh.runtime

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.jarves.mh.R
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/**
 * Captures the screen via MediaProjection and writes a rolling JPEG into the
 * active project workspace (`.mh-screen/live.jpg`) so the coding agent can
 * Read the user's current screen. Frames are throttled (~1.6s) and rendered
 * at a reduced size; this is a "what the user sees" feed, not video.
 */
class ScreenShareService : Service() {

    private var projection: MediaProjection? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var outputDir: File? = null
    private var lastSaveMillis = 0L
    private var stopping = false

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        getString(R.string.notif_screen_share_title),
                        NotificationManager.IMPORTANCE_LOW,
                    )
                )
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopEverything()
            return START_NOT_STICKY
        }
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra(EXTRA_PROJECTION_DATA) as? Intent
        val dirPath = intent?.getStringExtra(EXTRA_OUTPUT_DIR)
        if (data == null || dirPath == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0,
        )
        val dir = File(dirPath).apply { mkdirs() }
        outputDir = dir
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val mp = mpm.getMediaProjection(Activity.RESULT_OK, data)
        if (mp == null) {
            stopEverything()
            return START_NOT_STICKY
        }
        projection = mp
        val thread = HandlerThread("mh-screen-share").also { it.start() }
        this.thread = thread
        val handler = Handler(thread.looper)
        this.handler = handler
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() = stopEverything()
        }, handler)
        val metrics = resources.displayMetrics
        val scale = (1280f / maxOf(metrics.widthPixels, metrics.heightPixels)).coerceAtMost(1f)
        val width = (metrics.widthPixels * scale).toInt().and(1.inv())
        val height = (metrics.heightPixels * scale).toInt().and(1.inv())
        val reader = ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, 2)
        imageReader = reader
        reader.setOnImageAvailableListener({ r -> captureFrame(r) }, handler)
        virtualDisplay = mp.createVirtualDisplay(
            "mh-screen",
            width,
            height,
            metrics.densityDpi,
            android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            handler,
        )
        running.value = true
        Log.i(TAG, "screen share started ${width}x${height} -> $dirPath")
        return START_NOT_STICKY
    }

    private fun captureFrame(reader: ImageReader) {
        val image = reader.acquireLatestImage() ?: return
        try {
            val now = SystemClock.elapsedRealtime()
            if (now - lastSaveMillis < FRAME_INTERVAL_MS) return
            val dir = outputDir ?: return
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val width = image.width
            val height = image.height
            val rowPadding = rowStride - pixelStride * width
            val padded = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
            buffer.rewind()
            padded.copyPixelsFromBuffer(buffer)
            val frame = if (rowPadding == 0) padded else Bitmap.createBitmap(padded, 0, 0, width, height).also { padded.recycle() }
            val tmp = File(dir, "$OUTPUT_FILE.tmp")
            try {
                tmp.outputStream().use { frame.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
                if (!tmp.renameTo(File(dir, OUTPUT_FILE))) {
                    File(dir, OUTPUT_FILE).delete()
                    tmp.renameTo(File(dir, OUTPUT_FILE))
                }
            } finally {
                tmp.delete()
            }
            frame.recycle()
            lastSaveMillis = now
        } catch (e: Exception) {
            Log.w(TAG, "frame capture failed: ${e.message}")
        } finally {
            image.close()
        }
    }

    private fun stopEverything() {
        if (stopping) return
        stopping = true
        runCatching { virtualDisplay?.release() }
        runCatching { imageReader?.close() }
        runCatching { projection?.stop() }
        virtualDisplay = null
        imageReader = null
        projection = null
        thread?.quitSafely()
        thread = null
        handler = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        running.value = false
        stopSelf()
    }

    override fun onDestroy() {
        stopEverything()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            8,
            Intent(this, ScreenShareService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_screen_share_title))
            .setContentText(getString(R.string.notif_screen_share_text))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, getString(R.string.screen_share_stop), stopIntent)
            .build()
    }

    companion object {
        private const val TAG = "ScreenShare"
        private const val CHANNEL_ID = "screen_share"
        private const val NOTIFICATION_ID = 44
        private const val FRAME_INTERVAL_MS = 1600L
        private const val JPEG_QUALITY = 72

        const val ACTION_STOP = "com.jarves.mh.action.STOP_SCREEN_SHARE"
        const val EXTRA_PROJECTION_DATA = "com.jarves.mh.extra.PROJECTION_DATA"
        const val EXTRA_OUTPUT_DIR = "com.jarves.mh.extra.SCREEN_OUTPUT_DIR"
        const val OUTPUT_SUBDIR = ".mh-screen"
        const val OUTPUT_FILE = "live.jpg"

        /** True while capturing; the ViewModel mirrors this into UI state. */
        val running = MutableStateFlow(false)

        fun start(context: Context, projectionData: Intent, outputDir: String) {
            val intent = Intent(context, ScreenShareService::class.java)
                .putExtra(EXTRA_PROJECTION_DATA, projectionData)
                .putExtra(EXTRA_OUTPUT_DIR, outputDir)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ScreenShareService::class.java))
        }
    }
}
