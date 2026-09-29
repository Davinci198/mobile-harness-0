package com.jarves.mh.storage

import com.jarves.mh.runtime.ShizukuCommandResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuFileSystemTest {
    private val calls = mutableListOf<String>()

    /** Stands in for the real bridge so the test asserts on the command, not on a device. */
    private fun fs(
        start: String = "/data",
        reply: (String) -> ShizukuCommandResult,
    ): ShizukuFileSystem {
        calls.clear()
        return ShizukuFileSystem(
            root = DeviceRoot.Shizuku("test", start),
            run = { command, _ ->
                calls += command
                reply(command)
            },
        )
    }

    private fun ok(stdout: String) = ShizukuCommandResult(0, stdout, "")

    @Test
    fun listStartsFromTheRootAndParsesTheOutput() = runBlocking {
        val system = fs { ok("41f9|0|100|docs\n81a4|12|100|a.txt") }
        val entries = system.list("").valueOrNull()!!
        assertEquals(listOf("docs", "a.txt"), entries.map { it.name })
        assertTrue(calls.single().contains("cd '/data'"))
    }

    @Test
    fun aMissingDirectoryIsReportedAsNotFound() = runBlocking {
        val system = fs { ShizukuCommandResult(4, "", "") }
        assertEquals(FsErrorKind.NOT_FOUND, system.list("nope").errorOrNull()!!.kind)
    }

    @Test
    fun withoutShizukuTheErrorPointsAtRequestingIt() = runBlocking {
        val system = fs { ShizukuCommandResult(-1, "", "not available") }
        val error = system.list("").errorOrNull()!!
        assertEquals(FsErrorKind.NO_ACCESS, error.kind)
        assertEquals(FsRemedy.REQUEST_SHIZUKU, error.remedy)
    }

    @Test
    fun pathsAreAnchoredAtTheStartPath() = runBlocking {
        val system = fs(start = "/data/local/tmp") { ok("81a4|1|100|probe.txt") }
        system.stat("probe.txt")
        // The relative name is resolved against the root, not sent as-is.
        assertTrue(calls.single(), calls.single().contains("'/data/local/tmp/probe.txt'"))
        assertTrue(calls.single(), !calls.single().contains("stat 'probe.txt'"))
    }

    @Test
    fun traversalIsRefusedBeforeAnyCommandRuns() = runBlocking {
        val system = fs { ok("") }
        assertTrue(system.list("../etc").errorOrNull()!!.message.contains("escapes"))
        assertTrue(system.readText("..").errorOrNull()!!.message.contains("escapes"))
        assertTrue(calls.isEmpty())
    }

    @Test
    fun writeSendsBase64SoTheContentNeverTouchesTheShellLine() = runBlocking {
        val system = fs { ok("") }
        system.writeText("note.txt", "hello \"world\" \$(id)")
        val command = calls.single()
        assertTrue(command.contains("base64 -d"))
        // The raw payload must not appear: only its base64 form may.
        assertTrue(!command.contains("world"))
        assertTrue(!command.contains("\$(id)"))
    }

    @Test
    fun tooLargeAWriteIsRefusedWithoutRunningAnything() = runBlocking {
        val system = fs { ok("") }
        val big = "x".repeat((FsLimits.MAX_TEXT_READ_BYTES + 10).toInt())
        assertTrue(system.writeText("big.txt", big).errorOrNull()!!.message.contains("too large"))
        assertTrue(calls.isEmpty())
    }

    @Test
    fun appendingIsRefused() = runBlocking {
        val system = fs { ok("") }
        assertTrue(system.writeText("a.txt", "b", append = true).errorOrNull()!!.message.contains("Appending"))
    }

    @Test
    fun renameRejectsNamesWithASlash() = runBlocking {
        val system = fs { ok("") }
        assertTrue(system.rename("a.txt", "b/c").errorOrNull()!!.message.contains("Invalid name"))
        assertTrue(system.rename("a.txt", "..").errorOrNull()!!.message.contains("Invalid name"))
    }

    @Test
    fun deletingADirectoryRefusesToRecurseSilently() = runBlocking {
        val system = fs { command ->
            if (command.startsWith("ls -A")) ok("something") else ok("")
        }
        val error = system.delete("dir").errorOrNull()!!
        assertTrue(error.message.contains("not empty"))
    }

    @Test
    fun theRootItselfCannotBeDeletedOrRenamed() = runBlocking {
        val system = fs { ok("") }
        assertTrue(system.delete("").errorOrNull()!!.message.contains("root"))
        assertTrue(system.rename("", "x").errorOrNull()!!.message.contains("root"))
    }
}
