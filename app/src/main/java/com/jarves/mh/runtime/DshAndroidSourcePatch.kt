package com.jarves.mh.runtime

/**
 * Rewrites the known hard-link publication sites in one DSH source file to
 * `copyFile(..., COPYFILE_EXCL)`, which preserves the no-clobber contract on
 * Android, where PRoot blocks the link syscall.
 *
 * [importVariants] holds the per-release import rewrites and [callVariants]
 * the per-release call rewrites; whichever shapes are present are applied.
 * Returns the source unchanged when no unpatched call site remains, and
 * throws when the source matches no known release, so an incompatible DSH
 * update fails loudly instead of at session time.
 */
internal fun patchDshHardLinkSource(
    source: String,
    importVariants: List<Pair<String, String>>,
    callVariants: List<Pair<String, String>>,
    incompatible: () -> String,
): String {
    var result = source
    if (callVariants.any { (before, _) -> before in result }) {
        val (importBefore, importAfter) = importVariants.firstOrNull { (before, _) -> before in result }
            ?: throw IllegalStateException(incompatible())
        result = result.replace(importBefore, importAfter)
        callVariants.forEach { (before, after) -> result = result.replace(before, after) }
        check(COPY_FILE_IMPORT.containsMatchIn(result)) { incompatible() }
    }
    check(HARD_LINK_CALL_MARKERS.none { it in result }) { incompatible() }
    return result
}

internal val DSH_PERSISTENCE_IMPORT_VARIANTS = listOf(
    "import { link, mkdir, mkdtemp, open, readFile, readdir, realpath, rm, stat, truncate } from \"node:fs/promises\";" to
        "import { copyFile, link, mkdir, mkdtemp, open, readFile, readdir, realpath, rm, stat, truncate } from \"node:fs/promises\";",
    "import { link, lstat, mkdir, mkdtemp, open, readFile, readdir, realpath, rm, stat, truncate } from \"node:fs/promises\";" to
        "import { copyFile, link, lstat, mkdir, mkdtemp, open, readFile, readdir, realpath, rm, stat, truncate } from \"node:fs/promises\";",
)

internal val DSH_PERSISTENCE_CALL_VARIANTS = listOf(
    "await link(tmp, finalPath);" to "await copyFile(tmp, finalPath, 1);",
    "await internals.fs.link(staged, currentPath);" to "await copyFile(staged, currentPath, 1);",
)

internal val DSH_FS_LOCAL_IMPORT_VARIANTS = listOf(
    "import { chmod, link, lstat, mkdir, open, readFile, readdir, realpath, rename, rm, stat } from \"node:fs/promises\";" to
        "import { chmod, copyFile, link, lstat, mkdir, open, readFile, readdir, realpath, rename, rm, stat } from \"node:fs/promises\";",
    // 0.2.0-rc.2 resolves paths with the sync realpath.native from node:fs;
    // a second realpath binding from node:fs/promises would be a SyntaxError,
    // so this variant keeps it out of the promises import.
    "import { chmod, link, lstat, mkdir, open, readFile, readdir, rename, rm, stat } from \"node:fs/promises\";" to
        "import { chmod, copyFile, link, lstat, mkdir, open, readFile, readdir, rename, rm, stat } from \"node:fs/promises\";",
)

internal val DSH_FS_LOCAL_CALL_VARIANTS = listOf(
    "await linkFile(tempPath, absolutePath);" to "await copyFile(tempPath, absolutePath, 1);",
)

internal val DSH_ATTACHMENT_IMPORT_VARIANTS = listOf(
    "import { chmod, link, mkdir, open, readFile, rename, rm, unlink, writeFile } from \"node:fs/promises\";" to
        "import { chmod, copyFile, link, mkdir, open, readFile, rename, rm, unlink, writeFile } from \"node:fs/promises\";",
)

internal val DSH_ATTACHMENT_CALL_VARIANTS = listOf(
    "await link(temporary, target);" to "await copyFile(temporary, target, 1);",
    "await link(source, target);" to "await copyFile(source, target, 1);",
    "await link(staged.path, target);" to "await copyFile(staged.path, target, 1);",
)

private val COPY_FILE_IMPORT = Regex("""import \{[^}]*\bcopyFile\b[^}]*\} from "node:fs/promises";""")

private val HARD_LINK_CALL_MARKERS = listOf("await link(", ".link(", "linkFile(")
