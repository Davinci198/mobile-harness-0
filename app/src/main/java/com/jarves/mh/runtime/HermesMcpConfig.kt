package com.jarves.mh.runtime

internal data class McpParkedResult(
    val config: String,
    val parked: Int,
)

/**
 * Disables MCP servers in a Hermes `config.yaml` whose `command` is not
 * installed in the guest. Hermes treats a missing executable as a hard failure
 * yet still spends its startup budget on discovery and retries, so every run
 * paid seconds before the first API call. Parked servers stay parked: a config
 * that re-enables them is rewritten on the next run.
 *
 * Line-based on purpose: the guest config is written by Hermes and must keep
 * its formatting, comments and key order byte-for-byte everywhere else.
 */
internal fun parkMissingMcpServers(
    config: String,
    isAvailable: (String) -> Boolean,
): McpParkedResult {
    val lines = config.split('\n').toMutableList()
    var inSection = false
    var entryStart = -1
    var command: String? = null
    var parked = 0

    fun closeEntry(end: Int) {
        val missing = command?.takeIf { !isAvailable(it) }
        if (missing != null) {
            for (i in entryStart until end) {
                if (ENABLED_TRUE.matches(lines[i])) {
                    lines[i] = lines[i].replace("enabled: true", "enabled: false")
                    parked++
                    break
                }
            }
        }
        entryStart = -1
        command = null
    }

    for (i in lines.indices) {
        val line = lines[i]
        when {
            line == MCP_SERVERS_KEY -> {
                closeEntry(i)
                inSection = true
            }
            inSection && line.isNotBlank() && !line.startsWith(" ") && !line.startsWith("#") -> {
                closeEntry(i)
                inSection = false
            }
            !inSection -> Unit
            ENTRY_HEADER.matches(line) -> {
                closeEntry(i)
                entryStart = i
            }
            entryStart >= 0 && command == null ->
                COMMAND.matchEntire(line)?.let { command = unquoteCommand(it.groupValues[1]) }
        }
    }
    closeEntry(lines.size)
    return McpParkedResult(lines.joinToString("\n"), parked)
}

private val MCP_SERVERS_KEY = "mcp_servers:"
private val ENTRY_HEADER = Regex("^ {2}\\S[^\\n]*:\\s*$")
private val COMMAND = Regex("^ {4}command:\\s*(.*?)\\s*$")
private val ENABLED_TRUE = Regex("^ {4}enabled:\\s*true\\s*$")

private fun unquoteCommand(raw: String): String {
    val value = raw.trim()
    if (value.length >= 2 && value.first() == value.last() && (value.first() == '"' || value.first() == '\'')) {
        return value.substring(1, value.length - 1)
    }
    return value
}
