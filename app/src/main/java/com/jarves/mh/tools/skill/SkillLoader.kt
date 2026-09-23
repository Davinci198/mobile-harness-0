package com.jarves.mh.tools.skill

import com.jarves.mh.tools.AIToolHook
import com.jarves.mh.tools.ToolPermissionGate
import com.jarves.mh.tools.ToolPermissionGateDecision
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

class SkillLoader(
    private val root: File,
    private val guestRoot: String,
    private val gate: ToolPermissionGate,
    private val hooks: List<AIToolHook> = emptyList(),
) {
    private val rootPath: String by lazy { root.canonicalFile.absolutePath }

    fun scan(): SkillCatalog {
        val canonicalRoot = try {
            root.canonicalFile
        } catch (error: Throwable) {
            return SkillCatalog(
                emptyList(),
                listOf(SkillScanIssue(root.name, SkillErrorCode.ROOT_UNAVAILABLE, error.message ?: "Skills root is unavailable")),
            )
        }
        if (!canonicalRoot.isDirectory || !canonicalRoot.canRead()) {
            return SkillCatalog(
                emptyList(),
                listOf(SkillScanIssue(canonicalRoot.name, SkillErrorCode.ROOT_UNAVAILABLE, "Skills root is unavailable")),
            )
        }

        val directories = try {
            canonicalRoot.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name.lowercase() }.orEmpty()
        } catch (error: Throwable) {
            return SkillCatalog(
                emptyList(),
                listOf(SkillScanIssue(canonicalRoot.name, SkillErrorCode.ROOT_UNAVAILABLE, error.message ?: "Skills root cannot be read")),
            )
        }
        if (directories.size > SkillToolLimits.MAX_SKILLS) {
            return SkillCatalog(
                emptyList(),
                listOf(SkillScanIssue(canonicalRoot.name, SkillErrorCode.TOO_MANY_SKILLS, "Too many skill directories")),
            )
        }

        val skills = mutableListOf<SkillPackage>()
        val issues = mutableListOf<SkillScanIssue>()
        val names = mutableSetOf<String>()
        for (directory in directories) {
            val canonicalDirectory = try {
                directory.canonicalFile
            } catch (error: Throwable) {
                issues += SkillScanIssue(directory.name, SkillErrorCode.READ_FAILED, error.message ?: "Directory cannot be read")
                continue
            }
            if (!isInsideRoot(canonicalDirectory)) {
                issues += SkillScanIssue(directory.name, SkillErrorCode.READ_FAILED, "Directory escapes the skills root")
                continue
            }
            val manifest = skillManifest(canonicalDirectory)
            if (manifest == null) {
                issues += SkillScanIssue(directory.name, SkillErrorCode.MANIFEST_MISSING, "SKILL.md is missing")
                continue
            }
            val content = try {
                readManifest(manifest)
            } catch (error: Throwable) {
                issues += SkillScanIssue(directory.name, SkillErrorCode.READ_FAILED, error.message ?: "SKILL.md cannot be read")
                continue
            } ?: run {
                issues += SkillScanIssue(directory.name, SkillErrorCode.MANIFEST_TOO_LARGE, "SKILL.md is too large")
                continue
            }
            val metadata = SkillMetadataParser.parse(content)
            val skillName = metadata.name ?: canonicalDirectory.name
            if (!names.add(skillName)) {
                issues += SkillScanIssue(canonicalDirectory.name, SkillErrorCode.READ_FAILED, "Duplicate skill name: $skillName")
                continue
            }
            val guestDirectory = guestPath(canonicalDirectory.name)
            skills += SkillPackage(
                name = skillName,
                description = metadata.description,
                directory = canonicalDirectory,
                skillFile = manifest,
                guestDirectoryPath = guestDirectory,
                guestSkillFilePath = "$guestDirectory/${manifest.name}",
            )
        }
        return SkillCatalog(skills, issues)
    }

    fun activate(skillName: String): SkillToolResult {
        val tool = SkillToolLimits.TOOL_NAME
        val normalizedName = skillName.trim()
        if (normalizedName.isBlank()) {
            return SkillToolResult.Error(tool, SkillErrorCode.INVALID_NAME, "Skill name is empty")
        }
        val explanation = "Activate skill '$normalizedName'"
        when (val decision = gate.evaluate(tool, explanation.take(240))) {
            is ToolPermissionGateDecision.Allowed -> Unit
            is ToolPermissionGateDecision.NeedsApproval -> {
                return SkillToolResult.Error(tool, SkillErrorCode.PERMISSION_REQUIRED, "Approval required (ASK)")
            }
            is ToolPermissionGateDecision.Blocked -> {
                return SkillToolResult.Error(tool, SkillErrorCode.PERMISSION_DENIED, decision.reason)
            }
        }

        hooks.forEach { it.onToolExecutionStarted(tool) }
        return try {
            val catalog = scan()
            val packageInfo = catalog.skills.firstOrNull { it.name == normalizedName }
                ?: throw SkillLoadException(SkillErrorCode.NOT_FOUND, "Skill not found: $normalizedName")
            val canonicalDirectory = packageInfo.directory.canonicalFile
            if (!isInsideRoot(canonicalDirectory)) {
                throw SkillLoadException(SkillErrorCode.READ_FAILED, "Skill directory escapes the skills root")
            }
            val canonicalManifest = packageInfo.skillFile.canonicalFile
            if (canonicalManifest.parentFile != canonicalDirectory || !isInsideRoot(canonicalManifest)) {
                throw SkillLoadException(SkillErrorCode.READ_FAILED, "SKILL.md escapes the skill directory")
            }
            val content = readManifest(canonicalManifest)
                ?: throw SkillLoadException(SkillErrorCode.MANIFEST_TOO_LARGE, "SKILL.md is too large")
            val tree = buildDirectoryTree(canonicalDirectory, canonicalDirectory, mutableSetOf(), TreeBudget())
            val prompt = buildPrompt(packageInfo, content, tree)
            hooks.forEach { it.onToolExecutionResult(tool, true, packageInfo.name) }
            SkillToolResult.Activated(packageInfo, prompt)
        } catch (error: SkillLoadException) {
            val message = error.message ?: "Skill could not be loaded"
            hooks.forEach { it.onToolExecutionResult(tool, false, message) }
            SkillToolResult.Error(tool, error.code, message)
        } catch (error: Throwable) {
            hooks.forEach { it.onToolExecutionError(tool, error) }
            SkillToolResult.Error(tool, SkillErrorCode.READ_FAILED, error.message ?: "Skill could not be loaded")
        } finally {
            hooks.forEach { it.onToolExecutionFinished(tool) }
        }
    }

    private fun skillManifest(directory: File): File? {
        val candidates = listOf(File(directory, "SKILL.md"), File(directory, "skill.md"))
        return candidates.firstOrNull { candidate ->
            if (!candidate.isFile) return@firstOrNull false
            try {
                isInsideRoot(candidate.canonicalFile)
            } catch (_: Throwable) {
                false
            }
        }
    }

    private fun readManifest(file: File): String? {
        val output = java.io.ByteArrayOutputStream()
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(4_096)
            var remaining = SkillToolLimits.MAX_SKILL_FILE_BYTES + 1
            while (remaining > 0) {
                val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                if (count < 0) break
                output.write(buffer, 0, count)
                remaining -= count
            }
        }
        val bytes = output.toByteArray()
        if (bytes.size > SkillToolLimits.MAX_SKILL_FILE_BYTES) return null
        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun buildPrompt(packageInfo: SkillPackage, content: String, tree: String): String = buildString {
        appendLine("Using skill: ${packageInfo.name}")
        if (packageInfo.description.isNotBlank()) appendLine("Description: ${packageInfo.description}")
        appendLine("Skill file: ${packageInfo.guestSkillFilePath}")
        appendLine("Skill directory: ${packageInfo.guestDirectoryPath}")
        appendLine("Directory tree:")
        appendLine(tree)
        appendLine("SKILL.md content:")
        append(content)
    }

    private fun buildDirectoryTree(
        directory: File,
        skillRoot: File,
        visited: MutableSet<String>,
        budget: TreeBudget,
        depth: Int = 0,
    ): String {
        val canonical = try {
            directory.canonicalFile
        } catch (_: Throwable) {
            return "[unreadable]\n"
        }
        if (!isInside(canonical, skillRoot) || !visited.add(canonical.path) || depth > SkillToolLimits.MAX_DIRECTORY_DEPTH) {
            budget.truncated = true
            return ""
        }
        val entries = try {
            directory.listFiles()
                ?.filterNot { Files.isSymbolicLink(it.toPath()) }
                ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                .orEmpty()
        } catch (_: Throwable) {
            return "[unreadable]\n"
        }
        val output = StringBuilder()
        for (entry in entries) {
            if (budget.entries >= SkillToolLimits.MAX_DIRECTORY_ENTRIES || budget.chars >= SkillToolLimits.MAX_PROMPT_CHARS) {
                budget.truncated = true
                break
            }
            budget.entries++
            val before = output.length
            val indent = "  ".repeat(depth)
            output.append(indent).append(entry.name)
            if (entry.isDirectory) {
                output.append('/').append('\n')
                output.append(buildDirectoryTree(entry, skillRoot, visited, budget, depth + 1))
            } else {
                output.append('\n')
            }
            budget.chars += output.length - before
        }
        if (budget.truncated) output.append(indentFor(depth)).append("[truncated]\n")
        return output.toString()
    }

    private fun indentFor(depth: Int): String = "  ".repeat(depth)

    private fun isInside(file: File, root: File): Boolean =
        file.absolutePath == root.absolutePath || file.absolutePath.startsWith(root.absolutePath + File.separator)

    private fun guestPath(name: String): String = guestRoot.trimEnd('/') + "/" + name

    private fun isInsideRoot(file: File): Boolean =
        file.absolutePath == rootPath || file.absolutePath.startsWith(rootPath + File.separator)
}

private data class TreeBudget(
    var entries: Int = 0,
    var chars: Int = 0,
    var truncated: Boolean = false,
)

private class SkillLoadException(
    val code: SkillErrorCode,
    message: String,
) : IllegalStateException(message)
