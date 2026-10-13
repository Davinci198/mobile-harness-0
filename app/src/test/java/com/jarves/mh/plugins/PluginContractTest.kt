package com.jarves.mh.plugins

import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginContractTest {

    @Test
    fun contractConstantsMatchTheManifestContract() {
        assertEquals("com.jarves.mh.plugin.capabilities", PluginContract.META_CAPABILITIES)
        assertEquals("com.jarves.mh.plugin.ACTION_TOOL_HOST", PluginContract.ACTION_TOOL_HOST)
        assertEquals("tools.json", PluginContract.TOOLS_ASSET)
        assertEquals("listTools", PluginContract.ACTION_LIST_TOOLS)
        assertEquals("execute", PluginContract.ACTION_EXECUTE)
    }

    @Test
    fun parsesCommaSeparatedCapabilities() {
        assertEquals(
            setOf(PluginCapability.TOOLS, PluginCapability.COMMANDS),
            PluginContract.parseCapabilities("tools,commands"),
        )
    }

    @Test
    fun ignoresUnknownBlankAndPaddedTokens() {
        assertEquals(
            setOf(PluginCapability.TOOLS, PluginCapability.SETTINGS),
            PluginContract.parseCapabilities(" tools, teleport ,,settings "),
        )
    }

    @Test
    fun capabilityParsingIsCaseInsensitive() {
        assertEquals(
            setOf(PluginCapability.TOOLS, PluginCapability.COMMANDS, PluginCapability.SETTINGS),
            PluginContract.parseCapabilities("TOOLS,Commands,Settings"),
        )
    }

    @Test
    fun nullOrBlankCapabilitiesParseToEmpty() {
        assertTrue(PluginContract.parseCapabilities(null).isEmpty())
        assertTrue(PluginContract.parseCapabilities("").isEmpty())
        assertTrue(PluginContract.parseCapabilities("   ").isEmpty())
    }

    @Test
    fun parsesCapabilitiesFromTokenSet() {
        assertEquals(
            setOf(PluginCapability.TOOLS, PluginCapability.COMMANDS),
            PluginContract.parseCapabilities(listOf("Tools", " commands ", "teleport")),
        )
    }

    @Test
    fun parseCapabilityDropsUnusableTokens() {
        assertNull(PluginContract.parseCapability("teleport"))
        assertEquals(PluginCapability.SETTINGS, PluginContract.parseCapability(" settings "))
    }

    @Test
    fun parsesToolDeclarationsFromToolsJson() {
        val tools = PluginContract.parseTools(
            """
            {
              "tools": [
                {
                  "name": "MyTool",
                  "description": "What it does",
                  "inputSchema": {"type": "object", "properties": {"path": {"type": "string"}}}
                }
              ]
            }
            """.trimIndent(),
        )
        assertEquals(1, tools.size)
        assertEquals("MyTool", tools[0].name)
        assertEquals("What it does", tools[0].description)
        assertEquals("object", tools[0].inputSchema()?.optString("type"))
        assertEquals(
            "string",
            tools[0].inputSchema()?.optJSONObject("properties")?.optJSONObject("path")?.optString("type"),
        )
    }

    @Test
    fun missingToolsArrayYieldsNoDeclarations() {
        assertTrue(PluginContract.parseTools("{}").isEmpty())
        assertTrue(PluginContract.parseTools("""{"other": []}""").isEmpty())
    }

    @Test
    fun entriesWithoutAUsableNameAreSkipped() {
        val tools = PluginContract.parseTools(
            """
            {
              "tools": [
                {"description": "nameless"},
                {"name": ""},
                {"name": "  "},
                {"name": "Kept"}
              ]
            }
            """.trimIndent(),
        )
        assertEquals(listOf("Kept"), tools.map { it.name })
    }

    @Test
    fun malformedDocumentThrows() {
        assertThrows(JSONException::class.java) { PluginContract.parseTools("not json") }
    }

    @Test
    fun toolsDocumentRoundTripsThroughParse() {
        val original = listOf(
            PluginContract.PluginToolDeclaration(
                name = "MyTool",
                description = "What it does",
                inputSchemaJson = """{"type":"object","properties":{"path":{"type":"string"}}}""",
            ),
            PluginContract.PluginToolDeclaration(name = "PlainTool"),
        )
        val parsed = PluginContract.parseTools(PluginContract.toolsDocument(original).toString())
        assertEquals(original, parsed)
    }

    @Test
    fun toolsDocumentKeepsDeclaredShape() {
        val document = PluginContract.toolsDocument(
            listOf(PluginContract.PluginToolDeclaration("Echo", "Returns its input")),
        )
        val array = document.optJSONArray("tools")
        assertEquals(1, array!!.length())
        assertEquals("Echo", array.getJSONObject(0).optString("name"))
        assertEquals("Returns its input", array.getJSONObject(0).optString("description"))
    }
}
