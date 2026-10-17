package com.jarves.mh.runtime

import com.jarves.mh.model.AgentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Un agent fără root de skill-uri nu vede niciunul dintre cele 47 livrate în APK,
 * și nu se plânge: livrarea se oprește tăcut. Testul apără structura acelei
 * livrări — acoperire completă, un root distinct per agent și căi care nu pot ieși
 * din rootfs.
 *
 * Convențiile în sine (`.claude/skills`, `.config/opencode/skills`,
 * `.gemini/config/skills`, `.hermes/skills`) sunt fapte despre binarele agenților
 * din guest, care nu există în CI. Sunt documentate cu dovezi în
 * [RuntimeInstaller.AGENT_SKILL_DIRS] și verificate pe device, nu aici.
 */
class AgentSkillRootsTest {

    @Test
    fun mapsEveryAgentKind() {
        assertEquals(AgentKind.entries.toSet(), RuntimeInstaller.AGENT_SKILL_DIRS.keys)
    }

    @Test
    fun servesOnlyTheHarnessWithoutGuestFiles() {
        val hostSide = RuntimeInstaller.AGENT_SKILL_DIRS.filterValues { it == null }.keys

        assertEquals(setOf(AgentKind.DEEPSEEK_HARNESS), hostSide)
    }

    @Test
    fun keepsGuestRootsInsideTheGuestHome() {
        val guestRoots = RuntimeInstaller.AGENT_SKILL_DIRS.values.filterNotNull()

        assertTrue("expected guest roots", guestRoots.isNotEmpty())
        guestRoots.forEach { dir ->
            assertFalse(dir, dir.startsWith("/"))
            assertFalse(dir, dir.contains(".."))
            assertTrue(dir, dir.startsWith("root/"))
        }
    }

    @Test
    fun givesEachAgentItsOwnRoot() {
        val guestRoots = RuntimeInstaller.AGENT_SKILL_DIRS.values.filterNotNull()

        assertEquals(guestRoots.toSet().size, guestRoots.size)
    }
}
