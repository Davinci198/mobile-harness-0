package com.jarves.mh.runtime

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Crash-recovery ledger for background tasks. Before spawning a guest agent,
 * the bridge records the session on disk; when Android later kills the whole
 * app process (memory pressure, cached-app freezer escalation) and START_STICKY
 * recreates [RuntimeExecutionService], the fresh process reads this ledger and
 * learns that guest agents may still be running.
 *
 * The practical consequence: [RuntimeInstaller.killGuestOrphans] normally kills
 * reparented guest processes because a stale `opencode serve` blocks the next
 * headless run — but right after a recovery those "orphans" are the user's
 * still-working agent, so the sweep must spare them once.
 */
internal object RuntimeRecoveryState {
    private const val FILE_NAME = "runtime-recovery-tasks.txt"
    private const val MAX_ENTRIES = 8

    /** Records a session as in-flight, durable across process death. */
    fun beginIn(filesDir: File, sessionId: String, description: String) {
        runCatching {
            ledger(filesDir).apply {
                val lines = readLines().filter { it.isNotBlank() && !it.startsWith("$sessionId|") }
                lines += "$sessionId|${description.take(160).replace('\n', ' ')}"
                writeText(lines.takeLast(MAX_ENTRIES).joinToString(separator = "\n", postfix = "\n"))
            }
        }.onFailure { Log.w("RuntimeRecovery", "Could not record recovery state", it) }
    }

    /** Removes a finished session from the ledger. */
    fun endIn(filesDir: File, sessionId: String) {
        runCatching {
            val file = ledger(filesDir)
            if (!file.isFile) return
            val remaining = file.readLines().filter { it.isNotBlank() && !it.startsWith("$sessionId|") }
            if (remaining.isEmpty()) file.delete() else file.writeText(remaining.joinToString(separator = "\n", postfix = "\n"))
        }.onFailure { Log.w("RuntimeRecovery", "Could not clear recovery entry", it) }
    }

    /**
     * Reads and clears the ledger. Returns the sessions that were in flight
     * when the previous process died, oldest first.
     */
    fun consumeIn(filesDir: File): List<Pair<String, String>> {
        val file = ledger(filesDir)
        if (!file.isFile) return emptyList()
        return runCatching {
            val entries = file.readLines()
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    val separator = line.indexOf('|')
                    if (separator <= 0) null else line.take(separator) to line.substring(separator + 1)
                }
            file.delete()
            entries
        }.getOrElse {
            runCatching { file.delete() }
            emptyList()
        }
    }

    private fun ledger(filesDir: File): File = File(filesDir, FILE_NAME)
}

/**
 * Recovery-aware orphan helpers. Kept in one place so both
 * [RuntimeInstaller.killGuestOrphans] and the service's restart decision share
 * the same definitions of "surviving guest process".
 */
internal object GuestOrphanScan {
    /**
     * Counts reparented (ppid == 1) guest processes whose cmdline matches one
     * of the known agent/PRoot markers. Used after a STICKY restart to decide
     * whether anything worth reporting survived the kill.
     */
    fun countSurvivors(markers: List<String>): Int {
        val candidates = File("/proc").listFiles { file -> file.name.toIntOrNull() != null } ?: return 0
        var count = 0
        for (dir in candidates) {
            runCatching {
                val stat = File(dir, "stat").readText()
                val ppid = stat.substringAfterLast(')').trim().split(' ').getOrNull(1)?.toIntOrNull()
                    ?: return@runCatching
                if (ppid != 1) return@runCatching
                val cmdline = File(dir, "cmdline").inputStream().use { stream ->
                    stream.readBytes().decodeToString()
                }
                if (cmdline.isNotBlank() && markers.any { it in cmdline }) count++
            }
        }
        return count
    }

    /** The same markers [RuntimeInstaller.killGuestOrphans] sweeps by. */
    fun orphanMarkers(): List<String> = listOf(
        "libproot.so",
        RuntimeInstaller.OPENCODE_GUEST_PATH,
        RuntimeInstaller.HERMES_GUEST_PATH,
        "/usr/local/bin/claude",
        "hermes-agent",
        "opencode serve",
    )
}
