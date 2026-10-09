package com.jarves.mh.runtime

import android.os.Process
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

/**
 * Lifecycle owner for the official Hermes dashboard (`hermes dashboard`)
 * running inside the guest (PRoot Ubuntu). Same topology as
 * [StudioServerManager]: PRoot creates no PID or network namespace, so the
 * guest server listens on the host loopback and is reachable from the app
 * process (and the Dashboard WebView tab) at http://127.0.0.1:9119, and the
 * guest python process is a normal host pid that can be signaled directly.
 *
 * The bind is loopback, which is exactly the configuration the dashboard
 * accepts without an auth provider; a non-loopback bind would fail closed.
 *
 * The server is long-lived and survives headless agent sessions: its guest
 * cmdline (`… hermes dashboard …`) matches none of the
 * [RuntimeInstaller.killGuestOrphans] markers, and the recorded guest pid in
 * [GUEST_PID_FILE] is what [stop] uses for a precise teardown.
 */
class HermesDashboardManager(
    private val installer: RuntimeInstaller,
) {
    private var activeProcess: java.lang.Process? = null

    // Durable on purpose, same rationale as StudioServerManager: cacheDir is
    // wiped on every app update. Read it with:
    //   adb shell run-as com.jarves.mh tail -100 \
    //     files/runtime/ubuntu/root/hermes-dashboard-output.log
    private val outputFile get() = installer.guestFile("root/hermes-dashboard-output.log")

    private fun archivePreviousLog() {
        runCatching {
            if (outputFile.isFile && outputFile.length() > 0) {
                val archived = File(outputFile.parentFile, "${outputFile.name}.1")
                archived.delete()
                outputFile.renameTo(archived)
            }
        }
    }

    fun isRunning(): Boolean = guestPid()?.let { pid -> isDashboardProcess(pid) } == true

    /**
     * Starts the dashboard if it is not already up. Safe to call repeatedly:
     * an already-running server (from a previous app session) is adopted
     * without spawning a duplicate.
     *
     * The first boot builds the web UI (`npm install` + vite in `web/`),
     * which can take minutes on a phone, hence the generous start timeout;
     * callers keep polling [healthCheck] afterwards while the tab loads.
     */
    @Synchronized
    fun start(): StartResult {
        val installed = runCatching { installer.installedRuntime() }.getOrNull()
            ?: return StartResult.error("Core runtime is not ready")
        if (!installer.isAgentInstalled(com.jarves.mh.model.AgentKind.HERMES)) {
            return StartResult.error("Hermes is not installed")
        }
        if (healthCheck()) {
            Log.d(TAG, "Dashboard already serving on port $PORT; adopting it")
            return StartResult.Started(adopted = true)
        }
        guestPid()?.takeIf { isDashboardProcess(it) }?.let { pid ->
            // Stale server that no longer answers HTTP: tear it down first.
            Process.killProcess(pid)
            Thread.sleep(300)
        }
        stopWrapper()
        archivePreviousLog()

        val command =
            "echo \$\$ > $GUEST_PID_FILE && exec ${RuntimeInstaller.HERMES_GUEST_PATH} dashboard " +
                "--host 127.0.0.1 --port $PORT --no-open"
        Log.d(TAG, "Starting Hermes dashboard in guest (port $PORT)")
        val process = installer.process(
            proot = installed.proot,
            rootfs = installed.rootfs,
            workspace = File(installed.rootfs, "root"),
            environment = emptyMap(),
            guestCommand = listOf("/usr/bin/env", "bash", "-lc", command),
            outputFile = outputFile,
        )
        runCatching { process.outputStream.close() }
        activeProcess = process
        val deadline = System.currentTimeMillis() + START_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (!pidAlive(guestPid() ?: -1) && !process.isAlive) {
                val log = tailLog()
                return StartResult.error(
                    "Dashboard exited immediately: " +
                        log.lineSequence().lastOrNull().orEmpty().ifBlank { "no output" },
                )
            }
            if (healthCheck()) return StartResult.Started(adopted = false)
            Thread.sleep(500)
        }
        Log.w(TAG, "Dashboard did not answer within ${START_TIMEOUT_MS / 1000}s; keeping it starting in background")
        return StartResult.Started(adopted = false)
    }

    /**
     * True when something answers HTTP on the dashboard port. Any HTTP
     * status counts — even a 401 proves the server is up (the dashboard's
     * own probe uses the same semantics).
     */
    fun healthCheck(): Boolean {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL("http://127.0.0.1:$PORT/").openConnection() as HttpURLConnection).apply {
                connectTimeout = 1_500
                readTimeout = 1_500
                instanceFollowRedirects = false
                requestMethod = "GET"
            }
            connection.responseCode > 0
        } catch (_: Exception) {
            false
        } finally {
            connection?.disconnect()
        }
    }

    @Synchronized
    fun stop() {
        guestPid()?.let { pid ->
            if (isDashboardProcess(pid)) Process.killProcess(pid)
        }
        stopWrapper()
        runCatching { installer.guestFile(GUEST_PID_FILE).delete() }
        activeProcess = null
        Log.d(TAG, "Hermes dashboard stopped")
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
     * treat the pid as the dashboard when its cmdline says so (visible on
     * the host because PRoot has no PID namespace). The `hermes chat`
     * headless cmdline never contains "dashboard".
     */
    private fun isDashboardProcess(pid: Int): Boolean =
        pidAlive(pid) && runCatching {
            val cmdline = File("/proc/$pid/cmdline").inputStream().use { stream ->
                stream.readBytes().decodeToString()
            }
            DASHBOARD_CMDLINE_MARKER in cmdline
        }.getOrDefault(false)

    sealed class StartResult {
        data class Started(val adopted: Boolean) : StartResult()
        data class Failure(val message: String) : StartResult()

        companion object {
            fun error(message: String): StartResult = Failure(message)
        }
    }

    companion object {
        private const val TAG = "HermesDashboard"
        private const val GUEST_PID_FILE = "/root/.hermes-dashboard.pid"
        // First boot builds the web UI inside the guest (npm + vite); the
        // phone needs minutes, not the Studio server's 12s.
        private const val START_TIMEOUT_MS = 300_000L
        private const val DASHBOARD_CMDLINE_MARKER = "dashboard"

        /** The dashboard's default port; must not collide with the Studio server (8648). */
        const val PORT = 9119

        /** Quick TCP probe used by UI code before pointing the WebView at the port. */
        fun portOpen(): Boolean = runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", PORT), 1_000)
                true
            }
        }.getOrDefault(false)
    }
}
