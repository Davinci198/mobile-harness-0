package com.jarves.mh.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatHistoryActionsTest {

    private fun user(id: String, text: String) = ChatMessage(id = id, fromUser = true, text = text)
    private fun assistant(id: String, text: String) = ChatMessage(id = id, fromUser = false, text = text)

    private val history = listOf(
        user("u1", "first prompt"),
        assistant("a1", "first answer"),
        user("u2", "second prompt"),
        assistant("a2", "second answer"),
    )

    @Test
    fun regenerateUserMessageKeepsPrefixAndResendsItsOwnText() {
        val plan = ChatHistoryActions.planRegenerate(history, "u2")!!
        assertEquals(listOf("u1", "a1"), plan.kept.map { it.id })
        assertEquals("second prompt", plan.resend)
    }

    @Test
    fun regenerateAssistantDropsTheStaleExchangeAndResendsItsPrompt() {
        val plan = ChatHistoryActions.planRegenerate(history, "a2")!!
        assertEquals(listOf("u1", "a1"), plan.kept.map { it.id })
        assertEquals("second prompt", plan.resend)
    }

    @Test
    fun regenerateUnknownIdIsRejectedSoHistoryStaysIntact() {
        assertNull(ChatHistoryActions.planRegenerate(history, "missing"))
    }

    @Test
    fun regenerateAssistantWithoutAnyUserPromptIsRejected() {
        val orphan = listOf(assistant("a1", "no prompt"))
        assertNull(ChatHistoryActions.planRegenerate(orphan, "a1"))
    }

    @Test
    fun regenerateBlankPromptIsRejected() {
        val blank = listOf(user("u1", "   "), assistant("a1", "answer"))
        assertNull(ChatHistoryActions.planRegenerate(blank, "a1"))
    }

    @Test
    fun withoutMessageRemovesOnlyTheTargetAndKeepsOrder() {
        val result = ChatHistoryActions.withoutMessage(history, "a1")
        assertEquals(listOf("u1", "u2", "a2"), result.map { it.id })
        assertEquals(4, history.size)
    }

    @Test
    fun withoutMessageOnUnknownIdIsANoOp() {
        assertEquals(history, ChatHistoryActions.withoutMessage(history, "missing"))
    }
}
