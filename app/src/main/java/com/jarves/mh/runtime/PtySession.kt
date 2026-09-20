package com.jarves.mh.runtime

import android.os.ParcelFileDescriptor
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Pas 2 (PLAN-TERMINAL): sesiune PTY live peste [NativeSpawn].
 *
 * Spre deosebire de [NativeSpawnProcess] (care pompeaza master fd intr-un
 * fisier pentru citire ulterioara), aici capatul master ramane deschis ca
 * stream-uri live, pentru a alimenta un emulator VT (TerminalSession):
 * - [output]: citesti ce randeaza procesul (stdout+stderr, cu secvente ANSI)
 * - [input]: scrii taste/input (inclusiv Ctrl+C ca byte 0x03)
 * - [resize]: TIOCSWINSZ + SIGWINCH nu e trimis automat de kernel la ioctl
 *   pe master, asa ca apelantul poate trimite manual SIGWINCH via [kill].
 */
class PtySession private constructor(
    private val pid: Int,
    val input: OutputStream,
    val output: InputStream,
    private val masterFd: Int,
) : AutoCloseable {
    @Volatile private var closed = false

    fun resize(rows: Int, columns: Int): Int {
        if (closed) return -9 // EBADF: nu trimite ioctl pe fd refolosit
        return NativeSpawnPty.resizePty(masterFd, rows, columns)
    }

    fun sendSignal(signal: Int): Int = NativeSpawnPty.kill(pid, signal)

    fun waitFor(): Int = NativeSpawnPty.waitFor(pid, false)

    fun isAlive(): Boolean =
        NativeSpawnPty.waitFor(pid, true) == NativeSpawnPty.STILL_RUNNING

    override fun close() {
        closed = true
        runCatching { input.close() }
        runCatching { output.close() }
        runCatching { NativeSpawnPty.kill(pid, 9) }
    }

    companion object {
        fun start(
            argv: List<String>,
            environment: Map<String, String>,
            cwd: String,
            rows: Int = 40,
            columns: Int = 120,
        ): PtySession {
            // outputFile e ignorat pe calea PTY (nativul scrie direct in master);
            // dam un placeholder in cache pentru compatibilitate cu semnatura.
            val placeholder = File(
                System.getProperty("java.io.tmpdir") ?: "/tmp",
                "pocket-pty-${System.nanoTime()}.ignore",
            )
            val spawned = NativeSpawnPty.spawn(
                argv.toTypedArray(),
                environment.map { "${it.key}=${it.value}" }.toTypedArray(),
                cwd,
                placeholder.absolutePath,
                true,
                rows,
                columns,
            )
            check(spawned.size == 3 && spawned[0] > 0) { "PTY spawn failed" }
            check(spawned[2] >= 0) { "PTY master fd missing" }
            // spawned[1] = dup(master) pentru scris; spawned[2] = master pentru citit.
            // Le adoptam separat ca sa poata fi inchise independent.
            val input = ParcelFileDescriptor.AutoCloseOutputStream(
                ParcelFileDescriptor.adoptFd(spawned[1]),
            )
            val output = ParcelFileDescriptor.AutoCloseInputStream(
                ParcelFileDescriptor.adoptFd(spawned[2]),
            )
            return PtySession(spawned[0], input, output, spawned[2])
        }
    }
}

internal object NativeSpawnPty {
    const val STILL_RUNNING = -2

    init {
        System.loadLibrary("pocketspawn")
    }

    external fun spawn(
        argv: Array<String>,
        environment: Array<String>,
        cwd: String,
        outputFile: String,
        pseudoTerminal: Boolean,
        ptyRows: Int,
        ptyColumns: Int,
    ): IntArray

    external fun waitFor(pid: Int, noHang: Boolean): Int
    external fun kill(pid: Int, signal: Int): Int
    external fun resizePty(masterFd: Int, rows: Int, columns: Int): Int
}
