package com.jarves.mh.tools.skill

internal data class SkillMetadata(
    val name: String?,
    val description: String,
    val completionCriteria: List<String>,
    val failureConditions: List<String>,
)

internal object SkillMetadataParser {
    private val heading = Regex("#{1,6}(?:\\s.*)?")
    private val bullet = Regex("[-*+]\\s+(.+)")
    private val fence = Regex("(`{3,}|~{3,})(.*)")

    fun parse(content: String): SkillMetadata {
        val lines = content.lines()
        var name: String? = null
        var description = ""
        var index = 0

        if (lines.firstOrNull()?.trim() == "---") {
            index = 1
            while (index < lines.size && lines[index].trim() != "---") {
                parseMetadataLine(
                    lines[index],
                    existingName = { name },
                    existingDescription = { description },
                    setName = { name = it },
                    setDescription = { description = it },
                )
                index++
            }

        }

        if (name == null || description.isBlank()) {
            val fallbackEnd = minOf(lines.size, SkillToolLimits.MAX_METADATA_LINES)
            for (lineIndex in 0 until fallbackEnd) {
                parseMetadataLine(
                    lines[lineIndex],
                    existingName = { name },
                    existingDescription = { description },
                    setName = { name = it },
                    setDescription = { description = it },
                )
            }
        }

        return SkillMetadata(
            name = name?.trim()?.takeIf { it.isNotEmpty() },
            description = description.trim(),
            completionCriteria = bulletSection(lines, "## Completion Criteria"),
            failureConditions = bulletSection(lines, "## Failure Conditions"),
        )
    }

    private fun bulletSection(lines: List<String>, title: String): List<String> = buildList {
        var inSection = false
        var openFence = ""
        for (line in lines) {
            val trimmed = line.trim()
            val fenceMatch = fence.matchEntire(trimmed)
            if (fenceMatch != null) {
                val marker = fenceMatch.groupValues[1]
                if (openFence.isEmpty()) {
                    openFence = marker
                } else if (marker.first() == openFence.first() && marker.length >= openFence.length &&
                    fenceMatch.groupValues[2].isBlank()
                ) {
                    openFence = ""
                }
                continue
            }
            if (openFence.isNotEmpty()) continue
            if (heading.matches(trimmed)) {
                inSection = trimmed == title
            } else if (inSection) {
                val item = bullet.matchEntire(trimmed)?.groupValues?.get(1)?.trim()
                if (!item.isNullOrEmpty()) add(item)
            }
        }
    }

    private fun parseMetadataLine(
        line: String,
        existingName: () -> String?,
        existingDescription: () -> String,
        setName: (String) -> Unit,
        setDescription: (String) -> Unit,
    ) {
        val colon = line.indexOf(':')
        if (colon <= 0) return
        val key = line.substring(0, colon).trim()
        val value = unquote(line.substring(colon + 1).trim())
        if (value.isBlank()) return
        when (key.lowercase()) {
            "name" -> if (existingName() == null) setName(value)
            "description" -> if (existingDescription().isEmpty()) setDescription(value)
        }
    }

    private fun unquote(value: String): String {
        if (value.length >= 2) {
            val first = value.first()
            val last = value.last()
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length - 1)
            }
        }
        return value
    }
}
