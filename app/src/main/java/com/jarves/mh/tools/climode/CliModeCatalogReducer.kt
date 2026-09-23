package com.jarves.mh.tools.climode

data class CliModeInputSchema(
    val propertyNames: List<String> = emptyList(),
    val requiredNames: List<String> = emptyList(),
    val typeByName: Map<String, String> = emptyMap(),
)

data class CliModeTool(
    val name: String,
    val group: String,
    val description: String,
    val keywords: List<String> = emptyList(),
    val inputSchema: CliModeInputSchema? = null,
    val priority: Int = 0,
)

data class CliModeLimits(
    val maxTools: Int = 12,
    val maxCatalogChars: Int = 6_000,
    val maxDescriptionChars: Int = 240,
)

data class CliModeResult(
    val tools: List<CliModeTool>,
    val omittedGroups: List<String>,
    val originalToolCount: Int,
    val reducedToolCount: Int,
)

object CliModeCatalogReducer {
    fun reduce(tools: List<CliModeTool>, limits: CliModeLimits): CliModeResult {
        validate(tools, limits)
        if (tools.isEmpty()) return CliModeResult(emptyList(), emptyList(), 0, 0)

        val groups = linkedMapOf<String, MutableList<IndexedTool>>()
        tools.forEachIndexed { index, tool ->
            groups.getOrPut(tool.group.trim().lowercase()) { mutableListOf() } += IndexedTool(index, tool)
        }
        val selected = groups.values.map { candidates ->
            candidates.minWith(compareByDescending<IndexedTool> { it.tool.priority }.thenBy { it.index })
        }
        val output = mutableListOf<CliModeTool>()
        val omitted = mutableListOf<String>()
        var usedChars = 0
        for (entry in groups.entries) {
            val candidate = selected.first { it.tool.group.trim().lowercase() == entry.key }
            if (output.size >= limits.maxTools) {
                omitted += entry.value.first().tool.group.trim()
                continue
            }
            val compact = candidate.tool.copy(
                description = candidate.tool.description.take(limits.maxDescriptionChars),
                keywords = emptyList(),
                inputSchema = candidate.tool.inputSchema?.let { schema ->
                    schema.copy(
                        propertyNames = schema.propertyNames.toList(),
                        requiredNames = schema.requiredNames.toList(),
                        typeByName = schema.typeByName.toMap(),
                    )
                },
            )
            val size = estimateChars(compact)
            if (usedChars + size > limits.maxCatalogChars) {
                omitted += entry.value.first().tool.group.trim()
            } else {
                output += compact
                usedChars += size
            }
        }
        return CliModeResult(output, omitted, tools.size, output.size)
    }

    private fun validate(tools: List<CliModeTool>, limits: CliModeLimits) {
        require(limits.maxTools > 0) { "CLI mode tool budget must be positive" }
        require(limits.maxCatalogChars > 0) { "CLI mode catalog budget must be positive" }
        require(limits.maxDescriptionChars > 0) { "CLI mode description budget must be positive" }
        val names = mutableSetOf<String>()
        tools.forEach { tool ->
            require(tool.name.isNotBlank()) { "CLI mode tool name is empty" }
            require(tool.group.isNotBlank()) { "CLI mode tool group is empty" }
            require(names.add(tool.name)) { "Duplicate CLI mode tool name: ${tool.name}" }
        }
    }

    private fun estimateChars(tool: CliModeTool): Int {
        val schema = tool.inputSchema
        return buildString {
            append(tool.name.length)
            append(tool.group.length)
            append(tool.description.length)
            schema?.let {
                append(it.propertyNames.sumOf(String::length))
                append(it.requiredNames.sumOf(String::length))
                append(it.typeByName.entries.sumOf { entry -> entry.key.length + entry.value.length })
            }
        }.length
    }

    private data class IndexedTool(val index: Int, val tool: CliModeTool)
}
