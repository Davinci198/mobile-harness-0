package com.jarves.mh.tools.skill

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Validează bundle-ul real din `assets/skills` împotriva limitelor lui
 * [SkillLoader]. Un skill care depășește o limită nu produce o eroare vizibilă:
 * iese din catalog cu un [SkillErrorCode] și utilizatorul vede pur și simplu mai
 * puține skill-uri decât crede.
 *
 * De aceea verificarea rulează la build, peste fișierele care chiar ajung în APK:
 * un bundle validat doar cu o sursă falsă nu spune nimic despre conținutul livrat.
 */
class BundledSkillsBundleTest {

    private val bundle = File("src/main/assets/skills")

    private val skills: List<File> = bundle.listFiles().orEmpty().filter { it.isDirectory }

    @Test
    fun bundleIsPresent() {
        assertTrue("Lipsește ${bundle.absolutePath}", bundle.isDirectory)
        assertTrue("Bundle-ul nu are niciun skill", skills.isNotEmpty())
    }

    @Test
    fun everySkillFitsTheScanLimit() {
        assertTrue(
            "${skills.size} skill-uri depășesc MAX_SKILLS (${SkillToolLimits.MAX_SKILLS})",
            skills.size <= SkillToolLimits.MAX_SKILLS,
        )
    }

    @Test
    fun everySkillManifestFitsTheFileLimit() {
        val oversized = skills.filter { File(it, "SKILL.md").length() > SkillToolLimits.MAX_SKILL_FILE_BYTES }
        assertTrue(
            "SKILL.md peste ${SkillToolLimits.MAX_SKILL_FILE_BYTES} bytes, respins cu MANIFEST_TOO_LARGE: " +
                oversized.map { "${it.name} (${File(it, "SKILL.md").length()})" },
            oversized.isEmpty(),
        )
    }

    @Test
    fun everySkillCarriesNameAndDescriptionInsideTheMetadataWindow() {
        val broken = skills.filterNot { skill ->
            val head = File(skill, "SKILL.md").useLines { lines ->
                lines.take(SkillToolLimits.MAX_METADATA_LINES).joinToString("\n")
            }
            val lines = head.lineSequence().toList()
            lines.any { it.startsWith("name:") } && lines.any { it.startsWith("description:") }
        }
        assertTrue("Frontmatter incomplet în primele ${SkillToolLimits.MAX_METADATA_LINES} linii: ${broken.map { it.name }}", broken.isEmpty())
    }

    @Test
    fun everySkillFitsTheDirectoryWalkLimits() {
        val tooDeep = mutableListOf<String>()
        val tooWide = mutableListOf<String>()
        skills.forEach { skill ->
            skill.walkTopDown().forEach { entry ->
                if (entry.relativeTo(bundle).path.count { it == '/' } > SkillToolLimits.MAX_DIRECTORY_DEPTH) {
                    tooDeep += skill.name
                }
                if ((entry.list()?.size ?: 0) > SkillToolLimits.MAX_DIRECTORY_ENTRIES) {
                    tooWide += skill.name
                }
            }
        }
        assertTrue("Peste MAX_DIRECTORY_DEPTH: $tooDeep", tooDeep.isEmpty())
        assertTrue("Peste MAX_DIRECTORY_ENTRIES: $tooWide", tooWide.isEmpty())
    }
}
