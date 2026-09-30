package com.jarves.mh.storage

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Naming an archive, and saying whether a name may be one.
 *
 * A zip is a file, so it goes through the same rules as every other file: no slashes, no
 * empty names, and a name that is not already taken in the folder being written to.
 */
object FsArchive {

    /** "Arhivă 2026-09-30.zip" — dated so a second archive the same day is obvious. */
    fun archiveName(millis: Long = System.currentTimeMillis(), locale: Locale = Locale.getDefault()): String {
        val day = SimpleDateFormat("yyyy-MM-dd", locale).format(Date(millis))
        return "Arhivă $day.zip"
    }

    /** The first free name of that shape, so archiving twice does not overwrite. */
    fun uniqueName(taken: Set<String>, millis: Long = System.currentTimeMillis(), locale: Locale = Locale.getDefault()): String {
        val base = archiveName(millis, locale)
        if (base !in taken) return base
        var counter = 2
        while ("$base.dropLast(4) ($counter).zip" in taken) counter++
        return "$base.dropLast(4) ($counter).zip"
    }

    /**
     * A name the user typed has to be usable as a file. Returns null when it is not, rather
     * than letting the backend fail later with a message about the filesystem.
     */
    fun validate(name: String): String? {
        val trimmed = name.trim()
        return when {
            trimmed.isEmpty() -> null
            trimmed.contains('/') -> null
            trimmed == "." || trimmed == ".." -> null
            else -> trimmed
        }
    }

    /** What the zip holds, so a later extraction knows what to expect. */
    fun manifest(paths: List<String>): String = buildString {
        appendLine("created: ${archiveName()}")
        appendLine("files: ${paths.size}")
        paths.forEach { appendLine(it) }
    }
}
