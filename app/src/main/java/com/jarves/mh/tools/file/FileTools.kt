package com.jarves.mh.tools.file

import com.jarves.mh.tools.ToolPermissionGate
import com.jarves.mh.tools.ToolPermissionGateDecision
import java.io.File

/**
 * Native MH file tools: read / write / apply / grep / find.
 * Every call is gated by [ToolPermissionGate] (Claude tool-name mapping).
 * Paths are sandboxed to the workspace [root].
 */
class FileTools(
    private val root: File,
    private val gate: ToolPermissionGate,
    private val hooks: List<com.jarves.mh.tools.AIToolHook> = emptyList(),
) {
    private val sandbox = FileToolSandbox(root)

    // --- gate helpers -------------------------------------------------

    private fun check(toolName: String, explanation: String): FileToolResult.FileToolError? {
        return when (val decision = gate.evaluate(toolName, explanation)) {
            is ToolPermissionGateDecision.Allowed -> null
            is ToolPermissionGateDecision.Blocked ->
                FileToolResult.FileToolError(toolName, decision.reason)
            is ToolPermissionGateDecision.NeedsApproval ->
                FileToolResult.FileToolError(toolName, "Approval required (ASK)")
        }
    }

    private fun started(toolName: String) {
        hooks.forEach { it.onToolExecutionStarted(toolName) }
    }

    private fun finished(toolName: String) {
        hooks.forEach { it.onToolExecutionFinished(toolName) }
    }

    private fun result(toolName: String, r: FileToolResult) {
        val ok = r !is FileToolResult.FileToolError
        val summary = when (r) {
            is FileToolResult.FileContent -> "read ${r.totalLines} lines"
            is FileToolResult.FileWritten -> "wrote ${r.bytes} bytes"
            is FileToolResult.FileApplied -> "applied ${r.replacements} replacements"
            is FileToolResult.GrepMatches -> "${r.matches.size} matches"
            is FileToolResult.FoundFiles -> "${r.files.size} files"
            is FileToolResult.GrepMatch -> "grep match"
            is FileToolResult.FileToolError -> r.message
        }
        hooks.forEach { it.onToolExecutionResult(toolName, ok, summary) }
    }

    private fun error(toolName: String, t: Throwable) {
        hooks.forEach { it.onToolExecutionError(toolName, t) }
    }

    private fun relPath(f: File): String {
        val rootCanonical = File(sandbox.rootPath)
        return try {
            f.relativeTo(rootCanonical).path.ifEmpty { f.name }
        } catch (_: Exception) {
            f.path
        }
    }

    // --- public API ---------------------------------------------------

    /** Read a file with 1-based line numbers. Optional [startLine]/[endLine] (1-based, inclusive). */
    fun read(
        path: String,
        startLine: Int = 1,
        endLine: Int = FileToolLimits.DEFAULT_READ_PART_LINES,
    ): FileToolResult {
        val tool = "Read"
        val gateErr = check(tool, "read $path")
        if (gateErr != null) return gateErr
        started(tool)
        try {
            val file = sandbox.resolve(path)
                ?: return FileToolResult.FileToolError(tool, "Path escapes sandbox: $path")
            if (!file.isFile) return FileToolResult.FileToolError(tool, "Not a file: $path")

            val raw = file.readText()
            val lines = raw.split('\n')
            val total = lines.size
            val from = startLine.coerceAtLeast(1)
            val to = endLine.coerceAtLeast(from)
            val startIdx = (from - 1).coerceIn(0, total)
            val endIdx = minOf(to, total).coerceIn(startIdx, total)
            val slice = lines.subList(startIdx, endIdx)
            val numbered = slice.mapIndexed { i, l -> "${from + i}| $l" }.joinToString("\n")
            val body = if (raw.length > FileToolLimits.MAX_FILE_READ_BYTES) {
                numbered.take(FileToolLimits.MAX_FILE_READ_BYTES) + FileToolLimits.TRUNCATION_MARKER
            } else {
                numbered
            }
            val truncated = raw.length > FileToolLimits.MAX_FILE_READ_BYTES || to < total
            val r = FileToolResult.FileContent(path, body, total, truncated)
            result(tool, r)
            return r
        } catch (t: Throwable) {
            error(tool, t)
            return FileToolResult.FileToolError(tool, t.message ?: "read failed")
        } finally {
            finished(tool)
        }
    }

    /** Write (create/overwrite or append) a file inside the sandbox. */
    fun write(path: String, content: String, append: Boolean = false): FileToolResult {
        val tool = "Write"
        val gateErr = check(tool, "write $path")
        if (gateErr != null) return gateErr
        started(tool)
        try {
            val file = sandbox.resolve(path)
                ?: return FileToolResult.FileToolError(tool, "Path escapes sandbox: $path")
            file.parentFile?.mkdirs()
            if (append && file.exists()) {
                file.appendText(content)
            } else {
                file.writeText(content)
            }
            val r = FileToolResult.FileWritten(path, content.toByteArray().size.toLong(), append)
            result(tool, r)
            return r
        } catch (t: Throwable) {
            error(tool, t)
            return FileToolResult.FileToolError(tool, t.message ?: "write failed")
        } finally {
            finished(tool)
        }
    }

    /** Replace every occurrence of [old] with [new]. 0 matches = error. */
    fun apply(path: String, old: String, new: String): FileToolResult {
        val tool = "Edit"
        val gateErr = check(tool, "edit $path")
        if (gateErr != null) return gateErr
        started(tool)
        try {
            if (old.isEmpty()) return FileToolResult.FileToolError(tool, "old text is empty")
            val file = sandbox.resolve(path)
                ?: return FileToolResult.FileToolError(tool, "Path escapes sandbox: $path")
            if (!file.isFile) return FileToolResult.FileToolError(tool, "Not a file: $path")

            val text = file.readText()
            val count = text.split(old).size - 1
            if (count == 0) return FileToolResult.FileToolError(tool, "old text not found")
            file.writeText(text.replace(old, new))
            val r = FileToolResult.FileApplied(path, count)
            result(tool, r)
            return r
        } catch (t: Throwable) {
            error(tool, t)
            return FileToolResult.FileToolError(tool, t.message ?: "apply failed")
        } finally {
            finished(tool)
        }
    }

    /** Grep files under [path] (file or directory) for [pattern] (regex). */
    fun grep(
        path: String,
        pattern: String,
        caseInsensitive: Boolean = false,
        maxResults: Int = FileToolLimits.MAX_GREP_RESULTS,
    ): FileToolResult {
        val tool = "Grep"
        val gateErr = check(tool, "grep $pattern in $path")
        if (gateErr != null) return gateErr
        started(tool)
        try {
            val regex = try {
                if (caseInsensitive) Regex(pattern, RegexOption.IGNORE_CASE) else Regex(pattern)
            } catch (e: Exception) {
                return FileToolResult.FileToolError(tool, "Invalid regex: ${e.message}")
            }
            val start = sandbox.resolve(path)
                ?: return FileToolResult.FileToolError(tool, "Path escapes sandbox: $path")

            val files = if (start.isFile) listOf(start) else start.walkTopDown()
                .filter { it.isFile }.toList()

            val matches = mutableListOf<FileToolResult.GrepMatch>()
            var truncated = false
            for (f in files) {
                if (matches.size >= maxResults) { truncated = true; break }
                val lines = f.readText().split('\n')
                for ((idx, line) in lines.withIndex()) {
                    if (matches.size >= maxResults) { truncated = true; break }
                    if (regex.containsMatchIn(line)) {
                        matches += FileToolResult.GrepMatch(
                            path = relPath(f),
                            lineNumber = idx + 1,
                            line = line,
                        )
                    }
                }
            }
            val r = FileToolResult.GrepMatches(path, pattern, matches, truncated)
            result(tool, r)
            return r
        } catch (t: Throwable) {
            error(tool, t)
            return FileToolResult.FileToolError(tool, t.message ?: "grep failed")
        } finally {
            finished(tool)
        }
    }

    /** Find files under [path] matching [glob] (e.g. `*.kt`, `**\/Test*.kt`). */
    fun find(
        path: String = ".",
        glob: String,
        maxDepth: Int = -1,
        caseInsensitive: Boolean = false,
        maxResults: Int = FileToolLimits.MAX_FIND_RESULTS,
    ): FileToolResult {
        val tool = "Glob"
        val gateErr = check(tool, "find $glob in $path")
        if (gateErr != null) return gateErr
        started(tool)
        try {
            val regex = globToRegex(glob, caseInsensitive)
            val start = sandbox.resolve(path)
                ?: return FileToolResult.FileToolError(tool, "Path escapes sandbox: $path")
            if (!start.exists()) return FileToolResult.FileToolError(tool, "Not found: $path")

            val found = mutableListOf<String>()
            var truncated = false
            val walker = if (maxDepth >= 0) start.walkTopDown().maxDepth(maxDepth) else start.walkTopDown()
            for (f in walker) {
                if (!f.isFile) continue
                if (found.size >= maxResults) { truncated = true; break }
                val display = relPath(f)
                if (regex.matches(display) || regex.matches(f.name)) {
                    found += display
                }
            }
            val r = FileToolResult.FoundFiles(path, glob, found, truncated)
            result(tool, r)
            return r
        } catch (t: Throwable) {
            error(tool, t)
            return FileToolResult.FileToolError(tool, t.message ?: "find failed")
        } finally {
            finished(tool)
        }
    }

    companion object {
        /** Convert a glob pattern to a Regex (`*`→`.*`, `?`→`.`, `{a,b}`→`(a|b)`). */
        fun globToRegex(glob: String, caseInsensitive: Boolean = false): Regex {
            val sb = StringBuilder()
            var i = 0
            while (i < glob.length) {
                when (val c = glob[i]) {
                    '*' -> {
                        if (i + 1 < glob.length && glob[i + 1] == '*') {
                            sb.append(".*")
                            i += 2
                            if (i < glob.length && glob[i] == '/') i++
                            continue
                        } else {
                            sb.append("[^/]*")
                        }
                    }
                    '?' -> sb.append("[^/]")
                    '{' -> {
                        val end = glob.indexOf('}', i)
                        if (end > i) {
                            val alts = glob.substring(i + 1, end).split(',')
                            sb.append("(").append(alts.joinToString("|")).append(")")
                            i = end + 1
                            continue
                        } else sb.append("\\{")
                    }
                    else -> sb.append(Regex.escape(c.toString()))
                }
                i++
            }
            return Regex(
                sb.toString(),
                if (caseInsensitive) setOf(RegexOption.IGNORE_CASE) else emptySet(),
            )
        }
    }
}
