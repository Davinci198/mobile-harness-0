package com.jarves.mh.runtime

/** Identity of the outbound webhook entry this app owns inside the guest config. */
internal const val HERMES_HOOK_NAME = "mobile-harness-warm"

/** Stream lifecycle, tool lifecycle and API failures — everything a warm turn reports. */
internal const val HERMES_HOOK_EVENTS =
    "[on_stream_start, on_stream_delta, on_stream_end, pre_tool_call, post_tool_call, api_request_error]"

internal data class HermesHookConfigResult(
    val config: String,
    val changed: Boolean,
)

/**
 * Ensures the guest Hermes config carries an outbound webhook entry pointing at
 * [url] (the app's loopback listener). Hermes registers `hooks.outbound` on every
 * `hermes chat` startup and POSTs stream/tool/API events there, which is how a
 * warm interactive session reports assistant text: interactive stdout is a
 * rendered TUI, not parseable JSONL, while `--format stream-json` forces
 * single-query mode that exits after one turn.
 *
 * Line-based on purpose: the guest config is written by Hermes and must keep its
 * formatting, comments and key order byte-for-byte everywhere else.
 */
internal fun ensureHermesHooks(config: String, url: String): HermesHookConfigResult {
    val lines = config.replace("\r\n", "\n").split('\n').toMutableList()
    val hooksIndex = lines.indexOfFirst { it == HOOKS_KEY }
    if (hooksIndex < 0) {
        while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.lastIndex)
        if (lines.isNotEmpty()) lines.add("")
        lines.add(HOOKS_KEY)
        lines.add(OUTBOUND_KEY)
        lines.addAll(entryLines(url))
        return HermesHookConfigResult(lines.joinToString("\n"), true)
    }

    val sectionEnd = sectionEndAfter(lines, hooksIndex)
    val outboundIndex = (hooksIndex + 1 until sectionEnd).firstOrNull { lines[it] == OUTBOUND_KEY }
    if (outboundIndex == null) {
        lines.addAll(hooksIndex + 1, listOf(OUTBOUND_KEY) + entryLines(url))
        return HermesHookConfigResult(lines.joinToString("\n"), true)
    }

    val listEnd = outboundListEnd(lines, outboundIndex + 1, sectionEnd)
    val entryStart = (outboundIndex + 1 until listEnd).firstOrNull { isOurEntry(lines[it]) }
    if (entryStart == null) {
        lines.addAll(listEnd, entryLines(url))
        return HermesHookConfigResult(lines.joinToString("\n"), true)
    }

    val entryEnd = (entryStart + 1 until listEnd).firstOrNull { isEntryStart(lines[it]) } ?: listEnd
    var changed = false
    for (i in entryStart + 1 until entryEnd) {
        val line = lines[i]
        when {
            line.startsWith(URL_PREFIX) && line != URL_PREFIX + url -> {
                lines[i] = URL_PREFIX + url
                changed = true
            }
            line.startsWith(EVENTS_PREFIX) && line != EVENTS_PREFIX + HERMES_HOOK_EVENTS -> {
                lines[i] = EVENTS_PREFIX + HERMES_HOOK_EVENTS
                changed = true
            }
        }
    }
    return HermesHookConfigResult(lines.joinToString("\n"), changed)
}

private const val HOOKS_KEY = "hooks:"
private const val OUTBOUND_KEY = "  outbound:"
private const val ENTRY_PREFIX = "    - name: "
private const val URL_PREFIX = "      url: "
private const val EVENTS_PREFIX = "      events: "

private fun entryLines(url: String): List<String> = listOf(
    "$ENTRY_PREFIX$HERMES_HOOK_NAME",
    "$URL_PREFIX$url",
    "$EVENTS_PREFIX$HERMES_HOOK_EVENTS",
    "      timeout: 3",
)

private fun isOurEntry(line: String): Boolean = line.startsWith(ENTRY_PREFIX) &&
    line.substring(ENTRY_PREFIX.length).trim() == HERMES_HOOK_NAME

private fun isEntryStart(line: String): Boolean = line.startsWith(ENTRY_PREFIX)

/** End of the `hooks:` section: the next top-level key (comments do not end it). */
private fun sectionEndAfter(lines: List<String>, hooksIndex: Int): Int {
    var i = hooksIndex + 1
    while (i < lines.size) {
        val line = lines[i]
        if (line.isNotBlank() && !line.startsWith(" ") && !line.startsWith("#")) return i
        i++
    }
    return lines.size
}

/** End of the `  outbound:` entry list: the next sibling key under `hooks:`. */
private fun outboundListEnd(lines: List<String>, start: Int, sectionEnd: Int): Int {
    var i = start
    while (i < sectionEnd) {
        val line = lines[i]
        if (line.isBlank() || line.startsWith("#")) {
            i++
            continue
        }
        if (!line.startsWith("    ")) return i
        i++
    }
    return sectionEnd
}
