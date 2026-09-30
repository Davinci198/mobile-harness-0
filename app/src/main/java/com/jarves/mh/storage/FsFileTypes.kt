package com.jarves.mh.storage

/**
 * What a file is, judged by its name, so the browser can colour it like a file manager
 * does: an image reads differently from an archive, and a folder never reads as a file.
 *
 * Kept here, free of Android types, because the mapping is the kind of thing that quietly
 * rots and is much easier to pin down in a test than on a screen.
 */
enum class FsFileType {
    FOLDER,
    IMAGE,
    VIDEO,
    AUDIO,
    PDF,
    APK,
    ARCHIVE,
    DOC,
    OTHER,
}

object FsFileTypes {

    private val IMAGE = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "ico", "svg", "tiff")
    private val VIDEO = setOf("mp4", "mkv", "avi", "mov", "webm", "flv", "wmv", "m4v", "3gp")
    private val AUDIO = setOf("mp3", "wav", "flac", "aac", "ogg", "m4a", "opus", "wma", "amr")
    private val PDF = setOf("pdf")
    private val APK = setOf("apk", "apks", "xapk", "aab")
    private val ARCHIVE = setOf("zip", "tar", "gz", "bz2", "xz", "7z", "rar", "tgz", "iso", "zst")
    private val DOC = setOf(
        "doc", "docx", "odt", "txt", "md", "rtf", "csv", "xls", "xlsx", "ods", "ppt", "pptx", "odp", "json", "xml", "html", "yml", "yaml",
    )

    /** A folder is a folder whatever it is called; a file is judged by its extension. */
    fun of(entry: FsEntry): FsFileType {
        if (entry.isDirectory) return FsFileType.FOLDER
        return ofName(entry.name)
    }

    fun ofName(name: String): FsFileType {
        val dot = name.lastIndexOf('.')
        // A leading dot is part of the name, not an extension: ".bashrc" has none.
        if (dot <= 0 || dot == name.length - 1) return FsFileType.OTHER
        return when (name.substring(dot + 1).lowercase()) {
            in IMAGE -> FsFileType.IMAGE
            in VIDEO -> FsFileType.VIDEO
            in AUDIO -> FsFileType.AUDIO
            in PDF -> FsFileType.PDF
            in APK -> FsFileType.APK
            in ARCHIVE -> FsFileType.ARCHIVE
            in DOC -> FsFileType.DOC
            else -> FsFileType.OTHER
        }
    }

    /** The label the type card shows, already localised by the caller. */
    fun needsLabel(type: FsFileType): Boolean = type != FsFileType.OTHER
}
