package com.jarves.mh.workflows

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WorkflowMatcherTest {

    private fun workflow(
        id: String = "wf1",
        name: String = "Check Status",
        phrases: List<String> = listOf("check status"),
        steps: List<WorkflowStep> = listOf(WorkflowStep("Calculate", mapOf("expression" to "1+1"))),
    ) = AgentWorkflow(id, name, "", phrases, steps)

    @Test
    fun exactPhraseScores100() {
        assertEquals(100, WorkflowMatcher.score("check status", workflow()))
    }

    @Test
    fun inputContainingPhraseScores80() {
        assertEquals(80, WorkflowMatcher.score("please check status now", workflow()))
    }

    @Test
    fun phraseContainingShortInputScores60() {
        assertEquals(60, WorkflowMatcher.score("check stat", workflow()))
    }

    @Test
    fun tinyInputNeverMatchesViaContainment() {
        // "c" is contained in the phrase, but too short to win.
        assertEquals(0, WorkflowMatcher.score("c", workflow()))
    }

    @Test
    fun keywordOverlapScores() {
        // phrase tokens: build, project, status → 2 hits in input → (2*40)/3 = 26
        val w = workflow(phrases = listOf("build project status"))
        assertEquals(26, WorkflowMatcher.score("build the status", w))
    }

    @Test
    fun matchPicksScoringWorkflow() {
        val a = workflow(id = "mail", phrases = listOf("check mail"))
        val b = workflow(id = "build", phrases = listOf("build project"))
        assertEquals("mail", WorkflowMatcher.match("check mail", listOf(a, b))?.id)
    }

    @Test
    fun matchRejectsAmbiguousTie() {
        val a = workflow(id = "a", phrases = listOf("alpha beta"))
        val b = workflow(id = "b", phrases = listOf("alpha beta"))
        assertNull(WorkflowMatcher.match("alpha beta", listOf(a, b)))
    }

    @Test
    fun matchRejectsBelowThreshold() {
        val w = workflow(phrases = listOf("completely different topic"))
        assertNull(WorkflowMatcher.match("order pizza", listOf(w)))
    }

    @Test
    fun matchReturnsNullForEmptyInput() {
        assertNotNull(workflow())
        assertNull(WorkflowMatcher.match("   ", listOf(workflow())))
    }

    @Test
    fun normalizesCaseAndWhitespace() {
        assertEquals(100, WorkflowMatcher.score("  Check   STATUS ", workflow()))
    }
}
