package com.jarves.mh.model

/** Stored values of the chat expandable-sections setting (`chat_sections_mode`). */
object ChatSectionsMode {
    /** Open while the agent works, collapse when the run finishes. */
    const val AUTO = "auto"

    /** Always keep sections open (manual taps still collapse them). */
    const val ALWAYS = "always"

    /** Always keep sections closed (manual taps still open them). */
    const val NEVER = "never"
}

/**
 * Pure expansion policy for chat sections (activity rows, code blocks) so
 * the behavior is unit-testable without Compose. Row 0 is the summary row
 * shown when a section has no items.
 */
object ChatSectionsPolicy {

    /** Baseline expansion when the composable appears or the setting changes. */
    fun initialExpanded(mode: String, isRunning: Boolean, itemCount: Int): List<Int> = when (mode) {
        ChatSectionsMode.ALWAYS -> rowIndices(itemCount)
        ChatSectionsMode.AUTO -> if (isRunning) rowIndices(itemCount) else emptyList()
        else -> emptyList()
    }

    /** New rows appear (streaming grows the list): open them where the mode would. */
    fun onItemsGrown(mode: String, isRunning: Boolean, itemCount: Int, current: List<Int>): List<Int> {
        if (mode == ChatSectionsMode.NEVER) return current
        val shouldOpen = mode == ChatSectionsMode.ALWAYS || (mode == ChatSectionsMode.AUTO && isRunning)
        return if (shouldOpen) (current + rowIndices(itemCount)).distinct() else current
    }

    /** The run finished: AUTO collapses everything the user did not pin open by hand. */
    fun onRunFinished(mode: String, current: List<Int>): List<Int> =
        if (mode == ChatSectionsMode.AUTO) emptyList() else current

    fun modeAcceptsOpen(mode: String, isRunning: Boolean): Boolean = when (mode) {
        ChatSectionsMode.ALWAYS -> true
        ChatSectionsMode.AUTO -> isRunning
        else -> false
    }

    private fun rowIndices(itemCount: Int): List<Int> =
        if (itemCount <= 0) listOf(0) else (0 until itemCount).toList()
}
