package com.jarves.mh.storage

/**
 * Builds the shell commands the Shizuku backend runs, and parses what comes back.
 *
 * Split out from the backend so the quoting and the parsing can be unit-tested without a
 * device: quoting is where a file manager is easiest to get wrong, because a file name is
 * attacker-controlled input that ends up in a command line.
 */
object ShizukuFs {
    /**
     * Wraps a path for `sh -c`. Single quotes suppress every expansion, and an embedded
     * quote is closed, escaped and reopened — the only sequence that cannot break out.
     */
    fun quote(path: String): String = "'" + path.replace("'", "'\\''") + "'"

    /**
     * One line per entry, emitting `mode|size|mtime|name`. The name comes last so a file
     * whose name contains the delimiter still parses: only the first three fields split.
     */
    fun listCommand(directory: String): String {
        val here = quote(directory)
        return """
            cd $here 2>/dev/null || exit 4
            for f in * .[!.]*; do
              [ -e "${'$'}f" ] || continue
              stat -c '%f|%s|%Y|%n' "${'$'}f" 2>/dev/null || true
            done
        """.trimIndent()
    }

    /**
     * Lists every directory in one command, marking each block with its index.
     *
     * The device root is a dozen categories, and a command per category turns one wait
     * into a dozen. A stat line always begins with the file mode in hex, so a line that
     * starts with the marker cannot be confused with an entry.
     */
    fun listManyCommand(directories: List<String>): String {
        if (directories.isEmpty()) return "true"
        // The paths go straight into the for list, quoted. Putting them in a variable
        // would be shorter, but the shell splits an expanded variable on spaces without
        // honouring quotes that came from the expansion, so "/sdcard/My Files" would
        // arrive as two arguments.
        val list = directories.joinToString(" ") { quote(it) }
        return """
            i=0
            for d in __PATHS__; do
              echo "#MH${'$'}i"
              i=${'$'}((i+1))
              cd "${'$'}d" 2>/dev/null || { echo "#MHX"; continue; }
              find . -maxdepth 1 -printf '%y|%m|%s|%T@|%f\n' 2>/dev/null
            done
        """.trimIndent().replace("__PATHS__", list)
    }

    /** One directory's block out of a batched listing. */
    data class BatchedListing(val index: Int, val missing: Boolean, val entries: List<FsEntry>)

    /**
     * Splits batched output back into one listing per input path. Blocks that never
     * arrived, because a later command overran the output cap, come back as missing
     * rather than silently as empty.
     */
    fun parseBatched(output: String, expected: Int): List<BatchedListing> {
        val blocks = LinkedHashMap<Int, MutableList<String>>()
        var missing = mutableSetOf<Int>()
        var current: Int? = null
        for (line in output.lineSequence()) {
            if (line.startsWith(MARKER)) {
                val digits = line.drop(MARKER.length).toIntOrNull()
                if (digits != null) {
                    current = digits
                    blocks.getOrPut(digits) { mutableListOf() }
                    continue
                }
                if (line == FAILED_MARKER) {
                    current?.let { missing += it }
                    continue
                }
            }
            current?.let { blocks.getOrPut(it) { mutableListOf() } }?.add(line)
        }
        return (0 until expected).map { index ->
            BatchedListing(
                index = index,
                missing = index in missing || index !in blocks,
                // find reports the directory it was pointed at as the first line, and
                // that is the directory already known, not an entry inside it.
                entries = parseFindListing(blocks[index].orEmpty().drop(1)),
            )
        }
    }

    private const val MARKER = "#MH"
    private const val FAILED_MARKER = "#MHX"

    fun statCommand(path: String): String =
        "stat -c '%f|%s|%Y|%n' ${quote(path)} 2>/dev/null || true"

    /**
     * `cat` with a byte cap, so a huge or endless file cannot pin the pipe open. The cap
     * is enforced by the reader too, but stopping early here also frees the process.
     */
    fun readTextCommand(path: String, maxBytes: Long): String =
        "head -c ${maxBytes.coerceAtLeast(0L)} ${quote(path)} 2>/dev/null"

    fun writeTextCommand(path: String, base64: String): String =
        "printf '%s' ${quote(base64)} | base64 -d > ${quote(path)}"

    fun appendTextCommand(path: String, base64: String): String =
        "printf '%s' ${quote(base64)} | base64 -d >> ${quote(path)}"

    fun deleteCommand(path: String, recursive: Boolean): String =
        "rm -rf -- ${quote(path)} 2>/dev/null || rm -f -- ${quote(path)} 2>/dev/null"

    fun renameCommand(path: String, newName: String): String {
        val from = quote(path)
        // The parent must not be quoted before being spliced in: quoting the result twice
        // produces a command the shell reads as three separate arguments.
        val target = if (path.contains('/')) {
            path.substring(0, path.lastIndexOf('/') + 1) + newName
        } else {
            newName
        }
        return "mv -- $from ${quote(target)} 2>/dev/null"
    }

    fun createDirectoryCommand(path: String): String = "mkdir -p -- ${quote(path)} 2>/dev/null"

    fun joinPath(base: String, child: String): String {
        val left = base.trimEnd('/')
        val right = child.trimStart('/')
        return if (left.isEmpty()) "/$right" else "$left/$right"
    }

    /** Parses one `stat -c '%f|%s|%Y|%n'` line, or null when it is not one. */
    fun parseStatLine(line: String): FsEntry? {
        val parts = line.trim().split('|', limit = 4)
        if (parts.size < 4) return null
        val modeHex = parts[0].trim()
        val size = parts[1].trim().toLongOrNull() ?: return null
        val mtime = parts[2].trim().toLongOrNull() ?: 0L
        val name = parts[3]
        if (name.isEmpty()) return null
        val mode = modeHex.toIntOrNull(16)
        val isDirectory = mode != null && (mode shr 12) == 0x4
        return FsEntry(
            name = name,
            relativePath = name,
            isDirectory = isDirectory,
            sizeBytes = if (isDirectory) 0L else size,
            lastModifiedMillis = if (mtime <= 0L) 0L else mtime * 1000L,
            // %f is a hex mode, so the read bit has to be decoded: owner, group and other
            // each carry one at 0x100, 0x20 and 0x4. An entry we cannot read is still
            // worth listing, marked, rather than silently missing.
            readable = mode?.let { (it and 0b100_100_100) != 0 } ?: true,
        )
    }

    /**
     * Reads one `find -printf` line: type, permissions, size, mtime, name.
     *
     * The mtime carries a fractional part, which is dropped: the listing shows a day, and
     * a Long keeps the entry free of types the routing tests cannot build.
     */
    fun parseFindLine(line: String): FsEntry? {
        val parts = line.trim().split('|', limit = 5)
        if (parts.size < 5) return null
        val type = parts[0].trim()
        val name = parts[4]
        if (name.isEmpty() || name == ".") return null
        val isDirectory = type == "d"
        val size = parts[2].trim().toLongOrNull() ?: 0L
        val modified = parts[3].trim().substringBefore('.').toLongOrNull() ?: 0L
        // Octal permissions, so the read bit is what decides the padlock, the same way
        // the stat parser decides it.
        val permissions = parts[1].trim().toIntOrNull(8) ?: 0
        return FsEntry(
            name = name,
            relativePath = name,
            isDirectory = isDirectory,
            sizeBytes = if (isDirectory) 0L else size,
            lastModifiedMillis = modified,
            readable = permissions and 0o444 != 0,
        )
    }

    fun parseFindListing(lines: List<String>): List<FsEntry> = lines.asSequence()
        .mapNotNull { parseFindLine(it) }
        .sortedWith(compareByDescending<FsEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
        .take(FsLimits.MAX_LISTED_ENTRIES)
        .toList()

    fun parseListing(output: String): List<FsEntry> = output.lineSequence()
        .mapNotNull { parseStatLine(it) }
        // Directories first, then case-insensitive by name, the same order the local
        // backend uses so the two do not disagree on screen.
        .sortedWith(compareByDescending<FsEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
        .take(FsLimits.MAX_LISTED_ENTRIES)
        .toList()
}
