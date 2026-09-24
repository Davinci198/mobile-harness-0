package com.jarves.mh.runtime

import com.jarves.mh.model.AgentKind
import com.jarves.mh.model.ChangeItem
import com.jarves.mh.model.DiffLine
import com.jarves.mh.model.DiffLineType
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

/**
 * Workspace checkpoint / snapshot / diff store shared by agent bridges.
 *
 * Each Project and Agent gets a baseline copy before a session runs; after the
 * run the baseline is diffed to produce reviewable [ChangeItem]s with
 * per-file Undo/Keep. Semantics mirror the original Claude bridge store so
 * both agents behave identically in the Changes tab.
 */
enum class WorkspaceChangeMutationStatus {
    APPLIED,
    UNCHANGED,
    CONFLICT,
}

data class WorkspaceChangeMutationResult(
    val status: WorkspaceChangeMutationStatus,
    val conflicts: List<String> = emptyList(),
)

class WorkspaceCheckpoints(
    private val filesDir: File,
    private val agent: AgentKind? = null,
) {
    private val projectRoots = ConcurrentHashMap<String, String>()

    fun forAgent(agent: AgentKind): WorkspaceCheckpoints =
        WorkspaceCheckpoints(filesDir, agent)

    fun ensureWorkspace(projectId: String): File {
        val base = File(filesDir, "workspaces/$projectId").apply { mkdirs() }.canonicalFile
        val rootPath = projectRoots[projectId] ?: readProjectRoot(projectId)
        if (rootPath.isBlank()) return base
        val selected = File(base, rootPath).canonicalFile
        require(selected.toPath().startsWith(base.toPath())) { "Unsafe project root" }
        return selected.apply { mkdirs() }
    }

    fun configureProjectRoot(projectId: String, rootPath: String): Boolean {
        val normalized = rootPath.trim().trim('/')
        require(normalized.isBlank() || (!normalized.contains("..") && !normalized.startsWith('/'))) {
            "Unsafe project root"
        }
        val cachedRoot = projectRoots[projectId]
        val rootFile = projectRootFile(projectId)
        val previous = cachedRoot ?: readProjectRoot(projectId)
        val initializingMetadata = cachedRoot == null && !rootFile.isFile
        if (!initializingMetadata && previous != normalized && hasPendingHistory(projectId)) return false
        writeAtomically(projectRootFile(projectId), normalized)
        projectRoots[projectId] = normalized
        return true
    }

    fun checkpointDir(projectId: String): File {
        val scopedAgent = agent
        return if (scopedAgent == null) {
            File(filesDir, "checkpoints/$projectId/latest")
        } else {
            File(filesDir, "change-history/$projectId/${scopedAgent.stableId}")
        }
    }

    private fun projectRootFile(projectId: String): File = File(filesDir, "change-history/$projectId/root.txt")

    private fun readProjectRoot(projectId: String): String =
        projectRootFile(projectId).takeIf(File::isFile)?.readText()?.trim().orEmpty()

    private fun migrationMarker(projectId: String): File = File(filesDir, "change-history/$projectId/migration-agent.txt")

    private fun migrationOwner(projectId: String): String =
        migrationMarker(projectId).takeIf(File::isFile)?.readText()?.trim().orEmpty()

    private fun hasPendingHistory(projectId: String): Boolean {
        val scopedAgent = agent
        if (scopedAgent != null) return isValidCheckpoint(checkpointDir(projectId))
        val projectHistory = File(filesDir, "change-history/$projectId")
        return isValidCheckpoint(File(filesDir, "checkpoints/$projectId/latest")) ||
            projectHistory.listFiles().orEmpty().any { it.isDirectory && isValidCheckpoint(it) }
    }

    fun migrateLegacy(projectId: String, activeAgent: AgentKind, workspace: File): Boolean =
        synchronized(migrationLock(projectId)) { migrateLegacyLocked(projectId, activeAgent, workspace) }

    private fun migrateLegacyLocked(projectId: String, activeAgent: AgentKind, workspace: File): Boolean {
        val legacy = File(filesDir, "checkpoints/$projectId/latest")
        if (!legacy.isDirectory) return false
        val owner = migrationOwner(projectId)
        if (owner.isNotBlank() && owner != activeAgent.stableId) return false
        val destination = forAgent(activeAgent).checkpointDir(projectId)
        if (destination.exists()) {
            if (isValidCheckpoint(destination)) {
                if (owner.isBlank()) writeAtomically(migrationMarker(projectId), activeAgent.stableId)
                return false
            }
            destination.deleteRecursively()
        }
        destination.parentFile?.mkdirs()
        if (!legacy.renameTo(destination)) {
            try {
                legacy.copyRecursively(destination)
            } catch (error: Throwable) {
                destination.deleteRecursively()
                throw error
            }
            if (!isValidCheckpoint(destination)) {
                destination.deleteRecursively()
                return false
            }
            legacy.deleteRecursively()
        } else if (!isValidCheckpoint(destination)) {
            check(destination.renameTo(legacy)) { "Could not roll back incomplete checkpoint migration" }
            return false
        }
        if (owner.isBlank()) writeAtomically(migrationMarker(projectId), activeAgent.stableId)
        val history = forAgent(activeAgent)
        history.recordObservedState(projectId, workspace, history.readChangedPaths(projectId))
        return true
    }

    private fun isValidCheckpoint(directory: File): Boolean =
        File(directory, "project").isDirectory && File(directory, "changes.json").isFile

    fun prepare(projectId: String, workspace: File): WorkspaceCheckpoints {
        val scopedAgent = agent
        if (scopedAgent != null) migrateLegacy(projectId, scopedAgent, workspace)
        return this
    }

    fun createCheckpoint(projectId: String, workspace: File) {
        prepare(projectId, workspace)
        val checkpoint = checkpointDir(projectId)
        // Keep the original baseline until every pending file is accepted or undone.
        if (File(checkpoint, "project").isDirectory && File(checkpoint, "changes.json").isFile) return
        checkpoint.deleteRecursively()
        val backup = File(checkpoint, "project").apply { mkdirs() }
        val workspacePath = workspace.canonicalFile.toPath()
        workspace.walkTopDown()
            .onEnter { directory ->
                directory == workspace || (
                    !java.nio.file.Files.isSymbolicLink(directory.toPath()) &&
                        runCatching { directory.canonicalFile.toPath().startsWith(workspacePath) }.getOrDefault(false)
                    )
            }
            .filter {
                it.isFile &&
                    !isInternalRuntimePath(it.relativeTo(workspace).invariantSeparatorsPath) &&
                    !java.nio.file.Files.isSymbolicLink(it.toPath())
            }
            .forEach { source ->
                val relative = source.relativeTo(workspace).invariantSeparatorsPath
                val destination = safeWorkspaceFile(backup, relative)
                destination.parentFile?.mkdirs()
                source.copyTo(destination, overwrite = true)
            }
    }

    fun saveChangedPaths(projectId: String, paths: List<String>, workspace: File? = null) {
        val manifest = File(checkpointDir(projectId), "changes.json")
        manifest.parentFile?.mkdirs()
        val merged = (readChangedPaths(projectId) + paths)
            .filterNot(::isInternalRuntimePath)
            .distinct()
            .sorted()
        writeAtomically(manifest, JSONArray(merged).toString())
        if (workspace != null) recordObservedState(projectId, workspace, paths)
    }

    fun readChangedPaths(projectId: String): List<String> {
        val manifest = File(checkpointDir(projectId), "changes.json")
        if (!manifest.isFile) return emptyList()
        return runCatching {
            val array = JSONArray(manifest.readText())
            (0 until array.length()).map(array::getString)
        }.getOrDefault(emptyList())
    }

    fun removeChangedPath(projectId: String, path: String) {
        val remaining = readChangedPaths(projectId).filterNot { it == path }
        if (remaining.isEmpty()) {
            checkpointDir(projectId).deleteRecursively()
        } else {
            writeAtomically(File(checkpointDir(projectId), "changes.json"), JSONArray(remaining).toString())
        }
    }

    fun undoFile(projectId: String, workspace: File, path: String): WorkspaceChangeMutationResult =
        undoPaths(projectId, workspace, setOf(path))

    fun acceptFile(projectId: String, workspace: File, path: String): WorkspaceChangeMutationResult =
        acceptPaths(projectId, workspace, setOf(path))

    fun undoAll(projectId: String, workspace: File): WorkspaceChangeMutationResult {
        prepare(projectId, workspace)
        return undoPaths(projectId, workspace, readChangedPaths(projectId).toSet())
    }

    fun acceptAll(projectId: String, workspace: File): WorkspaceChangeMutationResult {
        prepare(projectId, workspace)
        return acceptPaths(projectId, workspace, readChangedPaths(projectId).toSet())
    }

    fun undoPaths(projectId: String, workspace: File, requestedPaths: Set<String>): WorkspaceChangeMutationResult {
        prepare(projectId, workspace)
        val paths = readChangedPaths(projectId).filter { it in requestedPaths && !isInternalRuntimePath(it) }
        if (paths.isEmpty()) return WorkspaceChangeMutationResult(WorkspaceChangeMutationStatus.UNCHANGED)
        val observed = readObservedState(projectId)
        val conflicts = paths.filter { observed[it] == null || observed[it] != currentDigest(workspace, it) }
        if (conflicts.isNotEmpty()) {
            return WorkspaceChangeMutationResult(WorkspaceChangeMutationStatus.CONFLICT, conflicts)
        }
        paths.forEach { restoreOriginal(projectId, workspace, it) }
        if (paths.size == readChangedPaths(projectId).size) {
            checkpointDir(projectId).deleteRecursively()
        } else {
            paths.forEach { removeChangedPath(projectId, it) }
        }
        return WorkspaceChangeMutationResult(WorkspaceChangeMutationStatus.APPLIED)
    }

    fun acceptPaths(projectId: String, workspace: File, requestedPaths: Set<String>): WorkspaceChangeMutationResult {
        prepare(projectId, workspace)
        val paths = readChangedPaths(projectId).filter { it in requestedPaths && !isInternalRuntimePath(it) }
        if (paths.isEmpty()) return WorkspaceChangeMutationResult(WorkspaceChangeMutationStatus.UNCHANGED)
        paths.forEach { acceptCurrent(projectId, workspace, it) }
        if (paths.size == readChangedPaths(projectId).size) {
            checkpointDir(projectId).deleteRecursively()
        } else {
            paths.forEach { removeChangedPath(projectId, it) }
        }
        return WorkspaceChangeMutationResult(WorkspaceChangeMutationStatus.APPLIED)
    }

    private fun restoreOriginal(projectId: String, workspace: File, path: String) {
        val target = safeWorkspaceFile(workspace, path)
        val original = safeWorkspaceFile(File(checkpointDir(projectId), "project"), path)
        if (original.isFile) {
            target.parentFile?.mkdirs()
            original.copyTo(target, overwrite = true)
        } else {
            target.delete()
        }
    }

    private fun acceptCurrent(projectId: String, workspace: File, path: String) {
        val current = safeWorkspaceFile(workspace, path)
        val baseline = safeWorkspaceFile(File(checkpointDir(projectId), "project"), path)
        if (current.isFile) {
            baseline.parentFile?.mkdirs()
            current.copyTo(baseline, overwrite = true)
        } else {
            baseline.delete()
        }
    }

    private fun recordObservedState(projectId: String, workspace: File, paths: List<String>) {
        val state = File(checkpointDir(projectId), "observed.json")
        val observed = readObservedState(projectId).toMutableMap()
        paths.filterNot(::isInternalRuntimePath).forEach { path ->
            observed[path] = currentDigest(workspace, path)
        }
        val json = JSONObject()
        observed.toSortedMap().forEach { (path, digest) -> json.put(path, digest) }
        writeAtomically(state, json.toString())
    }

    private fun readObservedState(projectId: String): Map<String, String> {
        val state = File(checkpointDir(projectId), "observed.json")
        if (!state.isFile) return emptyMap()
        return runCatching {
            val json = JSONObject(state.readText())
            json.keys().asSequence().associateWith { json.getString(it) }
        }.getOrDefault(emptyMap())
    }

    private fun writeAtomically(target: File, content: String) {
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, ".${target.name}.${System.nanoTime()}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(content.toByteArray())
                output.fd.sync()
            }
            check(temporary.renameTo(target)) { "Could not atomically update ${target.name}" }
        } finally {
            temporary.delete()
        }
    }

    private fun currentDigest(workspace: File, path: String): String =
        safeWorkspaceFile(workspace, path).takeIf(File::isFile)?.let(::digest) ?: MISSING_DIGEST

    fun buildChangeDetails(projectId: String, workspace: File, paths: List<String>): List<ChangeItem> {
        val backup = File(checkpointDir(projectId), "project")
        return paths.map { path ->
            val before = safeWorkspaceFile(backup, path).takeIf(File::isFile)?.readBytes() ?: ByteArray(0)
            val after = safeWorkspaceFile(workspace, path).takeIf(File::isFile)?.readBytes() ?: ByteArray(0)
            val binary = before.any { it == 0.toByte() } || after.any { it == 0.toByte() }
            val (additions, deletions) = lineChanges(before, after)
            ChangeItem(
                path = path,
                additions = additions,
                deletions = deletions,
                diffLines = buildDiffLines(before, after),
                binary = binary,
            )
        }
    }

    fun buildDiffLines(beforeBytes: ByteArray, afterBytes: ByteArray): List<DiffLine> {
        if (beforeBytes.any { it == 0.toByte() } || afterBytes.any { it == 0.toByte() }) {
            return listOf(DiffLine(DiffLineType.INFO, "Binary file changed"))
        }
        val before = textLines(beforeBytes)
        val after = textLines(afterBytes)
        if (before.size > MAX_RENDERED_DIFF_LINES || after.size > MAX_RENDERED_DIFF_LINES) {
            return listOf(
                DiffLine(
                    DiffLineType.INFO,
                    "Diff is too large to display (${before.size} → ${after.size} lines). Undo and Keep still work.",
                ),
            )
        }

        val lcs = Array(before.size + 1) { IntArray(after.size + 1) }
        for (oldIndex in before.lastIndex downTo 0) {
            for (newIndex in after.lastIndex downTo 0) {
                lcs[oldIndex][newIndex] = if (before[oldIndex] == after[newIndex]) {
                    lcs[oldIndex + 1][newIndex + 1] + 1
                } else {
                    maxOf(lcs[oldIndex + 1][newIndex], lcs[oldIndex][newIndex + 1])
                }
            }
        }

        val result = mutableListOf<DiffLine>()
        var oldIndex = 0
        var newIndex = 0
        while (oldIndex < before.size || newIndex < after.size) {
            when {
                oldIndex < before.size && newIndex < after.size && before[oldIndex] == after[newIndex] -> {
                    result += DiffLine(DiffLineType.CONTEXT, before[oldIndex], oldIndex + 1, newIndex + 1)
                    oldIndex++
                    newIndex++
                }
                newIndex < after.size && (oldIndex == before.size || lcs[oldIndex][newIndex + 1] >= lcs[oldIndex + 1][newIndex]) -> {
                    result += DiffLine(DiffLineType.ADDITION, after[newIndex], null, newIndex + 1)
                    newIndex++
                }
                oldIndex < before.size -> {
                    result += DiffLine(DiffLineType.DELETION, before[oldIndex], oldIndex + 1, null)
                    oldIndex++
                }
            }
        }
        return collapseUnchangedLines(result)
    }

    private fun collapseUnchangedLines(lines: List<DiffLine>): List<DiffLine> {
        val changedIndexes = lines.indices.filter { lines[it].type != DiffLineType.CONTEXT }
        if (changedIndexes.isEmpty()) return lines
        val visible = BooleanArray(lines.size)
        changedIndexes.forEach { changed ->
            for (index in maxOf(0, changed - DIFF_CONTEXT_LINES)..minOf(lines.lastIndex, changed + DIFF_CONTEXT_LINES)) {
                visible[index] = true
            }
        }
        val result = mutableListOf<DiffLine>()
        var index = 0
        while (index < lines.size) {
            if (visible[index]) {
                result += lines[index++]
            } else {
                val start = index
                while (index < lines.size && !visible[index]) index++
                result += DiffLine(DiffLineType.INFO, "… ${index - start} unchanged lines …")
            }
        }
        return result
    }

    private fun lineChanges(beforeBytes: ByteArray, afterBytes: ByteArray): Pair<Int, Int> {
        if (beforeBytes.any { it == 0.toByte() } || afterBytes.any { it == 0.toByte() }) {
            return (if (afterBytes.isNotEmpty()) 1 else 0) to (if (beforeBytes.isNotEmpty()) 1 else 0)
        }
        val before = textLines(beforeBytes)
        val after = textLines(afterBytes)
        if (before.size > MAX_DIFF_LINES || after.size > MAX_DIFF_LINES) {
            return maxOf(0, after.size - before.size) to maxOf(0, before.size - after.size)
        }
        var previous = IntArray(after.size + 1)
        before.forEach { oldLine ->
            val current = IntArray(after.size + 1)
            after.forEachIndexed { index, newLine ->
                current[index + 1] = if (oldLine == newLine) {
                    previous[index] + 1
                } else {
                    maxOf(previous[index + 1], current[index])
                }
            }
            previous = current
        }
        val common = previous[after.size]
        return (after.size - common) to (before.size - common)
    }

    private fun textLines(bytes: ByteArray): List<String> {
        if (bytes.isEmpty()) return emptyList()
        val lines = bytes.decodeToString().split('\n')
        return if (lines.lastOrNull().isNullOrEmpty()) lines.dropLast(1) else lines
    }

    fun safeWorkspaceFile(root: File, relative: String): File {
        require(relative.isNotBlank() && !relative.startsWith('/')) { "Unsafe workspace path" }
        val file = File(root, relative)
        val rootPath = root.canonicalFile.toPath()
        val parentPath = (file.parentFile ?: root).canonicalFile.toPath()
        require(parentPath.startsWith(rootPath)) { "Workspace path escapes project" }
        return file
    }

    fun snapshot(root: File): Map<String, String> = root.walkTopDown()
        .filter { it.isFile && !isInternalRuntimePath(it.relativeTo(root).invariantSeparatorsPath) }
        .associate { it.relativeTo(root).path to digest(it) }

    fun changedFiles(root: File, before: Map<String, String>): List<String> {
        val after = snapshot(root)
        return (before.keys + after.keys).distinct().filter { before[it] != after[it] }.sorted()
    }

    fun isInternalRuntimePath(path: String): Boolean {
        val normalized = path.replace('\\', '/')
        return normalized == ".claude" || normalized == ".claude.json" || normalized.startsWith(".claude/")
    }

    private fun digest(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private val migrationLocks = ConcurrentHashMap<String, Any>()
        private fun migrationLock(projectId: String): Any = migrationLocks.computeIfAbsent(projectId) { Any() }
        private const val MISSING_DIGEST = "<missing>"
        private const val MAX_DIFF_LINES = 2_000
        private const val MAX_RENDERED_DIFF_LINES = 600
        private const val DIFF_CONTEXT_LINES = 3
    }
}
