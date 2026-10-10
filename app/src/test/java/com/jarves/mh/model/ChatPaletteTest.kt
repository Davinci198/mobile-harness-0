package com.jarves.mh.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatPaletteTest {

    private val commands = listOf(
        PaletteCommand(PaletteAction.NEW_CHAT, PaletteGroup.SESSION, "New chat", listOf("start", "reset")),
        PaletteCommand(PaletteAction.MODELS, PaletteGroup.SESSION, "Models", listOf("model", "llm", "provider")),
        PaletteCommand(PaletteAction.STOP_TASK, PaletteGroup.SESSION, "Stop task", listOf("abort"), enabled = false),
        PaletteCommand(PaletteAction.OPEN_TERMINAL, PaletteGroup.TERMINAL, "Open terminal", listOf("shell")),
    )

    @Test
    fun modelsCommandIsReachableFromTheSlashPalette() {
        assertEquals(
            listOf(PaletteAction.MODELS),
            ChatPalette.match("/models", commands).map { it.action },
        )
        assertEquals(
            listOf(PaletteAction.MODELS),
            ChatPalette.match("provider", commands).map { it.action },
        )
    }

    @Test
    fun emptyQueryAndBareSlashShowEverything() {
        assertEquals(commands, ChatPalette.match("", commands))
        assertEquals(commands, ChatPalette.match("/", commands))
        assertEquals(commands, ChatPalette.match("   /  ", commands))
    }

    @Test
    fun labelMatchesCaseInsensitivelyAfterStrippingTheSlash() {
        assertEquals(
            listOf(PaletteAction.OPEN_TERMINAL),
            ChatPalette.match("/TERMINAL", commands).map { it.action },
        )
        assertEquals(
            listOf(PaletteAction.NEW_CHAT),
            ChatPalette.match("new", commands).map { it.action },
        )
    }

    @Test
    fun keywordMatchesFindTheCommand() {
        assertEquals(
            listOf(PaletteAction.STOP_TASK),
            ChatPalette.match("/abort", commands).map { it.action },
        )
    }

    @Test
    fun actionIdIsAlsoSearchable() {
        assertEquals(
            listOf(PaletteAction.OPEN_TERMINAL),
            ChatPalette.match("/open_terminal", commands).map { it.action },
        )
    }

    @Test
    fun noMatchMeansNoRowsSoRawTextCanReachTheAgent() {
        assertTrue(ChatPalette.match("/compact", commands).isEmpty())
    }

    @Test
    fun disabledCommandsStillMatchSoTheUserSeesTheyExist() {
        val matched = ChatPalette.match("/stop", commands).single()
        assertEquals(PaletteAction.STOP_TASK, matched.action)
        assertTrue(!matched.enabled)
    }
}
