package com.jarves.mh.tools.skill

import com.jarves.mh.tools.AIToolHook
import com.jarves.mh.tools.AIToolHookDecision
import com.jarves.mh.tools.ToolPermissionGate
import com.jarves.mh.tools.ToolPermissionLevel
import com.jarves.mh.tools.ToolPermissionStorage
import com.jarves.mh.tools.ToolPermissionStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class SkillLoaderTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var root: File
    private lateinit var store: ToolPermissionStore
    private lateinit var loader: SkillLoader

    @Before
    fun setUp() {
        root = temporaryFolder.newFolder("skills")
        store = ToolPermissionStore(object : ToolPermissionStorage {
            private val values = mutableMapOf<String, String>()
            override fun read(key: String): String? = values[key]
            override fun write(key: String, value: String?) {
                if (value == null) values.remove(key) else values[key] = value
            }
        })
        store.globalDefault = ToolPermissionLevel.ALLOW
        loader = newLoader()
    }

    private fun newLoader(hooks: List<AIToolHook> = emptyList()) = SkillLoader(
        root = root,
        guestRoot = "/workspace/demo/.mobilharness/skills",
        gate = ToolPermissionGate(store, hooks),
        hooks = hooks,
    )

    private fun skill(name: String, content: String, manifestName: String = "SKILL.md"): File {
        val directory = File(root, name).apply { mkdirs() }
        File(directory, manifestName).writeText(content)
        return directory
    }

    private fun activated(name: String): SkillToolResult.Activated {
        val result = loader.activate(name)
        assertTrue(result.toString(), result is SkillToolResult.Activated)
        return result as SkillToolResult.Activated
    }

    @Test
    fun parsesFrontmatterAndBuildsActivationPrompt() {
        skill("docs", "---\nName: Documentation\nDescription: \"Read project docs\"\n---\n# Docs\nUse files carefully.")

        val result = activated("Documentation")

        assertEquals("Read project docs", result.packageInfo.description)
        assertTrue(result.prompt.contains("Using skill: Documentation"))
        assertTrue(result.prompt.contains("/workspace/demo/.mobilharness/skills/docs/SKILL.md"))
        assertTrue(result.prompt.contains("Use files carefully."))
    }

    @Test
    fun scansManifestFallbackNameAndLowercaseManifest() {
        skill("fallback", "name: Fallback Skill\ndescription: Uses a folder name", "skill.md")
        val catalog = loader.scan()
        assertEquals(1, catalog.skills.size)
        assertEquals("Fallback Skill", catalog.skills.single().name)
        assertEquals("skill.md", catalog.skills.single().skillFile.name)
    }

    @Test
    fun parserFallsBackToFirstFortyLines() {
        val prefix = (1..39).joinToString("\n") { "line$it" }
        skill("late", "$prefix\nname: Late Name\ndescription: Late Description")
        val catalog = loader.scan()
        assertEquals(1, catalog.skills.size)
        assertEquals("Late Name", catalog.skills.single().name)
    }

    @Test
    fun parserIgnoresMetadataAfterLineForty() {
        val prefix = (1..40).joinToString("\n") { "line$it" }
        skill("late", "$prefix\nname: Too Late")
        val catalog = loader.scan()
        assertEquals("late", catalog.skills.single().name)
    }

    @Test
    fun scanIsDeterministicAndRejectsDuplicateNames() {
        skill("b", "name: Same")
        skill("a", "name: Same")
        skill("c", "name: Other")
        val catalog = loader.scan()
        assertEquals(listOf("Same", "Other"), catalog.skills.map { it.name })
        assertTrue(catalog.issues.any { it.message.contains("Duplicate skill name") })
    }

    @Test
    fun missingManifestCreatesIssue() {
        File(root, "empty").mkdirs()
        val catalog = loader.scan()
        assertTrue(catalog.skills.isEmpty())
        assertEquals(SkillErrorCode.MANIFEST_MISSING, catalog.issues.single().code)
    }

    @Test
    fun manifestSizeLimitIsEnforced() {
        skill("large", "x".repeat(SkillToolLimits.MAX_SKILL_FILE_BYTES + 1))
        val catalog = loader.scan()
        assertEquals(SkillErrorCode.MANIFEST_TOO_LARGE, catalog.issues.single().code)
    }

    @Test
    fun askDoesNotLoadSkill() {
        skill("private", "secret")
        store.globalDefault = ToolPermissionLevel.ASK
        val result = loader.activate("private")
        assertEquals(SkillErrorCode.PERMISSION_REQUIRED, (result as SkillToolResult.Error).code)
    }

    @Test
    fun forbidAndHookBlockDenySkill() {
        skill("private", "secret")
        store.globalDefault = ToolPermissionLevel.FORBID
        assertEquals(SkillErrorCode.PERMISSION_DENIED, (loader.activate("private") as SkillToolResult.Error).code)

        store.globalDefault = ToolPermissionLevel.ALLOW
        val blocked = newLoader(listOf(object : AIToolHook {
            override fun onToolCallIntercept(toolName: String, explanation: String) = AIToolHookDecision.Block("blocked by policy")
        }))
        assertEquals(SkillErrorCode.PERMISSION_DENIED, (blocked.activate("private") as SkillToolResult.Error).code)
    }

    @Test
    fun overrideAllowWinsOverGlobalAsk() {
        skill("private", "secret")
        store.globalDefault = ToolPermissionLevel.ASK
        store.setOverride(SkillToolLimits.TOOL_NAME, ToolPermissionLevel.ALLOW)
        assertEquals("private", activated("private").packageInfo.name)
    }

    @Test
    fun missingSkillReturnsNotFound() {
        val result = loader.activate("missing")
        assertEquals(SkillErrorCode.NOT_FOUND, (result as SkillToolResult.Error).code)
    }

    @Test
    fun hooksFireForSuccess() {
        skill("hooked", "ok")
        val events = mutableListOf<String>()
        val hooks = listOf(object : AIToolHook {
            override fun onToolExecutionStarted(toolName: String) { events += "start:$toolName" }
            override fun onToolExecutionResult(toolName: String, success: Boolean, summary: String) { events += "result:$success:$summary" }
            override fun onToolExecutionFinished(toolName: String) { events += "finished:$toolName" }
        })
        newLoader(hooks).activate("hooked")
        assertEquals(listOf("start:UseSkill", "result:true:hooked", "finished:UseSkill"), events)
    }

    @Test
    fun directoryTreeIsBounded() {
        val directory = skill("large-tree", "ok")
        repeat(SkillToolLimits.MAX_DIRECTORY_ENTRIES + 10) { index ->
            File(directory, "file-$index.txt").writeText("x")
        }
        val prompt = activated("large-tree").prompt
        assertTrue(prompt.contains("[truncated]"))
        assertTrue(prompt.length < SkillToolLimits.MAX_PROMPT_CHARS + 1_000)
    }

    @Test
    fun symlinkedSkillDirectoryOutsideRootIsRejected() {
        val outside = temporaryFolder.newFolder("outside")
        File(outside, "SKILL.md").writeText("name: Outside")
        Files.createSymbolicLink(File(root, "outside-link").toPath(), outside.toPath())
        val catalog = loader.scan()
        assertTrue(catalog.skills.isEmpty())
        assertTrue(catalog.issues.any { it.message.contains("escapes") })
    }

    @Test
    fun missingRootReturnsIssue() {
        assertTrue(root.delete())
        val catalog = loader.scan()
        assertEquals(SkillErrorCode.ROOT_UNAVAILABLE, catalog.issues.single().code)
    }

    @Test
    fun permissionExplanationIsBounded() {
        val name = "n".repeat(500)
        val explanations = mutableListOf<String>()
        val hooks = listOf(object : AIToolHook {
            override fun onToolCallIntercept(toolName: String, explanation: String): AIToolHookDecision {
                explanations += explanation
                return AIToolHookDecision.Block("stop")
            }
        })
        newLoader(hooks).activate(name)
        assertTrue(explanations.single().length <= 240)
    }

    @Test
    fun hooksFireForDomainFailure() {
        val events = mutableListOf<String>()
        val hooks = listOf(object : AIToolHook {
            override fun onToolExecutionResult(toolName: String, success: Boolean, summary: String) {
                events += "result:$success:$summary"
            }
            override fun onToolExecutionFinished(toolName: String) { events += "finished" }
        })
        newLoader(hooks).activate("missing")
        assertEquals(listOf("result:false:Skill not found: missing", "finished"), events)
    }
}
