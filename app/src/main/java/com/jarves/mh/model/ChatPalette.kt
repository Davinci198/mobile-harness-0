package com.jarves.mh.model

/** Actions the "/" palette can run directly from the composer. */
enum class PaletteAction {
    NEW_CHAT,
    STOP_TASK,
    ATTACH_FILES,
    TOGGLE_SCREEN_SHARE,
    TOGGLE_MIC,
    TOGGLE_SPEAK,
    OPEN_TERMINAL,
}

/** Grouping shown as section headers inside the palette. */
enum class PaletteGroup { SESSION, MEDIA, TERMINAL }

/** One tappable row: display label, matching hints, and run availability. */
data class PaletteCommand(
    val action: PaletteAction,
    val group: PaletteGroup,
    val label: String,
    val keywords: List<String> = emptyList(),
    val enabled: Boolean = true,
)

/** Pure filter behind the composer's "/" palette. */
object ChatPalette {

    /**
     * Matches [query] (an optional leading "/" is stripped) against the
     * label, the action id and the keywords, case-insensitively. An empty
     * query returns every command; no match returns an empty list — the UI
     * then hides the palette and the raw text passes through to the agent.
     */
    fun match(query: String, commands: List<PaletteCommand>): List<PaletteCommand> {
        val needle = query.trim().removePrefix("/").trim().lowercase()
        if (needle.isEmpty()) return commands
        return commands.filter { command ->
            command.label.lowercase().contains(needle) ||
                command.action.name.lowercase().contains(needle) ||
                command.keywords.any { it.lowercase().contains(needle) }
        }
    }
}
