package com.jarves.mh.tools.climode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CliModeCatalogReducerTest {
    @Test
    fun emptyCatalogReturnsZeroCounts() {
        val result = CliModeCatalogReducer.reduce(emptyList(), CliModeLimits())
        assertEquals(0, result.originalToolCount)
        assertEquals(0, result.reducedToolCount)
        assertTrue(result.tools.isEmpty())
    }

    @Test
    fun retainsOneHighestPriorityToolPerCaseInsensitiveGroup() {
        val result = reduce(
            listOf(
                tool("Read", "Files", priority = 0),
                tool("Write", "files", priority = 5),
                tool("Grep", "FILES", priority = 2),
                tool("Bash", "Terminal"),
            ),
        )
        assertEquals(listOf("Write", "Bash"), result.tools.map { it.name })
        assertEquals(listOf("files", "Terminal"), result.tools.map { it.group })
    }

    @Test
    fun toolBudgetLimitsRepresentativesNotAllTools() {
        val result = reduce(
            (1..10).map { tool("T$it", "group-$it") },
            CliModeLimits(maxTools = 3, maxCatalogChars = 10_000),
        )
        assertEquals(3, result.tools.size)
        assertEquals(10, result.originalToolCount)
        assertEquals(7, result.omittedGroups.size)
    }

    @Test
    fun characterBudgetOmitsWholeOversizedTool() {
        val input = listOf(
            tool("small", "one", description = "ok"),
            tool("large", "two", description = "x".repeat(100)),
        )
        val result = reduce(input, CliModeLimits(maxTools = 10, maxCatalogChars = 50, maxDescriptionChars = 100))
        assertEquals(listOf("small"), result.tools.map { it.name })
        assertEquals(listOf("two"), result.omittedGroups)
    }

    @Test
    fun descriptionIsBoundedAndKeywordsRemoved() {
        val result = reduce(
            listOf(tool("Read", "Files", description = "x".repeat(100), keywords = listOf("read"))),
            CliModeLimits(maxDescriptionChars = 10),
        )
        assertEquals(10, result.tools.single().description.length)
        assertTrue(result.tools.single().keywords.isEmpty())
    }

    @Test
    fun compactSchemaPreservesOrderRequiredAndTypes() {
        val schema = CliModeInputSchema(
            propertyNames = listOf("path", "startLine", "endLine"),
            requiredNames = listOf("path"),
            typeByName = linkedMapOf("path" to "string", "startLine" to "integer"),
        )
        val result = reduce(listOf(tool("Read", "Files", inputSchema = schema)))
        val compact = result.tools.single().inputSchema!!
        assertEquals(schema.propertyNames, compact.propertyNames)
        assertEquals(schema.requiredNames, compact.requiredNames)
        assertEquals(schema.typeByName, compact.typeByName)
    }

    @Test
    fun selectedExecutionNamesComeOnlyFromInput() {
        val input = listOf(tool("Read", "Files"), tool("Bash", "Terminal"))
        val names = reduce(input).tools.map { it.name }.toSet()
        assertTrue(names.all { candidate -> input.any { it.name == candidate } })
        assertTrue(names.none { it == "search" || it == "proxy" })
    }

    @Test
    fun sameInputProducesSameOrder() {
        val input = listOf(tool("B", "B"), tool("A", "A"), tool("C", "C"))
        assertEquals(reduce(input).tools, reduce(input).tools)
        assertEquals(listOf("B", "A", "C"), reduce(input).tools.map { it.name })
    }

    @Test
    fun rejectsInvalidBudgetsNamesGroupsAndDuplicates() {
        assertInvalid(CliModeLimits(maxTools = 0))
        assertInvalid(CliModeLimits(maxCatalogChars = 0))
        assertInvalid(CliModeLimits(maxDescriptionChars = 0))
        assertInvalid(tool(" ", "group"))
        assertInvalid(tool("Read", " "))
        assertInvalid(listOf(tool("Read", "A"), tool("Read", "B")))
    }

    @Test
    fun caseVariantNamesRemainDistinct() {
        val result = reduce(listOf(tool("Read", "A"), tool("read", "B")))
        assertEquals(listOf("Read", "read"), result.tools.map { it.name })
    }

    @Test
    fun inputToolObjectsAreNotMutated() {
        val inputTool = tool("Read", "Files", description = "original", keywords = listOf("x"))
        reduce(listOf(inputTool), CliModeLimits(maxDescriptionChars = 3))
        assertEquals("original", inputTool.description)
        assertEquals(listOf("x"), inputTool.keywords)
    }

    private fun reduce(
        tools: List<CliModeTool>,
        limits: CliModeLimits = CliModeLimits(),
    ) = CliModeCatalogReducer.reduce(tools, limits)

    private fun tool(
        name: String,
        group: String,
        description: String = "description",
        keywords: List<String> = emptyList(),
        inputSchema: CliModeInputSchema? = null,
        priority: Int = 0,
    ) = CliModeTool(name, group, description, keywords, inputSchema, priority)

    private fun assertInvalid(limits: CliModeLimits) {
        assertTrue(runCatching { reduce(emptyList(), limits) }.exceptionOrNull() is IllegalArgumentException)
    }

    private fun assertInvalid(tool: CliModeTool) {
        assertTrue(runCatching { reduce(listOf(tool)) }.exceptionOrNull() is IllegalArgumentException)
    }

    private fun assertInvalid(tools: List<CliModeTool>) {
        assertTrue(runCatching { reduce(tools) }.exceptionOrNull() is IllegalArgumentException)
    }
}
