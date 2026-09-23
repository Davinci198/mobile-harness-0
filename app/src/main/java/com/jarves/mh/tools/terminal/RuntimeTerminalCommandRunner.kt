package com.jarves.mh.tools.terminal

import com.jarves.mh.runtime.NativeSpawnProcess
import com.jarves.mh.runtime.RuntimeInstaller
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets

class RuntimeTerminalCommandRunner(
    private val installer: RuntimeInstaller,
) : TerminalCommandRunner {
    override fun run(
        projectSlug: String,
        command: String,
        timeoutMs: Long,
        rows: Int,
        columns: Int,
    ): TerminalCommandRun {
        val process = installer.processForProject(
            projectSlug = projectSlug,
            guestCommand = listOf("/usr/bin/env", "bash", "-lc", command),
            pseudoTerminal = true,
            ptyRows = rows,
            ptyColumns = columns,
        )
        val native = process as? NativeSpawnProcess
        val outputFile = native?.outputFile
        val collected = StringBuilder()
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        var offset = 0L
        var timedOut = false
        var exitCode = 0
        try {
            while (process.isAlive) {
                if (System.nanoTime() >= deadline) {
                    timedOut = true
                    process.destroyForcibly()
                    process.waitFor()
                    break
                }
                outputFile?.let { file ->
                    if (file.length() > offset) {
                        RandomAccessFile(file, "r").use { input ->
                            input.seek(offset)
                            val available = (input.length() - offset).coerceAtMost(16 * 1024).toInt()
                            val bytes = ByteArray(available)
                            input.readFully(bytes)
                            offset += available
                            appendOutput(collected, String(bytes, StandardCharsets.UTF_8))
                        }
                    }
                }
                Thread.sleep(50)
            }
            val exitCode = process.waitFor()
            outputFile?.let { file ->
                if (file.length() > offset) {
                    RandomAccessFile(file, "r").use { input ->
                        input.seek(offset)
                        val available = (input.length() - offset).coerceAtMost(16 * 1024).toInt()
                        val bytes = ByteArray(available)
                        input.readFully(bytes)
                        appendOutput(collected, String(bytes, StandardCharsets.UTF_8))
                    }
                }
            }
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
        return TerminalCommandRun(
            output = collected.toString(),
            exitCode = exitCode,
            timedOut = timedOut,
        )
    }

    private fun appendOutput(target: StringBuilder, value: String) {
        if (target.length >= TerminalToolLimits.MAX_OUTPUT_CHARS) return
        val normalized = ANSI_ESCAPE.replace(value.replace("\r\n", "\n").replace('\r', '\n'), "")
        val remaining = TerminalToolLimits.MAX_OUTPUT_CHARS - target.length
        target.append(normalized.take(remaining))
    }

    private companion object {
        val ANSI_ESCAPE = Regex("\u001B\\[[0-?]*[ -/]*[@-~]")
    }
}
