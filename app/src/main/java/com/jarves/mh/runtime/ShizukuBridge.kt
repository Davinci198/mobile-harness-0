package com.jarves.mh.runtime

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/** What the app knows about the Shizuku service on this device. */
data class ShizukuStatus(
    val installed: Boolean,
    val running: Boolean,
    val granted: Boolean,
) {
    val usable: Boolean get() = running && granted
}

/** Result of one command run through the Shizuku shell. */
data class ShizukuCommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false,
) {
    val ok: Boolean get() = exitCode == 0 && !timedOut
}

/**
 * Thin wrapper over the Shizuku API. Shizuku hands out a shell running as the `shell`
 * user, which is enough for `pm`, `am`, `dumpsys` and `settings` without rooting the
 * device. Every call is defensive: the service can disappear between two calls, so
 * nothing here throws at the caller.
 */
object ShizukuBridge {
    /** Request code used for the permission dialog the Shizuku provider shows. */
    const val REQUEST_CODE = 4021

    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val ALLOWED_UID_ROOT = 0
    private const val ALLOWED_UID_SHELL = 2000

    /** Guards against a runaway command flooding the guest/UI. */
    const val MAX_OUTPUT_CHARS = 64 * 1024

    private val permissionListeners = mutableMapOf<Int, (Boolean) -> Unit>()
    private var binderListenerRegistered = false

    /** Shizuku app present on the device (newer builds can also run headless via Sui). */
    fun isInstalled(context: Context): Boolean {
        val installed = runCatching {
            context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            true
        }.getOrDefault(false)
        return installed || isServiceRunning()
    }

    /** The Shizuku service is up and talking. */
    fun isServiceRunning(): Boolean = binderUid() != null

    /** We were granted the right to talk to the service. */
    fun hasPermission(): Boolean = try {
        isServiceRunning() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    fun status(context: Context): ShizukuStatus {
        val running = isServiceRunning()
        return ShizukuStatus(
            installed = isInstalled(context),
            running = running,
            granted = if (running) hasPermission() else false,
        )
    }

    /**
     * Asks the Shizuku provider to show its permission dialog. [onResult] fires with the
     * grant outcome, including when the service shows up later without a new request.
     */
    fun requestPermission(onResult: (Boolean) -> Unit) {
        ensureBinderListener()
        if (hasPermission()) {
            onResult(true)
            return
        }
        val existing = permissionListeners.remove(REQUEST_CODE)
        existing?.invoke(false)
        permissionListeners[REQUEST_CODE] = onResult
        runCatching {
            Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        }
        runCatching { Shizuku.requestPermission(REQUEST_CODE) }
            .onFailure { permissionListeners.remove(REQUEST_CODE)?.invoke(false) }
    }

    /** Notifies [listener] whenever the service becomes available, so the UI can refresh. */
    fun addBinderReceivedListener(listener: () -> Unit) {
        ensureBinderListener()
        runCatching { Shizuku.addBinderReceivedListener(shizukuBinderListener) }
        // If the service is already up, tell the caller straight away.
        if (isServiceRunning()) listener()
    }

    /**
     * Runs [command] through `sh -c` as the Shizuku shell user. Returns a non-zero
     * [ShizukuCommandResult.exitCode] with the reason in [ShizukuCommandResult.stderr]
     * instead of throwing when the service is unavailable.
     */
    suspend fun execute(command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): ShizukuCommandResult =
        withContext(Dispatchers.IO) {
            if (!hasPermission()) {
                return@withContext ShizukuCommandResult(
                    exitCode = -1,
                    stdout = "",
                    stderr = "Shizuku is not available or access was not granted",
                )
            }
            val process = runCatching { Shizuku.newProcess(arrayOf("sh", "-c", command), null, null) }
                .getOrElse { error ->
                    return@withContext ShizukuCommandResult(-1, "", error.message ?: "Cannot start Shizuku process")
                }

            val stdout = StringBuilder()
            val stderr = StringBuilder()
            val outThread = thread { drain(process.inputStream, stdout) }
            val errThread = thread { drain(process.errorStream, stderr) }

            // RemoteProcess only exposes a blocking waitFor(); bound it with a join so a
            // hung command cannot pin this thread forever.
            val waiter = thread { runCatching { process.waitFor() } }
            runCatching { waiter.join(timeoutMs) }
            val finished = !waiter.isAlive
            if (!finished) runCatching { process.destroy() }
            runCatching { outThread.join(READ_JOIN_MS) }
            runCatching { errThread.join(READ_JOIN_MS) }

            ShizukuCommandResult(
                exitCode = if (finished) waiter.get() else -1,
                stdout = stdout.toString(),
                stderr = stderr.toString(),
                timedOut = !finished,
            )
        }

    /** Shizuku hands the pipes back as file descriptors, so read them through a stream. */
    private fun drain(descriptor: ParcelFileDescriptor, sink: StringBuilder) {
        runCatching {
            FileInputStream(descriptor.fileDescriptor).bufferedReader().use { reader ->
                while (sink.length < MAX_OUTPUT_CHARS) {
                    val line = reader.readLine() ?: break
                    if (sink.isNotEmpty()) sink.append('\n')
                    sink.append(line)
                }
            }
        }
    }

    /**
     * Resolves the service uid the same way Shizuku's own sample does: a live binder plus
     * a uid of root or shell. Anything else means we are not talking to the real service.
     */
    private fun binderUid(): Int? = try {
        val binder = Shizuku.getBinder()
        if (binder == null || !binder.isBinderAlive) {
            null
        } else {
            val uid = Shizuku.getUid()
            if (uid == ALLOWED_UID_ROOT || uid == ALLOWED_UID_SHELL) uid else null
        }
    } catch (_: Throwable) {
        null
    }

    private fun ensureBinderListener() {
        if (binderListenerRegistered) return
        binderListenerRegistered = true
        runCatching { Shizuku.addBinderReceivedListener(shizukuBinderListener) }
    }

    private val shizukuBinderListener = Shizuku.OnBinderReceivedListener {
        // Service came up: anyone waiting on a permission decision can now be answered.
        val waiting = permissionListeners.values.toList()
        permissionListeners.clear()
        waiting.forEach { it(hasPermission()) }
    }

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        val callback = permissionListeners.remove(requestCode)
        if (callback != null) callback(grantResult == PackageManager.PERMISSION_GRANTED)
    }

    const val DEFAULT_TIMEOUT_MS = 30_000L
    private const val READ_JOIN_MS = 2_000L
}

/**
 * Falls back to the bundled Shizuku APK when the service is not installed on the device.
 * Installing it still needs the user, so we only ever hand it to the system installer.
 */
object BundledShizuku {
    private const val ASSET_APK = "shizuku.apk"
    private const val ASSET_VERSION = "shizuku_version.txt"
    private const val TARGET_DIR = "shizuku"

    fun bundledVersion(context: Context): String? = runCatching {
        context.assets.open(ASSET_VERSION).bufferedReader().use { it.readText().trim() }
    }.getOrNull()

    /** Copies the bundled APK out of assets so the system installer can read it. */
    fun extract(context: Context): File? = runCatching {
        val dir = File(context.filesDir, TARGET_DIR).apply { mkdirs() }
        val target = File(dir, ASSET_APK)
        if (!target.exists() || target.length() == 0L) {
            context.assets.open(ASSET_APK).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }
        target.takeIf { it.length() > 0L }
    }.getOrNull()

    /** Hands the bundled APK to the system installer. The user confirms from there. */
    fun promptInstall(context: Context): Boolean {
        val apk = extract(context) ?: return false
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        }.getOrElse { Uri.fromFile(apk) }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return runCatching { context.startActivity(intent) }.isSuccess
    }
}
