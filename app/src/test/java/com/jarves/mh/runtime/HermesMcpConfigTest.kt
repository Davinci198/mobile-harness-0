package com.jarves.mh.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesMcpConfigTest {
    private val fixture = """
        _config_version: 46
        mcp_servers:
          ekko-studio-api:
            command: ekko-studio-mcp
            args:
              - api
            env:
              HERMES_MCP_SERVER_NAME: ekko-studio-api
            enabled: true
          ekko-studio-browser:
            command: ekko-studio-mcp
            args:
              - browser
            enabled: true
          keep-me:
            command: /usr/local/bin/real-tool
            enabled: true
        tools:
          browser:
            enabled: true
        """.trimIndent()

    @Test
    fun parksServersWhoseCommandIsMissing() {
        val result = parkMissingMcpServers(fixture) { it == "/usr/local/bin/real-tool" }

        assertEquals(2, result.parked)
        assertTrue(result.config.contains("  ekko-studio-api:\n    command: ekko-studio-mcp\n    args:\n      - api\n    env:\n      HERMES_MCP_SERVER_NAME: ekko-studio-api\n    enabled: false"))
        assertTrue("keep-me must stay enabled", result.config.contains("  keep-me:\n    command: /usr/local/bin/real-tool\n    enabled: true"))
    }

    @Test
    fun leavesConfigUntouchedWhenEveryCommandExists() {
        val result = parkMissingMcpServers(fixture) { true }

        assertEquals(0, result.parked)
        assertEquals(fixture, result.config)
    }

    @Test
    fun doesNotDoubleParkAlreadyDisabledServers() {
        val config = """
            mcp_servers:
              gone:
                command: missing-tool
                enabled: false
            """.trimIndent()

        val result = parkMissingMcpServers(config) { false }

        assertEquals(0, result.parked)
        assertEquals(config, result.config)
    }

    @Test
    fun matchesQuotedCommands() {
        val config = """
            mcp_servers:
              quoted:
                command: "missing-tool"
                enabled: true
            """.trimIndent()

        val result = parkMissingMcpServers(config) { false }

        assertEquals(1, result.parked)
        assertTrue(result.config.contains("enabled: false"))
    }

    @Test
    fun skipsServersWithoutACommand() {
        val config = """
            mcp_servers:
              remote:
                url: https://example.invalid/mcp
                enabled: true
            """.trimIndent()

        val result = parkMissingMcpServers(config) { false }

        assertEquals(0, result.parked)
        assertEquals(config, result.config)
    }

    @Test
    fun ignoresEnabledFlagsOutsideTheMcpServersSection() {
        val config = """
            tools:
              browser:
                enabled: true
            mcp_servers:
              gone:
                command: missing-tool
                enabled: true
            hooks:
              pre:
                enabled: true
            """.trimIndent()

        val result = parkMissingMcpServers(config) { false }

        assertEquals(1, result.parked)
        val lines = result.config.lines()
        assertEquals("  browser:\n    enabled: true", lines[1] + "\n" + lines[2])
        assertTrue(lines.last().contains("enabled: true"))
    }

    @Test
    fun parksEveryMissingServerInTheRealHermesConfigShape() {
        val config = """
            _config_version: 46
            mcp_servers:
              ekko-studio-api:
                command: ekko-studio-mcp
                args:
                  - api
                env:
                  ELECTRON_RUN_AS_NODE: "1"
                  HERMES_MCP_TOOLSET: api
                enabled: true
              ekko-studio-browser:
                command: ekko-studio-mcp
                enabled: true
              ekko-studio-devices:
                command: ekko-studio-mcp
                enabled: true
              ekko-studio-use:
                command: ekko-studio-mcp
                enabled: true
            """.trimIndent()

        val result = parkMissingMcpServers(config) { false }

        assertEquals(4, result.parked)
        assertEquals(0, result.config.lines().count { it.trimEnd().endsWith("enabled: true") })
    }

    @Test
    fun preservesFormattingAndLineCount() {
        val result = parkMissingMcpServers(fixture) { false }

        assertEquals(fixture.lines().size, result.config.lines().size)
        val changed = fixture.lines().zip(result.config.lines()).count { (a, b) -> a != b }
        assertEquals(3, changed)
        assertTrue(result.config.contains("tools:\n  browser:\n    enabled: true"))
    }
}
