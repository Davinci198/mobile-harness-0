package com.jarves.mh.runtime

import android.content.Context
import android.os.Process
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

/**
 * Lifecycle owner for the Ekko Studio web server running inside the guest
 * (PRoot Ubuntu). PRoot creates no PID or network namespace, so:
 *
 *  - the guest server listens on the host loopback and is reachable from the
 *    app process (and the Studio WebView tab) at http://127.0.0.1:8648;
 *  - the guest node process is a normal host pid and can be signaled directly.
 *
 * The server is intentionally long-lived and survives headless agent sessions:
 * its guest cmdline (`node /usr/local/lib/studio/dist/server/index.js`) matches
 * none of the [RuntimeInstaller.killGuestOrphans] markers, and the recorded
 * guest pid in [GUEST_PID_FILE] is what [stop] uses for a precise teardown.
 */
class StudioServerManager(
    private val context: Context,
    private val installer: RuntimeInstaller,
) {
    private var activeProcess: java.lang.Process? = null
    private val outputFile get() = File(context.cacheDir, "studio-server-output.log")

    fun isRunning(): Boolean = guestPid()?.let { pid -> isStudioProcess(pid) } == true

    /**
     * Starts the Studio server if it is not already up. Safe to call
     * repeatedly: an already-running server (from a previous app session)
     * is adopted without spawning a duplicate.
     */
    @Synchronized
    fun start(): StartResult {
        val installed = runCatching { installer.installedRuntime() }.getOrNull()
            ?: return StartResult.error("Core runtime is not ready")
        if (!installer.isStudioInstalled()) return StartResult.error("Ekko Studio bundle is not installed")
        if (healthCheck()) {
            Log.d(TAG, "Studio already serving on port $PORT; adopting it")
            return StartResult.Started(adopted = true)
        }
        guestPid()?.takeIf { isStudioProcess(it) }?.let { pid ->
            // Stale server that no longer answers HTTP: tear it down first.
            Process.killProcess(pid)
            Thread.sleep(300)
        }
        stopWrapper()

        val command = "echo \$\$ > $GUEST_PID_FILE && exec ${RuntimeInstaller.STUDIO_GUEST_ENTRY}"
        val environment = mapOf(
            "HERMES_WEB_UI_HOME" to RuntimeInstaller.STUDIO_GUEST_HOME,
            "STUDIO_PORT" to PORT.toString(),
        )
        Log.d(TAG, "Starting Studio server in guest (port $PORT)")
        val process = installer.process(
            proot = installed.proot,
            rootfs = installed.rootfs,
            workspace = File(installed.rootfs, "root"),
            environment = environment,
            guestCommand = listOf("/usr/bin/env", "bash", "-lc", command),
            outputFile = outputFile,
        )
        runCatching { process.outputStream.close() }
        activeProcess = process
        // Give the server a moment to bind the port; a slow first boot
        // (SQLite migration, gateway scan) is still fine because callers
        // poll [healthCheck] while loading the Studio tab.
        val deadline = System.currentTimeMillis() + START_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (!pidAlive(guestPid() ?: -1) && !process.isAlive) {
                val log = tailLog()
                return StartResult.error("Studio exited immediately: ${log.lineSequence().lastOrNull().orEmpty().ifBlank { "no output" }}")
            }
            if (healthCheck()) return StartResult.Started(adopted = false)
            Thread.sleep(300)
        }
        Log.w(TAG, "Studio did not answer within ${START_TIMEOUT_MS / 1000}s; keeping it starting in background")
        return StartResult.Started(adopted = false)
    }

    /**
     * True when the guest server answers HTTP on the Studio port. Uses the
     * server's own /health route (packages/server/src/modules/studio/routes),
     * falling back to "any HTTP answer" semantics if the route moves.
     */
    fun healthCheck(): Boolean {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL("http://127.0.0.1:$PORT/health").openConnection() as HttpURLConnection).apply {
                connectTimeout = 1_500
                readTimeout = 1_500
                instanceFollowRedirects = false
                requestMethod = "GET"
            }
            connection.responseCode in 200..299
        } catch (_: Exception) {
            false
        } finally {
            connection?.disconnect()
        }
    }

    @Synchronized
    fun stop() {
        guestPid()?.let { pid ->
            if (isStudioProcess(pid)) Process.killProcess(pid)
        }
        stopWrapper()
        runCatching { installer.guestFile(GUEST_PID_FILE).delete() }
        activeProcess = null
        Log.d(TAG, "Studio server stopped")
    }

    fun tailLog(maxBytes: Int = 8_000): String = try {
        if (!outputFile.isFile) "" else {
            val bytes = outputFile.readBytes()
            val start = if (bytes.size > maxBytes) bytes.size - maxBytes else 0
            bytes.decodeToString(start, bytes.size).trim()
        }
    } catch (_: Exception) {
        ""
    }

    private fun stopWrapper() {
        runCatching { activeProcess?.destroy() }
        activeProcess = null
    }

    /**
     * Reads the guest pid written by the launch command. The pid file lives in
     * the rootfs, which is plain host storage, so no guest round trip is needed.
     */
    private fun guestPid(): Int? = runCatching {
        val file = installer.guestFile(GUEST_PID_FILE)
        if (!file.isFile) return@runCatching null
        file.readText().trim().toIntOrNull()?.takeIf { it > 0 }
    }.getOrNull()

    private fun pidAlive(pid: Int): Boolean = runCatching {
        val stat = File("/proc/$pid/stat")
        if (!stat.isFile) return@runCatching false
        val fields = stat.readText().substringAfterLast(')').trim().split(' ')
        val state = fields.getOrNull(0) ?: return@runCatching false
        state != "Z"
    }.getOrDefault(false)

    /**
     * Pid file entries can be recycled by Android between sessions; only
     * treat the pid as the Studio server when its cmdline says so (the guest
     * node cmdline is visible on the host because PRoot has no PID namespace).
     */
    private fun isStudioProcess(pid: Int): Boolean =
        pidAlive(pid) && runCatching {
            val cmdline = File("/proc/$pid/cmdline").inputStream().use { stream ->
                stream.readBytes().decodeToString()
            }
            STUDIO_CMDLINE_MARKER in cmdline
        }.getOrDefault(false)

    sealed class StartResult {
        data class Started(val adopted: Boolean) : StartResult()
        data class Failure(val message: String) : StartResult()

        companion object {
            fun error(message: String): StartResult = Failure(message)
        }
    }

    companion object {
        private const val TAG = "StudioServer"
        private const val GUEST_PID_FILE = "/root/.studio-runtime.pid"
        private const val START_TIMEOUT_MS = 12_000L
        private const val STUDIO_CMDLINE_MARKER = "/usr/local/lib/studio"

        /**
         * Single shared port for the guest Studio server. It must not collide
         * with the Hermes gateway (8642), agent web UIs (8700/9119), DSH (3082)
         * or OmniRoute (20128).
         */
        const val PORT = RuntimeInstaller.STUDIO_DEFAULT_PORT

        /** Quick TCP probe used by UI code before pointing the WebView at the port. */
        fun portOpen(): Boolean = runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", PORT), 1_000)
                true
            }
        }.getOrDefault(false)
    }
}
