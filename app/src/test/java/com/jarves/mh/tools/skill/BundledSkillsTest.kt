package com.jarves.mh.tools.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

class BundledSkillsTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var assetsRoot: File
    private lateinit var bundle: File
    private lateinit var target: File
    private lateinit var source: BundledSkills.Source

    @Before
    fun setUp() {
        assetsRoot = temporaryFolder.newFolder("assets")
        bundle = File(assetsRoot, BundledSkills.ASSET_DIR).apply { mkdirs() }
        target = temporaryFolder.newFolder("skills-root")
        source = folderSource(assetsRoot)
    }

    @Test
    fun installsEveryBundledSkillWithNestedFiles() {
        skill("alpha", "SKILL.md" to "# alpha", "scripts/run.sh" to "echo alpha")
        skill("beta", "SKILL.md" to "# beta")

        val report = BundledSkills.ensure(target, source)

        assertEquals(2, report.installed)
        assertFalse(report.skipped)
        assertTrue(report.removed.isEmpty())
        assertEquals("# alpha", File(target, "alpha/SKILL.md").readText())
        assertEquals("echo alpha", File(target, "alpha/scripts/run.sh").readText())
        assertEquals("# beta", File(target, "beta/SKILL.md").readText())
    }

    @Test
    fun createsTheTargetDirectoryWhenMissing() {
        skill("alpha", "SKILL.md" to "# alpha")
        val missing = File(target, "nested/deeper")

        val report = BundledSkills.ensure(missing, source)

        assertFalse(report.skipped)
        assertEquals("# alpha", File(missing, "alpha/SKILL.md").readText())
    }

    @Test
    fun skipsWorkWhenTheManifestIsCurrent() {
        skill("alpha", "SKILL.md" to "# bundled")
        BundledSkills.ensure(target, source)
        File(target, "alpha/SKILL.md").writeText("# edited locally")

        val report = BundledSkills.ensure(target, source)

        assertTrue(report.skipped)
        assertEquals("# edited locally", File(target, "alpha/SKILL.md").readText())
    }

    @Test
    fun rewritesBundledSkillsWhenTheVersionChanges() {
        skill("alpha", "SKILL.md" to "# bundled")
        BundledSkills.ensure(target, source, version = "v1")
        File(target, "alpha/SKILL.md").writeText("# edited locally")

        val report = BundledSkills.ensure(target, source, version = "v2")

        assertFalse(report.skipped)
        assertEquals("# bundled", File(target, "alpha/SKILL.md").readText())
    }

    @Test
    fun keepsUserAddedSkillsAcrossVersions() {
        skill("alpha", "SKILL.md" to "# alpha")
        BundledSkills.ensure(target, source, version = "v1")
        val mine = File(target, "my-skill").apply { mkdirs() }
        File(mine, "SKILL.md").writeText("# mine")

        BundledSkills.ensure(target, source, version = "v2")

        assertEquals("# mine", File(mine, "SKILL.md").readText())
    }

    @Test
    fun removesBundledSkillsDroppedFromTheBundle() {
        skill("alpha", "SKILL.md" to "# alpha")
        skill("beta", "SKILL.md" to "# beta")
        BundledSkills.ensure(target, source, version = "v1")
        File(bundle, "beta").deleteRecursively()

        val report = BundledSkills.ensure(target, source, version = "v2")

        assertEquals(listOf("beta"), report.removed)
        assertFalse(File(target, "beta").exists())
        assertTrue(File(target, "alpha/SKILL.md").isFile)
    }

    @Test
    fun ignoresBundleEntriesThatEscapeTheTarget() {
        val hostile = object : BundledSkills.Source {
            override fun list(path: String): List<String> =
                if (path == BundledSkills.ASSET_DIR) listOf("..", "safe") else emptyList()

            override fun open(path: String): InputStream = ByteArrayInputStream("x".toByteArray())
        }

        val report = BundledSkills.ensure(target, hostile)

        assertEquals(1, report.installed)
        val entries = target.list()?.filterNot { it == BundledSkills.MANIFEST_NAME }.orEmpty()
        assertEquals(listOf("safe"), entries)
    }

    private fun skill(name: String, vararg files: Pair<String, String>) {
        val directory = File(bundle, name).apply { mkdirs() }
        files.forEach { (path, content) ->
            val file = File(directory, path)
            file.parentFile?.mkdirs()
            file.writeText(content)
        }
    }

    /** Aceeași formă ca [BundledSkills.assetSource], dar peste un folder real. */
    private fun folderSource(base: File): BundledSkills.Source = object : BundledSkills.Source {
        override fun list(path: String): List<String> = File(base, path).list()?.toList().orEmpty()

        override fun open(path: String): InputStream = File(base, path).inputStream()
    }
}
