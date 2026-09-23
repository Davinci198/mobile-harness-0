package com.jarves.mh.tools.skill

import java.io.File

internal object SkillToolLimits {
    const val MAX_METADATA_LINES = 40
    const val MAX_SKILL_FILE_BYTES = 32_000
    const val MAX_DIRECTORY_ENTRIES = 500
    const val MAX_DIRECTORY_DEPTH = 16
    const val MAX_PROMPT_CHARS = 64_000
    const val MAX_SKILLS = 500
    const val TOOL_NAME = "UseSkill"
}

data class SkillPackage(
    val name: String,
    val description: String,
    val directory: File,
    val skillFile: File,
    val guestDirectoryPath: String,
    val guestSkillFilePath: String,
)

enum class SkillErrorCode {
    INVALID_NAME,
    NOT_FOUND,
    MANIFEST_MISSING,
    MANIFEST_TOO_LARGE,
    ROOT_UNAVAILABLE,
    TOO_MANY_SKILLS,
    PERMISSION_REQUIRED,
    PERMISSION_DENIED,
    READ_FAILED,
}

data class SkillScanIssue(
    val directoryName: String,
    val code: SkillErrorCode,
    val message: String,
)

data class SkillCatalog(
    val skills: List<SkillPackage>,
    val issues: List<SkillScanIssue>,
)

sealed interface SkillToolResult {
    data class Activated(
        val packageInfo: SkillPackage,
        val prompt: String,
    ) : SkillToolResult

    data class Error(
        val tool: String,
        val code: SkillErrorCode,
        val message: String,
    ) : SkillToolResult
}
