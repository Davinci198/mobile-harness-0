package com.jarves.mh.storage

/** What the user last did to a selection, so a later paste knows what to do. */
enum class FsClipboardOperation { COPY, CUT }

/**
 * The staging area between picking something and pasting it somewhere else.
 *
 * Only relative paths are kept, never absolute ones: a clipboard can outlive the folder it
 * was filled from, and a path that escaped the root would be a way out of the sandbox.
 */
data class FsClipboard(
    val items: List<String> = emptyList(),
    val operation: FsClipboardOperation = FsClipboardOperation.COPY,
) {
    val isEmpty: Boolean get() = items.isEmpty()

    fun isFullOf(relativePath: String): Boolean = items.any { FsPaths.join("", it) == relativePath }
}

/**
 * A copy, a cut, a paste and what the status line should say about them.
 *
 * Kept free of Android types so the rules can be pinned down in a test: a cut that is
 * pasted has to disappear from where it was, and pasting a folder into itself has to be
 * refused rather than quietly doing nothing.
 */
object FsClipboardRules {

    fun put(items: List<String>, operation: FsClipboardOperation): FsClipboard =
        FsClipboard(items.distinct().filter { FsPaths.isSafeRelative(it) }, operation)

    /**
     * The paths a paste would write, or null when the paste is impossible.
     *
     * [into] is the folder being pasted into, [root] the root everything is relative to.
     */
    fun pasteInto(clipboard: FsClipboard, into: String, root: String): List<String>? {
        if (clipboard.isEmpty) return null
        // Dropping a folder inside itself would walk forever and, on Shizuku, would be a
        // shell that never returns.
        val clashes = clipboard.items.any { item ->
            val source = FsPaths.join(root, item)
            into == source || into.startsWith("$source/")
        }
        if (clashes) return null
        return clipboard.items.map { FsPaths.join(into, FsPaths.nameOf(it)) }
    }

    /**
     * What the status line says: how many items are staged, and whether a paste would
     * actually do something here.
     */
    fun status(clipboard: FsClipboard, into: String, root: String): String {
        if (clipboard.isEmpty) return ""
        val verb = if (clipboard.operation == FsClipboardOperation.CUT) "Mutare" else "Copiere"
        return "${clipboard.items.size} în clipboard • $verb"
    }
}
