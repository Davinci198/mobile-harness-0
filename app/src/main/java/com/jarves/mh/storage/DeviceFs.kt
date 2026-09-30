package com.jarves.mh.storage

/**
 * One entry in a browsable tree, whatever backend produced it. Kept free of Android
 * types so the routing rules and the parsers can be unit-tested on the JVM.
 */
data class FsEntry(
    val name: String,
    /** Path relative to the root the listing was made from, "" for the root itself. */
    val relativePath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long = 0L,
    val lastModifiedMillis: Long = 0L,
    /** False when the backend knows an entry exists but cannot see inside it. */
    val readable: Boolean = true,
    /**
     * How many entries a directory holds, or -1 when not known yet. Knowing it costs one
     * listing per folder, so it is filled in the background and only where it is cheap
     * enough to be worth the wait.
     */
    val childCount: Int = -1,
)

/** A place the user can browse from. */
sealed class DeviceRoot {
    /** A display name, already localised by the caller. */
    abstract val label: String

    /**
     * The app's own sandbox. Always available: it is just the app's private files
     * directory, which is what the proot workspace is bind-mounted from.
     */
    data class Local(override val label: String) : DeviceRoot()

    /**
     * A Storage Access Framework tree the user granted. Works on any volume the system
     * picker offers, survives reboots once the permission is persisted, and needs no
     * special permission.
     */
    data class SafTree(override val label: String, val treeUri: String) : DeviceRoot()

    /**
     * Real filesystem paths, available only while MANAGE_EXTERNAL_STORAGE ("all files")
     * is granted. Covers shared storage directly, which is faster than SAF.
     */
    data class AllFiles(override val label: String) : DeviceRoot()

    /**
     * The device filesystem through the Shizuku shell. The broadest reach — it can see
     * /data and /system, which nothing else in an ordinary app can — but it goes through
     * a command pipe, so it is the slowest and the least convenient to browse.
     */
    data class Shizuku(override val label: String, val startPath: String = "/") : DeviceRoot()
}

/** Why something could not be read, phrased so the UI can offer a way out. */
enum class FsErrorKind {
    /** No backend in the app can see this path with the permissions granted right now. */
    NO_ACCESS,

    /** The path is simply not there. */
    NOT_FOUND,

    /** Read-only: SAF trees and some mounted volumes refuse writes. */
    READ_ONLY,

    /** The backend was fine but the operation failed. */
    FAILED,
}

data class FsError(
    val kind: FsErrorKind,
    val message: String,
    /**
     * What the user can do about it, if anything: request the SAF picker, open the
     * all-files settings screen, or ask for Shizuku access. Null when there is no fix.
     */
    val remedy: FsRemedy? = null,
)

enum class FsRemedy {
    PICK_FOLDER,
    GRANT_ALL_FILES,
    REQUEST_SHIZUKU,
}

/** Outcome of an operation. Errors are values, not exceptions, so the UI can branch. */
sealed class FsResult<out T> {
    data class Ok<T>(val value: T) : FsResult<T>()
    data class Err(val error: FsError) : FsResult<Nothing>()

    fun valueOrNull(): T? = (this as? Ok)?.value
    fun errorOrNull(): FsError? = (this as? Err)?.error
    val isOk: Boolean get() = this is Ok
}

fun <T> fsError(kind: FsErrorKind, message: String, remedy: FsRemedy? = null): FsResult<T> =
    FsResult.Err(FsError(kind, message, remedy))

/** Ce plafonezi ca să nu încarci tot device-ul în memorie. */
object FsLimits {
    const val MAX_TEXT_READ_BYTES: Long = 512_000L

    /**
     * An archive is built in memory, so this is the ceiling on what may be zipped at once.
     * A phone can hold far more; the limit is about the heap, not the disk.
     */
    const val MAX_ARCHIVE_BYTES: Long = 48L * 1024L * 1024L

    /** How many files one archive may hold, so a whole device cannot be pulled into RAM. */
    const val MAX_ARCHIVE_ENTRIES: Int = 2_000
    const val MAX_SHELL_OUTPUT_CHARS: Int = 64 * 1024
    const val MAX_LISTED_ENTRIES: Int = 5_000
}
