package com.jarves.mh.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FsFileTypesTest {

    private fun file(name: String) = FsEntry(name = name, relativePath = name, isDirectory = false)

    private fun folder(name: String) = FsEntry(name = name, relativePath = name, isDirectory = true)

    @Test
    fun aFolderIsAFolderWhateverItIsCalled() {
        assertEquals(FsFileType.FOLDER, FsFileTypes.of(folder("archive.zip")))
        assertEquals(FsFileType.FOLDER, FsFileTypes.of(folder("IMG_001.jpg")))
    }

    @Test
    fun theExtensionDecidesTheType() {
        val cases = mapOf(
            "IMG_20241210_092341.jpg" to FsFileType.IMAGE,
            "clip.mp4" to FsFileType.VIDEO,
            "Andra - Inevitabil.mp3" to FsFileType.AUDIO,
            "CV_2024.pdf" to FsFileType.PDF,
            "mobile-harness-v5e0cd55-pre-opencode.apk" to FsFileType.APK,
            "backup.tar.gz" to FsFileType.ARCHIVE,
            "notes.md" to FsFileType.DOC,
            "LICENSE" to FsFileType.OTHER,
        )
        cases.forEach { (name, expected) -> assertEquals(name, expected, FsFileTypes.of(file(name))) }
    }

    @Test
    fun extensionsAreMatchedWithoutRegardToCase() {
        assertEquals(FsFileType.IMAGE, FsFileTypes.ofName("PHOTO.JPG"))
        assertEquals(FsFileType.ARCHIVE, FsFileTypes.ofName("Archive.Zip"))
    }

    @Test
    fun aNameThatOnlyContainsADotHasNoExtension() {
        // ".bashrc" and "README" are names, not extensionless documents.
        assertEquals(FsFileType.OTHER, FsFileTypes.ofName(".bashrc"))
        assertEquals(FsFileType.OTHER, FsFileTypes.ofName("README"))
        assertEquals(FsFileType.OTHER, FsFileTypes.ofName("trailing."))
    }

    @Test
    fun onlyTheLastDotCounts() {
        assertEquals(FsFileType.DOC, FsFileTypes.ofName("v1.2.3 notes.md"))
        assertEquals(FsFileType.OTHER, FsFileTypes.ofName("weird.name.unknownext"))
    }

    @Test
    fun aVersionedApkIsStillAnApk() {
        assertEquals(FsFileType.APK, FsFileTypes.of(file("app-release-v1.2.3.apk")))
    }

    @Test
    fun textAndSourceStayInTheViewer() {
        val cases = listOf(
            "notes.md", "settings.json", "layout.xml", "gradle.properties", "MainViewModel.kt",
            "index.tsx", "build.sh", "table.csv", "README", ".bashrc", "trailing.",
        )
        cases.forEach { name -> assertTrue(name, FsFileTypes.opensInViewer(name)) }
    }

    @Test
    fun everythingTheViewerCannotRenderLeavesIt() {
        // A video read as text is an error message, an archive is raw bytes, and a page
        // belongs in a browser — none of them is a thing the viewer could ever draw.
        val cases = listOf(
            "clip.mp4", "IMG_0001.jpg", "song.flac", "CV_2024.pdf",
            "app.apk", "backup.tar.gz", "index.html", "report.docx",
        )
        cases.forEach { name -> assertFalse(name, FsFileTypes.opensInViewer(name)) }
    }

    @Test
    fun theViewerDecisionIgnoresCase() {
        assertTrue(FsFileTypes.opensInViewer("NOTES.MD"))
        assertFalse(FsFileTypes.opensInViewer("CLIP.MP4"))
    }
}
